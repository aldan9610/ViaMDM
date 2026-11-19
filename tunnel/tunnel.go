// Package tunnel brings zrok to the ViaMDM app: one enabled environment per device and, per
// share, a local TCP listener that carries the tunnel to the server (the API's tcpTunnel, the
// SOCKS egress, the drive folder). gomobile binds it into an AAR the app calls.
//
// The three connections the app keeps are three calls to ListenTCP with their own share token
// and their own port ("127.0.0.1:8463" the API, "127.0.0.1:9191" SOCKS, "127.0.0.1:8464" the
// drive): both are the phone's settings', and the server's share tokens arrive in its setup
// QR (mdm-tunnel://setup?api=…&socks=…&drive=…).
package tunnel

import (
	"fmt"
	"io"
	"net"
	"os"
	"sync"

	"github.com/michaelquigley/df/dl"
	"github.com/openziti/zrok/v2/environment"
	"github.com/openziti/zrok/v2/environment/env_core"
	restEnvironment "github.com/openziti/zrok/v2/rest_client_zrok/environment"
	"github.com/openziti/zrok/v2/sdk/golang/sdk"
	httptransport "github.com/go-openapi/runtime/client"
)

// The listeners this process keeps, by share token.
type listener struct {
	ln      net.Listener
	acc     *sdk.Access
	root    env_core.Root
	closing chan struct{}
	once    sync.Once
}

var (
	mu        sync.Mutex
	listeners = map[string]*listener{}
)

// Enabled reports whether the environment kept in dir is already enabled.
func Enabled(dir string) bool {
	root, err := loadRoot(dir)
	if err != nil {
		return false
	}
	return root.IsEnabled()
}

// Enable enables the environment kept in dir with the account's token, using apiEndpoint when
// it is not empty ("": zrok's own service). One environment per device: calling it again on an
// enabled one does nothing.
func Enable(apiEndpoint, accountToken, dir string) error {
	if accountToken == "" {
		return fmt.Errorf("no account token")
	}
	root, err := loadRoot(dir)
	if err != nil {
		return err
	}
	if root.IsEnabled() {
		return nil
	}
	// The service to use, before the client is made: the environment's own record is written
	// after the call, and the override rides in the environment variable the root reads.
	if apiEndpoint != "" {
		os.Setenv("ZROK2_API_ENDPOINT", apiEndpoint)
		defer os.Unsetenv("ZROK2_API_ENDPOINT")
	}
	zrok, err := root.Client()
	if err != nil {
		return fmt.Errorf("the zrok client could not be made: %w", err)
	}
	host, _ := os.Hostname()
	auth := httptransport.APIKeyAuth("X-TOKEN", "header", accountToken)
	req := restEnvironment.NewEnableParams()
	req.Body.Description = "ViaMDM (Android)"
	req.Body.Host = host
	resp, err := zrok.Environment.Enable(req, auth)
	if err != nil {
		return fmt.Errorf("the zrok service refused the environment: %w", err)
	}
	apiEndpoint, _ = root.ApiEndpoint()
	if err := root.SetEnvironment(&env_core.Environment{
		AccountToken: accountToken,
		ZitiIdentity: resp.Payload.Identity,
		ApiEndpoint:  apiEndpoint,
	}); err != nil {
		return fmt.Errorf("the environment could not be kept: %w", err)
	}
	if err := root.SaveZitiIdentityNamed(root.EnvironmentIdentityName(), resp.Payload.Cfg); err != nil {
		return fmt.Errorf("the identity could not be kept: %w", err)
	}
	dl.Infof("the zrok environment was enabled")
	return nil
}

// ListenTCP keeps a local listener at bind ("127.0.0.1:8463") carrying every connection to the
// share token's service: the app speaks to 127.0.0.1 and the tunnel does the rest. Calling it
// again for the same share replaces the listener (a moved port takes at once).
func ListenTCP(shareToken, bind string) error {
	if shareToken == "" || bind == "" {
		return fmt.Errorf("a share token and an address to bind are needed")
	}
	root, err := loadRoot("")
	if err != nil {
		return err
	}
	if !root.IsEnabled() {
		return fmt.Errorf("the environment is not enabled; Enable first")
	}
	acc, err := sdk.CreateAccess(root, &sdk.AccessRequest{ShareToken: shareToken})
	if err != nil {
		return fmt.Errorf("the share could not be accessed: %w", err)
	}
	ln, err := net.Listen("tcp", bind)
	if err != nil {
		_ = sdk.DeleteAccess(root, acc)
		return fmt.Errorf("%v could not be listened on: %w", bind, err)
	}
	l := &listener{ln: ln, acc: acc, root: root, closing: make(chan struct{})}
	mu.Lock()
	old := listeners[shareToken]
	listeners[shareToken] = l
	mu.Unlock()
	if old != nil {
		old.stop()
	}
	dl.Infof("the %v share listens at %v", shareToken, bind)
	go l.serve()
	return nil
}

// StopListener stops the listener of one share ("" stops them all), forgetting its access.
func StopListener(shareToken string) {
	mu.Lock()
	var stopping []*listener
	if shareToken == "" {
		for k, l := range listeners {
			stopping = append(stopping, l)
			delete(listeners, k)
		}
	} else if l, ok := listeners[shareToken]; ok {
		stopping = append(stopping, l)
		delete(listeners, shareToken)
	}
	mu.Unlock()
	for _, l := range stopping {
		l.stop()
	}
}

// Active reports which shares have a listener up (their tokens), oldest first.
func Active() []string {
	mu.Lock()
	defer mu.Unlock()
	out := make([]string, 0, len(listeners))
	for k := range listeners {
		out = append(out, k)
	}
	return out
}

func (l *listener) stop() {
	l.once.Do(func() {
		close(l.closing)
		_ = l.ln.Close()
		_ = sdk.DeleteAccess(l.root, l.acc)
	})
}

func (l *listener) serve() {
	for {
		conn, err := l.ln.Accept()
		if err != nil {
			select {
			case <-l.closing:
				return
			default:
			}
			dl.Errorf("the listener could not accept: %v", err)
			return
		}
		go l.pump(conn)
	}
}

// pump carries one local connection to the share: a dial per connection, the way the SDK
// dials, and both directions piped until either side closes.
func (l *listener) pump(conn net.Conn) {
	defer conn.Close()
	remote, err := sdk.NewDialer(l.acc.ShareToken, l.root)
	if err != nil {
		dl.Errorf("the share could not be dialed: %v", err)
		return
	}
	defer remote.Close()
	done := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(remote, conn)
		done <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(conn, remote)
		done <- struct{}{}
	}()
	<-done
}

// loadRoot loads the environment root, rooted at dir when it says so ("" the default place,
// which a server's CLI uses; the phone always names its own folder).
func loadRoot(dir string) (env_core.Root, error) {
	if dir != "" {
		environment.SetRootDirName(dir)
	}
	return environment.LoadRoot()
}

# La AAR del módulo Go y las APK. La CI hace lo mismo (ver .github/workflows/android.yml).

AAR := app/libs/viamdm-tunnel.aar

.PHONY: aar debug release clean

aar:
	go install golang.org/x/mobile/cmd/gomobile@latest
	gomobile init
	mkdir -p app/libs
	gomobile bind -target=android -androidapi 26 -o $(AAR) ./tunnel

debug: $(AAR)
	gradle assembleDebug

release: $(AAR)
	gradle assembleRelease

clean:
	gradle clean
	rm -f $(AAR)

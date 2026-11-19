package com.viamdm.app

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * El navegador: una barra superior con omnibox y controles, pestañas recicladas, y la pieza
 * que le da sentido — todo enlace puede irse al servidor MDM (descarga remota) por el túnel.
 * El interruptor de red cambia entre la IP del móvil (directo) y la del servidor (SOCKS5 por
 * el túnel), sin tocar nada más.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var secrets: Secrets
    private lateinit var mdm: MdmClient
    private var tabs: MutableList<WebView> = mutableListOf()
    private var current: Int = 0
    private lateinit var container: FrameLayout
    private lateinit var omnibox: EditText
    private lateinit var statusChip: TextView
    private var bridge: ConnectSocksBridge? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TunnelProvider.init(this)
        secrets = Secrets(this)
        mdm = MdmClient(secrets)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        // La barra superior, a la manera de Via: omnibox y, a su derecha, los controles.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            gravity = Gravity.CENTER_VERTICAL
            elevation = dp(3).toFloat()
        }
        omnibox = EditText(this).apply {
            hint = getString(R.string.omnibox_hint)
            setBackgroundResource(R.drawable.omnibox_bg)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            textSize = 15f
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, action, _ ->
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                    go(omnibox.text.toString())
                    true
                } else {
                    false
                }
            }
        }
        bar.addView(
            omnibox,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        statusChip = TextView(this).apply {
            text = "●"
            textSize = 18f
            setPadding(dp(10), 0, dp(4), 0)
        }
        bar.addView(statusChip)
        bar.addView(navButton("\u25C0") { currentTab()?.goBack() })
        bar.addView(navButton("\u25B6") { currentTab()?.goForward() })
        bar.addView(navButton("\u2302") { omnibox.setText(HOME); go(HOME) })
        bar.addView(navButton("\u25A4") { showTabs() })
        bar.addView(navButton("\u22EE") { showMenu() })
        root.addView(bar, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        container = FrameLayout(this)
        root.addView(
            container,
            LinearLayout.LayoutParams(-1, 0, 1f),
        )
        setContentView(root)

        // El puente CONNECT→SOCKS5 y el modo de red guardado.
        bridge = ConnectSocksBridge(secrets.bridgePort, secrets.localPort(Secrets.ROLE_SOCKS, Secrets.DEFAULT_SOCKS_PORT))
        if (secrets.proxyMode == "server") applyNetworkMode("server")

        // Túnel arriba (si hay token): el servicio en primer plano lo mantiene.
        if (secrets.zrokAccountToken.isNotEmpty()) {
            TunnelService.start(this)
        }

        // De donde venga: un enlace compartido o abierto con ViaMDM va al navegador, y con
        // «Enviar a MDM» desde otra app va al servidor.
        when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { shared ->
                offerToMdm(shared)
            }
            Intent.ACTION_VIEW -> intent.data?.toString()?.let { go(it) }
            else -> go(HOME)
        }
        updateChip()
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    /** Un botón de la barra: un glifo de texto, como los que Via dibuja con su propio trazo. */
    private fun navButton(glyph: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = glyph
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(6), dp(9), dp(6))
            setOnClickListener { onClick() }
        }

    private fun currentTab(): WebView? = tabs.getOrNull(current)

    @SuppressLint("SetJavaScriptEnabled")
    private fun newTab(): WebView {
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPAT_MODE
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.webViewClient = TabClient()
        wv.webChromeClient = WebChromeClient()
        wv.setBackgroundColor(Color.WHITE)
        tabs.add(wv)
        current = tabs.size - 1
        return wv
    }

    private fun show(wv: WebView) {
        container.removeAllViews()
        container.addView(
            wv,
            FrameLayout.LayoutParams(-1, -1),
        )
    }

    private fun go(input: String) {
        val wv = currentTab() ?: newTab()
        show(wv)
        val target = normalize(input)
        omnibox.setText(target)
        wv.loadUrl(target)
    }

    private fun normalize(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return HOME
        val looksLikeUrl = t.startsWith("http://") || t.startsWith("https://") ||
            (!t.contains(' ') && t.contains('.') && !t.endsWith("."))
        return if (looksLikeUrl) {
            if (t.startsWith("http")) t else "https://$t"
        } else {
            "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(t, "UTF-8")
        }
    }

    /** El menú principal: servidor, explorador, ajustes, y los gestos del navegador. */
    private fun showMenu() {
        val items = listOf(
            getString(R.string.menu_send),
            getString(R.string.menu_server),
            getString(R.string.menu_explorer),
            getString(R.string.menu_settings),
            getString(R.string.menu_night),
            getString(R.string.menu_ua),
            getString(R.string.menu_copy),
            getString(R.string.menu_stop_tunnel),
        )
        val actions = listOf(
            { currentTab()?.url?.let { offerToMdm(it) } },
            { showPairing() },
            { startActivity(Intent(this, ExplorerActivity::class.java)) },
            { startActivity(Intent(this, SettingsActivity::class.java)) },
            { toggleNight() },
            { toggleUserAgent() },
            { copyUrl() },
            { TunnelService.stop(this); updateChip() },
        )
        AlertDialog.Builder(this)
            .setItems(items.toTypedArray()) { _, i -> actions[i]() }
            .show()
    }

    /** Las pestañas: una lista de las abiertas, con cierre. */
    private fun showTabs() {
        val titles = tabs.mapIndexed { i, wv ->
            val title = wv.title ?: wv.url ?: getString(R.string.tab_empty)
            (if (i == current) "● " else "") + title.take(40)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.tabs_title))
            .setItems(titles.toTypedArray()) { _, i ->
                current = i
                show(tabs[i])
                omnibox.setText(tabs[i].url ?: "")
            }
            .setPositiveButton(getString(R.string.tab_new)) { _, _ -> go(HOME) }
            .show()
    }

    /** «Enviar a MDM»: el diálogo que ofrece el servidor primero y el móvil después. */
    private fun offerToMdm(url: String) {
        if (!secrets.paired) {
            Toast.makeText(this, getString(R.string.pair_first), Toast.LENGTH_LONG).show()
            showPairing()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.send_title))
            .setMessage(url)
            .setPositiveButton(getString(R.string.send_mdm)) { _, _ ->
                val ua = currentTab()?.settings?.userAgentString
                val referer = currentTab()?.url
                Thread {
                    mdm.add(url, ua, referer).fold(
                        onSuccess = {
                            runOnUiThread { Toast.makeText(this, R.string.sent, Toast.LENGTH_SHORT).show() }
                        },
                        onFailure = { e ->
                            runOnUiThread {
                                Toast.makeText(this, "${getString(R.string.send_failed)}: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        },
                    )
                }.start()
            }
            .setNegativeButton(getString(R.string.send_here)) { _, _ -> downloadHere(url) }
            .setNeutralButton(getString(R.string.send_browse), null)
            .show()
    }

    private fun downloadHere(url: String) {
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(android.net.Uri.parse(url))
        try {
            val cookies = CookieManager.getInstance().getCookie(url)
            if (cookies != null) request.addRequestHeader("Cookie", cookies)
        } catch (_: Exception) {
        }
        manager.enqueue(request)
        Toast.makeText(this, R.string.downloading_here, Toast.LENGTH_SHORT).show()
    }

    /** El emparejamiento: el código que el servidor muestra en Options → Remote → Pair a device. */
    private fun showPairing() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val code = EditText(this).apply { hint = getString(R.string.pair_code_hint); isSingleLine = true }
        val name = EditText(this).apply { hint = getString(R.string.pair_name_hint); setText(Build.MODEL) }
        box.addView(code); box.addView(name)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pair_title))
            .setView(box)
            .setPositiveButton(R.string.pair_go) { _, _ ->
                val base = secrets.mdmBaseUrl.ifEmpty {
                    "https://127.0.0.1:${secrets.localPort(Secrets.ROLE_API, Secrets.DEFAULT_API_PORT)}"
                }
                secrets.mdmBaseUrl = base
                Thread {
                    mdm.pair(code.text.toString(), name.text.toString()).fold(
                        onSuccess = {
                            runOnUiThread { Toast.makeText(this, R.string.paired, Toast.LENGTH_SHORT).show() }
                        },
                        onFailure = { e ->
                            runOnUiThread {
                                Toast.makeText(this, "${getString(R.string.pair_failed)}: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        },
                    )
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleNight() {
        val wv = currentTab() ?: return
        val dark = wv.settings.forceDark == WebSettings.FORCE_DARK_ON
        wv.settings.forceDark =
            if (dark) WebSettings.FORCE_DARK_OFF else WebSettings.FORCE_DARK_ON
        wv.reload()
    }

    private fun toggleUserAgent() {
        val wv = currentTab() ?: return
        val desktop = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
        val mobile = "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
        wv.settings.userAgentString = if (wv.settings.userAgentString == desktop) mobile else desktop
        wv.reload()
    }

    private fun copyUrl() {
        val url = currentTab()?.url ?: return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("url", url))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun applyNetworkMode(mode: String) {
        if (mode == "server") bridge?.start()
        WebViewProxy.apply(mode, secrets.bridgePort) { err ->
            runOnUiThread {
                if (err != null) {
                    Toast.makeText(this, "${getString(R.string.proxy_failed)}: ${err.message}", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, if (mode == "server") R.string.mode_server else R.string.mode_direct, Toast.LENGTH_SHORT).show()
                    currentTab()?.reload()
                }
            }
        }
    }

    private fun updateChip() {
        statusChip.setTextColor(
            ContextCompat.getColor(
                this,
                if (TunnelStatus.last?.contains(getString(R.string.tunnel_up_prefix)) == true) Color.parseColor("#1a7f37")
                else Color.parseColor("#9aa0a6"),
            ),
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { offerToMdm(it) }
            Intent.ACTION_VIEW -> intent.data?.toString()?.let { go(it) }
        }
    }

    override fun onBackPressed() {
        val wv = currentTab()
        if (wv != null && wv.canGoBack()) wv.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        bridge?.stop()
        super.onDestroy()
    }

    /** El cliente de cada pestaña: las descargas se ofrecen al servidor, el resto es navegar. */
    private inner class TabClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (url.startsWith("http")) return false
            return try {
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                true
            } catch (_: Exception) {
                true
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            omnibox.setText(view.url ?: url ?: "")
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            // El bloqueo básico: la lista de hosts que viene con la app.
            return if (AdBlock.blocked(request.url.host)) {
                WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
            } else {
                null
            }
        }

        override fun onDownloadStart(url: String, ua: String?, content: String?, mime: String?, length: Long) {
            offerToMdm(url)
        }
    }

    companion object {
        private const val HOME = "https://duckduckgo.com/"
    }
}

/** La lista de bloqueo, embebida: dominios de anuncios típicos, ampliados por el usuario. */
object AdBlock {
    private val hosts = setOf(
        "doubleclick.net", "googlesyndication.com", "google-analytics.com", "googletagmanager.com",
        "adnxs.com", "adsrvr.org", "criteo.com", "taboola.com", "outbrain.com", "scorecardresearch.com",
        "quantserve.com", "facebook.net", "hotjar.com", "mouseflow.com", "clarity.ms", "zedo.com",
        "popads.net", "popcash.net", "propellerads.com", "adsterra.com", "mgid.com", "exoclick.com",
    )

    fun blocked(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase()
        return hosts.any { h == it || h.endsWith(".$it") }
    }
}

/** El menú horizontal que usan las hojas inferiores del navegador (helper mínimo). */
class BottomBar(context: Context) : HorizontalScrollView(context) {
    init {
        layoutParams = ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

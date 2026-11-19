package com.viamdm.app

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Los ajustes: el token de la cuenta zrok y su endpoint, las tres conexiones (cada una con su
 * token de share privado y su puerto local — configurable, como se pidió), el proveedor del
 * túnel, el modo de red y el emparejamiento con el servidor. Todo se guarda al pulsar Guardar,
 * y el túnel se reinicia para tomar los puertos y tokens nuevos.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var secrets: Secrets

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TunnelProvider.init(this)
        secrets = Secrets(this)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        scroll.addView(root, ViewGroup.LayoutParams(-1, -2))
        setContentView(scroll)

        title = getString(R.string.settings_title)
        // Las etiquetas de sección, añadidas a la columna que se está construyendo.
        val label: (Int) -> Unit = { res ->
            root.addView(
                TextView(this).apply {
                    text = getString(res)
                    textSize = 15f
                    setPadding(0, dp(14), 0, dp(4))
                },
            )
        }

        label(R.string.settings_account)
        val account = edit(secrets.zrokAccountToken, password = true)
        root.addView(account)

        label(R.string.settings_endpoint)
        val endpoint = edit(secrets.zrokApiEndpoint)
        root.addView(endpoint)

        label(R.string.settings_connections)
        val roles = listOf(
            Triple(Secrets.ROLE_API, Secrets.DEFAULT_API_PORT, getString(R.string.conn_api)),
            Triple(Secrets.ROLE_SOCKS, Secrets.DEFAULT_SOCKS_PORT, getString(R.string.conn_socks)),
            Triple(Secrets.ROLE_DRIVE, Secrets.DEFAULT_DRIVE_PORT, getString(R.string.conn_drive)),
        )
        val tokenFields = mutableMapOf<String, EditText>()
        val portFields = mutableMapOf<String, EditText>()
        for ((role, defPort, name) in roles) {
            val title = TextView(this).apply {
                text = name
                textSize = 15f
                setPadding(0, dp(10), 0, dp(2))
            }
            root.addView(title)
            val token = edit(secrets.shareToken(role))
            token.hint = getString(R.string.share_token_hint)
            root.addView(token)
            tokenFields[role] = token
            val portRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val portLabel = TextView(this).apply {
                text = getString(R.string.local_port)
                setPadding(0, dp(10), dp(8), 0)
            }
            val port = edit(secrets.localPort(role, defPort).toString())
            portRow.addView(portLabel)
            portRow.addView(
                port,
                LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            root.addView(portRow)
            portFields[role] = port
        }

        label(R.string.settings_provider)
        val provider = edit(secrets.provider)
        provider.hint = "aar | binary"
        root.addView(provider)

        label(R.string.settings_mode)
        val mode = edit(secrets.proxyMode)
        mode.hint = "direct | server"
        root.addView(mode)

        label(R.string.settings_bridge)
        val bridge = edit(secrets.bridgePort.toString())
        root.addView(bridge)

        val serverLabel = TextView(this).apply {
            text = getString(R.string.settings_mdm)
            textSize = 15f
            setPadding(0, dp(14), 0, dp(2))
        }
        root.addView(serverLabel)
        val mdmUrl = edit(secrets.mdmBaseUrl)
        mdmUrl.hint = getString(R.string.mdm_url_hint)
        root.addView(mdmUrl)

        val qr = EditText(this).apply {
            hint = getString(R.string.setup_uri_hint)
            isSingleLine = true
        }
        root.addView(qr)
        val readQr = Button(this).apply { text = getString(R.string.read_setup) }
        root.addView(readQr)
        readQr.setOnClickListener {
            if (secrets.applySetupUri(qr.text.toString())) {
                Toast.makeText(this, R.string.setup_applied, Toast.LENGTH_SHORT).show()
                recreate()
            } else {
                Toast.makeText(this, R.string.setup_bad, Toast.LENGTH_SHORT).show()
            }
        }

        val save = Button(this).apply { text = getString(R.string.save) }
        val saveLp = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
        saveLp.setMargins(0, dp(18), 0, 0)
        root.addView(save, saveLp)
        save.setOnClickListener {
            secrets.zrokAccountToken = account.text.toString()
            secrets.zrokApiEndpoint = endpoint.text.toString()
            for ((role, _, _) in roles) {
                secrets.setShareToken(role, tokenFields[role]?.text?.toString() ?: "")
                secrets.setLocalPort(
                    role,
                    portFields[role]?.text?.toString()?.trim()?.toIntOrNull()
                        ?: secrets.localPort(role, 0).takeIf { it > 0 }
                        ?: when (role) {
                            Secrets.ROLE_API -> Secrets.DEFAULT_API_PORT
                            Secrets.ROLE_SOCKS -> Secrets.DEFAULT_SOCKS_PORT
                            else -> Secrets.DEFAULT_DRIVE_PORT
                        },
                )
            }
            secrets.provider = provider.text.toString().trim().ifEmpty { "aar" }
            secrets.proxyMode = mode.text.toString().trim().ifEmpty { "direct" }
            secrets.bridgePort = bridge.text.toString().trim().toIntOrNull() ?: secrets.bridgePort
            secrets.mdmBaseUrl = mdmUrl.text.toString()
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            // Los puertos y tokens nuevos toman al reiniciar el túnel.
            if (secrets.zrokAccountToken.isNotEmpty()) {
                TunnelService.stop(this)
                TunnelService.start(this)
            }
            // El modo de red también puede haber cambiado.
            MainActivityBridge.networkModeChanged = secrets.proxyMode
            finish()
        }
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()


    private fun edit(value: String, password: Boolean = false): EditText =
        EditText(this).apply {
            setText(value)
            isSingleLine = true
            if (password) transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        }
}

/** El canal mínimo entre los ajustes y el navegador: el modo de red que toca aplicar. */
object MainActivityBridge {
    @Volatile
    var networkModeChanged: String? = null
}

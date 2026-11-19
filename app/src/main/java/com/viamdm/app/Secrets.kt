package com.viamdm.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The app's secrets and settings: the zrok account token, the three shares' tokens and ports,
 * the MDM pairing, the proxy mode. The sensitive ones live in EncryptedSharedPreferences; the
 * rest (ports, toggles) in the plain prefs.
 *
 * The three connections the tunnel keeps — each with its own share token and its own local
 * port, both the phone's settings':
 *   api    → the server MDM's remote control (HTTPS, `127.0.0.1:8463` por defecto)
 *   socks  → la salida del servidor (SOCKS5, `127.0.0.1:9191`)
 *   drive  → el explorador de archivos (WebDAV, `127.0.0.1:8464`)
 */
class Secrets(context: Context) {

    private val secure: SharedPreferences by lazy {
        val master = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "viamdm_secrets", master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val plain: SharedPreferences =
        context.getSharedPreferences("viamdm", Context.MODE_PRIVATE)

    var zrokAccountToken: String
        get() = secure.getString(K_ACCOUNT, "") ?: ""
        set(v) = secure.edit().putString(K_ACCOUNT, v.trim()).apply()

    var zrokApiEndpoint: String
        get() = plain.getString(K_ENDPOINT, "") ?: ""
        set(v) = plain.edit().putString(K_ENDPOINT, v.trim()).apply()

    /** El token de cada conexión ("api", "socks", "drive"), del QR del servidor o tecleado. */
    fun shareToken(role: String): String =
        secure.getString("$K_SHARE$role", "") ?: ""

    fun setShareToken(role: String, token: String) {
        secure.edit().putString("$K_SHARE$role", token.trim()).apply()
    }

    /** El puerto local que la app escucha para cada conexión. */
    fun localPort(role: String, default: Int): Int =
        plain.getInt("$K_PORT$role", default)

    fun setLocalPort(role: String, port: Int) {
        plain.edit().putInt("$K_PORT$role", port).apply()
    }

    /** El puerto del puente HTTP CONNECT que el WebView usa como proxy. */
    var bridgePort: Int
        get() = plain.getInt(K_BRIDGE, 8899)
        set(v) = plain.edit().putInt(K_BRIDGE, v).apply()

    /** El modo de navegación: "direct" la IP del móvil, "server" la del servidor por SOCKS5. */
    var proxyMode: String
        get() = plain.getString(K_MODE, "direct") ?: "direct"
        set(v) = plain.edit().putString(K_MODE, v).apply()

    /** El proveedor del túnel: "aar" (gomobile) o "binary" (zrok empaquetado en jniLib). */
    var provider: String
        get() = plain.getString(K_PROVIDER, "aar") ?: "aar"
        set(v) = plain.edit().putString(K_PROVIDER, v).apply()

    // — Emparejamiento con el servidor MDM —

    var mdmBaseUrl: String
        get() = plain.getString(K_MDM_URL, "") ?: ""
        set(v) = plain.edit().putString(K_MDM_URL, v.trim()).apply()

    var mdmDeviceToken: String
        get() = secure.getString(K_MDM_TOKEN, "") ?: ""
        set(v) = secure.edit().putString(K_MDM_TOKEN, v).apply()

    var mdmDeviceName: String
        get() = plain.getString(K_MDM_NAME, "") ?: ""
        set(v) = plain.edit().putString(K_MDM_NAME, v).apply()

    /** El pin «confiar en el primer uso»: SHA-256 (hex) del certificado que el servidor mostró al emparejar. */
    var mdmCertPin: String
        get() = secure.getString(K_MDM_PIN, "") ?: ""
        set(v) = secure.edit().putString(K_MDM_PIN, v).apply()

    val paired: Boolean get() = mdmBaseUrl.isNotEmpty() && mdmDeviceToken.isNotEmpty()

    /** El URI de configuración que el servidor muestra en su QR: `mdm-tunnel://setup?api=…&socks=…&drive=…`. */
    fun applySetupUri(uri: String): Boolean {
        val rest = uri.removePrefix("mdm-tunnel://setup?").takeIf { it != uri } ?: return false
        for (part in rest.split('&')) {
            val key = part.substringBefore('=')
            val value = part.substringAfter('=', "")
            when (key) {
                "api" -> if (value.isNotEmpty()) setShareToken(ROLE_API, value)
                "socks" -> if (value.isNotEmpty()) setShareToken(ROLE_SOCKS, value)
                "drive" -> if (value.isNotEmpty()) setShareToken(ROLE_DRIVE, value)
            }
        }
        return true
    }

    companion object {
        private const val K_ACCOUNT = "zrok_account_token"
        private const val K_ENDPOINT = "zrok_api_endpoint"
        private const val K_SHARE = "share_token_"
        private const val K_PORT = "local_port_"
        private const val K_BRIDGE = "bridge_port"
        private const val K_MODE = "proxy_mode"
        private const val K_PROVIDER = "tunnel_provider"
        private const val K_MDM_URL = "mdm_base_url"
        private const val K_MDM_TOKEN = "mdm_device_token"
        private const val K_MDM_NAME = "mdm_device_name"
        private const val K_MDM_PIN = "mdm_cert_pin"

        const val ROLE_API = "api"
        const val ROLE_SOCKS = "socks"
        const val ROLE_DRIVE = "drive"

        const val DEFAULT_API_PORT = 8463
        const val DEFAULT_SOCKS_PORT = 9191
        const val DEFAULT_DRIVE_PORT = 8464

        /** Los tres roles en el orden del QR del servidor. */
        val ROLES = listOf(ROLE_API, ROLE_SOCKS, ROLE_DRIVE)
    }
}

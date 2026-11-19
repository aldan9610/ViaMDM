package com.viamdm.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.EOFException
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedTrustManager

/**
 * El cliente del servidor MDM: empareja con el código de 8 dígitos y habla la misma API que la
 * extensión de escritorio (/api/pair, /api/add, /api/list), siempre por el túnel — la base es
 * `https://127.0.0.1:<puerto del API en el móvil>`, que la escucha del túnel lleva al servidor.
 *
 * El certificado del servidor se ancla «confiar en el primer uso»: al emparejar se guarda el
 * SHA-256 del certificado que presentó, y de ahí en adelante solo ese sirve. Sin CA pública ni
 * advertencias: el pin es la llave.
 */
class MdmClient(private val secrets: Secrets) {

    private val json = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        val trust = PinTrustManager(secrets)
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf(trust), null)
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier { _, _ -> true } // el pin manda, el nombre no importa por el túnel
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun url(path: String): String =
        secrets.mdmBaseUrl.trimEnd('/') + path

    /** POST /api/pair: el código de 8 dígitos cambia por el token del dispositivo. */
    fun pair(code: String, name: String): Result<PairAnswer> = runCatching {
        val body = JSONObject().put("code", code.trim()).put("name", name.trim()).toString()
        val request = Request.Builder().url(url("/api/pair"))
            .post(body.toRequestBody(json)).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    when (JSONObject(text).optString("code")) {
                        "wrong-code" -> "Código equivocado"
                        "no-code" -> "El servidor no muestra código: pulsa Pair a device allí"
                        "too-soon" -> "Demasiado rápido: espera un momento"
                        else -> "El servidor dijo ${response.code}"
                    },
                )
            }
            val answer = JSONObject(text)
            secrets.mdmDeviceToken = answer.getString("token")
            secrets.mdmDeviceName = answer.optString("name", name)
            PairAnswer(answer.getString("token"), answer.optString("id", ""), answer.optString("name", name))
        }
    }

    /** POST /api/add: la URL al servidor, con el contexto de la página (agente, referer). */
    fun add(url: String, userAgent: String? = null, referer: String? = null): Result<Unit> = runCatching {
        val body = JSONObject().apply {
            put("url", url)
            put("start", true)
            userAgent?.takeIf { it.isNotBlank() }?.let { put("user_agent", it) }
            referer?.takeIf { it.isNotBlank() }?.let { put("referer", it) }
        }.toString()
        val request = Request.Builder().url(url("/api/add"))
            .header("Authorization", "Bearer ${secrets.mdmDeviceToken}")
            .post(body.toRequestBody(json)).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("El servidor dijo ${response.code} (${response.body?.string()?.take(120)})")
            }
        }
    }

    /** GET /api/list: el estado de la cola, tal cual el servidor lo responde. */
    fun list(): Result<String> = runCatching {
        val request = Request.Builder().url(url("/api/list"))
            .header("Authorization", "Bearer ${secrets.mdmDeviceToken}")
            .get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("El servidor dijo ${response.code}")
            response.body?.string() ?: "{}"
        }
    }

    data class PairAnswer(val token: String, val id: String, val name: String)
}

/**
 * El gestor de confianza del pin: acepta el certificado cuyo SHA-256 está guardado, o
 * cualquiera si aún no hay pin (el emparejamiento es el primer uso y lo guarda).
 */
class PinTrustManager(private val secrets: Secrets) : javax.net.ssl.X509TrustManager {

    private fun check(chain: Array<X509Certificate>) {
        if (chain.isEmpty()) throw CertificateException("Sin certificado")
        val pin = sha256(chain[0])
        val stored = secrets.mdmCertPin
        when {
            stored.isEmpty() -> secrets.mdmCertPin = pin // primer uso: se ancla
            !stored.equals(pin, ignoreCase = true) -> throw CertificateException(
                "El certificado del servidor cambió (esperado $stored, llegado $pin)",
            )
        }
    }

    private fun sha256(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02x".format(it) }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = check(chain)
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

/** Un cliente SOCKS5 mínimo: el saludo sin auth y el CONNECT por nombre de host (el DNS lo resuelve el servidor). */
object Socks5 {

    fun connect(host: String, port: Int, proxyHost: String, proxyPort: Int): Socket {
        val socket = Socket(InetAddress.getLoopbackAddress(), proxyPort)
        val out = socket.getOutputStream()
        val input = socket.getInputStream()
        out.write(byteArrayOf(5, 1, 0)) // SOCKS5, un método, sin auth
        val reply = ByteArray(2)
        readFully(input, reply)
        if (reply[0] != 5.toByte() || reply[1] != 0.toByte()) {
            socket.close()
            throw IllegalStateException("El proxy no aceptó SOCKS5 sin auth")
        }
        val hostBytes = host.toByteArray(Charsets.UTF_8)
        if (hostBytes.size > 255) {
            socket.close()
            throw EOFException("Nombre de host demasiado largo")
        }
        val req = ByteArray(7 + hostBytes.size)
        req[0] = 5; req[1] = 1; req[2] = 0; req[3] = 3
        req[4] = hostBytes.size.toByte()
        hostBytes.copyInto(req, 5)
        req[5 + hostBytes.size] = (port shr 8).toByte()
        req[6 + hostBytes.size] = port.toByte()
        out.write(req)
        val head = ByteArray(4)
        readFully(input, head)
        if (head[1] != 0.toByte()) {
            socket.close()
            throw IllegalStateException("El proxy rechazó el CONNECT (${head[1]})")
        }
        readFully(input, ByteArray(2)) // puerto del reply
        if (head[3] == 1.toByte()) readFully(input, ByteArray(6))
        else if (head[3] == 4.toByte()) readFully(input, ByteArray(18))
        else if (head[3] == 3.toByte()) {
            val len = ByteArray(1)
            readFully(input, len)
            readFully(input, ByteArray(len[0].toInt() + 2))
        }
        return socket
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray) {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) throw EOFException("El proxy cerró la conexión")
            read += n
        }
    }
}

/**
 * El puente que el WebView usa para salir por el servidor: un proxy HTTP CONNECT local que
 * habla SOCKS5 con la escucha del túnel. El WebView apunta aquí con ProxyController, el
 * dominio viaja dentro del CONNECT y lo resuelve el servidor — sin fuga de DNS.
 */
class ConnectSocksBridge(
    private val listenPort: Int,
    private val socksPort: Int,
) {

    @Volatile
    private var server: java.net.ServerSocket? = null

    @Volatile
    var running: Boolean = false
        private set

    fun start() {
        if (running) return
        val socket = java.net.ServerSocket(listenPort, 64, InetAddress.getLoopbackAddress())
        server = socket
        running = true
        Thread({
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                Thread { handle(client) }.start()
            }
        }, "via-mdm-bridge").start()
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
    }

    private fun handle(client: Socket) {
        try {
            client.tcpNoDelay = true
            val reader = client.getInputStream().bufferedReader()
            val first = reader.readLine() ?: return client.close()
            val parts = first.split(' ')
            if (parts.size >= 2 && parts[0] == "CONNECT") {
                val target = parts[1].split(':')
                val host = target[0]
                val port = target.getOrNull(1)?.toIntOrNull() ?: 443
                // Cabeceras restantes hasta la línea vacía.
                while (reader.readLine()?.isNotEmpty() == true) { /* las ignora */ }
                val remote = Socks5.connect(host, port, "127.0.0.1", socksPort)
                client.getOutputStream().apply {
                    write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                    flush()
                }
                pump(client, remote)
            } else {
                // HTTP simple por URI absoluta: hoy casi todo es HTTPS; se avisa y se corta.
                client.getOutputStream().apply {
                    write("HTTP/1.1 502 ViaMDM: usa HTTPS por el puente\r\n\r\n".toByteArray())
                    flush()
                }
                client.close()
            }
        } catch (_: Exception) {
            runCatching { client.close() }
        }
    }

    private fun pump(a: Socket, b: Socket) {
        val t1 = Thread { runCatching { a.getInputStream().copyTo(b.getOutputStream()) }.let { runCatching { b.close() } } }
        val t2 = Thread { runCatching { b.getInputStream().copyTo(a.getOutputStream()) }.let { runCatching { a.close() } } }
        t1.start(); t2.start()
        t1.join(60_000); t2.join(60_000)
    }
}

/** El interruptor de red del WebView: proxy por el puente (IP del servidor) o directo (IP del móvil). */
object WebViewProxy {

    fun apply(mode: String, bridgePort: Int, onComplete: (Throwable?) -> Unit) {
        val controller = androidx.webkit.ProxyController.getInstance()
        // setProxyOverride/clearProxyOverride piden un Runnable explícito (más un executor
        // opcional): el cambio se aplica en el próximo WebView que se cree.
        val done = java.lang.Runnable { onComplete(null) }
        if (mode == "server") {
            val config = androidx.webkit.ProxyConfig.Builder()
                .addProxyRule("http=127.0.0.1:$bridgePort")
                .addProxyRule("https=127.0.0.1:$bridgePort")
                .addDirect().build()
            controller.setProxyOverride(config, null, done)
        } else {
            controller.clearProxyOverride(done)
        }
    }
}

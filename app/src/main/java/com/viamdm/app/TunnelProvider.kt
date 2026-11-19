package com.viamdm.app

import android.content.Context
import java.io.File

/**
 * El proveedor del túnel: la librería zrok compilada con gomobile (la AAR `tunnel.Tunnel` de
 * la CI) o, si falta, el binario `zrok` empaquetado como `libzrok.so` en jniLib y ejecutado
 * con ProcessBuilder. Ambos exponen lo mismo a la app: enable una vez y una escucha local por
 * conexión (token + puerto, todo configurable).
 */
object TunnelProvider {

    /** Enciende (una vez) el entorno zrok del dispositivo. */
    fun enable(context: Context, secrets: Secrets) {
        val endpoint = secrets.zrokApiEndpoint
        when (provider(context, secrets)) {
            "binary" -> binaryEnable(context, endpoint, secrets.zrokAccountToken)
            else -> gomobileEnable(endpoint, secrets.zrokAccountToken, envDir(context))
        }
    }

    /**
     * Levanta la escucha local de una conexión: `127.0.0.1:puerto` habla con el share del
     * token por el túnel. El API y el drive hablan HTTP/HTTPS claros para la app; el SOCKS
     * habla SOCKS5 con el backend del servidor.
     */
    fun listen(role: String, token: String, bind: String) {
        when (providerOf(role)) {
            "binary" -> binaryListen(role, token, bind)
            else -> gomobileListen(token, bind)
        }
    }

    fun stopAll() {
        runCatching { gomobileStopAll() }
        runCatching { binaryStopAll() }
    }

    /** Qué conexiones están levantadas (sus roles). */
    fun active(): List<String> = active.keys.toList()

    // — Estado por rol, compartido por los dos proveedores —

    private val active = mutableMapOf<String, Process>()

    private fun provider(context: Context, secrets: Secrets): String {
        if (secrets.provider == "binary") return "binary"
        return if (aarAvailable()) "aar" else "binary"
    }

    private fun providerOf(role: String): String {
        // El rol guarda el proveedor que le tocó al encender: los tres van con el mismo.
        return lockedProvider ?: "aar"
    }

    @Volatile
    private var lockedProvider: String? = null

    private fun aarAvailable(): Boolean =
        runCatching { Class.forName("tunnel.Tunnel") }.isSuccess

    private fun envDir(context: Context): String =
        File(context.filesDir, "zrok").apply { mkdirs() }.absolutePath

    // — Proveedor AAR (gomobile): la clase la genera la CI; se llama por reflexión para que
    //    el APK compile también sin ella —

    private fun gomobileEnable(endpoint: String, accountToken: String, dir: String) {
        val cls = Class.forName("tunnel.Tunnel")
        lockedProvider = "aar"
        runCatching {
            // Enable falla «por diseño» si ya hay entorno: lo deja dicho el mensaje.
            cls.getMethod("Enable", String::class.java, String::class.java, String::class.java)
                .invoke(null, endpoint, accountToken, dir)
        }.onFailure { e ->
            val msg = (e.cause ?: e).message ?: ""
            if (!msg.contains("already")) throw e
        }
    }

    private fun gomobileListen(token: String, bind: String) {
        lockedProvider = "aar"
        Class.forName("tunnel.Tunnel")
            .getMethod("ListenTCP", String::class.java, String::class.java)
            .invoke(null, token, bind)
    }

    private fun gomobileStopAll() {
        Class.forName("tunnel.Tunnel").getMethod("StopListener", String::class.java)
            .invoke(null, "")
    }

    // — Proveedor binario: zrok como libzrok.so en jniLib, una escucha por proceso —

    private fun binary(context: Context): String? {
        val dir = context.applicationInfo.nativeLibraryDir
        val zrok = File(dir, "libzrok.so")
        return if (zrok.exists()) zrok.absolutePath else null
    }

    private fun binaryEnable(context: Context, endpoint: String, accountToken: String) {
        val zrok = binary(context) ?: throw IllegalStateException("No hay ni AAR ni binario zrok")
        lockedProvider = "binary"
        if (accountToken.isEmpty()) throw IllegalStateException("Falta el token de cuenta")
        val home = File(context.filesDir, "zrok-home").apply { mkdirs() }
        // El CLI usa su casa (~/.zrok2): la del app, para no tocar la del sistema; sin TUI.
        val env = mapOf(
            "HOME" to home.absolutePath,
            "ZROK2_API_ENDPOINT" to endpoint,
        )
        runEnv(env, zrok, "config", "set", "headless", "true")
        val status = runEnv(env, zrok, "status")
        if (status.exitCode != 0 || status.output.contains("disabled")) {
            runEnv(env, zrok, "enable", accountToken).let { out ->
                if (out.exitCode != 0 && !out.output.contains("already have an enabled environment")) {
                    throw IllegalStateException(out.output.takeLast(400))
                }
            }
        }
    }

    private fun binaryListen(role: String, token: String, bind: String) {
        val context = appContext ?: throw IllegalStateException("Sin contexto")
        val zrok = binary(context) ?: throw IllegalStateException("Sin binario zrok")
        // El cliente consume el share: `access private` con el puerto que los ajustes piden.
        val args = mutableListOf(
            "access", "private", "--force-local", "--bind", bind, token,
        )
        val home = File(context.filesDir, "zrok-home")
        val env = mapOf("HOME" to home.absolutePath)
        val proc = ProcessBuilder(zrok, *args.toTypedArray())
            .redirectErrorStream(true)
            .apply { environment().putAll(env) }
            .start()
        active[role] = proc
        Thread {
            val out = proc.inputStream.bufferedReader().readText()
            android.util.Log.i("TunnelProvider", "zrok $role terminó: ${out.takeLast(400)}")
            active.remove(role)
        }.start()
    }

    private fun binaryStopAll() {
        for ((_, proc) in active) {
            runCatching { proc.destroy() }
        }
        active.clear()
    }

    private fun runEnv(env: Map<String, String>, vararg cmd: String): RunOut {
        val proc = ProcessBuilder(*cmd)
            .redirectErrorStream(true)
            .apply { environment().putAll(env) }
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        return RunOut(code, out)
    }

    private class RunOut(val exitCode: Int, val output: String)

    /** El contexto de la app, para los hilos que no lo traen. */
    @Volatile
    var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}

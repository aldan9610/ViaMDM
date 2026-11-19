package com.viamdm.app

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL

/**
 * El explorador de archivos del servidor: la carpeta compartida llega por el túnel como
 * WebDAV (el backend `drive` de zrok), así que este activity habla WebDAV contra
 * `http://127.0.0.1:<puerto del drive>` — listar (PROPFIND), abrir/descargar (GET), subir
 * (PUT), crear carpeta (MKCOL), borrar (DELETE) y renombrar (MOVE). Todo viaja cifrado por
 * el overlay, sin puertos abiertos en el servidor.
 */
class ExplorerActivity : AppCompatActivity() {

    private lateinit var secrets: Secrets
    private lateinit var client: DriveClient
    private lateinit var list: ListView
    private lateinit var titleView: TextView
    private var path: String = "/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TunnelProvider.init(this)
        secrets = Secrets(this)
        client = DriveClient(
            "http://127.0.0.1:${secrets.localPort(Secrets.ROLE_DRIVE, Secrets.DEFAULT_DRIVE_PORT)}",
            this,
        )
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        titleView = TextView(this).apply {
            textSize = 16f
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        root.addView(titleView)
        list = ListView(this)
        root.addView(list, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        title(getString(R.string.explorer_title))

        list.onItemClickListener = AdapterView.OnItemClickListener { _, _, i, _ ->
            val entry = entries.getOrNull(i) ?: return@OnItemClickListener
            if (entry.isFolder) {
                path = entry.href
                refresh()
            } else {
                showFileMenu(entry)
            }
        }
        list.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, i, _ ->
            val entry = entries.getOrNull(i) ?: return@OnItemLongClickListener
            AlertDialog.Builder(this)
                .setItems(
                    arrayOf(
                        getString(R.string.explorer_delete),
                        getString(R.string.explorer_rename),
                    ),
                ) { _, which ->
                    when (which) {
                        0 -> background { client.delete(entry.href); refresh() }
                        1 -> renameTo(entry)
                    }
                }
                .show()
            true
        }
        refresh()
    }

    private var entries: List<DriveClient.Entry> = listOf()

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    private fun title(text: String) {
        titleView.text = getString(R.string.explorer_path, text)
    }

    private fun refresh() {
        title(path)
        background {
            val result = client.list(path)
            runOnUiThread {
                result.fold(
                    onSuccess = { found ->
                        entries = found
                        list.adapter = ArrayAdapter(
                            this,
                            android.R.layout.simple_list_item_1,
                            found.map { (if (it.isFolder) "\uD83D\uDCC1 " else "\uD83D\uDCC4 ") + it.name },
                        )
                    },
                    onFailure = { e ->
                        Toast.makeText(this, "${getString(R.string.explorer_list_failed)}: ${e.message}", Toast.LENGTH_LONG).show()
                        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, listOf<String>())
                    },
                )
            }
        }
    }

    private fun showFileMenu(entry: DriveClient.Entry) {
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(
                arrayOf(
                    getString(R.string.explorer_to_mdm),
                    getString(R.string.explorer_download),
                ),
            ) { _, which ->
                when (which) {
                    0 -> sendToMdm(entry)
                    1 -> background {
                        val saved = client.download(entry.href, entry.name)
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                if (saved != null) getString(R.string.explorer_saved, saved)
                                else getString(R.string.explorer_save_failed),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }
            .show()
    }

    /** El fichero del servidor puede irse también al propio MDM como descarga (la URL por el túnel). */
    private fun sendToMdm(entry: DriveClient.Entry) {
        if (!secrets.paired) {
            Toast.makeText(this, R.string.pair_first, Toast.LENGTH_LONG).show()
            return
        }
        val url = "http://127.0.0.1:${secrets.localPort(Secrets.ROLE_DRIVE, Secrets.DEFAULT_DRIVE_PORT)}${entry.href}"
        background {
            MdmClient(secrets).add(url).fold(
                onSuccess = { runOnUiThread { Toast.makeText(this, R.string.sent, Toast.LENGTH_SHORT).show() } },
                onFailure = { e ->
                    runOnUiThread {
                        Toast.makeText(this, "${getString(R.string.send_failed)}: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                },
            )
        }
    }

    private fun renameTo(entry: DriveClient.Entry) {
        val box = android.widget.EditText(this).apply { setText(entry.name) }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.explorer_rename))
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val target = path.trimEnd('/') + "/" + box.text.toString()
                background { client.move(entry.href, target); refresh() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onBackPressed() {
        if (path != "/") {
            path = path.trimEnd('/').substringBeforeLast('/') + "/"
            if (!path.startsWith("/")) path = "/"
            refresh()
        } else {
            super.onBackPressed()
        }
    }

    private fun background(block: () -> Unit) {
        Thread(block).start()
    }
}

/**
 * El cliente WebDAV del drive: los cinco verbos que el explorador necesita, con el URL final
 * exacto que el servidor espera (PROPFIND Depth 1 para listar).
 */
class DriveClient(private val base: String, private val context: android.content.Context) {

    data class Entry(val name: String, val href: String, val isFolder: Boolean)

    private fun request(method: String, path: String, body: ByteArray? = null, depth: Int? = null): HttpURLConnection {
        val url = URL(base.trimEnd('/') + path)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        depth?.let { conn.setRequestProperty("Depth", it.toString()) }
        if (body != null) {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(body.size)
        }
        return conn
    }

    /** PROPFIND Depth 1: los hijos de `path`, en orden (carpetas primero, por nombre). */
    fun list(path: String): Result<List<Entry>> = runCatching {
        val xml = """
            <?xml version="1.0"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>
        """.trimIndent().toByteArray()
        val conn = request("PROPFIND", path, xml, depth = 1)
        conn.setRequestProperty("Content-Type", "application/xml")
        if (conn.responseCode / 100 != 2) throw IllegalStateException("El servidor dijo ${conn.responseCode}")
        val text = conn.inputStream.bufferedReader().readText()
        parseMultistatus(text).sortedWith(compareByDescending<Entry> { it.isFolder }.thenBy { it.name.lowercase() })
    }

    private fun parseMultistatus(xml: String): List<Entry> {
        val parser = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xml.reader())
        val entries = mutableListOf<Entry>()
        var inResponse = false
        var href = ""
        var folder = false
        var inSelf = false
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (event) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> when (parser.name) {
                    "response" -> { inResponse = true; folder = false; href = "" }
                    "collection" -> folder = true
                    "href" -> if (inResponse) href = decode(parser.nextText())
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> if (parser.name == "response" && inResponse) {
                    inResponse = false
                    val name = java.net.URLDecoder.decode(href.trimEnd('/').substringAfterLast('/'), "UTF-8")
                    if (name.isNotEmpty() && !inSelf) entries.add(Entry(name, href, folder))
                }
            }
            // El primer <response> es la propia carpeta: sus hijos son el resto.
            inSelf = entries.isEmpty() && inResponse && href == ""
            event = parser.next()
        }
        return entries
    }

    private fun decode(href: String): String =
        java.net.URLDecoder.decode(href, "UTF-8")

    fun download(path: String, name: String): String? {
        val conn = request("GET", path)
        if (conn.responseCode / 100 != 2) return null
        // Carpeta propia de la app dentro de Descargas (sin permisos ni scoped storage).
        val dir = java.io.File(
            context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS),
            "ViaMDM",
        ).apply { mkdirs() }
        val file = java.io.File(dir, name)
        conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file.absolutePath
    }


    fun upload(path: String, local: java.io.File): Result<Unit> = runCatching {
        val conn = request("PUT", path, local.readBytes())
        if (conn.responseCode / 100 !in 2..3) throw IllegalStateException("El servidor dijo ${conn.responseCode}")
    }

    fun mkdir(path: String): Result<Unit> = runCatching {
        if (request("MKCOL", path).responseCode / 100 !in 2..3) throw IllegalStateException()
    }

    fun delete(path: String): Result<Unit> = runCatching {
        if (request("DELETE", path).responseCode / 100 !in 2..3) throw IllegalStateException()
    }

    fun move(from: String, to: String): Result<Unit> = runCatching {
        val conn = request("MOVE", from)
        conn.setRequestProperty("Destination", base.trimEnd('/') + to)
        if (conn.responseCode / 100 !in 2..3) throw IllegalStateException()
    }
}

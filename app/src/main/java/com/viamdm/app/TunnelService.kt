package com.viamdm.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * El servicio en primer plano que mantiene las tres conexiones del túnel vivas: el API del
 * servidor MDM, la salida SOCKS5 y el explorador de archivos (drive/WebDAV). Cada conexión es
 * un par (token de share privado, puerto local) de los ajustes, y ambas cosas las cambia el
 * usuario: al reiniciar el servicio toman efecto los puertos nuevos, y un token vacío deja esa
 * conexión apagada.
 *
 * El proveedor del túnel: la librería zrok empaquetada con gomobile (la AAR de la CI) o, si no
 * está, el binario `zrok` empaquetado en jniLib (libzrok.so, ejecutado con ProcessBuilder).
 */
class TunnelService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var secrets: Secrets

    override fun onCreate() {
        super.onCreate()
        secrets = Secrets(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnels()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startForeground(NOTIFY_ID, buildNotification())
        scope.launch { bringUp() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Enciende el entorno (una vez) y las tres escuchas, según los ajustes. */
    private suspend fun bringUp() {
        val account = secrets.zrokAccountToken
        if (account.isEmpty()) {
            TunnelStatus.post(this, getString(R.string.tunnel_no_token))
            return
        }
        try {
            TunnelProvider.enable(this, secrets)
        } catch (e: Exception) {
            TunnelStatus.post(this, "${getString(R.string.tunnel_enable_failed)}: ${e.message}")
            return
        }
        val up = mutableListOf<String>()
        val specs = listOf(
            Triple(Secrets.ROLE_API, Secrets.DEFAULT_API_PORT, R.string.conn_api),
            Triple(Secrets.ROLE_SOCKS, Secrets.DEFAULT_SOCKS_PORT, R.string.conn_socks),
            Triple(Secrets.ROLE_DRIVE, Secrets.DEFAULT_DRIVE_PORT, R.string.conn_drive),
        )
        for ((role, defPort, label) in specs) {
            val token = secrets.shareToken(role)
            if (token.isEmpty()) continue
            val port = secrets.localPort(role, defPort)
            try {
                TunnelProvider.listen(role, token, "127.0.0.1:$port")
                up += getString(label)
            } catch (e: Exception) {
                TunnelStatus.post(this, "${getString(label)}: ${e.message}")
            }
        }
        TunnelStatus.post(
            this,
            if (up.isEmpty()) getString(R.string.tunnel_no_connections)
            else getString(R.string.tunnel_up, up.joinToString(" · ")),
        )
    }

    private fun stopTunnels() {
        try {
            TunnelProvider.stopAll()
        } catch (_: Exception) {
        }
        TunnelStatus.post(this, getString(R.string.tunnel_stopped))
    }

    private fun buildNotification(): android.app.Notification {
        val text = TunnelStatus.last ?: getString(R.string.tunnel_starting)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.tunnel_notification))
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            TunnelProvider.stopAll()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val CHANNEL = "tunnel"
        private const val NOTIFY_ID = 1
        const val ACTION_STOP = "com.viamdm.app.STOP_TUNNEL"

        fun start(context: Context) {
            ensureChannel(context)
            context.startForegroundService(Intent(context, TunnelService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TunnelService::class.java).setAction(ACTION_STOP),
            )
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        context.getString(R.string.tunnel_channel),
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
        }
    }
}

/** El último texto de estado del túnel, para el chip de la barra y los ajustes. */
object TunnelStatus {
    @Volatile
    var last: String? = null
        private set

    fun post(context: Context, text: String) {
        last = text
    }
}

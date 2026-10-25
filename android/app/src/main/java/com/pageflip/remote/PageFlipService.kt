package com.pageflip.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Servicio en primer plano que:
 *  - mantiene una conexión TCP con el plugin de KOReader, y
 *  - registra una MediaSession con control de volumen "remoto", de forma que
 *    Android le entrega las pulsaciones de volumen incluso con la pantalla
 *    apagada (igual que al controlar el volumen de un Chromecast).
 */
class PageFlipService : Service() {

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val ACTION_STOP = "com.pageflip.remote.STOP"
        const val ACTION_NEXT = "com.pageflip.remote.NEXT"
        const val ACTION_PREV = "com.pageflip.remote.PREV"

        private const val CHANNEL_ID = "pageflip"
        private const val NOTIFICATION_ID = 1
        private const val CONNECT_TIMEOUT_MS = 4000
        private const val READ_TIMEOUT_MS = 5000
        private const val PING_INTERVAL_S = 10L
        private const val RETRY_DELAY_MS = 2000L
        // Evita pasar decenas de páginas si se deja pulsado el botón.
        private const val MIN_PRESS_INTERVAL_MS = 250L

        @Volatile var running = false
            private set
        @Volatile var status: String = "Detenido"
            private set
        /** La actividad se suscribe aquí para mostrar el estado. */
        var statusListener: ((String) -> Unit)? = null

        fun send(context: Context, action: String) {
            context.startService(Intent(context, PageFlipService::class.java).setAction(action))
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val commands = LinkedBlockingQueue<String>()
    private var session: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var socket: Socket? = null
    private var lastPress = 0L
    private var host = ""
    private var port = 8134

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_NEXT, ACTION_PREV -> {
                if (running) enqueue(if (intent.action == ACTION_NEXT) "NEXT" else "PREV")
                else stopSelf()
                return START_NOT_STICKY
            }
        }

        val newHost = intent?.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
        val newPort = intent.getIntExtra(EXTRA_PORT, 8134)

        startInForeground()
        if (!running || newHost != host || newPort != port) {
            host = newHost
            port = newPort
            stopWorker()
            acquireLocks()
            setupSession()
            startWorker()
            running = true
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // "Mientras la app esté abierta": si se cierra desde recientes, paramos.
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        stopWorker()
        session?.run {
            isActive = false
            release()
        }
        session = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        updateStatus("Detenido")
        super.onDestroy()
    }

    // ---- Botones de volumen -------------------------------------------------

    private fun setupSession() {
        if (session != null) return
        val s = MediaSession(this, "PageFlip")
        val volumeProvider = object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
            override fun onAdjustVolume(direction: Int) {
                when {
                    direction > 0 -> onVolumeKey("NEXT")
                    direction < 0 -> onVolumeKey("PREV")
                }
            }
        }
        s.setPlaybackToRemote(volumeProvider)
        // Android sólo envía el volumen a sesiones "reproduciendo".
        s.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                .build()
        )
        s.setCallback(object : MediaSession.Callback() {})
        s.isActive = true
        session = s
    }

    private fun onVolumeKey(cmd: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPress < MIN_PRESS_INTERVAL_MS) return
        lastPress = now
        enqueue(cmd)
    }

    private fun enqueue(cmd: String) {
        commands.offer(cmd)
    }

    // ---- Conexión con el Kindle ---------------------------------------------

    private fun startWorker() {
        worker = Thread({ connectionLoop() }, "PageFlip-net").also { it.start() }
    }

    private fun stopWorker() {
        val old = worker ?: return
        worker = null
        try { socket?.close() } catch (_: Exception) {}
        old.interrupt()
    }

    /** Cada hilo de red sigue vivo sólo mientras sea el hilo actual. */
    private val stopping get() = worker !== Thread.currentThread()

    private fun connectionLoop() {
        while (!stopping) {
            try {
                updateStatus("Conectando con $host:$port…")
                Socket().use { sock ->
                    socket = sock
                    if (stopping) return
                    sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    sock.tcpNoDelay = true
                    sock.soTimeout = READ_TIMEOUT_MS
                    val out = OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8)
                    val input = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))

                    fun call(cmd: String): String {
                        out.write(cmd + "\n")
                        out.flush()
                        return input.readLine() ?: throw java.io.IOException("Conexión cerrada")
                    }

                    val hello = call("HELLO")
                    if (!hello.startsWith("PAGEFLIP")) throw java.io.IOException("No es un Kindle con Page Flip")
                    // Las pulsaciones hechas mientras estaba desconectado se descartan,
                    // para no avanzar varias páginas de golpe al reconectar.
                    commands.clear()
                    updateStatus("✅ Conectado a $host\nVol + = siguiente · Vol − = anterior")

                    while (!stopping) {
                        val cmd = commands.poll(PING_INTERVAL_S, TimeUnit.SECONDS) ?: "PING"
                        call(cmd)
                    }
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (stopping) break
                updateStatus("⚠️ Sin conexión con $host:$port\n${e.message ?: e.javaClass.simpleName}\nReintentando…")
                try { Thread.sleep(RETRY_DELAY_MS) } catch (_: InterruptedException) { break }
            }

        }
    }

    // ---- Notificación / primer plano ----------------------------------------

    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PageFlip:net").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "PageFlip:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Page Flip", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = buildNotification(status)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        fun action(act: String, req: Int) = PendingIntent.getService(
            this, req, Intent(this, PageFlipService::class.java).setAction(act), flags
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Page Flip")
            .setContentText(text.lineSequence().first())
            .setContentIntent(open)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, "◀ Anterior", action(ACTION_PREV, 1)).build())
            .addAction(Notification.Action.Builder(null, "Siguiente ▶", action(ACTION_NEXT, 2)).build())
            .addAction(Notification.Action.Builder(null, "Detener", action(ACTION_STOP, 3)).build())
            .build()
    }

    private fun updateStatus(text: String) {
        status = text
        mainHandler.post {
            if (running) {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(text))
            }
            statusListener?.invoke(text)
        }
    }
}

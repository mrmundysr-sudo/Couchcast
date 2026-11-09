package com.mundymorning.couchcast.carreceiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.File
import java.io.IOException

/** Keeps the control server alive while the phone is in the car with the screen off. */
class ReceiverService : Service() {
    private var server: ControlServer? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        CarReceiver.init(application)
        startInForeground()
        acquireLocks()
        if (server == null) startServer()
        return START_STICKY
    }

    private fun startServer() {
        val uploads = File(cacheDir, "uploads")
        val asset = { name: String -> try { assets.open("web/$name") } catch (_: IOException) { null } }
        for (port in listOf(ControlServer.DEFAULT_PORT, 0)) {
            val s = ControlServer(port, ReceiverRuntime.pairing, CarReceiver, asset, uploads)
            try {
                s.start(30_000, false)
                server = s
                ReceiverRuntime.port.value = s.listeningPort
                ReceiverRuntime.running.value = true
                return
            } catch (_: IOException) { /* port busy: try any free port */ }
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wake == null) wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "carreceiver:server").apply { acquire(12 * 60 * 60 * 1000L) }
        if (wifi == null) wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "carreceiver:server").apply { acquire() }
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Receiver", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ReceiverService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Couchcast Car Receiver is on")
            .setContentText("Ready for your phone to send a video, a picture or a game.")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) else startForeground(ID, n)
    }

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        server?.stop()
        server = null
        ReceiverRuntime.running.value = false
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        runCatching { wifi?.takeIf { it.isHeld }?.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "receiver"
        private const val ID = 4201
        const val ACTION_STOP = "com.mundymorning.couchcast.carreceiver.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ReceiverService::class.java))
        }
    }
}

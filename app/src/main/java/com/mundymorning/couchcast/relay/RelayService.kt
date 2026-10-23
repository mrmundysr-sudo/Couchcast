package com.mundymorning.couchcast.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.mundymorning.couchcast.MainActivity
import com.mundymorning.couchcast.playback.NetworkAddress
import com.mundymorning.couchcast.playback.PlaybackEndDetector
import com.mundymorning.couchcast.receiver.RokuReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the relay alive while the phone is in your pocket, and ends it when playback ends, the user stops it,
 * the relay goes idle, or Wi-Fi changes. Shows a persistent notification while active.
 */
class RelayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var watcher: Job? = null
    private var boundIp: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopRelay("stopped"); return START_NOT_STICKY }
        startInForeground()
        acquireLocks()
        val rokuIp = intent?.getStringExtra(EXTRA_ROKU_IP)
        watchNetwork()
        Relay.manager.onChange = { if (Relay.manager.activeCount == 0) stopSelf() }
        watcher?.cancel()
        watcher = scope.launch { supervise(rokuIp) }
        return START_NOT_STICKY
    }

    /** Idle/max-age expiry plus "has the Roku finished?" checks, independent of the app being on screen. */
    private suspend fun supervise(rokuIp: String?) {
        val detector = PlaybackEndDetector()
        val player = RokuReceiver()
        var tick = 0
        while (true) {
            delay(5_000)
            tick++
            if (Relay.manager.activeCount == 0) { stopSelf(); return }
            if (tick % 6 == 0) Relay.manager.expire()
            if (rokuIp != null && detector.onReport(player.playerInfo(rokuIp)?.state)) {
                Relay.manager.closeAll(); stopSelf(); return
            }
        }
    }

    private fun watchNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) { Relay.manager.closeAll() }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                val ip = NetworkAddress.pickLanIpv4(lp.linkAddresses.map { it.address })
                if (boundIp == null) boundIp = ip
                else if (ip != null && ip != boundIp) Relay.manager.closeAll() // the Roku was given the old address
            }
        }
        callback = cb
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build()
        cm.registerNetworkCallback(req, cb)
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wake == null) {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "couchcast:relay").apply { acquire(12 * 60 * 60 * 1000L) }
        }
        if (wifi == null) {
            wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "couchcast:relay").apply { acquire() }
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Phone relay", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RelayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Playing through your phone")
            .setContentText("Keep this phone on the same Wi-Fi until the video ends.")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop relay", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(ID, n)
    }

    private fun stopRelay(@Suppress("UNUSED_PARAMETER") why: String) {
        Relay.manager.closeAll()
        stopSelf()
    }

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        Relay.manager.onChange = null
        Relay.manager.closeAll()
        callback?.let { runCatching { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } }
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        runCatching { wifi?.takeIf { it.isHeld }?.release() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "relay"
        private const val ID = 4101
        const val ACTION_STOP = "com.mundymorning.couchcast.relay.STOP"
        const val EXTRA_ROKU_IP = "roku_ip"

        fun start(context: Context, rokuIp: String) {
            context.startForegroundService(Intent(context, RelayService::class.java).putExtra(EXTRA_ROKU_IP, rokuIp))
        }

        fun stop(context: Context) {
            Relay.manager.closeAll()
            context.stopService(Intent(context, RelayService::class.java))
        }
    }
}

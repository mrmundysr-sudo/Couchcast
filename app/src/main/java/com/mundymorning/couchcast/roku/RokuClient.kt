package com.mundymorning.couchcast.roku

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** ECP client. One shared OkHttp client, ~2 s timeouts, one retry. */
object RokuClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build()

    private val empty = ByteArray(0).toRequestBody(null)

    fun base(ip: String) = "http://$ip:8060"
    fun iconUrl(ip: String, appId: String) = "${base(ip)}/query/icon/$appId"

    private fun execute(req: Request): String? {
        repeat(2) {
            try {
                http.newCall(req).execute().use { r ->
                    if (r.isSuccessful) return r.body?.string().orEmpty()
                    if (r.code == 403) throw EcpRefused()
                    return null
                }
            } catch (e: EcpRefused) {
                throw e
            } catch (_: IOException) {
                // retry once
            }
        }
        return null
    }

    class EcpRefused : IOException("Roku refused network control")

    private suspend fun post(ip: String, path: String): Boolean = withContext(Dispatchers.IO) {
        execute(Request.Builder().url(base(ip) + path).post(empty).build()) != null
    }

    private suspend fun get(ip: String, path: String): String? = withContext(Dispatchers.IO) {
        execute(Request.Builder().url(base(ip) + path).get().build())
    }

    suspend fun key(ip: String, key: String) = post(ip, "/keypress/$key")
    suspend fun keyDown(ip: String, key: String) = post(ip, "/keydown/$key")
    suspend fun keyUp(ip: String, key: String) = post(ip, "/keyup/$key")

    suspend fun typeText(ip: String, text: String) {
        for (ch in text) {
            post(ip, "/keypress/Lit_" + URLEncoder.encode(ch.toString(), "UTF-8"))
        }
    }

    suspend fun launch(ip: String, channelId: String) = post(ip, "/launch/$channelId")

    suspend fun deviceInfo(ip: String): DeviceInfo? =
        get(ip, "/query/device-info")?.let { RokuParse.deviceInfo(it) }

    suspend fun apps(ip: String): List<RokuApp> =
        get(ip, "/query/apps")?.let { RokuParse.apps(it) } ?: emptyList()

    /** SSDP M-SEARCH for roku:ecp. Returns the IPs that answered. */
    suspend fun discover(context: Context): List<String> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("couchcast").apply {
            setReferenceCounted(false)
            acquire()
        }
        val found = linkedSetOf<String>()
        try {
            val socket = DatagramSocket()
            try {
                socket.soTimeout = 500
                val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: roku:ecp\r\n\r\n").toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { socket.send(DatagramPacket(msg, msg.size, group, 1900)) }
                val end = SystemClock.elapsedRealtime() + 3500
                val buf = ByteArray(2048)
                while (SystemClock.elapsedRealtime() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        socket.receive(p)
                        RokuParse.ssdpLocationHost(String(p.data, 0, p.length))?.let { found += it }
                    } catch (_: SocketTimeoutException) {
                    }
                }
            } finally {
                socket.close()
            }
        } catch (_: IOException) {
        } finally {
            lock.release()
        }
        found.toList()
    }
}

package com.mundymorning.couchcast.relay

import com.mundymorning.couchcast.playback.CookieSource
import com.mundymorning.couchcast.playback.UpstreamPolicy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class RelaySpec(
    /** The phone's own LAN address; the server binds only here. */
    val bindHost: String,
    /** The only client allowed to fetch (the Roku). */
    val clientIp: String,
    /** The stream address to relay. */
    val sourceUrl: String,
    /** What the user pasted. A private-network source is only allowed when the user themself pointed at it. */
    val userEnteredUrl: String,
    val context: RequestContext,
    /** hls or mp4. DASH and DRM are not relayed. */
    val format: String,
)

sealed class RelayOpen {
    class Ready(val key: Int, val playUrl: String, val format: String) : RelayOpen()
    class Refused(val reason: String) : RelayOpen()
}

/** Owns the local relay server and its sessions. Sessions end on request, on idle/max age, or when the network changes. */
class RelayManager(
    private val now: () -> Long = System::currentTimeMillis,
    private val serverFactory: (String, (String) -> RelaySession?) -> RelayServer = { host, lookup -> RelayServer(host, 0, lookup) },
) {
    private val byToken = ConcurrentHashMap<String, RelaySession>()
    private val byKey = ConcurrentHashMap<Int, RelaySession>()
    private val keys = AtomicInteger(1)
    private var server: RelayServer? = null
    private var boundTo: String? = null

    @Volatile var onChange: (() -> Unit)? = null

    val activeCount: Int get() = byKey.size

    @Synchronized
    fun open(spec: RelaySpec): RelayOpen {
        if (spec.format != "hls" && spec.format != "mp4")
            return RelayOpen.Refused("The phone relay supports MP4 and HLS (.m3u8) streams only.")
        val userHost = hostOf(spec.userEnteredUrl)
        val privateOk = if (userHost != null && isPrivateHost(userHost)) setOf(userHost) else emptySet()
        UpstreamPolicy.checkUrl(spec.sourceUrl, privateOk)?.let {
            return RelayOpen.Refused("The relay won't fetch that address ($it).")
        }
        if (byKey.size >= MAX_SESSIONS) return RelayOpen.Refused("Another relay is already running. Stop it first.")

        val srv = ensureServer(spec.bindHost)
        val session = RelaySession(
            RelaySession.newToken(), RelayServer.normalizeIp(spec.clientIp), spec.sourceUrl, spec.context,
            privateOk, "http://${spec.bindHost}:${srv.listeningPort}", now,
        )
        byToken[session.token] = session
        val key = keys.getAndIncrement()
        byKey[key] = session
        val playUrl = session.relayUrlFor(spec.sourceUrl)
        onChange?.invoke()
        return RelayOpen.Ready(key, playUrl, spec.format)
    }

    /**
     * Serves a file that lives on the phone (a picked video, or a photo prepared for the TV) to the Roku, with the same
     * protections as a web relay: unguessable token, only that Roku's address, only this one file.
     */
    @Synchronized
    fun openLocal(bindHost: String, clientIp: String, source: LocalSource, ext: String, format: String): RelayOpen {
        if (byKey.size >= MAX_SESSIONS) return RelayOpen.Refused("Another relay is already running. Stop it first.")
        val srv = ensureServer(bindHost)
        val session = RelaySession(
            RelaySession.newToken(), RelayServer.normalizeIp(clientIp), "local-file",
            RequestContext(null, null, CookieSource.NONE, false, emptySet()),
            emptySet(), "http://$bindHost:${srv.listeningPort}", now,
        )
        byToken[session.token] = session
        val key = keys.getAndIncrement()
        byKey[key] = session
        val playUrl = session.localUrlFor(source, ext)
        onChange?.invoke()
        return RelayOpen.Ready(key, playUrl, format)
    }

    private fun ensureServer(bindHost: String): RelayServer {
        if (server != null && boundTo != bindHost) shutdown()
        return server ?: serverFactory(bindHost) { byToken[it] }.also {
            it.start(30_000, false)
            server = it; boundTo = bindHost
        }
    }

    fun stats(key: Int): RelayStats? = byKey[key]?.stats

    @Synchronized
    fun close(key: Int) {
        val s = byKey.remove(key) ?: return
        s.closed = true
        byToken.remove(s.token)
        server?.forget(s.token)
        if (byKey.isEmpty()) shutdown()
        onChange?.invoke()
    }

    @Synchronized
    fun closeAll() {
        byKey.keys.toList().forEach { close(it) }
        shutdown()
    }

    /** Called periodically by the foreground service. Returns how many sessions were ended. */
    @Synchronized
    fun expire(idleMs: Long = IDLE_MS, maxMs: Long = MAX_MS): Int {
        val t = now()
        val dead = byKey.filter { (_, s) -> t - s.lastActivity > idleMs || t - s.createdAt > maxMs }.keys.toList()
        dead.forEach { close(it) }
        return dead.size
    }

    private fun shutdown() {
        server?.stop()
        server = null
        boundTo = null
    }

    companion object {
        const val MAX_SESSIONS = 2
        const val IDLE_MS = 10 * 60 * 1000L
        const val MAX_MS = 12 * 60 * 60 * 1000L

        private fun hostOf(url: String) = Regex("^https?://(?:[^/@]*@)?([^/:?#]+)", RegexOption.IGNORE_CASE).find(url.trim())?.groupValues?.get(1)?.lowercase()

        private fun isPrivateHost(host: String): Boolean {
            if (host == "localhost" || host.endsWith(".local")) return true
            if (!UpstreamPolicy.isIpLiteral(host)) return false
            return try { UpstreamPolicy.isBlocked(java.net.InetAddress.getByName(host)) } catch (_: Exception) { false }
        }
    }
}

/** One relay for the whole app process. */
object Relay {
    val manager = RelayManager()
}

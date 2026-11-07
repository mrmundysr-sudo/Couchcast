package com.mundymorning.couchcast.relay

import com.mundymorning.couchcast.playback.CookieSource
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** What a browser would send for the source site. Cookies are fetched on demand and never stored here. */
class RequestContext(
    val referer: String?,
    val userAgent: String?,
    val cookieSource: CookieSource,
    /** Cookies are attached only when true (the user chose a signed-in/session relay) and only to [credentialHosts]. */
    val attachCookies: Boolean,
    val credentialHosts: Set<String>,
)

/** Counters only; no URLs, tokens or cookies. Safe to show in diagnostics. */
class RelayStats {
    val requests = AtomicInteger()
    val bytes = AtomicLong()
    val rewrittenPlaylists = AtomicInteger()
    val upstreamStatus: MutableMap<Int, Int> = ConcurrentHashMap()
    @Volatile var drmDetected: String? = null
    @Volatile var lastError: String? = null

    fun count(status: Int) { upstreamStatus.merge(status, 1, Int::plus) }
    fun summary(): String =
        "requests=${requests.get()}, bytes=${bytes.get()}, upstream=${upstreamStatus.entries.sortedBy { it.key }.joinToString { "${it.key}x${it.value}" }}"
}

class RelaySession(
    /** Unguessable per-session credential. Appears only in the relay URL the Roku is given; never logged. */
    val token: String,
    val clientIp: String,
    val originUrl: String,
    val context: RequestContext,
    val privateOk: Set<String>,
    val baseUrl: String,
    private val now: () -> Long,
) {
    private val table = ConcurrentHashMap<Int, String>()
    private val ids = AtomicInteger()
    val stats = RelayStats()
    @Volatile var lastActivity: Long = now()
    @Volatile var closed = false
    val createdAt: Long = now()

    fun register(url: String): Int {
        table.entries.firstOrNull { it.value == url }?.let { return it.key }
        val id = ids.getAndIncrement()
        table[id] = url
        return id
    }

    fun lookup(id: Int): String? = table[id]

    private val locals = ConcurrentHashMap<Int, LocalSource>()

    /** A file on this phone (not a web address). It is served only by this session, to this session's Roku. */
    fun localUrlFor(source: LocalSource, ext: String): String {
        val id = ids.getAndIncrement()
        locals[id] = source
        val clean = ext.lowercase().takeIf { it.length in 2..5 && it.all { c -> c.isLetterOrDigit() } }
        return "$baseUrl/r/$token/$id" + (clean?.let { ".$it" } ?: "")
    }

    fun localLookup(id: Int): LocalSource? = locals[id]
    fun touch() { lastActivity = now() }

    fun relayUrlFor(url: String): String {
        val id = register(url)
        val ext = url.substringBefore('?').substringBefore('#').substringAfterLast('/', "").substringAfterLast('.', "")
            .takeIf { it.length in 2..5 && it.all { c -> c.isLetterOrDigit() } }
        return "$baseUrl/r/$token/$id" + (ext?.let { ".${it.lowercase()}" } ?: "")
    }

    override fun toString() = "RelaySession(redacted)"

    companion object {
        fun newToken(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}

package com.mundymorning.couchcast.playback

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Stops the relay from becoming an open proxy into the user's network. Public internet hosts only,
 * except a host the user themself gave as the source (e.g. their own media server).
 */
object UpstreamPolicy {
    fun isBlocked(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return true
        val b = a.address
        if (b.size == 4) {
            val x = b[0].toInt() and 0xff
            val y = b[1].toInt() and 0xff
            if (x == 100 && y in 64..127) return true      // carrier-grade NAT
            if (x == 0 || x >= 240) return true             // "this" network / reserved
            if (x == 192 && y == 0) return true             // IETF protocol assignments / TEST-NET-1 family
        } else if (b.size == 16) {
            if ((b[0].toInt() and 0xfe) == 0xfc) return true // unique local fc00::/7
            // IPv4-mapped (::ffff:a.b.c.d) is already converted to Inet4Address by the JDK.
        }
        return false
    }

    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    fun isIpLiteral(host: String) = IPV4.matches(host) || host.contains(':')

    /** [privateOk] are hosts the user explicitly chose as the source. Returns a reason when blocked, else null. */
    fun checkUrl(url: String, privateOk: Set<String>): String? {
        val u = url.toHttpUrlOrNull() ?: return "not a web address"
        if (u.scheme != "http" && u.scheme != "https") return "only http/https"
        val host = u.host.lowercase()
        if (host in privateOk) return null
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal")) return "local network address"
        if (isIpLiteral(host)) {
            val addr = try { InetAddress.getByName(host) } catch (_: Exception) { return "bad address" }
            if (isBlocked(addr)) return "private or local network address"
        }
        return null
    }

    /** DNS that refuses names resolving to private/local addresses, so a public name can't point inside the home network. */
    class PolicyDns(private val privateOk: Set<String>, private val delegate: Dns = Dns.SYSTEM) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = delegate.lookup(hostname)
            if (hostname.lowercase() in privateOk) return all
            val ok = all.filterNot { isBlocked(it) }
            if (ok.isEmpty()) throw UnknownHostException("blocked: resolves to a private or local address")
            return ok
        }
    }
}

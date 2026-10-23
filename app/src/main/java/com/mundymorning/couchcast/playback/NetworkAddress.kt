package com.mundymorning.couchcast.playback

import java.net.Inet4Address
import java.net.InetAddress

object NetworkAddress {
    /** The phone's private IPv4 address on the current Wi-Fi/Ethernet network, or null. */
    fun pickLanIpv4(addresses: List<InetAddress>): String? =
        addresses.filterIsInstance<Inet4Address>().filterNot { it.isLoopbackAddress || it.isLinkLocalAddress || it.isAnyLocalAddress }
            .let { v4 -> (v4.firstOrNull { it.isSiteLocalAddress } ?: v4.firstOrNull())?.hostAddress }

    /** Is this Roku on the same /24 as the phone? Used only to explain "Roku can't reach the phone" cases. */
    fun sameSubnet24(a: String, b: String): Boolean =
        a.substringBeforeLast('.', "x") == b.substringBeforeLast('.', "y") && a.count { it == '.' } == 3 && b.count { it == '.' } == 3
}

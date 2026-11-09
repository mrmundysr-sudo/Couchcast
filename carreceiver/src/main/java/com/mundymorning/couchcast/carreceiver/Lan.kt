package com.mundymorning.couchcast.carreceiver

import java.net.Inet4Address
import java.net.NetworkInterface

object Lan {
    /** Only devices on the local network (or this device) may talk to the receiver. */
    fun isLocalClient(raw: String?): Boolean {
        var a = raw.orEmpty().trim().removePrefix("/").substringBefore('%')
        if (a.startsWith("::ffff:", true)) a = a.substring(7)
        if (a == "::1" || a == "localhost") return true
        val lower = a.lowercase()
        if (lower.startsWith("fe80:") || lower.startsWith("fc") && ':' in lower || lower.startsWith("fd") && ':' in lower) return true
        val p = a.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 127 || p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) || (p[0] == 169 && p[1] == 254)
    }

    /** This device's private IPv4 addresses, to show on screen as the controller address. */
    fun addresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .map { it.hostAddress.orEmpty() }.filter { isLocalClient(it) && !it.startsWith("127.") }.distinct()
    } catch (_: Exception) { emptyList() }
}

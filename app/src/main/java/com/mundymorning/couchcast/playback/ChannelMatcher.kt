package com.mundymorning.couchcast.playback

import com.mundymorning.couchcast.roku.RokuApp

/** Finds a service's official channel in the Roku's own installed-app list. Identifiers always come from the device. */
object ChannelMatcher {
    fun normalize(name: String): String = name.lowercase().filter { it.isLetterOrDigit() || it == '+' }

    fun find(service: StreamingService, apps: List<RokuApp>): RokuApp? {
        val wanted = service.channelNames.map { normalize(it) }.toSet()
        return apps.filter { it.id != "dev" }.firstOrNull { normalize(it.name) in wanted }
    }

    /** Every installed channel that belongs to a known service (for the manual "Open a streaming channel" list). */
    fun installedServices(apps: List<RokuApp>): List<Pair<StreamingService, RokuApp>> =
        StreamingServices.all.distinctBy { it.displayName }.mapNotNull { s -> find(s, apps)?.let { s to it } }
}

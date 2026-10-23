package com.mundymorning.couchcast.playback

/** How (and whether) a service's Roku channel can be sent to a specific title. Best effort only. */
enum class DeepLink { NONE, YOUTUBE_VIDEO, NETFLIX_TITLE }

data class StreamingService(
    val id: String,
    val displayName: String,
    /** Host names (and their subdomains) that identify this service. */
    val hosts: List<String>,
    /** Names the Roku lists the official channel under. Matched against the Roku's own app list; no channel IDs are hardcoded. */
    val channelNames: List<String>,
    val deepLink: DeepLink = DeepLink.NONE,
    /** For shared hosts (e.g. amazon.com) only paths containing one of these count. Empty = any path. */
    val pathContains: List<String> = emptyList(),
)

data class ServiceMatch(val service: StreamingService, val contentId: String?)

object StreamingServices {
    val all: List<StreamingService> = listOf(
        StreamingService("netflix", "Netflix", listOf("netflix.com"), listOf("Netflix"), DeepLink.NETFLIX_TITLE),
        StreamingService("youtube", "YouTube", listOf("youtube.com", "youtu.be", "youtube-nocookie.com"), listOf("YouTube"), DeepLink.YOUTUBE_VIDEO),
        StreamingService("youtubetv", "YouTube TV", listOf("tv.youtube.com"), listOf("YouTube TV")),
        StreamingService("hulu", "Hulu", listOf("hulu.com"), listOf("Hulu")),
        StreamingService("disney", "Disney+", listOf("disneyplus.com"), listOf("Disney+", "Disney Plus")),
        StreamingService("prime", "Prime Video", listOf("primevideo.com"), listOf("Prime Video", "Amazon Prime Video")),
        StreamingService("prime-amazon", "Prime Video", listOf("amazon.com"), listOf("Prime Video", "Amazon Prime Video"),
            pathContains = listOf("/gp/video", "/Prime-Video", "/video/detail")),
        StreamingService("peacock", "Peacock", listOf("peacocktv.com"), listOf("Peacock TV", "Peacock")),
        StreamingService("paramount", "Paramount+", listOf("paramountplus.com"), listOf("Paramount+", "Paramount Plus")),
        StreamingService("max", "Max", listOf("max.com", "hbomax.com"), listOf("Max", "HBO Max")),
        StreamingService("appletv", "Apple TV", listOf("tv.apple.com"), listOf("Apple TV", "Apple TV+")),
        StreamingService("tubi", "Tubi", listOf("tubitv.com"), listOf("Tubi", "Tubi TV")),
        StreamingService("pluto", "Pluto TV", listOf("pluto.tv"), listOf("Pluto TV")),
        StreamingService("twitch", "Twitch", listOf("twitch.tv"), listOf("Twitch")),
        StreamingService("sling", "Sling TV", listOf("sling.com"), listOf("Sling TV", "Sling")),
        StreamingService("fubo", "Fubo", listOf("fubo.tv"), listOf("fuboTV", "Fubo")),
        StreamingService("crunchyroll", "Crunchyroll", listOf("crunchyroll.com"), listOf("Crunchyroll")),
        StreamingService("plex", "Plex", listOf("plex.tv"), listOf("Plex")),
        StreamingService("rokuchannel", "The Roku Channel", listOf("therokuchannel.roku.com"), listOf("The Roku Channel")),
    )

    private val URL = Regex("^https?://(?:[^/@]*@)?([^/:?#]+)(?::\\d+)?([^?#]*)(?:\\?([^#]*))?", RegexOption.IGNORE_CASE)

    /** Recognises a streaming-service link. Returns null for ordinary pages and direct media links. */
    fun recognize(rawUrl: String): ServiceMatch? {
        val m = URL.find(rawUrl.trim()) ?: return null
        val host = m.groupValues[1].lowercase().removePrefix("www.")
        val path = m.groupValues[2]
        val query = m.groupValues[3]
        val service = all
            .filter { s -> s.hosts.any { host == it || host.endsWith(".$it") } }
            .filter { s -> s.pathContains.isEmpty() || s.pathContains.any { path.contains(it, ignoreCase = true) } }
            // most specific host wins (tv.youtube.com over youtube.com)
            .maxByOrNull { s -> s.hosts.filter { host == it || host.endsWith(".$it") }.maxOf { it.length } }
            ?: return null
        return ServiceMatch(service, contentIdFor(service, host, path, query))
    }

    private val YT_ID = Regex("^[A-Za-z0-9_-]{11}$")

    private fun contentIdFor(s: StreamingService, host: String, path: String, query: String): String? = when (s.deepLink) {
        DeepLink.YOUTUBE_VIDEO -> {
            val fromQuery = query.split('&').firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
            val fromPath = when {
                host == "youtu.be" -> path.trim('/').substringBefore('/')
                else -> Regex("^/(?:shorts|embed|live|v)/([^/?#]+)").find(path)?.groupValues?.get(1)
            }
            listOfNotNull(fromQuery, fromPath).firstOrNull { YT_ID.matches(it) }
        }
        DeepLink.NETFLIX_TITLE -> Regex("/(?:title|watch)/(\\d+)").find(path)?.groupValues?.get(1)
        DeepLink.NONE -> null
    }
}

package com.mundymorning.couchcast.roku

data class Room(
    val ip: String,
    val name: String,
    val model: String = "",
    val isTv: Boolean = false,
    val online: Boolean = false,
)

data class DeviceInfo(
    val name: String,
    val model: String,
    val isTv: Boolean,
    val powerMode: String,
)

data class RokuApp(val id: String, val name: String, val version: String = "")

object RokuParse {
    private fun tag(xml: String, name: String): String =
        Regex("<$name>([^<]*)</$name>").find(xml)?.groupValues?.get(1)?.trim().orEmpty()

    fun unescape(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&")

    fun deviceInfo(xml: String): DeviceInfo {
        val userName = unescape(tag(xml, "user-device-name"))
        val friendly = unescape(tag(xml, "friendly-device-name"))
        return DeviceInfo(
            name = userName.ifEmpty { friendly }.ifEmpty { "Roku" },
            model = unescape(tag(xml, "model-name")),
            isTv = tag(xml, "is-tv").equals("true", ignoreCase = true),
            powerMode = tag(xml, "power-mode"),
        )
    }

    fun apps(xml: String): List<RokuApp> =
        Regex("<app\\s+([^>]*)>([^<]*)</app>").findAll(xml).mapNotNull { m ->
            val id = Regex("id=\"([^\"]+)\"").find(m.groupValues[1])?.groupValues?.get(1)
            val version = Regex("version=\"([^\"]*)\"").find(m.groupValues[1])?.groupValues?.get(1).orEmpty()
            id?.let { RokuApp(it, unescape(m.groupValues[2].trim()), version) }
        }.toList()

    fun ssdpLocationHost(response: String): String? =
        Regex("(?im)^location:\\s*http://([^:/\\s]+)").find(response)?.groupValues?.get(1)
}

package com.mundymorning.couchcast.playback

enum class ReceiverPlatform { ROKU, GOOGLE_TV, ANDROID_TV, FIRE_TV, GOOGLE_CAST, UNKNOWN }

/** YES = verified by the device, LIKELY = usual for this platform but model-dependent, UNKNOWN = can't tell, NO = not available. */
enum class Support { YES, LIKELY, UNKNOWN, NO }

data class ReceiverCapabilities(
    val platform: ReceiverPlatform,
    val directPlay: Support,
    val channelLinks: Support,
    val mirroring: Support,
    val relay: Support,
    val notes: List<String> = emptyList(),
)

/**
 * Per-platform capability table. Couch Cast discovers and controls Rokus today; the other platforms are described
 * so the same planner and guidance can serve them once a receiver for them exists.
 */
object CapabilityDetector {
    /** [receiverInstalled]: true/false once the Roku's app list was read, null if unknown. */
    fun roku(receiverInstalled: Boolean?): ReceiverCapabilities = ReceiverCapabilities(
        platform = ReceiverPlatform.ROKU,
        directPlay = when (receiverInstalled) { true -> Support.YES; false -> Support.NO; null -> Support.UNKNOWN },
        channelLinks = Support.YES,
        // ECP does not report screen-mirroring support, so this can't be verified from the phone.
        mirroring = Support.LIKELY,
        relay = when (receiverInstalled) { true -> Support.YES; false -> Support.NO; null -> Support.UNKNOWN },
        notes = listOf("Screen mirroring support depends on the Roku model; check Settings > System > Screen mirroring on the TV."),
    )

    fun forPlatform(platform: ReceiverPlatform): ReceiverCapabilities = when (platform) {
        ReceiverPlatform.ROKU -> roku(null)
        ReceiverPlatform.GOOGLE_TV, ReceiverPlatform.ANDROID_TV, ReceiverPlatform.GOOGLE_CAST -> ReceiverCapabilities(
            platform, Support.NO, Support.NO, Support.YES, Support.NO,
            listOf("Built-in Google Cast supports mirroring from Android's Cast screen. Couch Cast cannot play or relay to this platform yet."))
        ReceiverPlatform.FIRE_TV -> ReceiverCapabilities(
            platform, Support.NO, Support.NO, Support.LIKELY, Support.NO,
            listOf("Fire TV supports Display Mirroring (Miracast) when it is switched on in the Fire TV settings."))
        ReceiverPlatform.UNKNOWN -> ReceiverCapabilities(platform, Support.NO, Support.NO, Support.UNKNOWN, Support.NO, emptyList())
    }
}

package com.mundymorning.couchcast.playback

/** The plain-English state shown to the user. */
enum class PlaybackStatus(val label: String) {
    IDLE(""),
    CHECKING("Checking…"),
    PLAYING_DIRECT("Playing directly on TV"),
    OPENING_CHANNEL("Opening Roku channel"),
    CHANNEL_OPEN("Roku channel is open"),
    CHANNEL_NOT_CONFIRMED("Sent to the Roku channel (not confirmed)"),
    MIRRORING("Screen mirroring"),
    MIRROR_NOT_CONFIRMED("Mirroring not confirmed"),
    STARTING_RELAY("Starting phone relay…"),
    PLAYING_RELAY("Playing through phone relay"),
    SIGN_IN_REQUIRED("Sign-in required"),
    UNSUPPORTED_PROTECTED("Unsupported protected stream"),
    FAILED("Couldn't play"),
}

/** Which fallbacks to show, given a diagnosis. The manual "More ways to play" list is separate and always available. */
object PlaybackPlanner {
    fun statusFor(d: Diagnosis): PlaybackStatus = when (d.kind) {
        FailureKind.DRM_PROTECTED -> PlaybackStatus.UNSUPPORTED_PROTECTED
        FailureKind.SIGN_IN_REQUIRED -> PlaybackStatus.SIGN_IN_REQUIRED
        else -> PlaybackStatus.FAILED
    }

    /** Offers the platform can actually carry out. */
    fun usable(offers: List<Offer>, caps: ReceiverCapabilities): List<Offer> = offers.filter {
        when (it) {
            Offer.OPEN_CHANNEL -> caps.channelLinks != Support.NO
            Offer.MIRROR -> caps.mirroring != Support.NO
            Offer.RELAY -> caps.relay != Support.NO
            Offer.SIGN_IN -> true
        }
    }.distinct()
}

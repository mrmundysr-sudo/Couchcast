package com.mundymorning.couchcast.playback

data class GuideStep(val where: String, val text: String)

data class MirrorGuide(
    val support: Support,
    val headline: String,
    val steps: List<GuideStep>,
    /** True when Android's own Cast/Smart View screen is the right place to continue. */
    val canOpenPhoneCastSettings: Boolean,
    val caveat: String,
)

/** What the user must do to mirror to each platform. Android has no public API to start Miracast silently, so this guides. */
object MirroringGuide {
    private const val PROTECTED = "Protected apps (Netflix, Disney+, Hulu and similar) usually show a black picture when mirrored. That is the app's copy protection, not a Couch Cast fault."

    fun forPlatform(platform: ReceiverPlatform, support: Support): MirrorGuide = when {
        support == Support.NO -> unsupported()
        platform == ReceiverPlatform.ROKU -> MirrorGuide(
            support, "Mirror your phone screen to the Roku",
            listOf(
                GuideStep("On the TV", "Press Home, then open Settings > System > Screen mirroring."),
                GuideStep("On the TV", "Set Screen mirroring mode to Prompt or Always allow. If you don't see a Screen mirroring item, this Roku doesn't support it."),
                GuideStep("On your phone", "Tap Open phone cast settings below, then choose your Roku from the list. Some phones call this Cast, Smart View or Screen Cast."),
                GuideStep("On the TV", "If the Roku asks to allow the connection, choose Accept."),
            ), canOpenPhoneCastSettings = true, caveat = PROTECTED)
        platform == ReceiverPlatform.GOOGLE_TV || platform == ReceiverPlatform.ANDROID_TV || platform == ReceiverPlatform.GOOGLE_CAST -> MirrorGuide(
            support, "Mirror your phone screen with Google Cast",
            listOf(
                GuideStep("On your phone", "Tap Open phone cast settings below, or use Cast screen from the quick settings."),
                GuideStep("On your phone", "Choose your TV or Chromecast from the list and accept the screen-capture request."),
            ), canOpenPhoneCastSettings = true, caveat = PROTECTED)
        platform == ReceiverPlatform.FIRE_TV -> MirrorGuide(
            support, "Mirror your phone screen to the Fire TV",
            listOf(
                GuideStep("On the TV", "Open Settings > Display & Sounds > Enable Display Mirroring (wording varies by model)."),
                GuideStep("On your phone", "Tap Open phone cast settings below and choose the Fire TV."),
            ), canOpenPhoneCastSettings = true, caveat = PROTECTED)
        else -> unsupported()
    }

    private fun unsupported() = MirrorGuide(
        Support.NO, "This TV doesn't support screen mirroring",
        listOf(GuideStep("Options", "Use a direct video link, open the streaming service's own Roku channel, or try the phone relay for ordinary video streams.")),
        canOpenPhoneCastSettings = false, caveat = "")
}

/** What Android itself reports. Mirroring is only called connected when an external display exists. */
enum class MirrorState { NOT_DETECTED, CONNECTED }

object MirrorStatus {
    fun fromDisplays(externalDisplayCount: Int): MirrorState =
        if (externalDisplayCount > 0) MirrorState.CONNECTED else MirrorState.NOT_DETECTED

    fun message(state: MirrorState) = when (state) {
        MirrorState.CONNECTED -> "Android reports a wireless display is connected. Check the TV to confirm your screen is showing."
        MirrorState.NOT_DETECTED -> "Mirroring not confirmed yet. Some phones don't report Cast connections to apps, so check the TV."
    }
}

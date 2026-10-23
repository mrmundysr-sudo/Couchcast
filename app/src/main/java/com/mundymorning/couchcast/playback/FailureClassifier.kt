package com.mundymorning.couchcast.playback

enum class FailureKind {
    DRM_PROTECTED, NEEDS_REQUEST_CONTEXT, NEEDS_SESSION, SIGN_IN_REQUIRED, ACCESS_DENIED,
    NOT_FOUND, NETWORK, DASH_ONLY, ROKU_COULD_NOT_PLAY, UNKNOWN,
}

/** The fallbacks Couch Cast may offer. Direct playback is never in this list: it is always tried first. */
enum class Offer { OPEN_CHANNEL, MIRROR, RELAY, SIGN_IN }

data class Diagnosis(
    val kind: FailureKind,
    val headline: String,
    val detail: String,
    val offers: List<Offer>,
)

data class DiagnoseInput(
    val probe: ProbeResult?,
    /** What the Roku itself reported when it stopped, if anything. */
    val rokuDrm: String? = null,
    val service: ServiceMatch? = null,
    /** True when the phone's browser store already has cookies for the source's site. */
    val hasSiteSession: Boolean = false,
    val directFailed: Boolean = true,
)

object FailureClassifier {
    private enum class V { OPEN, NEEDS_CONTEXT, NEEDS_SESSION, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, NETWORK, OTHER }

    private fun verdict(l: Ladder): V {
        val all = listOfNotNull(l.bare, l.context, l.session)
        return when {
            l.bare.ok -> V.OPEN
            l.context.ok -> V.NEEDS_CONTEXT
            l.session?.ok == true -> V.NEEDS_SESSION
            all.any { it.status == 401 } -> V.UNAUTHORIZED
            all.any { it.status == 404 || it.status == 410 } -> V.NOT_FOUND
            all.any { it.status == 403 || it.status == 451 } -> V.FORBIDDEN
            all.all { it.status == null } -> V.NETWORK
            else -> V.OTHER
        }
    }

    fun diagnose(i: DiagnoseInput): Diagnosis {
        val chan = if (i.service != null) listOf(Offer.OPEN_CHANNEL) else emptyList()
        val drm = i.rokuDrm?.takeIf { it.isNotBlank() && !it.equals("none", true) } ?: i.probe?.drm
        if (drm != null) return Diagnosis(
            FailureKind.DRM_PROTECTED, "Unsupported protected stream",
            "This video is copy-protected ($drm). Couch Cast won't try to get around that." +
                (if (i.service != null) " ${i.service.service.displayName}'s own Roku channel can play it." else ""),
            chan + Offer.MIRROR)

        val p = i.probe ?: return Diagnosis(
            FailureKind.UNKNOWN, "The Roku couldn't play this",
            "Couch Cast couldn't check the stream from the phone either, so the reason isn't known.",
            chan + listOf(Offer.RELAY, Offer.MIRROR))

        if (p.isDash && !p.manifest.bare.ok && !p.manifest.context.ok) return worse(i, p, chan)
        if (p.isDash) return Diagnosis(FailureKind.DASH_ONLY, "This is a DASH (.mpd) stream",
            "The phone relay can't carry DASH streams, and the Roku couldn't play this one directly.", chan + Offer.MIRROR)

        val m = verdict(p.manifest)
        val s = p.segment?.let { verdict(it) } ?: V.OPEN
        val v = when {
            m != V.OPEN -> m
            s != V.OPEN -> s
            else -> V.OPEN
        }
        return when (v) {
            V.NEEDS_CONTEXT -> Diagnosis(FailureKind.NEEDS_REQUEST_CONTEXT, "The site only serves this to a browser",
                "The server refuses a bare request but accepts one that looks like it came from the page. The phone relay can make the request for the Roku.",
                listOf(Offer.RELAY) + chan + Offer.MIRROR)
            V.NEEDS_SESSION -> Diagnosis(FailureKind.NEEDS_SESSION, "The site needs your session",
                if (i.hasSiteSession) "The server only answers when your signed-in session is included. The phone relay can use it for this one playback."
                else "The server only answers with a session cookie from the site. Sign in or visit the site in Couch Cast first, then try the phone relay.",
                if (i.hasSiteSession) listOf(Offer.RELAY, Offer.MIRROR) else listOf(Offer.SIGN_IN, Offer.RELAY, Offer.MIRROR))
            V.UNAUTHORIZED -> Diagnosis(FailureKind.SIGN_IN_REQUIRED, "Sign-in required",
                "The site says you must be signed in to watch this.", listOf(Offer.SIGN_IN) + chan + Offer.MIRROR)
            V.FORBIDDEN -> Diagnosis(FailureKind.ACCESS_DENIED,
                if (i.hasSiteSession) "The site refused access" else "The site refused access (a sign-in may be needed)",
                if (i.hasSiteSession) "Even with your session the server refuses this stream. It may be region-locked, paid, or protected."
                else "The server refuses every kind of request from here. Signing in may help if the site needs an account.",
                (if (i.hasSiteSession) emptyList() else listOf(Offer.SIGN_IN)) + chan + Offer.MIRROR)
            V.NOT_FOUND -> Diagnosis(FailureKind.NOT_FOUND, "This stream link no longer works",
                "The site says it can't find the video. The link may have expired. Reload the page to find a fresh one.", chan)
            V.NETWORK -> Diagnosis(FailureKind.NETWORK, "Couldn't reach the stream",
                "The phone couldn't reach the video server either. Check your internet connection.", chan)
            V.OPEN, V.OTHER -> {
                val hop = if (p.manifest.bare.hops > 0) " The link also redirects through other servers, which some players handle badly; the relay follows redirects for the Roku." else ""
                val codec = p.codecs.filter { c -> RISKY_CODECS.any { c.startsWith(it, true) } }
                    .takeIf { it.isNotEmpty() }?.let { " It uses ${it.joinToString()}, which some Rokus can't decode." } ?: ""
                Diagnosis(FailureKind.ROKU_COULD_NOT_PLAY, "The stream is reachable, but the Roku couldn't play it",
                    "The server answers normally, so the problem is on the Roku's side (format or network handling).$codec$hop The phone relay may help; if not, mirroring will.",
                    listOf(Offer.RELAY) + chan + Offer.MIRROR)
            }
        }
    }

    private fun worse(i: DiagnoseInput, p: ProbeResult, chan: List<Offer>) = Diagnosis(
        FailureKind.UNKNOWN, "Couldn't open the stream", "The server didn't answer the phone either.", chan)

    private val RISKY_CODECS = listOf("av01", "vp09", "vp9", "hvc1", "hev1", "dvh1", "dvhe")
}

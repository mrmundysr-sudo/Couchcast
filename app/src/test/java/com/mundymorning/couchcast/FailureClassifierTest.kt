package com.mundymorning.couchcast

import com.mundymorning.couchcast.playback.*
import org.junit.Assert.*
import org.junit.Test

class FailureClassifierTest {
    private fun a(code: Int?, hops: Int = 0) = Attempt(code, null, hops)
    private fun probe(bare: Int?, ctx: Int?, ses: Int? = null, seg: Ladder? = null, drm: String? = null, hls: Boolean = true, codecs: List<String> = emptyList(), hops: Int = 0) =
        ProbeResult(hls, drm, codecs, Ladder(a(bare, hops), a(ctx), ses?.let { a(it) }), seg, hadSessionCookies = ses != null)

    private fun dx(p: ProbeResult?, service: ServiceMatch? = null, session: Boolean = false, rokuDrm: String? = null) =
        FailureClassifier.diagnose(DiagnoseInput(p, rokuDrm, service, session))

    @Test fun drmIsNeverRelayedOrBypassed() {
        val d = dx(probe(200, 200, drm = "com.apple.streamingkeydelivery"))
        assertEquals(FailureKind.DRM_PROTECTED, d.kind)
        assertFalse(d.offers.contains(Offer.RELAY)); assertFalse(d.offers.contains(Offer.SIGN_IN))
        assertTrue(d.offers.contains(Offer.MIRROR))
        assertEquals(PlaybackStatus.UNSUPPORTED_PROTECTED, PlaybackPlanner.statusFor(d))
    }

    @Test fun rokuReportedDrmWinsEvenWithoutAProbe() {
        assertEquals(FailureKind.DRM_PROTECTED, dx(null, rokuDrm = "widevine").kind)
        assertEquals("'none' is not DRM", FailureKind.UNKNOWN, dx(null, rokuDrm = "none").kind)
    }

    @Test fun serviceLinksOfferTheOfficialChannelForProtectedVideo() {
        val d = dx(probe(200, 200, drm = "SAMPLE-AES"), StreamingServices.recognize("https://netflix.com/title/1"))
        assertEquals(Offer.OPEN_CHANNEL, d.offers.first())
    }

    @Test fun missingHeadersPointToTheRelay() {
        val d = dx(probe(403, 200))
        assertEquals(FailureKind.NEEDS_REQUEST_CONTEXT, d.kind)
        assertEquals(Offer.RELAY, d.offers.first())
    }

    @Test fun segmentsThatNeedHeadersAreCaughtEvenWhenThePlaylistIsOpen() {
        val d = dx(probe(200, 200, seg = Ladder(a(403), a(200), null)))
        assertEquals(FailureKind.NEEDS_REQUEST_CONTEXT, d.kind)
    }

    @Test fun sessionOnlyAccessOffersSignInUnlessAlreadySignedIn() {
        val noSession = dx(probe(403, 403, ses = 200), session = false)
        assertEquals(FailureKind.NEEDS_SESSION, noSession.kind)
        assertTrue(noSession.offers.contains(Offer.SIGN_IN))
        val signedIn = dx(probe(403, 403, ses = 200), session = true)
        assertEquals(Offer.RELAY, signedIn.offers.first()); assertFalse(signedIn.offers.contains(Offer.SIGN_IN))
    }

    @Test fun unauthorizedMeansSignInRequired() {
        val d = dx(probe(401, 401))
        assertEquals(FailureKind.SIGN_IN_REQUIRED, d.kind); assertEquals(Offer.SIGN_IN, d.offers.first())
        assertEquals(PlaybackStatus.SIGN_IN_REQUIRED, PlaybackPlanner.statusFor(d))
    }

    @Test fun refusalEvenWithASessionIsNotSolvedBySigningInAgain() {
        val d = dx(probe(403, 403, ses = 403), session = true)
        assertEquals(FailureKind.ACCESS_DENIED, d.kind)
        assertFalse(d.offers.contains(Offer.SIGN_IN)); assertFalse(d.offers.contains(Offer.RELAY))
    }

    @Test fun expiredAndUnreachableStreamsAreExplained() {
        assertEquals(FailureKind.NOT_FOUND, dx(probe(404, 404)).kind)
        assertEquals(FailureKind.NETWORK, dx(probe(null, null)).kind)
        assertTrue(dx(probe(404, 404)).offers.isEmpty())
    }

    @Test fun reachableButUnplayableMentionsCodecsAndRedirects() {
        val d = dx(probe(200, 200, codecs = listOf("avc1.4d401e", "hvc1.2.4.L93.B0"), hops = 2))
        assertEquals(FailureKind.ROKU_COULD_NOT_PLAY, d.kind)
        assertTrue(d.detail.contains("hvc1")); assertTrue(d.detail.contains("redirects"))
        assertTrue(d.offers.containsAll(listOf(Offer.RELAY, Offer.MIRROR)))
    }

    @Test fun dashStreamsAreNotOfferedTheRelay() {
        val p = ProbeResult(false, null, emptyList(), Ladder(a(200), a(200), null), null, false, isDash = true)
        val d = dx(p)
        assertEquals(FailureKind.DASH_ONLY, d.kind); assertFalse(d.offers.contains(Offer.RELAY))
    }

    @Test fun offersAreFilteredByWhatTheReceiverCanDo() {
        val offers = listOf(Offer.OPEN_CHANNEL, Offer.MIRROR, Offer.RELAY, Offer.SIGN_IN)
        val cast = CapabilityDetector.forPlatform(ReceiverPlatform.GOOGLE_CAST)
        assertEquals(listOf(Offer.MIRROR, Offer.SIGN_IN), PlaybackPlanner.usable(offers, cast))
        val roku = CapabilityDetector.roku(true)
        assertEquals(offers, PlaybackPlanner.usable(offers, roku))
    }
}

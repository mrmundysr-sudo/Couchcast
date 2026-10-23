package com.mundymorning.couchcast

import com.mundymorning.couchcast.playback.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StreamProbeTest {
    private lateinit var site: MockWebServer
    private val referer = "https://site.example/page"
    private val ua = "UA-Test"

    @Before fun start() {
        site = MockWebServer()
        site.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val p = r.path!!.substringBefore('?')
                val ctx = r.getHeader("Referer") == referer && r.getHeader("User-Agent") == ua
                val cookie = r.getHeader("Cookie")?.contains("sid=ok") == true
                return when (p) {
                    "/open.mp4", "/open.m3u8" -> if (p.endsWith("m3u8")) hls("s.ts") else MockResponse().setBody("x")
                    "/s.ts" -> MockResponse().setBody("seg")
                    "/ctx.mp4" -> if (ctx) MockResponse().setBody("x") else MockResponse().setResponseCode(403)
                    "/ses.mp4" -> if (ctx && cookie) MockResponse().setBody("x") else MockResponse().setResponseCode(403)
                    "/auth.mp4" -> MockResponse().setResponseCode(401)
                    "/gone.mp4" -> MockResponse().setResponseCode(404)
                    "/segctx.m3u8" -> hls("segctx.ts")
                    "/segctx.ts" -> if (ctx) MockResponse().setBody("seg") else MockResponse().setResponseCode(403)
                    "/drm.m3u8" -> MockResponse().setBody("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://k\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n#EXTINF:4,\ns.ts\n")
                    "/hop.mp4" -> MockResponse().setResponseCode(302).setHeader("Location", "/open.mp4")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        site.start()
    }

    @After fun stop() = site.shutdown()

    private fun hls(seg: String) = MockResponse().setHeader("Content-Type", "application/x-mpegURL")
        .setBody("#EXTM3U\n#EXTINF:4,\n$seg\n#EXT-X-ENDLIST\n")

    private fun probe(path: String, cookies: String? = null) = runBlocking {
        StreamProbe().probe("http://127.0.0.1:${site.port}$path", referer, ua, CookieSource { cookies }, setOf("127.0.0.1"))
    }

    @Test fun openStreamsAnswerEveryWay() {
        val p = probe("/open.mp4")
        assertTrue(p.manifest.bare.ok && p.manifest.context.ok); assertNull(p.manifest.session)
    }

    @Test fun detectsStreamsThatNeedRequestContext() {
        val p = probe("/ctx.mp4")
        assertEquals(403, p.manifest.bare.status); assertEquals(200, p.manifest.context.status)
        assertEquals(FailureKind.NEEDS_REQUEST_CONTEXT, FailureClassifier.diagnose(DiagnoseInput(p)).kind)
    }

    @Test fun detectsStreamsThatNeedASessionOnlyWhenCookiesExist() {
        assertEquals(403, probe("/ses.mp4").manifest.context.status)
        val p = probe("/ses.mp4", cookies = "sid=ok")
        assertEquals(200, p.manifest.session!!.status)
        assertEquals(FailureKind.NEEDS_SESSION, FailureClassifier.diagnose(DiagnoseInput(p, hasSiteSession = true)).kind)
    }

    @Test fun separatesSignInFromNotFound() {
        assertEquals(FailureKind.SIGN_IN_REQUIRED, FailureClassifier.diagnose(DiagnoseInput(probe("/auth.mp4"))).kind)
        assertEquals(FailureKind.NOT_FOUND, FailureClassifier.diagnose(DiagnoseInput(probe("/gone.mp4"))).kind)
    }

    @Test fun looksAtTheFirstSegmentNotJustThePlaylist() {
        val p = probe("/segctx.m3u8")
        assertTrue(p.isHls); assertTrue(p.manifest.bare.ok)
        assertEquals(403, p.segment!!.bare.status); assertEquals(200, p.segment!!.context.status)
        assertEquals(FailureKind.NEEDS_REQUEST_CONTEXT, FailureClassifier.diagnose(DiagnoseInput(p)).kind)
    }

    @Test fun protectedStreamsAreRecognisedAndNotProbedFurther() {
        val p = probe("/drm.m3u8")
        assertNotNull(p.drm); assertNull(p.segment)
        assertEquals(FailureKind.DRM_PROTECTED, FailureClassifier.diagnose(DiagnoseInput(p)).kind)
    }

    @Test fun countsRedirects() {
        assertEquals(1, probe("/hop.mp4").manifest.bare.hops)
    }

    @Test fun unreachableHostsAreNetworkFailuresAndPrivateAddressesAreNeverFetched() = runBlocking {
        val p = StreamProbe().probe("http://192.168.1.1/x.mp4", referer, ua, CookieSource.NONE)
        assertEquals("blocked", p.manifest.bare.error)
        assertNull(p.manifest.bare.status)
    }
}

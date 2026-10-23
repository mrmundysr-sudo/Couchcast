package com.mundymorning.couchcast

import com.mundymorning.couchcast.playback.CookieSource
import com.mundymorning.couchcast.relay.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Runs the real relay against a fake website that demands ordinary request context
 * (Referer, User-Agent, a session cookie). "The Roku" is a plain HTTP client. Nothing here is DRM or access-control evasion.
 */
class RelayTest {
    private lateinit var site: MockWebServer
    private lateinit var manager: RelayManager
    private val referer = "https://site.example/watch/1"
    private val ua = "UA-Test/1.0"
    private val cookieValue = "S3CR3T-SESSION-VALUE"
    private val mp4 = ByteArray(10_000) { (it % 251).toByte() }
    private val seen = CopyOnWriteArrayList<RecordedRequest>()
    private var now = 1_000_000L
    private val roku = OkHttpClient.Builder().followRedirects(false).build()

    @Before fun start() {
        site = MockWebServer()
        site.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val path = request.path!!.substringBefore('?')
                val ctxOk = request.getHeader("Referer") == referer && request.getHeader("User-Agent") == ua
                val cookieOk = request.getHeader("Cookie")?.contains("sid=$cookieValue") == true
                return when (path) {
                    "/video.mp4" -> if (!ctxOk) MockResponse().setResponseCode(403) else ranged(request)
                    "/session.mp4" -> if (!ctxOk || !cookieOk) MockResponse().setResponseCode(403) else ranged(request)
                    "/master.m3u8" -> if (!ctxOk) MockResponse().setResponseCode(403) else MockResponse()
                        .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                        .setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,CODECS=\"avc1.4d401e,mp4a.40.2\"\nv/index.m3u8\n")
                    "/v/index.m3u8" -> if (!ctxOk) MockResponse().setResponseCode(403) else MockResponse()
                        .setHeader("Content-Type", "application/x-mpegURL")
                        .setBody("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:4,\nseg0.ts?sig=UPSTREAM-SIG\n#EXT-X-ENDLIST\n")
                    "/v/seg0.ts" -> if (!ctxOk) MockResponse().setResponseCode(403) else MockResponse().setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(ByteArray(2000) { 7 }))
                    "/v/key.bin" -> if (!ctxOk) MockResponse().setResponseCode(403) else MockResponse().setBody(Buffer().write(ByteArray(16) { 9 }))
                    "/drm.m3u8" -> MockResponse().setHeader("Content-Type", "application/x-mpegURL")
                        .setBody("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://k\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n#EXTINF:4,\ns.ts\n")
                    "/redir-private" -> MockResponse().setResponseCode(302).setHeader("Location", "http://169.254.169.254/latest/meta-data")
                    "/redir-ok" -> MockResponse().setResponseCode(302).setHeader("Location", "/video.mp4")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        site.start()
        manager = RelayManager(now = { now })
    }

    @After fun stop() { manager.closeAll(); site.shutdown() }

    private fun ranged(r: RecordedRequest): MockResponse {
        val range = r.getHeader("Range")?.removePrefix("bytes=")
        if (range == null) return MockResponse().setHeader("Accept-Ranges", "bytes").setHeader("Content-Type", "video/mp4").setBody(Buffer().write(mp4))
        val (a, b) = range.split('-')
        val from = a.toInt(); val to = if (b.isEmpty()) mp4.size - 1 else b.toInt()
        return MockResponse().setResponseCode(206).setHeader("Content-Type", "video/mp4").setHeader("Accept-Ranges", "bytes")
            .setHeader("Content-Range", "bytes $from-$to/${mp4.size}").setBody(Buffer().write(mp4.copyOfRange(from, to + 1)))
    }

    private fun url(path: String) = "http://127.0.0.1:${site.port}$path"

    private fun open(
        path: String, format: String = "mp4", attachCookies: Boolean = false, clientIp: String = "127.0.0.1",
        credentialHosts: Set<String> = setOf("127.0.0.1"), userEntered: String = url(path),
    ): RelayOpen = manager.open(RelaySpec(
        "127.0.0.1", clientIp, url(path), userEntered,
        RequestContext(referer, ua, CookieSource { "sid=$cookieValue" }, attachCookies, credentialHosts), format))

    private fun ready(o: RelayOpen) = (o as? RelayOpen.Ready) ?: fail("not ready: ${(o as RelayOpen.Refused).reason}").let { error("unreachable") }
    private fun get(u: String, range: String? = null) = roku.newCall(Request.Builder().url(u).apply { range?.let { header("Range", it) } }.build()).execute()

    @Test fun relaysAnMp4TheSiteWouldRefuseABareClient() {
        // The Roku alone is refused...
        assertEquals(403, get(url("/video.mp4")).use { it.code })
        // ...but through the relay the same request carries ordinary browser context.
        val r = ready(open("/video.mp4"))
        get(r.playUrl).use { assertEquals(200, it.code); assertArrayEquals(mp4, it.body!!.bytes()) }
    }

    @Test fun supportsSeekingWithRanges() {
        val r = ready(open("/video.mp4"))
        get(r.playUrl, "bytes=1000-1999").use {
            assertEquals(206, it.code)
            assertEquals("bytes 1000-1999/10000", it.header("Content-Range"))
            assertArrayEquals(mp4.copyOfRange(1000, 2000), it.body!!.bytes())
        }
        get(r.playUrl, "bytes=9000-").use { assertArrayEquals(mp4.copyOfRange(9000, 10000), it.body!!.bytes()) }
    }

    @Test fun relaysHlsManifestsKeysAndSegmentsWithoutLeakingUpstreamAddresses() {
        val r = ready(open("/master.m3u8", format = "hls"))
        val master = get(r.playUrl).use { assertEquals(200, it.code); it.body!!.string() }
        assertFalse(master.contains("127.0.0.1:${site.port}"))
        val mediaUrl = master.lines().first { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(mediaUrl.startsWith("http://127.0.0.1:"))
        val media = get(mediaUrl).use { it.body!!.string() }
        assertFalse("upstream signature must not appear on the LAN", media.contains("UPSTREAM-SIG") || media.contains(":${site.port}"))
        val keyUrl = Regex("URI=\"([^\"]+)\"").find(media)!!.groupValues[1]
        val segUrl = media.lines().first { it.isNotBlank() && !it.startsWith("#") }
        get(keyUrl).use { assertEquals(16, it.body!!.bytes().size) }
        get(segUrl).use { assertEquals(2000, it.body!!.bytes().size) }
        // every upstream request, including the segment on another path, carried the page context
        assertTrue(seen.filter { it.path!!.startsWith("/v/") || it.path!!.startsWith("/master") }.all { it.getHeader("Referer") == referer })
        assertTrue(seen.first { it.path!!.startsWith("/v/seg0.ts") }.path!!.contains("sig=UPSTREAM-SIG"))
    }

    @Test fun cookiesAreOnlySentWhenOptedInAndOnlyToTheSourcesHosts() {
        val noCookies = ready(open("/session.mp4", attachCookies = false))
        assertEquals(403, get(noCookies.playUrl).use { it.code })
        assertTrue(seen.none { it.getHeader("Cookie") != null })

        manager.closeAll()
        val otherHost = ready(open("/session.mp4", attachCookies = true, credentialHosts = setOf("other.example")))
        assertEquals("host not in the credential set: no cookie", 403, get(otherHost.playUrl).use { it.code })

        manager.closeAll()
        val ok = ready(open("/session.mp4", attachCookies = true))
        assertEquals(200, get(ok.playUrl).use { it.code })
    }

    @Test fun thePhoneNeverExposesSessionSecrets() {
        val r = ready(open("/session.mp4", attachCookies = true))
        get(r.playUrl).close()
        assertFalse(r.playUrl.contains(cookieValue)); assertFalse(r.playUrl.contains("sid="))
        val stats = manager.stats(r.key)!!
        assertFalse(stats.summary().contains(cookieValue)); assertFalse((stats.lastError ?: "").contains(cookieValue))
        assertEquals("RelaySession(redacted)", RelaySession("t", "ip", "u", RequestContext(null, null, CookieSource.NONE, false, emptySet()), emptySet(), "b") { 0L }.toString())
    }

    @Test fun onlyTheRokusAddressMayFetch() {
        val r = ready(open("/video.mp4", clientIp = "10.9.9.9"))
        assertEquals("another device on the network gets nothing", 403, get(r.playUrl).use { it.code })
        assertTrue("and no upstream request was made", seen.isEmpty())
    }

    @Test fun wrongTokensAndUnregisteredIdsAreNotFoundSoItIsNotAProxy() {
        val r = ready(open("/video.mp4"))
        val base = r.playUrl.substringBefore("/r/")
        assertEquals(404, get("$base/r/${"0".repeat(32)}/0.mp4").use { it.code })
        assertEquals("unknown id", 404, get(r.playUrl.replace(Regex("/\\d+\\.mp4$"), "/99.mp4")).use { it.code })
        assertEquals(404, get("$base/anything").use { it.code })
        assertEquals(404, get("$base/r/x").use { it.code })
        assertEquals("a URL is not a valid id", 404, get(r.playUrl.substringBeforeLast('/') + "/" + "http%3A%2F%2Fexample.com").use { it.code })
        assertTrue(seen.isEmpty())
    }

    @Test fun redirectsIntoThePrivateNetworkAreBlocked() {
        val r = ready(open("/redir-private"))
        assertEquals(403, get(r.playUrl).use { it.code })
        assertTrue(manager.stats(r.key)!!.lastError!!.startsWith("blocked"))
    }

    @Test fun ordinaryRedirectsAreFollowedWithContext() {
        val r = ready(open("/redir-ok"))
        get(r.playUrl).use { assertEquals(200, it.code); assertArrayEquals(mp4, it.body!!.bytes()) }
    }

    @Test fun drmStreamsAreRefusedNotRelayed() {
        val r = ready(open("/drm.m3u8", format = "hls"))
        assertEquals(403, get(r.playUrl).use { it.code })
        assertNotNull(manager.stats(r.key)!!.drmDetected)
    }

    @Test fun privateSourcesAreOnlyAllowedWhenTheUserChoseThem() {
        val sneaky = open("/video.mp4", userEntered = "https://innocent.example/page")
        assertTrue("a public page can't point the relay at a private address", sneaky is RelayOpen.Refused)
        assertTrue(open("/video.mp4") is RelayOpen.Ready)
    }

    @Test fun onlyMp4AndHlsAreRelayedAndSessionsAreLimited() {
        assertTrue(open("/video.mp4", format = "dash") is RelayOpen.Refused)
        assertTrue(open("/video.mp4") is RelayOpen.Ready)
        assertTrue(open("/video.mp4") is RelayOpen.Ready)
        assertTrue("third session refused", open("/video.mp4") is RelayOpen.Refused)
    }

    @Test fun closingASessionEndsAccessAndStopsTheServer() {
        val r = ready(open("/video.mp4"))
        assertEquals(200, get(r.playUrl).use { it.code })
        manager.close(r.key)
        assertEquals(0, manager.activeCount)
        val failed = try { get(r.playUrl).use { it.code } } catch (e: java.io.IOException) { -1 }
        assertTrue("session gone: 404 or server stopped ($failed)", failed == 404 || failed == -1)
        assertEquals("server is down when nothing is relaying", -1, try { get(r.playUrl).use { it.code } } catch (e: java.io.IOException) { -1 })
    }

    @Test fun idleAndOverlongSessionsExpire() {
        val r = ready(open("/video.mp4"))
        now += 5 * 60_000
        assertEquals(0, manager.expire()); assertEquals(1, manager.activeCount)
        now += 6 * 60_000
        assertEquals(1, manager.expire()); assertEquals(0, manager.activeCount)
        assertNull(manager.stats(r.key))
    }

    @Test fun statsTellRelayFailureApartFromDirectFailure() {
        val r = ready(open("/video.mp4"))
        assertEquals("the Roku never reached the phone", 0, manager.stats(r.key)!!.requests.get())
        get(r.playUrl).close()
        val s = manager.stats(r.key)!!
        assertEquals(1, s.requests.get()); assertEquals(1, s.upstreamStatus[200])
    }
}

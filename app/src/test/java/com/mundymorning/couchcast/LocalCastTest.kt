package com.mundymorning.couchcast

import com.mundymorning.couchcast.play.LocalKind
import com.mundymorning.couchcast.play.LocalMediaType
import com.mundymorning.couchcast.play.PlayResult
import com.mundymorning.couchcast.play.PlayerInfo
import com.mundymorning.couchcast.play.ReceiverPlayer
import com.mundymorning.couchcast.playback.PhotoSize
import com.mundymorning.couchcast.relay.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/** Casting a video or photo that lives on the phone: the relay serves just that file, seekably, to just that Roku. */
class LocalCastTest {
    private lateinit var manager: RelayManager
    private lateinit var file: File
    private val data = ByteArray(10_000) { (it % 251).toByte() }
    private val roku = OkHttpClient.Builder().followRedirects(false).build()

    @Before fun start() {
        manager = RelayManager()
        file = File.createTempFile("clip", ".mp4").also { it.writeBytes(data) }
    }

    @After fun stop() { manager.closeAll(); file.delete() }

    private fun open(clientIp: String = "127.0.0.1", ext: String = "mp4", format: String = "mp4"): RelayOpen.Ready =
        manager.openLocal("127.0.0.1", clientIp, FileLocalSource(file, "video/mp4", "clip.mp4"), ext, format) as RelayOpen.Ready

    private fun call(url: String, method: String = "GET", range: String? = null) =
        roku.newCall(Request.Builder().url(url).method(method, null).apply { range?.let { header("Range", it) } }.build()).execute()

    @Test fun servesTheWholeFileWithAnExtensionTheRokuCanRecognise() {
        val r = open()
        assertTrue(r.playUrl, r.playUrl.endsWith(".mp4"))
        assertEquals("mp4", r.format)
        call(r.playUrl).use {
            assertEquals(200, it.code)
            assertEquals("video/mp4", it.header("Content-Type"))
            assertEquals("bytes", it.header("Accept-Ranges"))
            assertArrayEquals(data, it.body!!.bytes())
        }
        assertEquals(data.size.toLong(), manager.stats(r.key)!!.bytes.get())
    }

    @Test fun seeksWithRanges() {
        val r = open()
        call(r.playUrl, range = "bytes=1000-1999").use {
            assertEquals(206, it.code)
            assertEquals("bytes 1000-1999/10000", it.header("Content-Range"))
            assertArrayEquals(data.copyOfRange(1000, 2000), it.body!!.bytes())
        }
        call(r.playUrl, range = "bytes=9000-").use { assertArrayEquals(data.copyOfRange(9000, 10000), it.body!!.bytes()) }
        call(r.playUrl, range = "bytes=-500").use {
            assertEquals("bytes 9500-9999/10000", it.header("Content-Range"))
            assertArrayEquals(data.copyOfRange(9500, 10000), it.body!!.bytes())
        }
        call(r.playUrl, range = "bytes=20000-").use {
            assertEquals(416, it.code)
            assertEquals("bytes */10000", it.header("Content-Range"))
        }
    }

    @Test fun headReportsTheSizeWithoutABody() {
        val r = open()
        call(r.playUrl, "HEAD").use {
            assertEquals(200, it.code)
            assertEquals("10000", it.header("Content-Length"))
            assertEquals(0, it.body!!.bytes().size)
        }
    }

    @Test fun onlyThatRokuWithTheRightAddressCanFetchIt() {
        val r = open(clientIp = "192.168.1.77")          // some other device is the Roku; the test client is 127.0.0.1
        assertEquals("wrong client", 403, call(r.playUrl).use { it.code })
        val ok = open()
        assertEquals(404, call(ok.playUrl.replace(Regex("/r/[0-9a-f]+/"), "/r/${"0".repeat(32)}/")).use { it.code })
        assertEquals("only registered ids are served", 404, call(ok.playUrl.substringBeforeLast('/') + "/999.mp4").use { it.code })
        assertEquals("read-only", 405, call(ok.playUrl, "DELETE").use { it.code })
    }

    @Test fun endingTheSessionRemovesIt() {
        val r = open()
        assertEquals(200, call(r.playUrl).use { it.code })
        assertEquals(1, manager.activeCount)
        manager.close(r.key)
        assertEquals(0, manager.activeCount)
        assertNull(manager.stats(r.key))
    }

    @Test fun logsNeverContainTheFileNameOrAddress() {
        val r = open()
        call(r.playUrl).close()
        val s = manager.stats(r.key)!!.summary()
        assertFalse(s, s.contains("clip") || s.contains(r.playUrl))
    }

    // ---- pure rules -----------------------------------------------------------------------------

    @Test fun rangeParsing() {
        assertEquals(ByteRange.Full, ByteRange.parse(null, 100))
        assertEquals(ByteRange.Full, ByteRange.parse("bytes=0-9", -1))                 // size unknown: no ranges
        assertEquals(ByteRange.Partial(0, 9), ByteRange.parse("bytes=0-9", 100))
        assertEquals(ByteRange.Partial(90, 99), ByteRange.parse("bytes=90-", 100))
        assertEquals(ByteRange.Partial(90, 99), ByteRange.parse("bytes=90-500", 100))  // clipped to the file
        assertEquals(ByteRange.Partial(70, 99), ByteRange.parse("bytes=-30", 100))
        assertEquals(ByteRange.Partial(0, 99), ByteRange.parse("bytes=-500", 100))
        assertEquals(ByteRange.Unsatisfiable, ByteRange.parse("bytes=100-", 100))
        assertEquals(ByteRange.Unsatisfiable, ByteRange.parse("bytes=-0", 100))
        assertEquals(ByteRange.Full, ByteRange.parse("bytes=5-1", 100))                // malformed: ignore the header
        assertEquals(ByteRange.Full, ByteRange.parse("bytes=0-1,5-9", 100))            // multiple ranges: serve it whole
        assertEquals(ByteRange.Full, ByteRange.parse("items=0-1", 100))
        assertEquals(ByteRange.Full, ByteRange.parse("bytes=abc-", 100))
    }

    @Test fun photosAreShrunkToFitTheTvWithoutDistortion() {
        assertEquals(1440 to 1080, PhotoSize.fit(4000, 3000))
        assertEquals("portrait photos fit too", 1080 to 1440, PhotoSize.fit(3000, 4000))
        assertEquals("never enlarged", 800 to 600, PhotoSize.fit(800, 600))
        assertEquals(1920 to 1080, PhotoSize.fit(1920, 1080))
        assertEquals("panorama is limited by its long side", 1920 to 480, PhotoSize.fit(7680, 1920))
        assertEquals(1, PhotoSize.sampleSize(1000, 1000))
        assertEquals(2, PhotoSize.sampleSize(4000, 3000))      // 2000x1500 still >= 1440x1080
        assertEquals(4, PhotoSize.sampleSize(8000, 6000))
    }

    @Test fun recognisesVideosAndPhotosByTypeOrByName() {
        assertEquals(LocalKind.VIDEO, LocalMediaType.kindOf("video/mp4", "x"))
        assertEquals(LocalKind.PHOTO, LocalMediaType.kindOf("image/heic", "x"))
        assertEquals(LocalKind.VIDEO, LocalMediaType.kindOf("application/octet-stream", "Holiday.MOV"))
        assertEquals(LocalKind.PHOTO, LocalMediaType.kindOf(null, "a.jpeg"))
        assertNull(LocalMediaType.kindOf("application/pdf", "doc.pdf"))
        assertEquals("mkv", LocalMediaType.videoFormat("video/x-matroska", "a"))
        assertEquals("mkv", LocalMediaType.videoFormat("", "film.MKV"))
        assertEquals("mp4", LocalMediaType.videoFormat("video/quicktime", "a.mov"))
    }

    @Test fun explainsWhyAPhoneVideoDidNotPlay() {
        val none = LocalMediaType.videoFailure(PlayResult.RECEIVER_OPEN_NOT_CONFIRMED, 0, 0, null)
        assertTrue(none, none.contains("same Wi-Fi"))
        val asked = LocalMediaType.videoFailure(PlayResult.RECEIVER_OPEN_NOT_CONFIRMED, 3, 0, null)
        assertTrue(asked, asked.contains("asked your phone"))
        val early = LocalMediaType.videoFailure(PlayResult.STOPPED_EARLY, 5, 2, PlayerInfo("close", true, video = "hevc", audio = "aac"))
        assertTrue(early, early.contains("2 seconds") && early.contains("hevc"))
    }

    @Test fun photoDeepLinkIsMarkedAsAPhoto() {
        val q = ReceiverPlayer.query("http://192.168.1.5:8000/r/t/0.jpg", ReceiverPlayer.PHOTO_FORMAT, mediaType = ReceiverPlayer.PHOTO_MEDIA_TYPE)
        assertTrue(q, q.endsWith("&mediaType=photo&format=photo"))
        assertTrue(ReceiverPlayer.requestPath(true, "http://x/p.jpg", "photo", mediaType = "photo").startsWith("/input?contentId="))
        // videos are unchanged
        assertTrue(ReceiverPlayer.query("https://x.com/v.mp4", "mp4").endsWith("&mediaType=shortFormVideo&format=mp4"))
    }
}

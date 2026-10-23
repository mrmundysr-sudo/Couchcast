package com.mundymorning.couchcast

import com.mundymorning.couchcast.install.RokuDevClient
import com.mundymorning.couchcast.play.PlayResult
import com.mundymorning.couchcast.play.ReceiverPlayer
import com.mundymorning.couchcast.playback.ReceiverPlatform
import com.mundymorning.couchcast.receiver.PlayRequest
import com.mundymorning.couchcast.receiver.Receiver
import com.mundymorning.couchcast.receiver.RokuReceiver
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Regression guard for the [Receiver] interface: going through [RokuReceiver] must send the very same ECP requests,
 * in the same order, as calling the hardware-verified [ReceiverPlayer] directly. A fake Roku, not a real one.
 */
class RokuReceiverTest {
    private lateinit var server: MockWebServer
    private val seen = CopyOnWriteArrayList<String>()
    @Volatile private var activeId = "tvinput.hdmi1"

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += "${request.method} ${request.path}"
                return when {
                    request.path!!.startsWith("/query/active-app") ->
                        MockResponse().setBody("<active-app><app id=\"$activeId\">x</app></active-app>")
                    request.path!!.startsWith("/query/media-player") ->
                        MockResponse().setBody("<player error=\"false\" state=\"play\"><format audio=\"aac\" drm=\"none\" video=\"h264\" video_res=\"1280x720\"/></player>")
                    request.path!!.startsWith("/launch/") || request.path!!.startsWith("/input") -> MockResponse()
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun player() = ReceiverPlayer(RokuDevClient(ecpPort = server.port), server.port)

    private fun mutating(log: List<String>) = log.filter { it.startsWith("POST ") }

    @Test fun reportsTheRokuPlatform() {
        val r: Receiver = RokuReceiver(player())
        assertEquals(ReceiverPlatform.ROKU, r.platform)
    }

    @Test fun sendsTheSameLaunchRequestAsTheDirectPlayer() = runBlocking {
        player().play("127.0.0.1", "https://x.com/v.m3u8?a=1&b=2", "hls", 3000, 1500, "https://site.com/watch", "UA/1")
        val direct = mutating(seen); seen.clear()

        val via: Receiver = RokuReceiver(player(), 3000, 1500)
        val out = via.play("127.0.0.1", PlayRequest("https://x.com/v.m3u8?a=1&b=2", "hls", "https://site.com/watch", "UA/1"))

        assertEquals(PlayResult.PLAYING, out.result)
        assertEquals(direct, mutating(seen))
        assertEquals(listOf("POST " + ReceiverPlayer.requestPath(false, "https://x.com/v.m3u8?a=1&b=2", "hls", "https://site.com/watch", "UA/1")), direct)
    }

    @Test fun sendsTheSameInputRequestWhenTheReceiverIsRunning() = runBlocking {
        activeId = "dev"
        player().play("127.0.0.1", "https://x.com/v.mp4", "mp4", 3000, 1500)
        val direct = mutating(seen); seen.clear()

        RokuReceiver(player(), 3000, 1500).play("127.0.0.1", PlayRequest("https://x.com/v.mp4", "mp4"))

        assertEquals(direct, mutating(seen))
        assertTrue(direct.toString(), direct.single().startsWith("POST /input?contentId="))
    }

    @Test fun plainRequestsAddNoOptionalHeaders() {
        val path = ReceiverPlayer.requestPath(false, "https://x.com/v.mp4", "mp4")
        assertEquals("/launch/dev?contentId=https%3A%2F%2Fx.com%2Fv.mp4&mediaType=shortFormVideo&format=mp4", path)
    }

    @Test fun playerInfoIsPassedThrough() = runBlocking {
        val info = RokuReceiver(player()).playerInfo("127.0.0.1")!!
        assertEquals("play", info.state); assertEquals("h264", info.video); assertEquals("1280x720", info.resolution)
    }
}

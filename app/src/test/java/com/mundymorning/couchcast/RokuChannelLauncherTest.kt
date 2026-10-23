package com.mundymorning.couchcast

import com.mundymorning.couchcast.install.RokuDevClient
import com.mundymorning.couchcast.playback.*
import com.mundymorning.couchcast.roku.RokuApp
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** A fake Roku ECP endpoint. It is not a real Roku. */
class RokuChannelLauncherTest {
    private lateinit var server: MockWebServer
    private val seen = CopyOnWriteArrayList<String>()
    @Volatile private var active = "tvinput.hdmi1"
    /** Channel ids that "open" when launched. Empty means launches are accepted but nothing opens. */
    @Volatile private var opens = setOf<String>()
    /** Ids whose deep-linked launch is ignored (plain launch still works), like a channel that rejects the link. */
    @Volatile private var rejectDeepLink = setOf<String>()
    @Volatile private var launchStatus = 200

    private val apps = listOf(RokuApp("12", "Netflix"), RokuApp("837", "YouTube"), RokuApp("dev", "Couchcast Receiver"))

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                seen += "${request.method} $path"
                return when {
                    path.startsWith("/query/active-app") -> MockResponse().setBody("<active-app><app id=\"$active\">x</app></active-app>")
                    path.startsWith("/launch/") -> {
                        val id = path.removePrefix("/launch/").substringBefore('?')
                        if (launchStatus == 200 && id in opens && !(path.contains("?") && id in rejectDeepLink)) active = id
                        MockResponse().setResponseCode(launchStatus)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun launcher() = RokuChannelLauncher(server.port, RokuDevClient(ecpPort = server.port))
    private fun m(url: String) = StreamingServices.recognize(url)!!

    @Test fun opensAnInstalledChannelAndWaitsForTheRokuToConfirm() = runBlocking {
        opens = setOf("12")
        val r = launcher().open("127.0.0.1", m("https://netflix.com/browse"), apps, confirmMs = 3000)
        assertTrue(r.toString(), r is ChannelLaunch.Opened)
        assertEquals("12", (r as ChannelLaunch.Opened).app.id)
        assertFalse("no title id in the link, so none is sent", r.titleLinkSent)
        assertEquals(listOf("POST /launch/12"), seen.filter { it.startsWith("POST") })
    }

    @Test fun usesTheDevicesIdNotAHardcodedOne() = runBlocking {
        opens = setOf("999")
        val r = launcher().open("127.0.0.1", m("https://netflix.com/title/80100172"),
            listOf(RokuApp("999", "Netflix")), confirmMs = 3000)
        assertTrue(r is ChannelLaunch.Opened)
        assertTrue(seen.toString(), seen.any { it.startsWith("POST /launch/999?contentId=80100172") })
    }

    @Test fun missingChannelIsReportedAndNothingIsSent() = runBlocking {
        val r = launcher().open("127.0.0.1", m("https://hulu.com/watch/x"), apps, confirmMs = 500)
        assertTrue(r is ChannelLaunch.NotInstalled)
        assertEquals("Hulu", (r as ChannelLaunch.NotInstalled).service.displayName)
        assertTrue(seen.none { it.startsWith("POST") })
    }

    @Test fun acceptedButNeverOpenedIsNotClaimedAsOpen() = runBlocking {
        opens = emptySet()
        val r = launcher().open("127.0.0.1", m("https://netflix.com/browse"), apps, confirmMs = 1200)
        assertTrue(r.toString(), r is ChannelLaunch.NotConfirmed)
    }

    @Test fun rejectedCommandIsAFailure() = runBlocking {
        launchStatus = 403
        val r = launcher().open("127.0.0.1", m("https://netflix.com/browse"), apps, confirmMs = 500)
        assertTrue(r is ChannelLaunch.Failed)
    }

    @Test fun aChannelThatIgnoresTheTitleLinkStillOpensWithAPlainLaunch() = runBlocking {
        opens = setOf("837"); rejectDeepLink = setOf("837")
        val r = launcher().open("127.0.0.1", m("https://youtube.com/watch?v=dQw4w9WgXcQ"), apps, confirmMs = 1500)
        assertTrue(r.toString(), r is ChannelLaunch.Opened)
        assertFalse("must not claim the title link worked", (r as ChannelLaunch.Opened).titleLinkSent)
        assertTrue(seen.any { it.startsWith("POST /launch/837?contentId=dQw4w9WgXcQ") })
        assertTrue(seen.any { it == "POST /launch/837" })
    }
}

package com.mundymorning.couchcast

import com.mundymorning.couchcast.install.DigestAuth
import com.mundymorning.couchcast.install.InstallOutcome
import com.mundymorning.couchcast.install.InstallerProbe
import com.mundymorning.couchcast.install.RokuDevClient
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
import java.net.ServerSocket

/** A fake Roku development installer that really enforces Digest auth. It is not a real Roku. */
class RokuDevClientFakeServerTest {
    private lateinit var server: MockWebServer
    @Volatile private var lastUpload = ""
    @Volatile private var reply = "<font color=\"red\">Install Success.</font>"
    @Volatile private var dropReply = false
    @Volatile private var dropFirstChallenge = false
    private val challengeHits = java.util.concurrent.atomic.AtomicInteger()
    private val nonce = "fakenonce123"
    private val password = "secret"

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val auth = request.getHeader("Authorization")
                var ok = false
                if (auth != null && auth.startsWith("Digest")) {
                    fun f(k: String) = Regex("$k=\"?([^\",]+)\"?").find(auth)?.groupValues?.get(1).orEmpty()
                    val ch = DigestAuth.Challenge("rokudev", nonce, "auth", null)
                    val expect = DigestAuth.response("rokudev", password, request.method.orEmpty(), f("uri"), ch, f("nc"), f("cnonce"))
                    ok = f("response") == expect && f("username") == "rokudev"
                }
                if (!ok) {
                    if (dropFirstChallenge && challengeHits.getAndIncrement() == 0) {
                        return MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    }
                    return MockResponse().setResponseCode(401)
                        .addHeader("WWW-Authenticate", "Digest realm=\"rokudev\", qop=\"auth\", nonce=\"$nonce\"")
                }
                lastUpload = request.body.readString(Charsets.ISO_8859_1)
                if (dropReply) return MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
                return MockResponse().setBody(reply)
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun client() = RokuDevClient(installerPort = server.port)

    @Test fun installsWithCorrectPassword() = runBlocking {
        val out = client().install("127.0.0.1", "rokudev", password, "PKZIPDATA".toByteArray())
        assertEquals(InstallOutcome.Accepted(false), out)
        assertTrue(lastUpload.contains("name=\"archive\""))
        assertTrue(lastUpload.contains("name=\"mysubmit\""))
        assertTrue(lastUpload.contains("PKZIPDATA"))
        // Roku's strict parser rejects extra per-part headers (OkHttp's Content-Length): never send them.
        assertTrue(!lastUpload.contains("Content-Length"))
        assertTrue(lastUpload.contains("name=\"mysubmit\"\r\n\r\nReplace\r\n"))
    }

    @Test fun wrongPasswordIsReportedAsSuch() = runBlocking {
        assertEquals(InstallOutcome.BadPassword, client().install("127.0.0.1", "rokudev", "wrong", "x".toByteArray()))
    }

    @Test fun compileFailureIsNotSuccess() = runBlocking {
        reply = "<font color=\"red\">Install Failure: Compilation Failed.</font>"
        assertTrue(client().install("127.0.0.1", "rokudev", password, "x".toByteArray()) is InstallOutcome.CompileFailed)
    }

    @Test fun lostReplyAfterUploadIsNotReportedAsSuccessOrPlainFailure() = runBlocking {
        dropReply = true
        val out = client().install("127.0.0.1", "rokudev", password, "x".toByteArray())
        assertTrue("got $out", out is InstallOutcome.ResponseLost)
    }

    @Test fun droppedSocketOnChallengeIsRetried() = runBlocking {
        dropFirstChallenge = true
        val out = client().install("127.0.0.1", "rokudev", password, "x".toByteArray())
        assertEquals(InstallOutcome.Accepted(false), out)
    }

    @Test fun probeSeesOpenAndClosedInstaller() = runBlocking {
        assertEquals(InstallerProbe.OPEN, client().probeInstaller("127.0.0.1"))
        val closed = ServerSocket(0).use { it.localPort }
        assertEquals(InstallerProbe.CLOSED, RokuDevClient(installerPort = closed).probeInstaller("127.0.0.1"))
    }

    @Test fun closedInstallerOnUploadMeansDeveloperModeOff() = runBlocking {
        val closed = ServerSocket(0).use { it.localPort }
        val out = RokuDevClient(installerPort = closed).install("127.0.0.1", "rokudev", password, "x".toByteArray())
        assertTrue(out is InstallOutcome.InstallerClosed || out is InstallOutcome.Unreachable)
    }
}

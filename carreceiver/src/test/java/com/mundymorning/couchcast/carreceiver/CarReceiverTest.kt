package com.mundymorning.couchcast.carreceiver

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

private class FakeCommands : ReceiverCommands {
    val played = mutableListOf<Triple<String, MediaKind, String?>>()
    val controls = mutableListOf<Pair<String, Long?>>()
    val inputs = mutableListOf<InputEvent>()
    var started: String? = null
    var gameRunning = false
    var status = ReceiverStatus("idle")
    override fun play(source: String, kind: MediaKind, title: String?): String? { played += Triple(source, kind, title); return null }
    override fun control(action: String, value: Long?): Boolean { controls += action to value; return true }
    override fun games() = listOf(GameInfo("7-come-11", "Touch 7 Come 11"))
    override fun startGame(id: String): Boolean { if (id != "7-come-11") return false; started = id; gameRunning = true; return true }
    override fun input(e: InputEvent): Boolean { if (!gameRunning) return false; inputs += e; return true }
    override fun status() = status
}

class ControlServerTest {
    private lateinit var server: ControlServer
    private lateinit var pairing: Pairing
    private val fake = FakeCommands()
    private lateinit var dir: File
    private val http = OkHttpClient.Builder().followRedirects(false).build()
    private lateinit var base: String

    @Before fun start() {
        dir = java.nio.file.Files.createTempDirectory("uploads").toFile()
        pairing = Pairing()
        server = ControlServer(0, pairing, fake, { if (it == "controller.html") ByteArrayInputStream("<html>controller</html>".toByteArray()) else null }, dir)
        server.start(5000, false)
        base = "http://127.0.0.1:${server.listeningPort}"
    }

    @After fun stop() { server.stop(); dir.deleteRecursively() }

    private fun form(vararg kv: Pair<String, String>) = FormBody.Builder().also { b -> kv.forEach { b.add(it.first, it.second) } }.build()
    private fun pair(): String {
        val r = http.newCall(Request.Builder().url("$base/api/pair").post(form("pin" to pairing.pin)).build()).execute()
        return r.use { Regex("\"token\":\"([0-9a-f]+)\"").find(it.body!!.string())!!.groupValues[1] }
    }
    private fun post(path: String, token: String?, vararg kv: Pair<String, String>): Response =
        http.newCall(Request.Builder().url(base + path).apply { token?.let { header("X-Token", it) } }.post(form(*kv)).build()).execute()
    private fun get(path: String, token: String?): Response =
        http.newCall(Request.Builder().url(base + path).apply { token?.let { header("X-Token", it) } }.get().build()).execute()

    @Test fun servesTheControllerPageAndPingWithoutPairing() {
        get("/", null).use { assertEquals(200, it.code); assertTrue(it.body!!.string().contains("controller")) }
        get("/api/ping", null).use { assertTrue(it.body!!.string().contains("couchcast-car-receiver")) }
    }

    @Test fun everythingElseNeedsAPairedToken() {
        assertEquals(401, get("/api/status", null).use { it.code })
        assertEquals(401, get("/api/status", "deadbeef").use { it.code })
        assertEquals(401, post("/api/play", null, "url" to "https://x.com/a.mp4").use { it.code })
        assertEquals(401, post("/api/input", "nope", "type" to "tap").use { it.code })
        assertTrue(fake.played.isEmpty() && fake.inputs.isEmpty())
        val token = pair()
        assertEquals(200, get("/api/status", token).use { it.code })
    }

    @Test fun wrongCodesLockPairingThenIssueANewCode() {
        val wrong = if (pairing.pin == "111111") "222222" else "111111"
        repeat(Pairing.MAX_FAILURES) { assertEquals(403, post("/api/pair", null, "pin" to wrong).use { it.code }) }
        assertEquals("locked even with the right code", 429, post("/api/pair", null, "pin" to pairing.pin).use { it.code })
    }

    @Test fun playsAWebLinkAndPicksTheKind() {
        val t = pair()
        assertEquals(200, post("/api/play", t, "url" to "https://x.com/a/clip.mp4?x=1").use { it.code })
        assertEquals(200, post("/api/play", t, "url" to "https://x.com/song.mp3", "title" to "My song").use { it.code })
        assertEquals(200, post("/api/play", t, "url" to "https://x.com/pic.jpg").use { it.code })
        assertEquals(listOf(MediaKind.VIDEO, MediaKind.AUDIO, MediaKind.PHOTO), fake.played.map { it.second })
        assertEquals("My song", fake.played[1].third)
        assertEquals(400, post("/api/play", t, "url" to "file:///etc/passwd").use { it.code })
        assertEquals(400, post("/api/play", t, "url" to "javascript:alert(1)").use { it.code })
        assertEquals(3, fake.played.size)
    }

    @Test fun acceptsAnUploadedFileAndPlaysIt() {
        val t = pair()
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        val r = http.newCall(Request.Builder().url("$base/api/upload?name=Holiday%20Clip.mp4").header("X-Token", t).post(bytes.toRequestBody()).build()).execute()
        r.use { assertEquals(200, it.code) }
        val (src, kind, title) = fake.played.single()
        assertEquals(MediaKind.VIDEO, kind); assertEquals("Holiday Clip", title)
        assertTrue(src, src.startsWith("file:") && src.endsWith(".mp4"))
        assertArrayEquals(bytes, File(java.net.URI(src)).readBytes())
        // a second upload replaces the first
        http.newCall(Request.Builder().url("$base/api/upload?name=b.png").header("X-Token", t).post(ByteArray(10).toRequestBody()).build()).execute().close()
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test fun rejectsUploadsThatAreNotMedia() {
        val t = pair()
        val r = http.newCall(Request.Builder().url("$base/api/upload?name=virus.apk").header("X-Token", t).post(ByteArray(10).toRequestBody()).build()).execute()
        r.use { assertEquals(415, it.code) }
        assertTrue(fake.played.isEmpty())
    }

    @Test fun controlsAndGamesAndInput() {
        val t = pair()
        assertEquals(200, post("/api/control", t, "action" to "seekBy", "value" to "-10000").use { it.code })
        assertEquals("seekBy" to -10000L, fake.controls.last())
        assertEquals(400, post("/api/control", t, "action" to "format-disk").use { it.code })
        assertEquals(409, post("/api/input", t, "type" to "tap").use { it.code })           // no game yet
        get("/api/games", t).use { assertTrue(it.body!!.string().contains("Touch 7 Come 11")) }
        assertEquals(404, post("/api/game", t, "id" to "nope").use { it.code })
        assertEquals(400, post("/api/game", t, "id" to "../../etc").use { it.code })
        assertEquals(200, post("/api/game", t, "id" to "7-come-11").use { it.code })
        assertEquals(200, post("/api/input", t, "type" to "move", "dx" to "0.25", "dy" to "-9").use { it.code })
        assertEquals(InputEvent("move", 0.25f, -1f), fake.inputs.last())                     // clamped to the pad
        assertEquals(200, post("/api/input", t, "type" to "key", "key" to "Up").use { it.code })
        assertEquals(400, post("/api/input", t, "type" to "rm").use { it.code })
    }

    @Test fun statusReportsWhatIsHappening() {
        val t = pair()
        fake.status = ReceiverStatus("video", "A \"quoted\" title", true, 5000, 60000, error = null)
        get("/api/status", t).use {
            val s = it.body!!.string()
            assertTrue(s, s.contains("\"mode\":\"video\"") && s.contains("\"playing\":true") && s.contains("A \\\"quoted\\\" title") && s.contains("\"error\":null"))
        }
    }
}

class PairingTest {
    @Test fun rightCodeGivesATokenWhichStopsWorkingAfterReset() {
        val p = Pairing()
        val ok = p.pair(p.pin) as PairResult.Ok
        assertTrue(p.isValid(ok.token)); assertFalse(p.isValid("x")); assertFalse(p.isValid(null))
        p.reset()
        assertFalse(p.isValid(ok.token))
    }

    @Test fun fiveWrongTriesLockThirtySecondsAndChangeTheCode() {
        var t = 1_000L
        val p = Pairing { t }
        val wrong = if (p.pin == "123456") "654321" else "123456"
        repeat(Pairing.MAX_FAILURES) { assertEquals(PairResult.Wrong, p.pair(wrong)) }
        val locked = p.pair(p.pin) as PairResult.Locked       // even the right code is refused while locked
        assertTrue(locked.seconds in 1..30)
        t += Pairing.LOCK_MS + 1
        assertTrue(p.pair(p.pin) is PairResult.Ok)
    }
}

class PointerAndMiscTest {
    @Test fun pointerMovesInProportionAndStaysOnScreen() {
        val p = PointerState(1000f, 500f)
        assertEquals(500f, p.x, 0.01f); assertEquals(250f, p.y, 0.01f)
        p.move(0.1f, 0f)
        assertEquals(620f, p.x, 0.01f); assertTrue(p.visible)
        p.move(5f, 5f)
        assertEquals(999f, p.x, 0.01f); assertEquals(499f, p.y, 0.01f)
        p.move(-5f, -5f)
        assertEquals(0f, p.x, 0.01f); assertEquals(0f, p.y, 0.01f)
        p.resize(2000f, 1000f)
        assertTrue(p.x <= 1999f && p.y <= 999f)
        p.dragging = true; p.hide()
        assertFalse(p.dragging); assertFalse(p.visible)
    }

    @Test fun mediaKindFromHintTypeOrName() {
        assertEquals(MediaKind.VIDEO, MediaKinds.detect("https://x.com/a.MP4?t=1"))
        assertEquals(MediaKind.AUDIO, MediaKinds.detect("song.flac"))
        assertEquals(MediaKind.PHOTO, MediaKinds.detect("x", "image/jpeg"))
        assertEquals(MediaKind.VIDEO, MediaKinds.detect("x", "video/quicktime; codecs=hevc"))
        assertEquals(MediaKind.AUDIO, MediaKinds.detect("x.mp4", null, "audio"))
        assertNull(MediaKinds.detect("setup.apk")); assertNull(MediaKinds.detect("photo.heic"))
    }

    @Test fun gameTitlesAndIds() {
        assertEquals("Touch 7 Come 11", GameCatalog.titleOf("<html><title>Touch 7 Come 11</title>", "7-come-11"))
        assertEquals("Find A Word", GameCatalog.titleOf("<html>", "find-a-word"))
        assertTrue(GameCatalog.isSafeId("7-come-11")); assertFalse(GameCatalog.isSafeId("../x")); assertFalse(GameCatalog.isSafeId(""))
    }

    @Test fun onlyLocalClientsAreServed() {
        listOf("127.0.0.1", "/192.168.1.5", "10.0.0.8", "172.20.1.1", "::1", "::ffff:192.168.0.9", "fe80::1%wlan0", "169.254.3.3").forEach { assertTrue(it, Lan.isLocalClient(it)) }
        listOf("8.8.8.8", "172.32.0.1", "203.0.113.5", "", null, "2001:4860:4860::8888", "evil.com").forEach { assertFalse("$it", Lan.isLocalClient(it)) }
    }

    @Test fun jsonEscaping() {
        assertEquals("\"a\\\"b\\\\c\\n\"", ControlServer.q("a\"b\\c\n"))
        assertEquals("one line", ControlServer.cleanTitle("  one line\u0007\n "))
    }
}

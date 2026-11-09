package com.mundymorning.couchcast.carreceiver

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

sealed interface Mode {
    object Home : Mode
    data class Media(val kind: MediaKind, val title: String, val source: String) : Mode
    data class Game(val id: String, val title: String) : Mode
}

/** The game on screen, which turns controller gestures into touches and keys for it. */
interface GameHost { fun handle(e: InputEvent): Boolean }

/** What the receiver is doing, and what paired controllers can ask it to do. Player and screen work happens on the main thread. */
object CarReceiver : ReceiverCommands {
    private lateinit var app: Application
    private val main = Handler(Looper.getMainLooper())
    private val _mode = MutableStateFlow<Mode>(Mode.Home)
    private val _error = MutableStateFlow<String?>(null)
    private var gamesCache: List<GameInfo>? = null
    @Volatile private var snapshot = ReceiverStatus("idle")
    @Volatile var gameHost: GameHost? = null

    val mode: StateFlow<Mode> = _mode
    val error: StateFlow<String?> = _error

    fun init(application: Application) { if (!::app.isInitialized) app = application }

    private val uploads get() = File(app.cacheDir, "uploads")

    override fun play(source: String, kind: MediaKind, title: String?): String? {
        val uri = try { java.net.URI(source) } catch (_: Exception) { return "That address isn't valid." }
        when (uri.scheme?.lowercase()) {
            "http", "https" -> {}
            "file" -> if (!File(uri.path).canonicalPath.startsWith(uploads.canonicalPath)) return "That file isn't allowed."
            else -> return "Only web addresses and sent files can be played."
        }
        val name = title?.takeIf { it.isNotBlank() } ?: "Couchcast"
        main.post {
            _error.value = null
            stopPlayer()
            if (kind != MediaKind.PHOTO) {
                val p = PlayerHolder.get(app)
                p.setMediaItem(
                    MediaItem.Builder().setUri(source)
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(name).setArtist("Couchcast").build()).build(),
                )
                p.prepare()
                p.play()
                try { app.startService(Intent(app, PlaybackService::class.java)) } catch (_: Exception) { /* the screen still plays it */ }
            }
            _mode.value = Mode.Media(kind, name, source)
            refresh()
            startTicker()
        }
        return null
    }

    override fun control(action: String, value: Long?): Boolean {
        val m = _mode.value
        if (action == "stop") {
            main.post { exitToHome() }
            return m != Mode.Home
        }
        if (m !is Mode.Media || m.kind == MediaKind.PHOTO) return false
        main.post {
            val p = PlayerHolder.peek() ?: return@post
            when (action) {
                "pause" -> p.pause()
                "resume" -> p.play()
                "toggle" -> if (p.isPlaying) p.pause() else p.play()
                "seekBy" -> p.seekTo((p.currentPosition + (value ?: 0L)).coerceAtLeast(0L))
                "seekTo" -> p.seekTo((value ?: 0L).coerceAtLeast(0L))
            }
            refresh()
        }
        return true
    }

    override fun games(): List<GameInfo> = gamesCache ?: AndroidGames.list(app).also { gamesCache = it }

    override fun startGame(id: String): Boolean {
        val g = games().firstOrNull { it.id == id } ?: return false
        main.post {
            _error.value = null
            stopPlayer()
            _mode.value = Mode.Game(g.id, g.title)
            refresh()
        }
        return true
    }

    override fun input(e: InputEvent): Boolean {
        if (e.type == "exit") {
            main.post { exitToHome() }
            return true
        }
        val host = gameHost ?: return false
        main.post { host.handle(e) }
        return true
    }

    override fun status(): ReceiverStatus = snapshot

    fun exitToHome() {
        stopPlayer()
        _mode.value = Mode.Home
        uploads.listFiles()?.forEach { it.delete() }   // a private video shouldn't linger in the cache
        refresh()
    }

    fun onPlaybackEnded() = exitToHome()

    fun onPlaybackError(e: PlaybackException) {
        exitToHome()
        _error.value = "Couldn't play that (${e.errorCodeName}). The link may need a sign-in, have expired, or use a format this phone can't play."
        refresh()
    }

    private fun stopPlayer() {
        PlayerHolder.peek()?.let { it.stop(); it.clearMediaItems() }
    }

    private fun refresh() {
        val p = PlayerHolder.peek()
        snapshot = when (val m = _mode.value) {
            Mode.Home -> ReceiverStatus("idle", error = _error.value)
            is Mode.Game -> ReceiverStatus("game", m.title, game = m.id)
            is Mode.Media ->
                if (m.kind == MediaKind.PHOTO) ReceiverStatus("photo", m.title)
                else ReceiverStatus(
                    if (m.kind == MediaKind.VIDEO) "video" else "audio", m.title, p?.isPlaying == true,
                    p?.currentPosition ?: 0L, p?.duration?.takeIf { it > 0 } ?: 0L,
                )
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            if (_mode.value is Mode.Media) main.postDelayed(this, 500)
        }
    }

    private fun startTicker() { main.removeCallbacks(ticker); main.post(ticker) }
}

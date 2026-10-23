package com.mundymorning.couchcast.cast

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.SessionManagerListener
import com.mundymorning.couchcast.play.MediaLinks
import com.mundymorning.couchcast.play.PageScanner
import com.mundymorning.couchcast.play.PlayResult
import com.mundymorning.couchcast.play.ReceiverPlayer
import com.mundymorning.couchcast.receiver.PlayRequest
import com.mundymorning.couchcast.receiver.Receiver
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CastPhase { IDLE, SCANNING, SENDING, PLAYING, NOT_CONFIRMED, FAILED, NO_VIDEO, CHOOSE }

data class CastUi(
    /** False when Google Play services is missing or too old, so Cast cannot run on this phone. */
    val available: Boolean = true,
    val connected: Boolean = false,
    val deviceName: String? = null,
    val phase: CastPhase = CastPhase.IDLE,
    val message: String? = null,
    val candidates: List<String> = emptyList(),
    val chosen: String? = null,
)

class CastViewModel(app: Application) : AndroidViewModel(app) {
    private val receiver: Receiver = GoogleCastReceiver(GoogleCastTransport(app))
    private val scanner = PageScanner(app)
    private var job: Job? = null
    private var sessions: SessionManager? = null

    private val _ui = MutableStateFlow(CastUi())
    val ui: StateFlow<CastUi> = _ui.asStateFlow()

    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) = refresh()
        override fun onSessionStarted(session: CastSession, sessionId: String) = refresh()
        override fun onSessionStartFailed(session: CastSession, error: Int) = refresh()
        override fun onSessionEnding(session: CastSession) = refresh()
        override fun onSessionEnded(session: CastSession, error: Int) = refresh()
        override fun onSessionResuming(session: CastSession, sessionId: String) = refresh()
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = refresh()
        override fun onSessionResumeFailed(session: CastSession, error: Int) = refresh()
        override fun onSessionSuspended(session: CastSession, reason: Int) = refresh()
    }

    init {
        try {
            sessions = CastContext.getSharedInstance(app).sessionManager.also {
                it.addSessionManagerListener(listener, CastSession::class.java)
            }
            refresh()
        } catch (_: Exception) {
            _ui.value = CastUi(available = false, message = "Google Cast needs Google Play services, which this phone doesn't have or needs to update.")
        }
    }

    override fun onCleared() {
        sessions?.removeSessionManagerListener(listener, CastSession::class.java)
    }

    private fun refresh() {
        val s = sessions?.currentCastSession?.takeIf { it.isConnected }
        _ui.update { it.copy(connected = s != null, deviceName = s?.castDevice?.friendlyName) }
    }

    /** A direct video link plays straight away; a web page is scanned for video first. */
    fun play(rawUrl: String) {
        val url = CastLinks.normalize(rawUrl) ?: run { fail("Enter a link that starts with http:// or https://."); return }
        if (!_ui.value.connected) { fail(CastMessages.forOutcome(com.mundymorning.couchcast.play.PlayOutcome(PlayResult.SEND_FAILED))); return }
        val format = ReceiverPlayer.formatFor(url)
        if (format != null) { send(url, format); return }
        job?.cancel()
        job = viewModelScope.launch {
            _ui.update { it.copy(phase = CastPhase.SCANNING, message = "Looking for video on that page… this can take up to 15 seconds.", candidates = emptyList(), chosen = null) }
            val found = try { scanner.scan(url).urls } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }
            when (found.size) {
                0 -> _ui.update { it.copy(phase = CastPhase.NO_VIDEO, message = "No playable video found on that page. Sites that protect their video (Netflix, Hulu, Disney+, YouTube and similar) can't be cast this way. Try a page with an ordinary video, or a direct .mp4 / .m3u8 link.") }
                1 -> send(found[0], ReceiverPlayer.formatFor(found[0]) ?: "mp4")
                else -> _ui.update { it.copy(phase = CastPhase.CHOOSE, message = "Found ${found.size} videos on that page. Pick one:", candidates = found) }
            }
        }
    }

    fun playCandidate(url: String) = send(url, ReceiverPlayer.formatFor(url) ?: "mp4")

    private fun send(url: String, format: String) {
        job?.cancel()
        job = viewModelScope.launch {
            _ui.update { it.copy(phase = CastPhase.SENDING, message = "Sending the video to ${it.deviceName ?: "your TV"}…", chosen = url, candidates = emptyList()) }
            val out = receiver.play("cast", PlayRequest(url, format))
            _ui.update {
                it.copy(
                    phase = when (out.result) {
                        PlayResult.PLAYING -> CastPhase.PLAYING
                        PlayResult.RECEIVER_OPEN_NOT_CONFIRMED -> CastPhase.NOT_CONFIRMED
                        else -> CastPhase.FAILED
                    },
                    message = CastMessages.forOutcome(out),
                )
            }
        }
    }

    fun stop() {
        job?.cancel()
        viewModelScope.launch {
            receiver.stop("cast")
            _ui.update { it.copy(phase = CastPhase.IDLE, message = null, chosen = null, candidates = emptyList()) }
        }
    }

    fun cancel() {
        job?.cancel()
        _ui.update { it.copy(phase = CastPhase.IDLE, message = null, candidates = emptyList()) }
    }

    private fun fail(msg: String) = _ui.update { it.copy(phase = CastPhase.FAILED, message = msg) }

    fun label(url: String) = MediaLinks.label(url)
}

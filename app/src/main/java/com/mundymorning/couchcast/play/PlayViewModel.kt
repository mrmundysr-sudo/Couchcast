package com.mundymorning.couchcast.play

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mundymorning.couchcast.install.InstallPlanner
import com.mundymorning.couchcast.install.ReceiverPackage
import com.mundymorning.couchcast.install.ReceiverState
import com.mundymorning.couchcast.roku.RokuClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

enum class PlayPhase { IDLE, SCANNING, SENDING, PLAYING, NOT_CONFIRMED, FAILED, NEEDS_RECEIVER, NO_VIDEO, CHOOSE }

data class PlayUi(val phase: PlayPhase = PlayPhase.IDLE, val message: String? = null, val candidates: List<String> = emptyList())

class PlayViewModel(app: Application) : AndroidViewModel(app) {
    private val player = ReceiverPlayer()
    private val pkg = ReceiverPackage.load(app)
    private val scanner = PageScanner(app)
    private var job: kotlinx.coroutines.Job? = null
    private val _ui = MutableStateFlow(PlayUi())
    val ui: StateFlow<PlayUi> = _ui.asStateFlow()

    /** A direct video link plays straight away; a web page is scanned for video first. */
    fun play(ip: String?, rawUrl: String, forceMp4: Boolean = false) {
        if (ip == null) { fail("Add a Roku in the Rooms tab first."); return }
        val url = normalize(rawUrl) ?: run { fail("Enter a link that starts with http:// or https://."); return }
        val format = ReceiverPlayer.formatFor(url) ?: if (forceMp4) "mp4" else null
        if (format != null) { send(ip, url, format); return }
        job?.cancel()
        job = viewModelScope.launch {
            _ui.value = PlayUi(PlayPhase.SCANNING, "Looking for video on that page… this can take up to 15 seconds.")
            val found = try { scanner.scan(url) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }
            when (found.size) {
                0 -> _ui.value = PlayUi(PlayPhase.NO_VIDEO,
                    "No playable video found on that page. Sites like YouTube, Netflix, Hulu and Disney+ protect their video or hide it inside their own player, so it can't be sent to a Roku this way. Try a page with an ordinary video, or a direct .mp4 / .m3u8 link.")
                1 -> send(ip, found[0], ReceiverPlayer.formatFor(found[0]) ?: "mp4")
                else -> _ui.value = PlayUi(PlayPhase.CHOOSE, "Found ${found.size} videos on that page. Pick one:", found)
            }
        }
    }

    fun playCandidate(ip: String?, url: String) {
        if (ip == null) return
        send(ip, url, ReceiverPlayer.formatFor(url) ?: "mp4")
    }

    fun cancel() {
        job?.cancel()
        _ui.value = PlayUi()
    }

    private fun send(ip: String, url: String, format: String) {
        job?.cancel()
        job = viewModelScope.launch {
            _ui.value = PlayUi(PlayPhase.SENDING, "Checking the receiver on your Roku…")
            val apps = try { RokuClient.apps(ip) } catch (_: IOException) { null }
            when (InstallPlanner.classify(apps, pkg)) {
                ReceiverState.CURRENT -> {}
                ReceiverState.UNKNOWN -> { fail("Couldn't reach the Roku. Check that the TV is on and on the same Wi-Fi."); return@launch }
                ReceiverState.NONE -> { needsReceiver("The Couchcast Receiver isn't installed on this Roku yet."); return@launch }
                ReceiverState.OUTDATED -> { needsReceiver("The receiver on this Roku is out of date. Update it first."); return@launch }
                ReceiverState.OTHER_DEV_APP -> { needsReceiver("A different sideloaded channel is on this Roku. Install the receiver first."); return@launch }
            }
            _ui.update { it.copy(message = "Sending the video to your Roku…") }
            val out = player.play(ip, url, format)
            _ui.value = when (out.result) {
                PlayResult.PLAYING -> PlayUi(PlayPhase.PLAYING, "Confirmed: the Roku reports it is playing." + formatNote(out.last))
                PlayResult.STOPPED_EARLY -> PlayUi(PlayPhase.FAILED, stoppedMessage(out))
                PlayResult.RECEIVER_OPEN_NOT_CONFIRMED -> PlayUi(PlayPhase.NOT_CONFIRMED,
                    "The receiver opened, but the Roku hasn't reported playing. Look at the TV: it shows the reason if the video failed. The link may need sign-in, or be a format the Roku can't play.")
                PlayResult.NOT_STARTED -> PlayUi(PlayPhase.FAILED, "The Roku didn't start the receiver. Make sure the TV is on and try again.")
                PlayResult.SEND_FAILED -> PlayUi(PlayPhase.FAILED, "The Roku didn't accept the command. Check Wi-Fi, and that network control is allowed on the TV.")
            }
        }
    }

    private fun formatNote(i: PlayerInfo?): String {
        i ?: return ""
        val bits = listOfNotNull(i.video?.takeIf { it.isNotBlank() }?.let { "video $it" }, i.resolution?.takeIf { it.isNotBlank() },
            i.audio?.takeIf { it.isNotBlank() }?.let { "audio $it" })
        return if (bits.isEmpty()) "" else " (${bits.joinToString(", ")})"
    }

    /** Plain-English reason from what the Roku itself reported. */
    private fun stoppedMessage(out: PlayOutcome): String {
        val i = out.last
        val noVideo = i != null && i.video.isNullOrBlank() && !i.audio.isNullOrBlank()
        val drm = i?.drm?.takeIf { it.isNotBlank() && !it.equals("none", true) }
        val why = when {
            drm != null -> "The Roku says this video is copy-protected ($drm), which the receiver can't play."
            noVideo -> "The Roku played the sound but could not decode the picture, which usually means the video uses a format this Roku doesn't support."
            else -> "The link may have expired, need a sign-in, or use a format this Roku can't play."
        }
        val seen = formatNote(i).trim().removePrefix("(").removeSuffix(")")
        return "The video started, then the Roku stopped it after about ${out.playedSeconds} seconds. $why" +
            (if (seen.isNotEmpty()) " The Roku reported: $seen." else "") + " The TV screen shows more detail."
    }

    /** Back stops playback in the receiver. */
    fun stop(ip: String?) {
        ip ?: return
        viewModelScope.launch {
            try { RokuClient.key(ip, "Back") } catch (_: IOException) {}
            _ui.value = PlayUi()
        }
    }

    private fun needsReceiver(msg: String) { _ui.value = PlayUi(PlayPhase.NEEDS_RECEIVER, msg) }
    private fun fail(msg: String) { _ui.value = PlayUi(PlayPhase.FAILED, msg) }

    private fun normalize(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val u = if (Regex("^(?i)https?://.+").matches(t)) t else if ("://" in t) return null else "https://$t"
        return if (Regex("^(?i)https?://[^/\\s]+.*").matches(u)) u else null
    }
}

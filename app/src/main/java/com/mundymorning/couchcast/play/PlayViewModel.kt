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

enum class PlayPhase { IDLE, SENDING, PLAYING, NOT_CONFIRMED, FAILED, NEEDS_RECEIVER, UNKNOWN_FORMAT }

data class PlayUi(val phase: PlayPhase = PlayPhase.IDLE, val message: String? = null)

class PlayViewModel(app: Application) : AndroidViewModel(app) {
    private val player = ReceiverPlayer()
    private val pkg = ReceiverPackage.load(app)
    private val _ui = MutableStateFlow(PlayUi())
    val ui: StateFlow<PlayUi> = _ui.asStateFlow()

    fun play(ip: String?, rawUrl: String, forceMp4: Boolean = false) {
        if (ip == null) { fail("Add a Roku in the Rooms tab first."); return }
        val url = normalize(rawUrl) ?: run { fail("Enter a video link that starts with http:// or https://."); return }
        val format = ReceiverPlayer.formatFor(url) ?: if (forceMp4) "mp4" else null
        if (format == null) {
            _ui.value = PlayUi(PlayPhase.UNKNOWN_FORMAT,
                "That doesn't look like a direct video file. The Roku can play links ending in .mp4, .m3u8 or .mpd, not web pages.")
            return
        }
        viewModelScope.launch {
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
            _ui.value = when (player.play(ip, url, format)) {
                PlayResult.PLAYING -> PlayUi(PlayPhase.PLAYING, "Confirmed: the Roku reports it is playing.")
                PlayResult.RECEIVER_OPEN_NOT_CONFIRMED -> PlayUi(PlayPhase.NOT_CONFIRMED,
                    "The receiver opened, but the Roku hasn't reported playing. Look at the TV. The link may need sign-in, or be a format the Roku can't play.")
                PlayResult.NOT_STARTED -> PlayUi(PlayPhase.FAILED, "The Roku didn't start the receiver. Make sure the TV is on and try again.")
                PlayResult.SEND_FAILED -> PlayUi(PlayPhase.FAILED, "The Roku didn't accept the command. Check Wi-Fi, and that network control is allowed on the TV.")
            }
        }
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

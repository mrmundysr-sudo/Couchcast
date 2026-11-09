package com.mundymorning.couchcast.carreceiver

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/** One player for the whole app, used by the screen, the media session and Android Auto. Touch it on the main thread only. */
object PlayerHolder {
    private var player: ExoPlayer? = null

    fun peek(): ExoPlayer? = player

    fun get(ctx: Context): ExoPlayer = player ?: ExoPlayer.Builder(ctx.applicationContext)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .build()
        .also { p ->
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) CarReceiver.onPlaybackEnded()
                }

                override fun onPlayerError(error: PlaybackException) {
                    CarReceiver.onPlaybackError(error)
                }
            })
            player = p
        }
}

package com.mundymorning.couchcast.receiver

import com.mundymorning.couchcast.play.PlayOutcome
import com.mundymorning.couchcast.play.PlayerInfo
import com.mundymorning.couchcast.playback.ReceiverPlatform

/** A video link to play, plus what a browser would have sent for it (both optional). */
data class PlayRequest(
    val url: String,
    val format: String,
    val referer: String? = null,
    val userAgent: String? = null,
)

/**
 * One TV or streaming device that Couch Cast can send a video to. Platform-specific code (Roku today; Google Cast,
 * DLNA and others later) lives behind this interface so the Play flow, relay and troubleshooting stay shared.
 *
 * Contract: [play] reports PLAYING only when the device itself confirmed it (see [PlayOutcome]).
 * [address] is whatever the platform uses to reach the device (an IP for Roku).
 */
interface Receiver {
    val platform: ReceiverPlatform
    suspend fun play(address: String, request: PlayRequest): PlayOutcome
    suspend fun playerInfo(address: String): PlayerInfo?
    /** Shows a photo full screen. Returns true when the device accepted the command; false when it can't or didn't. */
    suspend fun show(address: String, request: PlayRequest): Boolean = false
    /** Best-effort stop; returns false if the device could not be reached. */
    suspend fun stop(address: String): Boolean
}

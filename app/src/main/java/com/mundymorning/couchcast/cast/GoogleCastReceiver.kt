package com.mundymorning.couchcast.cast

import com.mundymorning.couchcast.play.PlayOutcome
import com.mundymorning.couchcast.play.PlayerInfo
import com.mundymorning.couchcast.playback.ReceiverPlatform
import com.mundymorning.couchcast.receiver.PlayRequest
import com.mundymorning.couchcast.receiver.Receiver

/**
 * Google Cast adapter for the shared [Receiver] interface. Cast has one active session chosen with the Cast button, so
 * [address] is ignored. Request headers (Referer, User-Agent) cannot be sent by the Cast media receiver and are dropped.
 */
class GoogleCastReceiver(
    private val transport: CastTransport,
    waitMs: Long = 20_000,
    stableMs: Long = 6_000,
    pollMs: Long = 1_000,
) : Receiver {
    private val playback = CastPlayback(transport, waitMs, stableMs, pollMs)

    override val platform = ReceiverPlatform.GOOGLE_CAST

    override suspend fun play(address: String, request: PlayRequest): PlayOutcome = playback.play(request.url, request.format)

    override suspend fun playerInfo(address: String): PlayerInfo? = transport.snapshot()?.toPlayerInfo()

    override suspend fun stop(address: String): Boolean = transport.stop()
}

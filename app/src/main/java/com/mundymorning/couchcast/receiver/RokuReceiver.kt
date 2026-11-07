package com.mundymorning.couchcast.receiver

import com.mundymorning.couchcast.play.PlayOutcome
import com.mundymorning.couchcast.play.PlayerInfo
import com.mundymorning.couchcast.play.ReceiverPlayer
import com.mundymorning.couchcast.playback.ReceiverPlatform
import com.mundymorning.couchcast.roku.RokuClient
import java.io.IOException

/**
 * Roku adapter. It adds no behaviour: every call is handed to the existing, hardware-verified [ReceiverPlayer] and
 * [RokuClient] unchanged, so the ECP requests are identical to before the [Receiver] interface existed.
 */
class RokuReceiver(
    private val player: ReceiverPlayer = ReceiverPlayer(),
    private val waitMs: Long = 20_000,
    private val stableMs: Long = 6_000,
) : Receiver {
    override val platform = ReceiverPlatform.ROKU

    override suspend fun play(address: String, request: PlayRequest): PlayOutcome =
        player.play(address, request.url, request.format, waitMs, stableMs, request.referer, request.userAgent)

    override suspend fun show(address: String, request: PlayRequest): Boolean = player.showPhoto(address, request.url)

    override suspend fun playerInfo(address: String): PlayerInfo? = player.playerInfo(address)

    /** The receiver channel stops its video on Back. */
    override suspend fun stop(address: String): Boolean =
        try { RokuClient.key(address, "Back") } catch (_: IOException) { false }
}

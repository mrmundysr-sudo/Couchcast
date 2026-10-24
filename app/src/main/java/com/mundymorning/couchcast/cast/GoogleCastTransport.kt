package com.mundymorning.couchcast.cast

import android.content.Context
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** The real Google Cast session (Chromecast, Google TV, Android TV, TVs with Cast built in). Cast calls run on the main thread. */
class GoogleCastTransport(private val context: Context) : CastTransport {
    private fun client(): RemoteMediaClient? = try {
        CastContext.getSharedInstance(context).sessionManager.currentCastSession?.takeIf { it.isConnected }?.remoteMediaClient
    } catch (_: Exception) {
        null // Google Play services missing or too old
    }

    override fun isConnected(): Boolean = client() != null

    override suspend fun load(url: String, contentType: String): Boolean = withContext(Dispatchers.Main) {
        val c = client() ?: return@withContext false
        val info = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(contentType)
            .build()
        val request = MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build()
        withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine<Boolean> { cont ->
                c.load(request).setResultCallback { r -> if (cont.isActive) cont.resume(r.status.isSuccess) }
            }
        } ?: false
    }

    override suspend fun snapshot(): CastSnapshot? = withContext(Dispatchers.Main) {
        val st = client()?.mediaStatus ?: return@withContext null
        val idle = st.playerState == MediaStatus.PLAYER_STATE_IDLE
        CastSnapshot(
            state = when (st.playerState) {
                MediaStatus.PLAYER_STATE_PLAYING -> CastPlayerState.PLAYING
                MediaStatus.PLAYER_STATE_BUFFERING -> CastPlayerState.BUFFERING
                MediaStatus.PLAYER_STATE_PAUSED -> CastPlayerState.PAUSED
                MediaStatus.PLAYER_STATE_LOADING -> CastPlayerState.LOADING
                MediaStatus.PLAYER_STATE_IDLE -> CastPlayerState.IDLE
                else -> CastPlayerState.UNKNOWN
            },
            error = idle && st.idleReason == MediaStatus.IDLE_REASON_ERROR,
            finished = idle && st.idleReason == MediaStatus.IDLE_REASON_FINISHED,
        )
    }

    override suspend fun stop(): Boolean = withContext(Dispatchers.Main) {
        val c = client() ?: return@withContext false
        c.stop()
        true
    }
}

package com.mundymorning.couchcast.play

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

enum class LocalKind { VIDEO, PHOTO }

/** A video or photo the user picked from the phone. */
class LocalPick(val uri: Uri, val name: String, val mime: String, val kind: LocalKind, val size: Long)

/** Pure rules for picked files (no Android types), so they can be tested on a plain JVM. */
object LocalMediaType {
    private val VIDEO_EXT = setOf("mp4", "m4v", "mov", "mkv", "webm", "3gp", "avi", "ts", "mpg", "mpeg")
    private val PHOTO_EXT = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif")

    fun kindOf(mime: String?, name: String?): LocalKind? {
        val m = mime?.lowercase().orEmpty()
        if (m.startsWith("video/")) return LocalKind.VIDEO
        if (m.startsWith("image/")) return LocalKind.PHOTO
        return when (extOf(name)) {
            in VIDEO_EXT -> LocalKind.VIDEO
            in PHOTO_EXT -> LocalKind.PHOTO
            else -> null
        }
    }

    fun extOf(name: String?): String = name?.substringAfterLast('.', "")?.lowercase().orEmpty()

    /** The receiver's stream-format hint: mkv stays mkv, everything else a phone records is MP4-family. */
    fun videoFormat(mime: String?, name: String?): String =
        if (mime?.contains("matroska", true) == true || extOf(name) == "mkv") "mkv" else "mp4"

    /** The file extension the Roku sees in the address, which helps it pick a player. */
    fun videoExt(mime: String?, name: String?): String = if (videoFormat(mime, name) == "mkv") "mkv" else "mp4"

    /** Plain-English reason a phone video didn't play, from what the Roku and the relay reported. */
    fun videoFailure(result: PlayResult, requests: Int, playedSeconds: Int, info: PlayerInfo?): String = when (result) {
        PlayResult.SEND_FAILED -> "The Roku didn't accept the command. Check Wi-Fi, and that network control is allowed on the TV."
        PlayResult.NOT_STARTED -> "The Roku didn't start the receiver. Make sure the TV is on and try again."
        PlayResult.STOPPED_EARLY -> "The video started, then the Roku stopped it after about $playedSeconds seconds. " +
            "Some phone recordings (for example 10-bit HEVC, or very high frame rates) aren't supported by every Roku." +
            (info?.let { i -> listOfNotNull(i.video?.takeIf { it.isNotBlank() }?.let { "video $it" }, i.audio?.takeIf { it.isNotBlank() }?.let { "audio $it" }) }
                ?.takeIf { it.isNotEmpty() }?.let { " The Roku reported: ${it.joinToString(", ")}." }.orEmpty())
        PlayResult.RECEIVER_OPEN_NOT_CONFIRMED, PlayResult.PLAYING ->
            if (requests == 0) noContact() else "The Roku asked your phone for the video but hasn't started playing it. Look at the TV: it shows the reason if the video failed."
    }

    fun photoNotFetched(): String = "The photo was sent, but the Roku never downloaded it. " + noContact()

    private fun noContact() = "Check that the phone and the Roku are on the same Wi-Fi, and that the router isn't isolating devices (guest networks often do)."
}

object LocalMedia {
    /** Name, type and size of a picked file, or null when it is neither a video nor a photo. */
    fun describe(context: Context, uri: Uri): LocalPick? {
        val resolver = context.contentResolver
        var name = ""
        var size = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it).orEmpty() }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                }
            }
        } catch (_: Exception) { /* a provider without metadata is still usable */ }
        if (name.isBlank()) name = uri.lastPathSegment.orEmpty().substringAfterLast('/')
        val mime = resolver.getType(uri).orEmpty()
        val kind = LocalMediaType.kindOf(mime, name) ?: return null
        return LocalPick(uri, name.ifBlank { if (kind == LocalKind.VIDEO) "video" else "photo" }, mime, kind, size)
    }
}

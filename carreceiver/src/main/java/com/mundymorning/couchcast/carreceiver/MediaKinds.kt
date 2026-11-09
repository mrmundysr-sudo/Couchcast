package com.mundymorning.couchcast.carreceiver

enum class MediaKind { VIDEO, AUDIO, PHOTO }

object MediaKinds {
    private val video = setOf("mp4", "m4v", "mov", "mkv", "webm", "3gp", "m3u8", "mpd", "ts")
    private val audio = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus")
    private val photo = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

    fun ext(nameOrUrl: String?): String =
        nameOrUrl.orEmpty().substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase()

    /** From the explicit hint, else the content type, else the file extension. Null when it is none of the three. */
    fun detect(nameOrUrl: String?, mime: String? = null, hint: String? = null): MediaKind? {
        hint?.let { h -> MediaKind.values().firstOrNull { it.name.equals(h, true) }?.let { return it } }
        val m = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        when {
            m.startsWith("video/") -> return MediaKind.VIDEO
            m.startsWith("audio/") -> return MediaKind.AUDIO
            m.startsWith("image/") && m != "image/heic" && m != "image/heif" -> return MediaKind.PHOTO
        }
        val e = ext(nameOrUrl)
        return when (e) {
            in video -> MediaKind.VIDEO
            in audio -> MediaKind.AUDIO
            in photo -> MediaKind.PHOTO
            else -> null
        }
    }
}

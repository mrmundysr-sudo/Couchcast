package com.mundymorning.couchcast.playback

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Photos are re-encoded as JPEG before they go to the Roku: phone photos are often HEIC/WebP or 12+ megapixels, which a
 * Roku can't decode or may run out of memory on. The TV is 1080p at most, so nothing larger than that is useful.
 */
object PhotoSize {
    const val LONG_SIDE = 1920
    const val SHORT_SIDE = 1080

    /** Scales to fit 1920x1080 whichever way the photo is turned. Never enlarges. Aspect ratio is kept. */
    fun fit(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        val scale = min(1.0, min(LONG_SIDE.toDouble() / max(width, height), SHORT_SIDE.toDouble() / min(width, height)))
        if (scale >= 1.0) return width to height
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /** Largest power-of-two shrink that still leaves the decoded size at or above the target (for BitmapFactory.inSampleSize). */
    fun sampleSize(width: Int, height: Int): Int {
        val (w, h) = fit(width, height)
        var s = 1
        while (width / (s * 2) >= w && height / (s * 2) >= h) s *= 2
        return s
    }
}

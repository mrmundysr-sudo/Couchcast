package com.mundymorning.couchcast.playback

/** Plain-English reason a relayed playback failed, from the relay's own counters (no URLs, headers or cookies). */
object RelayFailure {
    fun explain(requests: Int, upstream: Map<Int, Int>, drm: String?, result: com.mundymorning.couchcast.play.PlayResult, lastError: String?): String = when {
        drm != null ->
            "The stream turned out to be copy-protected ($drm). Couch Cast won't relay protected video."
        requests == 0 ->
            "The Roku never contacted your phone, so the relay itself wasn't reached. The phone and the Roku must be on the same Wi-Fi network (not a guest network or one with device isolation). Direct playback is unaffected."
        lastError?.startsWith("blocked") == true ->
            "The relay refused an address outside the public internet, as designed."
        upstream.keys.any { it == 401 || it == 403 } ->
            "The site refused the phone's request too, even with ordinary browser headers. It may need a sign-in, or it restricts who can watch."
        upstream.keys.any { it == 404 || it == 410 } ->
            "The site says the video no longer exists. The link may have expired; reload the page to find a fresh one."
        upstream.keys.any { it in 200..299 } && result != com.mundymorning.couchcast.play.PlayResult.PLAYING ->
            "The relay delivered the video to the Roku, but the Roku couldn't play it. That usually means a format this Roku can't decode. Try mirroring your phone screen instead."
        else -> "The relay couldn't start this stream. The Roku didn't report playing."
    }
}

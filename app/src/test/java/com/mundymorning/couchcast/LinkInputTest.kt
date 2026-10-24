package com.mundymorning.couchcast

import com.mundymorning.couchcast.play.LinkInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkInputTest {
    /** The cleanup as it was before this change, kept here only to prove valid links behave identically. */
    private fun before(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val u = if (Regex("^(?i)https?://.+").matches(t)) t else if ("://" in t) return null else "https://$t"
        return if (Regex("^(?i)https?://[^/\\s]+.*").matches(u)) u else null
    }

    private val validLinks = listOf(
        "https://x.com/v.mp4", "http://x.com/v.mp4", "HTTPS://X.COM/V.M3U8?a=1&b=2#t=3", "  https://x.com/a  ",
        "http://192.168.1.20:8080/video.mp4", "https://user:pw@host.example/a.mp4", "http://[::1]:8080/a.m3u8",
        "https://cdn.example.co.uk/a/b/master.m3u8?token=abc", "https://bücher.de/film.mp4", "http://localhost:3000/x.mp4",
        "x.com/a", "x.com", "www.example.com/watch?v=1", "192.168.1.5:8080/v.mp4", "my_host-1.local/a.mp4",
        "bücher.de/film", "[::1]:8080/v.mp4", "user@host.example/a", "example.com:443", "example.com?x=1", "example.com#frag",
    )

    @Test fun validLinksBehaveExactlyAsBefore() {
        for (v in validLinks) assertEquals(v, before(v), LinkInput.normalize(v))
    }

    @Test fun addsHttpsToBareHosts() {
        assertEquals("https://x.com/a", LinkInput.normalize(" x.com/a "))
        assertEquals("https://192.168.1.5:8080/v.mp4", LinkInput.normalize("192.168.1.5:8080/v.mp4"))
    }

    @Test fun rejectsEntriesThatAreNotHosts() {
        val bad = listOf(
            "javascript:alert(1)", "JavaScript:alert(document.cookie)", "javascript:void(0)", "data:text/html,<b>x</b>",
            "mailto:a@b.com", "tel:5551234", "intent:#Intent;end", "javascript:alert(1)//@x.com", "x.com:abc/path", "host:99999999/a",
            "ftp://x.com/v.mp4", "file:///sdcard/a.mp4", "content://media/1", "javascript://x.com/%0aalert(1)",
            "two words", "", "   ", "https://", "https:///a", "x y.com",
        )
        for (b in bad) assertNull(b, LinkInput.normalize(b))
    }

    @Test fun anExplicitHttpLinkIsNeverReinterpreted() {
        // Typed with a scheme: accepted as before, the stricter host check applies only to bare entries.
        assertEquals("http://x.com:80/a", LinkInput.normalize("http://x.com:80/a"))
    }
}

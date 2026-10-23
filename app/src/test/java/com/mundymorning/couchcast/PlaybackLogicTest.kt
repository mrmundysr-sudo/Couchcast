package com.mundymorning.couchcast

import com.mundymorning.couchcast.playback.*
import com.mundymorning.couchcast.roku.RokuApp
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class StreamingServicesTest {
    private fun svc(u: String) = StreamingServices.recognize(u)

    @Test fun recognisesMajorServices() {
        assertEquals("netflix", svc("https://www.netflix.com/title/80100172")!!.service.id)
        assertEquals("hulu", svc("https://www.hulu.com/watch/abc")!!.service.id)
        assertEquals("disney", svc("https://www.disneyplus.com/movies/x/123")!!.service.id)
        assertEquals("prime", svc("https://www.primevideo.com/detail/XYZ")!!.service.id)
        assertEquals("peacock", svc("https://www.peacocktv.com/watch/asset/x")!!.service.id)
        assertEquals("paramount", svc("https://www.paramountplus.com/shows/x/")!!.service.id)
        assertEquals("max", svc("https://play.max.com/show/1")!!.service.id)
        assertEquals("max", svc("https://www.hbomax.com/x")!!.service.id)
    }

    @Test fun mostSpecificHostWinsAndSharedHostsNeedAPath() {
        assertEquals("youtubetv", svc("https://tv.youtube.com/watch/x")!!.service.id)
        assertEquals("youtube", svc("https://m.youtube.com/watch?v=dQw4w9WgXcQ")!!.service.id)
        assertEquals("prime-amazon", svc("https://www.amazon.com/gp/video/detail/B0X")!!.service.id)
        assertNull("amazon shopping is not Prime Video", svc("https://www.amazon.com/dp/B0123456"))
    }

    @Test fun ordinaryPagesAndMediaLinksAreNotServices() {
        assertNull(svc("https://example.com/video-page"))
        assertNull(svc("https://cdn.example.com/a/master.m3u8"))
        assertNull(svc("https://notnetflix.com/title/1"))
        assertNull(svc("https://netflix.com.evil.example/title/1"))
    }

    @Test fun extractsTitleIdsOnlyWhereTheServiceSupportsIt() {
        assertEquals("dQw4w9WgXcQ", svc("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=5")!!.contentId)
        assertEquals("dQw4w9WgXcQ", svc("https://youtu.be/dQw4w9WgXcQ?si=zz")!!.contentId)
        assertEquals("dQw4w9WgXcQ", svc("https://www.youtube.com/shorts/dQw4w9WgXcQ")!!.contentId)
        assertNull("an invalid id is still YouTube, just without a title link", svc("https://www.youtube.com/watch?v=short")!!.contentId)
        assertEquals("80100172", svc("https://www.netflix.com/title/80100172")!!.contentId)
        assertEquals("80100172", svc("https://www.netflix.com/watch/80100172?trackId=1")!!.contentId)
        assertNull("Hulu has no supported title link, so none is promised", svc("https://www.hulu.com/watch/abc")!!.contentId)
    }
}

class ChannelMatcherTest {
    private val apps = listOf(
        RokuApp("12", "Netflix"), RokuApp("837", "YouTube"), RokuApp("195316", "YouTube TV"),
        RokuApp("291097", "Disney Plus"), RokuApp("dev", "Netflix"), RokuApp("tvinput.hdmi1", "HDMI 1"),
    )

    @Test fun matchesByNameUsingTheDevicesOwnIds() {
        val n = StreamingServices.recognize("https://netflix.com/title/1")!!.service
        assertEquals("12", ChannelMatcher.find(n, apps)!!.id)
        val d = StreamingServices.recognize("https://disneyplus.com/x")!!.service
        assertEquals("291097", ChannelMatcher.find(d, apps)!!.id)
    }

    @Test fun youtubeDoesNotMatchYoutubeTvAndSideloadedAppsNeverCount() {
        val y = StreamingServices.recognize("https://youtube.com/watch?v=dQw4w9WgXcQ")!!.service
        assertEquals("837", ChannelMatcher.find(y, apps)!!.id)
        assertNull(ChannelMatcher.find(y, listOf(RokuApp("195316", "YouTube TV"))))
        assertNull("the sideloaded dev channel must never be taken for Netflix",
            ChannelMatcher.find(StreamingServices.recognize("https://netflix.com/x")!!.service, listOf(RokuApp("dev", "Netflix"))))
    }

    @Test fun missingChannelIsNull() {
        assertNull(ChannelMatcher.find(StreamingServices.recognize("https://hulu.com/x")!!.service, apps))
    }

    @Test fun listsInstalledKnownServices() {
        val names = ChannelMatcher.installedServices(apps).map { it.first.displayName }
        assertTrue(names.containsAll(listOf("Netflix", "YouTube", "YouTube TV", "Disney+")))
        assertFalse(names.contains("Hulu"))
    }
}

class CapabilityAndMirroringTest {
    @Test fun rokuCapabilitiesDoNotOverclaim() {
        val c = CapabilityDetector.roku(receiverInstalled = null)
        assertEquals(Support.UNKNOWN, c.directPlay)
        assertEquals("mirroring can't be verified from the phone", Support.LIKELY, c.mirroring)
        assertEquals(Support.NO, CapabilityDetector.roku(false).directPlay)
        assertEquals(Support.YES, CapabilityDetector.roku(true).relay)
    }

    @Test fun otherPlatformsAreDescribedHonestly() {
        val cast = CapabilityDetector.forPlatform(ReceiverPlatform.GOOGLE_CAST)
        assertEquals(Support.YES, cast.mirroring); assertEquals(Support.NO, cast.directPlay); assertEquals(Support.NO, cast.relay)
        assertEquals(Support.LIKELY, CapabilityDetector.forPlatform(ReceiverPlatform.FIRE_TV).mirroring)
        assertEquals(Support.NO, CapabilityDetector.forPlatform(ReceiverPlatform.UNKNOWN).channelLinks)
    }

    @Test fun unsupportedDevicesGetClearInstructionsNotAMirrorButton() {
        val g = MirroringGuide.forPlatform(ReceiverPlatform.UNKNOWN, Support.NO)
        assertFalse(g.canOpenPhoneCastSettings)
        assertEquals(Support.NO, g.support)
        assertTrue(g.headline.contains("doesn't support"))
        assertTrue(g.steps.isNotEmpty())
    }

    @Test fun rokuGuideTellsTheUserWhatToDoOnTheTv() {
        val g = MirroringGuide.forPlatform(ReceiverPlatform.ROKU, Support.LIKELY)
        assertTrue(g.canOpenPhoneCastSettings)
        assertTrue(g.steps.any { it.where == "On the TV" && it.text.contains("Screen mirroring") })
        assertTrue(g.caveat.contains("black picture"))
    }

    @Test fun mirroringIsOnlyConnectedWhenAndroidReportsADisplay() {
        assertEquals(MirrorState.NOT_DETECTED, MirrorStatus.fromDisplays(0))
        assertEquals(MirrorState.CONNECTED, MirrorStatus.fromDisplays(1))
        assertFalse(MirrorStatus.message(MirrorState.NOT_DETECTED).contains("connected."))
    }
}

class RedactorTest {
    @Test fun urlsLoseQueriesTokensAndUserinfo() {
        val r = Redactor.url("https://user:pw@cdn.example.com:8443/a/b/master.m3u8?token=SECRET123&x=1#frag")
        assertEquals("https://cdn.example.com:8443/…/master.m3u8?…", r)
        assertFalse(r.contains("SECRET123")); assertFalse(r.contains("pw"))
    }

    @Test fun cookieHeadersShowNamesOnly() {
        val r = Redactor.cookieHeader("sid=abc123; theme=dark")
        assertEquals("sid=•••; theme=•••", r)
        assertEquals("", Redactor.cookieHeader(null))
    }

    @Test fun secretsAreScrubbedFromFreeText() {
        assertEquals("failed for •••", Redactor.text("failed for abc123", listOf("abc123")))
    }
}

class UpstreamPolicyTest {
    private fun blocked(ip: String) = UpstreamPolicy.isBlocked(InetAddress.getByName(ip))

    @Test fun privateAndLocalAddressesAreBlocked() {
        listOf("127.0.0.1", "10.0.0.5", "172.16.3.4", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "224.0.0.1", "::1", "fd00::1")
            .forEach { assertTrue(it, blocked(it)) }
        listOf("8.8.8.8", "93.184.216.34", "2606:4700:4700::1111").forEach { assertFalse(it, blocked(it)) }
    }

    @Test fun urlChecksRejectLocalHostsAndOddSchemes() {
        assertNotNull(UpstreamPolicy.checkUrl("http://192.168.1.1/admin", emptySet()))
        assertNotNull(UpstreamPolicy.checkUrl("http://localhost:8080/x", emptySet()))
        assertNotNull(UpstreamPolicy.checkUrl("http://printer.local/x", emptySet()))
        assertNotNull(UpstreamPolicy.checkUrl("ftp://example.com/x", emptySet()))
        assertNotNull(UpstreamPolicy.checkUrl("file:///etc/passwd", emptySet()))
        assertNull(UpstreamPolicy.checkUrl("https://example.com/v.mp4", emptySet()))
        assertNull("the user's own server is allowed only when they chose it",
            UpstreamPolicy.checkUrl("http://192.168.1.50/v.mp4", setOf("192.168.1.50")))
    }
}

class HlsPlaylistTest {
    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=800000,CODECS="avc1.4d401e,mp4a.40.2"
        low/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2000000,CODECS="hvc1.2.4.L93.B0,mp4a.40.2"
        https://other.example.com/hi/index.m3u8?t=1
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",URI="audio/en.m3u8"
    """.trimIndent()

    private val media = """
        #EXTM3U
        #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x01
        #EXT-X-MAP:URI="init.mp4"
        #EXTINF:4.0,
        seg0.ts
        #EXTINF:4.0,
        /abs/seg1.ts?sig=XYZ
        #EXT-X-ENDLIST
    """.trimIndent()

    @Test fun rewritesEveryReferenceThroughTheRelay() {
        val seen = mutableListOf<String>()
        val out = HlsPlaylist.rewrite(media, "https://cdn.example.com/v/index.m3u8") { seen += it; "R${seen.size}" }
        assertEquals(listOf(
            "https://cdn.example.com/v/key.bin", "https://cdn.example.com/v/init.mp4",
            "https://cdn.example.com/v/seg0.ts", "https://cdn.example.com/abs/seg1.ts?sig=XYZ"), seen)
        assertTrue(out.contains("URI=\"R1\"")); assertTrue(out.contains("URI=\"R2\""))
        assertTrue(out.lines().contains("R3")); assertTrue(out.lines().contains("R4"))
        assertFalse("no upstream URL may remain", out.contains("cdn.example.com") || out.contains("sig=XYZ"))
        assertTrue(out.contains("IV=0x01")); assertTrue(out.contains("#EXT-X-ENDLIST"))
    }

    @Test fun masterReferencesAndAudioRenditionsAreRewritten() {
        val seen = mutableListOf<String>()
        HlsPlaylist.rewrite(master, "https://cdn.example.com/m/master.m3u8") { seen += it; "x" }
        assertEquals(listOf("https://cdn.example.com/m/low/index.m3u8", "https://other.example.com/hi/index.m3u8?t=1", "https://cdn.example.com/m/audio/en.m3u8"), seen)
        assertTrue(HlsPlaylist.isMaster(master)); assertFalse(HlsPlaylist.isMaster(media))
    }

    @Test fun dataUrisAreLeftAlone() {
        val t = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"data:text/plain;base64,AAAA\"\nseg.ts"
        val out = HlsPlaylist.rewrite(t, "https://a.example.com/i.m3u8") { "R" }
        assertTrue(out.contains("data:text/plain;base64,AAAA"))
    }

    @Test fun detectsRealDrmButNotPlainAes() {
        assertNull(HlsPlaylist.drmFinding(media))
        assertNull(HlsPlaylist.drmFinding("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\",KEYFORMAT=\"identity\"\ns.ts"))
        assertNotNull(HlsPlaylist.drmFinding("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\ns.ts"))
        assertNotNull(HlsPlaylist.drmFinding("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES-CTR,URI=\"data:x\",KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"\ns.ts"))
        assertNotNull(HlsPlaylist.drmFinding("#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=AES-128,URI=\"k\",KEYFORMAT=\"com.microsoft.playready\"\n"))
    }

    @Test fun listsCodecsAndFirstReference() {
        assertEquals(listOf("avc1.4d401e", "mp4a.40.2", "hvc1.2.4.L93.B0"), HlsPlaylist.codecs(master))
        assertEquals("https://cdn.example.com/m/low/index.m3u8", HlsPlaylist.firstReference(master, "https://cdn.example.com/m/master.m3u8"))
        assertTrue(HlsPlaylist.looksLikePlaylist("﻿#EXTM3U\n"))
        assertFalse(HlsPlaylist.looksLikePlaylist("<html>"))
    }
}

class NetworkAddressTest {
    @Test fun picksThePrivateIpv4() {
        val v6 = InetAddress.getByName("fe80::1")
        val lan = InetAddress.getByName("192.168.1.20")
        assertEquals("192.168.1.20", NetworkAddress.pickLanIpv4(listOf(v6, lan)))
        assertNull(NetworkAddress.pickLanIpv4(listOf(v6, InetAddress.getByName("127.0.0.1"))))
        assertTrue(NetworkAddress.sameSubnet24("192.168.1.20", "192.168.1.114"))
        assertFalse(NetworkAddress.sameSubnet24("192.168.1.20", "192.168.2.114"))
    }
}

class SiteSessionTest {
    @Test fun cookieDomainsCoverTheSiteAndItsParents() {
        val d = SiteSession.cookieDomains("video.example.com")
        assertTrue(d.containsAll(listOf("video.example.com", ".video.example.com", "example.com", ".example.com")))
        assertFalse("never a bare top-level domain", d.contains("com") || d.contains(".com"))
    }

    @Test fun signInMustBeHttpsAndPublic() {
        assertNull(SiteSession.startProblem("https://accounts.example.com/login"))
        assertNotNull(SiteSession.startProblem("http://example.com/login"))
        assertNotNull(SiteSession.startProblem("https://192.168.1.1/login"))
        assertNotNull(SiteSession.startProblem("javascript:alert(1)"))
    }

    @Test fun signInBrowserOnlyFollowsWebPages() {
        assertTrue(SiteSession.navigationAllowed("https://a.example.com/x"))
        assertTrue(SiteSession.navigationAllowed("http://a.example.com/x"))
        listOf("javascript:alert(1)", "file:///sdcard/x", "intent://x#Intent;end", "content://x", "data:text/html,hi").forEach {
            assertFalse(it, SiteSession.navigationAllowed(it))
        }
    }
}

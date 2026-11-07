import XCTest
@testable import CouchcastCore

final class StreamingServicesTests: XCTestCase {
    private func svc(_ u: String) -> ServiceMatch? { StreamingServices.recognize(u) }

    func testRecognisesMajorServices() {
        XCTAssertEqual(svc("https://www.netflix.com/title/80100172")?.service.id, "netflix")
        XCTAssertEqual(svc("https://www.hulu.com/watch/abc")?.service.id, "hulu")
        XCTAssertEqual(svc("https://www.disneyplus.com/movies/x/123")?.service.id, "disney")
        XCTAssertEqual(svc("https://www.primevideo.com/detail/XYZ")?.service.id, "prime")
        XCTAssertEqual(svc("https://www.peacocktv.com/watch/asset/x")?.service.id, "peacock")
        XCTAssertEqual(svc("https://www.paramountplus.com/shows/x/")?.service.id, "paramount")
        XCTAssertEqual(svc("https://play.max.com/show/1")?.service.id, "max")
        XCTAssertEqual(svc("https://www.hbomax.com/x")?.service.id, "max")
    }

    func testMostSpecificHostWinsAndSharedHostsNeedAPath() {
        XCTAssertEqual(svc("https://tv.youtube.com/watch/x")?.service.id, "youtubetv")
        XCTAssertEqual(svc("https://m.youtube.com/watch?v=dQw4w9WgXcQ")?.service.id, "youtube")
        XCTAssertEqual(svc("https://www.amazon.com/gp/video/detail/B0X")?.service.id, "prime-amazon")
        XCTAssertNil(svc("https://www.amazon.com/dp/B0123456"))
    }

    func testOrdinaryPagesAndMediaLinksAreNotServices() {
        XCTAssertNil(svc("https://example.com/video-page"))
        XCTAssertNil(svc("https://cdn.example.com/a/master.m3u8"))
        XCTAssertNil(svc("https://notnetflix.com/title/1"))
        XCTAssertNil(svc("https://netflix.com.evil.example/title/1"))
    }

    func testExtractsTitleIdsOnlyWhereTheServiceSupportsIt() {
        XCTAssertEqual(svc("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=5")?.contentId, "dQw4w9WgXcQ")
        XCTAssertEqual(svc("https://youtu.be/dQw4w9WgXcQ?si=zz")?.contentId, "dQw4w9WgXcQ")
        XCTAssertEqual(svc("https://www.youtube.com/shorts/dQw4w9WgXcQ")?.contentId, "dQw4w9WgXcQ")
        XCTAssertNil(svc("https://www.youtube.com/watch?v=short")?.contentId)
        XCTAssertNotNil(svc("https://www.youtube.com/watch?v=short"))
        XCTAssertEqual(svc("https://www.netflix.com/title/80100172")?.contentId, "80100172")
        XCTAssertEqual(svc("https://www.netflix.com/watch/80100172?trackId=1")?.contentId, "80100172")
        XCTAssertNil(svc("https://www.hulu.com/watch/abc")?.contentId)
    }

    func testChannelMatcher() {
        let apps = [RokuApp(id: "12", name: "Netflix"), RokuApp(id: "837", name: "YouTube"), RokuApp(id: "195316", name: "YouTube TV"),
                    RokuApp(id: "291097", name: "Disney Plus"), RokuApp(id: "dev", name: "Netflix"), RokuApp(id: "tvinput.hdmi1", name: "HDMI 1")]
        XCTAssertEqual(ChannelMatcher.find(svc("https://netflix.com/title/1")!.service, apps)?.id, "12")
        XCTAssertEqual(ChannelMatcher.find(svc("https://disneyplus.com/x")!.service, apps)?.id, "291097")
        XCTAssertEqual(ChannelMatcher.find(svc("https://youtube.com/watch?v=dQw4w9WgXcQ")!.service, apps)?.id, "837")
        XCTAssertNil(ChannelMatcher.find(svc("https://youtube.com/watch?v=dQw4w9WgXcQ")!.service, [RokuApp(id: "195316", name: "YouTube TV")]))
        // the sideloaded dev channel must never be taken for Netflix
        XCTAssertNil(ChannelMatcher.find(svc("https://netflix.com/x")!.service, [RokuApp(id: "dev", name: "Netflix")]))
        XCTAssertNil(ChannelMatcher.find(svc("https://hulu.com/x")!.service, apps))
        let names = ChannelMatcher.installedServices(apps).map { $0.0.displayName }
        XCTAssertTrue(["Netflix", "YouTube", "YouTube TV", "Disney+"].allSatisfy(names.contains))
        XCTAssertFalse(names.contains("Hulu"))
    }

    func testDeepLinkPath() {
        let m = svc("https://www.youtube.com/watch?v=dQw4w9WgXcQ")!
        XCTAssertEqual(RokuChannelLauncher.deepLinkPath(channelId: "837", match: m), "/launch/837?contentId=dQw4w9WgXcQ&mediaType=shortFormVideo")
        let n = svc("https://www.netflix.com/title/80100172")!
        XCTAssertEqual(RokuChannelLauncher.deepLinkPath(channelId: "12", match: n), "/launch/12?contentId=80100172&mediaType=movie")
    }
}

final class SafetyTests: XCTestCase {
    func testRedactor() {
        let r = Redactor.url("https://user:pw@cdn.example.com:8443/a/b/master.m3u8?token=SECRET123&x=1#frag")
        XCTAssertEqual(r, "https://cdn.example.com:8443/…/master.m3u8?…")
        XCTAssertFalse(r.contains("SECRET123")); XCTAssertFalse(r.contains("pw"))
        XCTAssertEqual(Redactor.cookieHeader("sid=abc123; theme=dark"), "sid=•••; theme=•••")
        XCTAssertEqual(Redactor.cookieHeader(nil), "")
        XCTAssertEqual(Redactor.text("failed for abc123", secrets: ["abc123"]), "failed for •••")
    }

    func testPrivateAndLocalAddressesAreBlocked() {
        for ip in ["127.0.0.1", "10.0.0.5", "172.16.3.4", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "224.0.0.1"] {
            XCTAssertTrue(UpstreamPolicy.isBlockedIPv4(UpstreamPolicy.parseIPv4(ip)!), ip)
        }
        for ip in ["8.8.8.8", "93.184.216.34"] { XCTAssertFalse(UpstreamPolicy.isBlockedIPv4(UpstreamPolicy.parseIPv4(ip)!), ip) }
        for ip in ["::1", "fd00::1", "fe80::1", "::ffff:192.168.1.5"] { XCTAssertTrue(UpstreamPolicy.isBlockedIPv6(ip), ip) }
        XCTAssertFalse(UpstreamPolicy.isBlockedIPv6("2606:4700:4700::1111"))
    }

    func testUrlChecksRejectLocalHostsAndOddSchemes() {
        XCTAssertNotNil(UpstreamPolicy.checkUrl("http://192.168.1.1/admin", privateOk: []))
        XCTAssertNotNil(UpstreamPolicy.checkUrl("http://localhost:8080/x", privateOk: []))
        XCTAssertNotNil(UpstreamPolicy.checkUrl("http://printer.local/x", privateOk: []))
        XCTAssertNotNil(UpstreamPolicy.checkUrl("http://[::1]:8080/x", privateOk: []))
        XCTAssertNotNil(UpstreamPolicy.checkUrl("ftp://example.com/x", privateOk: []))
        XCTAssertNotNil(UpstreamPolicy.checkUrl("file:///etc/passwd", privateOk: []))
        XCTAssertNil(UpstreamPolicy.checkUrl("https://example.com/v.mp4", privateOk: []))
        XCTAssertNil(UpstreamPolicy.checkUrl("http://192.168.1.50/v.mp4", privateOk: ["192.168.1.50"]))   // only when the user chose it
    }

    func testSubnetAndSiteSession() {
        XCTAssertTrue(NetworkAddress.sameSubnet24("192.168.1.20", "192.168.1.114"))
        XCTAssertFalse(NetworkAddress.sameSubnet24("192.168.1.20", "192.168.2.114"))
        let d = SiteSession.cookieDomains("video.example.com")
        XCTAssertTrue(["video.example.com", ".video.example.com", "example.com", ".example.com"].allSatisfy(d.contains))
        XCTAssertFalse(d.contains("com") || d.contains(".com"))
        XCTAssertNil(SiteSession.startProblem("https://accounts.example.com/login"))
        XCTAssertNotNil(SiteSession.startProblem("http://example.com/login"))
        XCTAssertNotNil(SiteSession.startProblem("https://192.168.1.1/login"))
        XCTAssertNotNil(SiteSession.startProblem("javascript:alert(1)"))
        XCTAssertTrue(SiteSession.navigationAllowed("https://a.example.com/x"))
        for u in ["javascript:alert(1)", "file:///x", "intent://x", "content://x", "data:text/html,hi", "couchcast://x"] { XCTAssertFalse(SiteSession.navigationAllowed(u), u) }
    }
}

final class HlsTests: XCTestCase {
    let master = """
    #EXTM3U
    #EXT-X-STREAM-INF:BANDWIDTH=800000,CODECS="avc1.4d401e,mp4a.40.2"
    low/index.m3u8
    #EXT-X-STREAM-INF:BANDWIDTH=2000000,CODECS="hvc1.2.4.L93.B0,mp4a.40.2"
    https://other.example.com/hi/index.m3u8?t=1
    #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",URI="audio/en.m3u8"
    """
    let media = """
    #EXTM3U
    #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x01
    #EXT-X-MAP:URI="init.mp4"
    #EXTINF:4.0,
    seg0.ts
    #EXTINF:4.0,
    /abs/seg1.ts?sig=XYZ
    #EXT-X-ENDLIST
    """

    func testRewritesEveryReferenceThroughTheRelay() {
        var seen: [String] = []
        let out = HlsPlaylist.rewrite(media, base: "https://cdn.example.com/v/index.m3u8") { seen.append($0); return "R\(seen.count)" }
        XCTAssertEqual(seen, ["https://cdn.example.com/v/key.bin", "https://cdn.example.com/v/init.mp4", "https://cdn.example.com/v/seg0.ts", "https://cdn.example.com/abs/seg1.ts?sig=XYZ"])
        XCTAssertTrue(out.contains("URI=\"R1\"")); XCTAssertTrue(out.contains("URI=\"R2\""))
        let lines = out.components(separatedBy: "\n")
        XCTAssertTrue(lines.contains("R3")); XCTAssertTrue(lines.contains("R4"))
        XCTAssertFalse(out.contains("cdn.example.com") || out.contains("sig=XYZ"))   // no upstream URL may remain
        XCTAssertTrue(out.contains("IV=0x01")); XCTAssertTrue(out.contains("#EXT-X-ENDLIST"))
    }

    func testMasterReferencesAndAudioRenditionsAreRewritten() {
        var seen: [String] = []
        _ = HlsPlaylist.rewrite(master, base: "https://cdn.example.com/m/master.m3u8") { seen.append($0); return "x" }
        XCTAssertEqual(seen, ["https://cdn.example.com/m/low/index.m3u8", "https://other.example.com/hi/index.m3u8?t=1", "https://cdn.example.com/m/audio/en.m3u8"])
        XCTAssertTrue(HlsPlaylist.isMaster(master)); XCTAssertFalse(HlsPlaylist.isMaster(media))
    }

    func testDataUrisAreLeftAlone() {
        let t = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"data:text/plain;base64,AAAA\"\nseg.ts"
        XCTAssertTrue(HlsPlaylist.rewrite(t, base: "https://a.example.com/i.m3u8") { _ in "R" }.contains("data:text/plain;base64,AAAA"))
    }

    func testDetectsRealDrmButNotPlainAes() {
        XCTAssertNil(HlsPlaylist.drmFinding(media))
        XCTAssertNil(HlsPlaylist.drmFinding("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\",KEYFORMAT=\"identity\"\ns.ts"))
        XCTAssertNotNil(HlsPlaylist.drmFinding("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\ns.ts"))
        XCTAssertNotNil(HlsPlaylist.drmFinding("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES-CTR,URI=\"data:x\",KEYFORMAT=\"urn:uuid:edef8ba9\"\ns.ts"))
        XCTAssertNotNil(HlsPlaylist.drmFinding("#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=AES-128,URI=\"k\",KEYFORMAT=\"com.microsoft.playready\"\n"))
    }

    func testCodecsFirstReferenceAndPlaylistSniffing() {
        XCTAssertEqual(HlsPlaylist.codecs(master), ["avc1.4d401e", "mp4a.40.2", "hvc1.2.4.L93.B0"])
        XCTAssertEqual(HlsPlaylist.firstReference(master, "https://cdn.example.com/m/master.m3u8"), "https://cdn.example.com/m/low/index.m3u8")
        XCTAssertTrue(HlsPlaylist.looksLikePlaylist("\u{FEFF}#EXTM3U\n"))
        XCTAssertFalse(HlsPlaylist.looksLikePlaylist("<html>"))
    }

    func testRelayTableOnlyServesWhatWasRegistered() {
        var t = RelayTable()
        let a = t.register("https://c.example.com/v/seg0.ts?sig=1")
        XCTAssertEqual(a.ext, "ts")
        XCTAssertEqual(t.register("https://c.example.com/v/seg0.ts?sig=1").id, a.id)   // same address, same id
        let b = t.register("https://c.example.com/v/index.m3u8")
        XCTAssertNotEqual(a.id, b.id); XCTAssertEqual(b.ext, "m3u8")
        XCTAssertEqual(t.url(for: a.id), "https://c.example.com/v/seg0.ts?sig=1")
        XCTAssertNil(t.url(for: 99))
        XCTAssertNil(t.register("https://c.example.com/noext").ext)
    }
}

final class DiagnosisTests: XCTestCase {
    private func ok(_ hops: Int = 0) -> Attempt { Attempt(status: 200, hops: hops) }
    private func st(_ s: Int?) -> Attempt { Attempt(status: s) }
    private func probe(_ m: Ladder, seg: Ladder? = nil, drm: String? = nil, dash: Bool = false, codecs: [String] = []) -> ProbeResult {
        ProbeResult(isHls: true, drm: drm, codecs: codecs, manifest: m, segment: seg, hadSessionCookies: false, isDash: dash)
    }
    private let netflix = StreamingServices.recognize("https://netflix.com/title/1")

    func testDrmIsReportedAsProtectedAndNeverOffersRelay() {
        let d = FailureClassifier.diagnose(DiagnoseInput(probe: probe(Ladder(bare: ok(), context: ok(), session: nil), drm: "SAMPLE-AES"), service: netflix))
        XCTAssertEqual(d.kind, .drmProtected)
        XCTAssertEqual(d.offers, [.openChannel, .mirror])
        XCTAssertFalse(d.offers.contains(.relay))
        let r = FailureClassifier.diagnose(DiagnoseInput(probe: nil, rokuDrm: "widevine"))
        XCTAssertEqual(r.kind, .drmProtected)
        XCTAssertEqual(FailureClassifier.diagnose(DiagnoseInput(probe: nil, rokuDrm: "none")).kind, .unknown)
    }

    func testBrowserOnlyAndSessionOnlyStreams() {
        XCTAssertEqual(FailureClassifier.diagnose(DiagnoseInput(probe: probe(Ladder(bare: st(403), context: ok(), session: nil)))).kind, .needsRequestContext)
        let s = FailureClassifier.diagnose(DiagnoseInput(probe: probe(Ladder(bare: st(403), context: st(403), session: ok()))))
        XCTAssertEqual(s.kind, .needsSession); XCTAssertEqual(s.offers, [.signIn, .relay, .mirror])
        let s2 = FailureClassifier.diagnose(DiagnoseInput(probe: probe(Ladder(bare: st(403), context: st(403), session: ok())), hasSiteSession: true))
        XCTAssertEqual(s2.offers, [.relay, .mirror])
    }

    func testErrorsMapToClearAdvice() {
        XCTAssertEqual(FailureClassifier.diagnose(DiagnoseInput(probe: probe(Ladder(bare: st(401), context: st(401), session: nil)))).kind, .signInRequired)
        XCTAssertEqual(FailureClassifier.diagnose(DiagnoseInput(probe: probe(Ladder(bare: st(404), context: st(404), session: nil)))).kind, .notFound)
        XCTAssertEqual(FailureClassifier.diagnose(DiagnoseInput(probe: probe(Ladder(bare: st(403), context: st(403), session: nil)))).kind, .accessDenied)
        XCTAssertEqual(FailureClassifier.diagnose(DiagnoseInput(probe: probe(Ladder(bare: st(nil), context: st(nil), session: nil)))).kind, .network)
        XCTAssertEqual(FailureClassifier.diagnose(DiagnoseInput(probe: probe(Ladder(bare: ok(), context: ok(), session: nil), seg: Ladder(bare: st(403), context: ok(), session: nil)))).kind, .needsRequestContext)
        let d = FailureClassifier.diagnose(DiagnoseInput(probe: probe(Ladder(bare: ok(2), context: ok(), session: nil), codecs: ["av01.0.05M.08", "mp4a.40.2"])))
        XCTAssertEqual(d.kind, .rokuCouldNotPlay)
        XCTAssertTrue(d.detail.contains("av01") && d.detail.contains("redirects"))
        XCTAssertEqual(FailureClassifier.diagnose(DiagnoseInput(probe: probe(Ladder(bare: ok(), context: ok(), session: nil), dash: true))).kind, .dashOnly)
        XCTAssertEqual(FailureClassifier.diagnose(DiagnoseInput(probe: nil)).offers, [.relay, .mirror])
    }

    func testPlannerAndStatus() {
        let caps = ReceiverCapabilities.roku(receiverInstalled: true)
        XCTAssertEqual(PlaybackPlanner.usable([.relay, .relay, .mirror, .signIn, .openChannel], caps), [.relay, .mirror, .signIn, .openChannel])
        XCTAssertEqual(PlaybackPlanner.usable([.relay], ReceiverCapabilities.roku(receiverInstalled: false)), [])
        XCTAssertEqual(ReceiverCapabilities.roku(receiverInstalled: nil).directPlay, .unknown)
        XCTAssertEqual(ReceiverCapabilities.roku(receiverInstalled: nil).mirroring, .likely)   // can't be verified from the phone
        XCTAssertEqual(PlaybackPlanner.status(for: Diagnosis(kind: .drmProtected, headline: "", detail: "", offers: [])), .unsupportedProtected)
    }

    func testRelayFailureExplanations() {
        XCTAssertTrue(RelayFailure.explain(requests: 0, upstream: [:], drm: nil, playing: false, lastError: nil).contains("never contacted your phone"))
        XCTAssertTrue(RelayFailure.explain(requests: 3, upstream: [403: 1], drm: nil, playing: false, lastError: nil).contains("refused"))
        XCTAssertTrue(RelayFailure.explain(requests: 3, upstream: [200: 5], drm: nil, playing: false, lastError: nil).contains("couldn't play it"))
        XCTAssertTrue(RelayFailure.explain(requests: 1, upstream: [:], drm: "SAMPLE-AES", playing: false, lastError: nil).contains("copy-protected"))
    }

    func testEndDetector() {
        var t = Date(timeIntervalSince1970: 0)
        let d = PlaybackEndDetector(now: { t })
        XCTAssertFalse(d.onReport("none"))                       // nothing is over before playback was seen
        t = t.addingTimeInterval(61); XCTAssertTrue(d.onReport("none"))
        let e = PlaybackEndDetector(now: { t })
        XCTAssertFalse(e.onReport("play")); XCTAssertFalse(e.onReport("close")); XCTAssertFalse(e.onReport("close")); XCTAssertTrue(e.onReport("close"))
    }

    func testMirroringGuide() {
        let g = MirroringGuide.forRoku()
        XCTAssertTrue(g.steps.contains { $0.where == "On your iPhone" && $0.text.contains("Screen Mirroring") })
        XCTAssertTrue(g.caveat.contains("black picture"))
        XCTAssertEqual(MirroringGuide.forRoku(support: .no).support, .no)
        XCTAssertEqual(MirrorStatus.fromCaptured(false), .notDetected); XCTAssertEqual(MirrorStatus.fromCaptured(true), .connected)
        XCTAssertFalse(MirrorStatus.message(.notDetected).contains("connected."))
    }
}

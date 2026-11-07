import XCTest
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
@testable import CouchcastCore

final class ParseTests: XCTestCase {
    func testDeviceInfo() {
        let xml = "<device-info><user-device-name>Living &amp; Den</user-device-name><friendly-device-name>Roku TV</friendly-device-name><model-name>58R6</model-name><is-tv>true</is-tv><power-mode>PowerOn</power-mode></device-info>"
        let d = RokuParse.deviceInfo(xml)
        XCTAssertEqual(d.name, "Living & Den"); XCTAssertEqual(d.model, "58R6"); XCTAssertTrue(d.isTv)
        XCTAssertEqual(RokuParse.deviceInfo("<device-info></device-info>").name, "Roku")
    }

    func testApps() {
        let xml = "<apps><app id=\"dev\" type=\"appl\" version=\"1.0.5\">Couchcast Receiver</app><app id=\"12\" type=\"appl\" version=\"4.1\">Netflix</app><app id=\"tvinput.hdmi1\" type=\"tvin\">HDMI 1</app></apps>"
        let a = RokuParse.apps(xml)
        XCTAssertEqual(a.map { $0.id }, ["dev", "12", "tvinput.hdmi1"])
        XCTAssertEqual(a[0].version, "1.0.5"); XCTAssertEqual(a[2].type, "tvin")
    }

    func testPlayerAndActiveApp() {
        let p = RokuParse.player("<player state=\"play\" error=\"false\"><format audio=\"aac_adts\" video=\"mpeg4_10\" drm=\"none\" video_res=\"1920x1080\"/></player>")
        XCTAssertEqual(p?.state, "play"); XCTAssertEqual(p?.error, false); XCTAssertEqual(p?.video, "mpeg4_10"); XCTAssertEqual(p?.resolution, "1920x1080")
        XCTAssertTrue(RokuParse.player("<player state=\"close\" error=\"true\"></player>")?.error ?? false)
        XCTAssertNil(RokuParse.player("<nothing/>"))
        XCTAssertEqual(RokuParse.activeAppId("<active-app><app id=\"dev\" type=\"appl\">Couchcast Receiver</app></active-app>"), "dev")
    }
}

final class ReceiverTests: XCTestCase {
    func testFormats() {
        XCTAssertEqual(ReceiverPlayer.formatFor("https://x.com/a/b.MP4"), "mp4")
        XCTAssertEqual(ReceiverPlayer.formatFor("https://x.com/live/master.m3u8?token=abc"), "hls")
        XCTAssertEqual(ReceiverPlayer.formatFor("http://x.com/v.mpd#t=1"), "dash")
        XCTAssertEqual(ReceiverPlayer.formatFor("https://x.com/v.mkv"), "mkv")
        XCTAssertNil(ReceiverPlayer.formatFor("https://www.youtube.com/watch?v=abc"))
        XCTAssertNil(ReceiverPlayer.formatFor("https://x.com/page.html?file=a.mp4"))
    }

    func testQueryEncodesTheLink() {
        let q = ReceiverPlayer.query(url: "https://x.com/v.mp4?a=1&b=2 c", format: "mp4")
        XCTAssertTrue(q.hasPrefix("contentId=https%3A%2F%2Fx.com%2Fv.mp4%3Fa%3D1%26b%3D2%20c&"), q)
        XCTAssertTrue(q.hasSuffix("&mediaType=shortFormVideo&format=mp4"), q)
        let bad = ReceiverPlayer.query(url: "https://x.com/v.m3u8", format: "hls", referer: "https://a.com/\r\nX-Evil: 1", userAgent: "ok\nbad")
        XCTAssertFalse(bad.contains("referer") || bad.contains("useragent") || bad.contains("Evil"), bad)
    }

    func testPhotoAndPath() {
        let q = ReceiverPlayer.query(url: "http://192.168.1.5:8000/r/t/0.jpg", format: ReceiverPlayer.photoFormat, mediaType: ReceiverPlayer.photoMediaType)
        XCTAssertTrue(q.hasSuffix("&mediaType=photo&format=photo"), q)
        XCTAssertTrue(ReceiverPlayer.requestPath(running: true, url: "http://x/p.jpg", format: "photo", mediaType: "photo").hasPrefix("/input?contentId="))
        XCTAssertTrue(ReceiverPlayer.requestPath(running: false, url: "http://x/v.mp4", format: "mp4").hasPrefix("/launch/dev?contentId="))
    }

    func testReceiverState() {
        func app(_ v: String, _ name: String = "Couchcast Receiver") -> [RokuApp] { [RokuApp(id: "dev", name: name, version: v)] }
        XCTAssertEqual(ReceiverCheck.classify(nil), .unknown)
        XCTAssertEqual(ReceiverCheck.classify([]), .notInstalled)
        XCTAssertEqual(ReceiverCheck.classify(app("1.0.4")), .outdated)
        XCTAssertEqual(ReceiverCheck.classify(app("1.0.5")), .current)
        XCTAssertEqual(ReceiverCheck.classify(app("1.1.0")), .current)
        XCTAssertEqual(ReceiverCheck.classify(app("1.0.5", "Other")), .otherDevApp)
        XCTAssertEqual(ReceiverCheck.classify(app("abc")), .outdated)
    }
}

final class RangeAndPhotoTests: XCTestCase {
    func testRanges() {
        XCTAssertEqual(ByteRange.parse(nil, length: 100), .full)
        XCTAssertEqual(ByteRange.parse("bytes=0-9", length: -1), .full)
        XCTAssertEqual(ByteRange.parse("bytes=0-9", length: 100), .partial(start: 0, end: 9))
        XCTAssertEqual(ByteRange.parse("bytes=90-", length: 100), .partial(start: 90, end: 99))
        XCTAssertEqual(ByteRange.parse("bytes=90-500", length: 100), .partial(start: 90, end: 99))
        XCTAssertEqual(ByteRange.parse("bytes=-30", length: 100), .partial(start: 70, end: 99))
        XCTAssertEqual(ByteRange.parse("bytes=-500", length: 100), .partial(start: 0, end: 99))
        XCTAssertEqual(ByteRange.parse("bytes=100-", length: 100), .unsatisfiable)
        XCTAssertEqual(ByteRange.parse("bytes=-0", length: 100), .unsatisfiable)
        XCTAssertEqual(ByteRange.parse("bytes=5-1", length: 100), .full)
        XCTAssertEqual(ByteRange.parse("bytes=0-1,5-9", length: 100), .full)
        XCTAssertEqual(ByteRange.parse("items=0-1", length: 100), .full)
        XCTAssertEqual(ByteRange.parse("bytes=abc-", length: 100), .full)
    }

    func testPhotoSize() {
        XCTAssertEqual(PhotoSize.fit(width: 4000, height: 3000), PixelSize(1440, 1080))
        XCTAssertEqual(PhotoSize.fit(width: 3000, height: 4000), PixelSize(1080, 1440))
        XCTAssertEqual(PhotoSize.fit(width: 800, height: 600), PixelSize(800, 600))
        XCTAssertEqual(PhotoSize.fit(width: 1920, height: 1080), PixelSize(1920, 1080))
        XCTAssertEqual(PhotoSize.fit(width: 7680, height: 1920), PixelSize(1920, 480))
    }

    private func png(_ w: Int, _ h: Int) -> Data {
        let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.setFillColor(CGColor(red: 1, green: 0, blue: 0, alpha: 1)); ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
        let out = NSMutableData()
        let dest = CGImageDestinationCreateWithData(out, UTType.png.identifier as CFString, 1, nil)!
        CGImageDestinationAddImage(dest, ctx.makeImage()!, nil); CGImageDestinationFinalize(dest)
        return out as Data
    }

    private func size(of data: Data) -> PixelSize? {
        guard let s = CGImageSourceCreateWithData(data as CFData, nil), let p = CGImageSourceCopyPropertiesAtIndex(s, 0, nil) as? [CFString: Any],
              let w = p[kCGImagePropertyPixelWidth] as? Int, let h = p[kCGImagePropertyPixelHeight] as? Int else { return nil }
        return PixelSize(w, h)
    }

    func testBigPhotoIsShrunkToJpeg() throws {
        let jpg = try XCTUnwrap(PhotoPrep.jpegData(from: png(4000, 3000)))
        XCTAssertEqual(jpg.prefix(2), Data([0xFF, 0xD8]))                 // JPEG signature
        XCTAssertEqual(size(of: jpg), PixelSize(1440, 1080))
    }

    func testSmallPhotoIsNotEnlarged() throws {
        let jpg = try XCTUnwrap(PhotoPrep.jpegData(from: png(640, 480)))
        XCTAssertEqual(size(of: jpg), PixelSize(640, 480))
        XCTAssertNil(PhotoPrep.jpegData(from: Data("not an image".utf8)))
    }
}

final class MediaLinkTests: XCTestCase {
    func testCleanPrefersPlaylistsAndDedupes() {
        let c = MediaLinks.clean(["https://a.com/x.mp4", "https://a.com/live/master.m3u8?t=1", "https://a.com/x.mp4#frag", "https://a.com/page.html", "javascript:alert(1)"])
        XCTAssertEqual(c, ["https://a.com/live/master.m3u8?t=1", "https://a.com/x.mp4"])
    }

    func testExtractFindsAbsoluteRelativeAndEscaped() {
        let html = "<video src=\"/media/clip.mp4\"></video><script>var c={\"file\":\"https:\\/\\/cdn.example.com\\/v\\/index.m3u8?sig=1&amp;x=2\"}</script>"
        let found = MediaLinks.extract(html: html, base: URL(string: "https://site.example/watch/1")!)
        XCTAssertTrue(found.contains("https://site.example/media/clip.mp4"), "\(found)")
        XCTAssertTrue(found.contains("https://cdn.example.com/v/index.m3u8?sig=1&x=2"), "\(found)")
        XCTAssertEqual(found.first, "https://cdn.example.com/v/index.m3u8?sig=1&x=2")   // playlist first
    }

    func testLabel() {
        XCTAssertEqual(MediaLinks.label("https://cdn.example.com/v/clip.mp4?x=1"), "cdn.example.com / clip.mp4")
    }

    func testNeighborsAndPrivate() {
        XCTAssertEqual(LocalNetwork.neighbors(of: "192.168.1.20").count, 253)
        XCTAssertFalse(LocalNetwork.neighbors(of: "192.168.1.20").contains("192.168.1.20"))
        XCTAssertTrue(LocalNetwork.isPrivate("10.0.0.5")); XCTAssertTrue(LocalNetwork.isPrivate("172.20.1.1"))
        XCTAssertFalse(LocalNetwork.isPrivate("8.8.8.8")); XCTAssertFalse(LocalNetwork.isPrivate("172.32.0.1"))
    }
}

/// "The Roku" is a plain HTTP client on 127.0.0.1.
final class LocalServerTests: XCTestCase {
    private var server: LocalServer!
    private var file: URL!
    private let data = Data((0..<10_000).map { UInt8($0 % 251) })
    private let client = URLSession(configuration: .ephemeral)

    override func setUp() async throws {
        server = LocalServer()
        try await server.start()
        file = FileManager.default.temporaryDirectory.appendingPathComponent("clip-\(UUID().uuidString).mp4")
        try data.write(to: file)
    }

    override func tearDown() async throws {
        server.stop()
        try? FileManager.default.removeItem(at: file)
    }

    private func publish(clientIP: String = "127.0.0.1") throws -> ServedFile {
        server.publish(file: try FileSource(url: file, mime: "video/mp4"), clientIP: clientIP, host: "127.0.0.1", ext: "mp4")
    }

    private func call(_ url: String, method: String = "GET", range: String? = nil) async throws -> (Data, HTTPURLResponse) {
        var req = URLRequest(url: URL(string: url)!)
        req.httpMethod = method
        if let r = range { req.setValue(r, forHTTPHeaderField: "Range") }
        let (d, resp) = try await client.data(for: req)
        return (d, resp as! HTTPURLResponse)
    }

    func testServesWholeFile() async throws {
        let s = try publish()
        XCTAssertTrue(s.url.hasSuffix(".mp4"), s.url)
        let (d, r) = try await call(s.url)
        XCTAssertEqual(r.statusCode, 200); XCTAssertEqual(d, data)
        XCTAssertEqual(r.value(forHTTPHeaderField: "Content-Type"), "video/mp4")
        XCTAssertEqual(r.value(forHTTPHeaderField: "Accept-Ranges"), "bytes")
        XCTAssertEqual(server.stats(token: s.token)?.bytes, 10_000)
    }

    func testSeeksWithRanges() async throws {
        let s = try publish()
        var (d, r) = try await call(s.url, range: "bytes=1000-1999")
        XCTAssertEqual(r.statusCode, 206); XCTAssertEqual(r.value(forHTTPHeaderField: "Content-Range"), "bytes 1000-1999/10000")
        XCTAssertEqual(d, data.subdata(in: 1000..<2000))
        (d, r) = try await call(s.url, range: "bytes=9500-")
        XCTAssertEqual(d, data.subdata(in: 9500..<10_000))
        (d, r) = try await call(s.url, range: "bytes=-100")
        XCTAssertEqual(d, data.subdata(in: 9900..<10_000))
        (_, r) = try await call(s.url, range: "bytes=20000-")
        XCTAssertEqual(r.statusCode, 416)
    }

    func testHeadHasSizeAndNoBody() async throws {
        let s = try publish()
        let (d, r) = try await call(s.url, method: "HEAD")
        XCTAssertEqual(r.statusCode, 200); XCTAssertEqual(r.value(forHTTPHeaderField: "Content-Length"), "10000"); XCTAssertEqual(d.count, 0)
    }

    func testOnlyThatRokuWithTheRightAddress() async throws {
        let other = try publish(clientIP: "192.168.1.77")
        let (_, r1) = try await call(other.url)
        XCTAssertEqual(r1.statusCode, 403)
        let ok = try publish()
        let wrongToken = ok.url.replacingOccurrences(of: ok.token, with: String(repeating: "0", count: 32))
        let (_, r2) = try await call(wrongToken)
        XCTAssertEqual(r2.statusCode, 404)
        let (_, r3) = try await call(ok.url.replacingOccurrences(of: "/0.mp4", with: "/99.mp4").replacingOccurrences(of: "/1.mp4", with: "/99.mp4"))
        XCTAssertEqual(r3.statusCode, 404)
        let (_, r4) = try await call(ok.url, method: "DELETE")
        XCTAssertEqual(r4.statusCode, 405)
    }

    func testClosingTheSessionStopsServing() async throws {
        let s = try publish()
        let (_, before) = try await call(s.url)
        XCTAssertEqual(before.statusCode, 200)
        server.close(token: s.token)
        let (_, after) = try await call(s.url)
        XCTAssertEqual(after.statusCode, 404)
    }
}

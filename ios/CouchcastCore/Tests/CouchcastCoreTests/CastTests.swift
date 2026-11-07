import XCTest
@testable import CouchcastCore

/// A scripted TV, not a real Cast device. Each snapshot() call returns the next entry; the last one repeats.
private final class FakeTv: CastTransport {
    let script: [CastSnapshot?]
    var connected: Bool, accepts: Bool
    var loaded: (String, String)?
    var stopped = false
    private var i = 0
    init(_ script: [CastSnapshot?], connected: Bool = true, accepts: Bool = true) { self.script = script; self.connected = connected; self.accepts = accepts }
    func isConnected() -> Bool { connected }
    func load(url: String, contentType: String) async -> Bool { loaded = (url, contentType); return accepts }
    func snapshot() async -> CastSnapshot? { let v = script[min(i, script.count - 1)]; i += 1; return v }
    func stop() async -> Bool { stopped = true; return connected }
}

final class CastTests: XCTestCase {
    private func playback(_ tv: CastTransport) -> CastPlayback { CastPlayback(tv, waitSeconds: 1.5, stableSeconds: 0.1, pollSeconds: 0.01) }

    func testConfirmsPlayingOnlyWhenTheTvSaysPlayingAndStaysUp() async {
        let tv = FakeTv([CastSnapshot(.idle), CastSnapshot(.loading), CastSnapshot(.buffering), CastSnapshot(.playing)])
        let out = await playback(tv).play(url: "https://x.com/v.mp4", format: "mp4")
        XCTAssertEqual(out.result, .playing); XCTAssertEqual(out.last?.state, "play")
        XCTAssertEqual(tv.loaded?.0, "https://x.com/v.mp4"); XCTAssertEqual(tv.loaded?.1, "video/mp4")
    }

    func testNeverClaimsPlayingWhileTheTvOnlyBuffers() async {
        let out = await playback(FakeTv([CastSnapshot(.buffering)])).play(url: "https://x.com/v.mp4", format: "mp4")
        XCTAssertEqual(out.result, .receiverOpenNotConfirmed)
    }

    func testAnErrorBeforePlayingIsFailedNotPlaying() async {
        let out = await playback(FakeTv([CastSnapshot(.loading), CastSnapshot(.idle, error: true)])).play(url: "https://x.com/v.m3u8", format: "hls")
        XCTAssertEqual(out.result, .stoppedEarly); XCTAssertEqual(out.playedSeconds, 0); XCTAssertEqual(out.last?.error, true)
    }

    func testAStreamThatStartsThenDiesIsStoppedEarly() async {
        let out = await playback(FakeTv([CastSnapshot(.playing), CastSnapshot(.idle)])).play(url: "https://x.com/v.mp4", format: "mp4")
        XCTAssertEqual(out.result, .stoppedEarly)
    }

    func testAShortVideoThatFinishesIsStillPlayed() async {
        let out = await playback(FakeTv([CastSnapshot(.playing), CastSnapshot(.idle, finished: true)])).play(url: "https://x.com/v.mp4", format: "mp4")
        XCTAssertEqual(out.result, .playing)
    }

    func testPausedAndBufferingDuringTheStableWindowAreFine() async {
        let out = await playback(FakeTv([CastSnapshot(.playing), CastSnapshot(.buffering), CastSnapshot(.paused)])).play(url: "https://x.com/v.mp4", format: "mp4")
        XCTAssertEqual(out.result, .playing)
    }

    func testSilentTvIsNotStarted() async {
        let out = await playback(FakeTv([nil])).play(url: "https://x.com/v.mp4", format: "mp4")
        XCTAssertEqual(out.result, .notStarted)
    }

    func testNotConnectedOrRejectedLoadIsSendFailed() async {
        let a = await playback(FakeTv([CastSnapshot(.playing)], connected: false)).play(url: "https://x.com/v.mp4", format: "mp4")
        XCTAssertEqual(a.result, .sendFailed)
        let b = await playback(FakeTv([CastSnapshot(.playing)], accepts: false)).play(url: "https://x.com/v.mp4", format: "mp4")
        XCTAssertEqual(b.result, .sendFailed)
    }

    func testFormatsMessagesAndLinks() {
        XCTAssertEqual(CastFormats.contentType("hls"), "application/x-mpegURL")
        XCTAssertEqual(CastFormats.contentType("dash"), "application/dash+xml")
        XCTAssertEqual(CastFormats.contentType("mkv"), "video/x-matroska")
        XCTAssertEqual(CastFormats.contentType("anything-else"), "video/mp4")
        for res in [PlayResult.playing, .stoppedEarly, .receiverOpenNotConfirmed, .notStarted, .sendFailed] {
            XCTAssertEqual(CastMessages.forOutcome(PlayOutcome(res, playedSeconds: 3)).hasPrefix("Confirmed"), res == .playing, "\(res)")
        }
        XCTAssertTrue(CastMessages.forOutcome(PlayOutcome(.stoppedEarly, playedSeconds: 0)).contains("could not play"))
        XCTAssertTrue(CastMessages.forOutcome(PlayOutcome(.stoppedEarly, playedSeconds: 7)).contains("7 seconds"))
        XCTAssertEqual(CastLinks.normalize(" x.com/a "), "https://x.com/a")
        XCTAssertEqual(CastLinks.normalize("http://x.com/v.mp4"), "http://x.com/v.mp4")
        for bad in ["javascript:alert(1)", "ftp://x.com/v.mp4", "two words", "", "file:///sdcard/a.mp4"] { XCTAssertNil(CastLinks.normalize(bad), bad) }
    }
}

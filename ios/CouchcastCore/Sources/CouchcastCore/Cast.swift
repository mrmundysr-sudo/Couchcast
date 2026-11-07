import Foundation

public enum CastPlayerState: Equatable { case unknown, idle, loading, buffering, playing, paused }

/// What the TV last reported. `error`: it gave up on the media. `finished`: it played to the end.
public struct CastSnapshot: Equatable {
    public let state: CastPlayerState
    public let error: Bool, finished: Bool
    public init(_ state: CastPlayerState, error: Bool = false, finished: Bool = false) { self.state = state; self.error = error; self.finished = finished }

    public func toPlayerInfo() -> PlayerInfo {
        let s: String
        switch state {
        case .playing: s = "play"
        case .buffering, .loading: s = "buffer"
        case .paused: s = "pause"
        default: s = "close"
        }
        return PlayerInfo(state: s, error: error)
    }
}

/// The part of Google Cast the confirmation logic needs, so that logic can be tested without the Cast SDK.
public protocol CastTransport: AnyObject {
    func isConnected() -> Bool
    /// True only when the TV accepted the load request.
    func load(url: String, contentType: String) async -> Bool
    func snapshot() async -> CastSnapshot?
    func stop() async -> Bool
}

public enum CastFormats {
    /// MIME type the Cast media receiver needs for a format name used by the Play flow (hls / dash / mp4 / mkv).
    public static func contentType(_ format: String) -> String {
        switch format.lowercased() {
        case "hls": return "application/x-mpegURL"
        case "dash": return "application/dash+xml"
        case "mkv": return "video/x-matroska"
        default: return "video/mp4"
        }
    }
}

/// Same promise as the Roku path: report playing only when the TV itself says it is playing and keeps saying it for
/// `stableSeconds`. A stream that starts and then dies is stoppedEarly. Never guesses.
public struct CastPlayback {
    private let transport: CastTransport
    private let wait: TimeInterval, stable: TimeInterval, poll: TimeInterval
    public init(_ transport: CastTransport, waitSeconds: TimeInterval = 20, stableSeconds: TimeInterval = 6, pollSeconds: TimeInterval = 1) {
        self.transport = transport; wait = waitSeconds; stable = stableSeconds; poll = pollSeconds
    }

    private func pause() async { try? await Task.sleep(nanoseconds: UInt64(poll * 1_000_000_000)) }

    public func play(url: String, format: String) async -> PlayOutcome {
        guard transport.isConnected() else { return PlayOutcome(.sendFailed) }
        guard await transport.load(url: url, contentType: CastFormats.contentType(format)) else { return PlayOutcome(.sendFailed) }

        let end = Date().addingTimeInterval(wait)
        var last: CastSnapshot?
        var playingSince: Date?
        while Date() < end {
            let s = await transport.snapshot()
            if let s = s { last = s }
            if let s = s, s.error { return PlayOutcome(.stoppedEarly, last: s.toPlayerInfo(), playedSeconds: 0) }
            if let s = s, s.state == .playing { playingSince = Date(); break }
            await pause()
        }
        guard let since = playingSince else {
            return PlayOutcome(last == nil ? .notStarted : .receiverOpenNotConfirmed, last: last?.toPlayerInfo())
        }
        while Date().timeIntervalSince(since) < stable {
            await pause()
            guard let s = await transport.snapshot() else { continue }
            let alive = !s.error && ([.playing, .buffering, .paused].contains(s.state) || s.finished)
            if alive { last = s; continue }
            return PlayOutcome(.stoppedEarly, last: (last ?? s).toPlayerInfo(), playedSeconds: Int(Date().timeIntervalSince(since)))
        }
        return PlayOutcome(.playing, last: last?.toPlayerInfo())
    }
}

/// Plain-English results for the Cast screen. Honest about what Cast can and cannot do.
public enum CastMessages {
    public static let hint = "Cast TVs fetch the video themselves, so sites that need a sign-in, a browser header, or don't allow other apps to read their streams won't work here yet. Direct MP4 links work best."

    public static func forOutcome(_ out: PlayOutcome) -> String {
        switch out.result {
        case .playing: return "Confirmed: the TV reports it is playing."
        case .stoppedEarly:
            return out.playedSeconds == 0 ? "The TV could not play this video. \(hint)"
                : "The video started, then the TV stopped it after about \(out.playedSeconds) seconds. \(hint)"
        case .receiverOpenNotConfirmed: return "The TV accepted the video but has not reported playing. It may still be loading, or the video may be a format the TV can't play."
        case .notStarted: return "The TV did not respond. Make sure it is on, and reconnect with the Cast button."
        case .sendFailed: return "The TV is not connected. Tap the Cast button and choose your TV first."
        }
    }
}

public enum CastLinks {
    /// http(s) links only; adds https:// to a bare host. Nil when it can't be a web link.
    public static func normalize(_ raw: String) -> String? {
        let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty || t.contains(where: { $0.isWhitespace }) { return nil }
        let lower = t.lowercased()
        let u: String
        if lower.hasPrefix("http://") || lower.hasPrefix("https://") { u = t } else if t.contains("://") { return nil } else { u = "https://" + t }
        guard let after = u.range(of: "://") else { return nil }
        var authority = String(u[after.upperBound...])
        for sep in ["/", "?", "#"] { authority = authority.components(separatedBy: sep)[0] }
        // The host must look like a host (and optional port), so "javascript:alert(1)" can't pass as "https://javascript:alert(1)".
        return Rx.first("^((?:[^@]*@)?[A-Za-z0-9._-]+(?::\\d{1,5})?)$", in: authority) != nil ? u : nil
    }
}

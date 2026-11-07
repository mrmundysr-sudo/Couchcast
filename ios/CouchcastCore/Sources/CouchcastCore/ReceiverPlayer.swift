import Foundation

public enum ReceiverState: Equatable { case current, outdated, notInstalled, otherDevApp, unknown }

/// The Couchcast Receiver is a sideloaded Roku channel (id "dev"). This app can use it but, for now, cannot install it.
public enum ReceiverCheck {
    public static let title = "Couchcast Receiver"
    public static let requiredVersion = [1, 0, 5]   // receiver/manifest 1.0.00005: adds photo support

    public static func parseVersion(_ v: String) -> [Int]? {
        let parts = v.trimmingCharacters(in: .whitespaces).split(separator: ".", omittingEmptySubsequences: false)
        let nums = parts.compactMap { Int($0) }
        return (parts.isEmpty || nums.count != parts.count) ? nil : nums
    }

    public static func compare(_ a: [Int], _ b: [Int]) -> Int {
        for i in 0..<max(a.count, b.count) {
            let x = i < a.count ? a[i] : 0, y = i < b.count ? b[i] : 0
            if x != y { return x < y ? -1 : 1 }
        }
        return 0
    }

    public static func classify(_ apps: [RokuApp]?) -> ReceiverState {
        guard let apps = apps else { return .unknown }
        guard let dev = apps.first(where: { $0.id == ReceiverPlayer.devAppId }) else { return .notInstalled }
        if dev.name != title { return .otherDevApp }
        guard let v = parseVersion(dev.version) else { return .outdated }
        return compare(v, requiredVersion) >= 0 ? .current : .outdated
    }
}

public enum PlayResult: Equatable { case playing, stoppedEarly, receiverOpenNotConfirmed, notStarted, sendFailed }

public struct PlayOutcome: Equatable {
    public let result: PlayResult
    public let last: PlayerInfo?
    public let playedSeconds: Int
    public init(_ result: PlayResult, last: PlayerInfo? = nil, playedSeconds: Int = 0) {
        self.result = result; self.last = last; self.playedSeconds = playedSeconds
    }
}

/// Sends a link to the Couchcast Receiver with an ECP deep link and only reports "playing" when the Roku's own player says so.
public struct ReceiverPlayer {
    public static let devAppId = "dev"
    public static let photoFormat = "photo"
    public static let photoMediaType = "photo"

    private let ecp: ECPClient
    public init(ecp: ECPClient = ECPClient()) { self.ecp = ecp }

    private static let formatRegex = try! NSRegularExpression(pattern: "^[^?#]*\\.(m3u8|mpd|mp4|m4v|mov|mkv)(?:[?#]|$)", options: [.caseInsensitive])

    /// hls / dash / mp4 / mkv, or nil when the link doesn't look like a direct video file.
    public static func formatFor(_ url: String) -> String? {
        let t = url.trimmingCharacters(in: .whitespacesAndNewlines)
        let ns = t as NSString
        guard let m = formatRegex.firstMatch(in: t, range: NSRange(location: 0, length: ns.length)) else { return nil }
        switch ns.substring(with: m.range(at: 1)).lowercased() {
        case "m3u8": return "hls"
        case "mpd": return "dash"
        case "mkv": return "mkv"
        default: return "mp4"
        }
    }

    private static let unreserved = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
    static func enc(_ s: String) -> String { s.addingPercentEncoding(withAllowedCharacters: unreserved) ?? "" }

    private static func oneLine(_ v: String?) -> String? {
        // "\r\n" is ONE Character in Swift, so check the unicode scalars: a header value must be a single line.
        guard let t = v?.trimmingCharacters(in: .whitespaces), !t.isEmpty,
              !t.unicodeScalars.contains(where: { $0 == "\r" || $0 == "\n" }) else { return nil }
        return t
    }

    public static func query(url: String, format: String, referer: String? = nil, userAgent: String? = nil, mediaType: String = "shortFormVideo") -> String {
        var q = "contentId=\(enc(url))&mediaType=\(enc(mediaType))&format=\(enc(format))"
        if let r = oneLine(referer) { q += "&referer=\(enc(r))" }
        if let u = oneLine(userAgent) { q += "&useragent=\(enc(String(u.prefix(300))))" }
        return q
    }

    /// Already running: deliver as an input event. Otherwise launch the receiver with the link.
    public static func requestPath(running: Bool, url: String, format: String, referer: String? = nil, userAgent: String? = nil, mediaType: String = "shortFormVideo") -> String {
        (running ? "/input?" : "/launch/\(devAppId)?") + query(url: url, format: format, referer: referer, userAgent: userAgent, mediaType: mediaType)
    }

    public func play(ip: String, url: String, format: String, referer: String? = nil, userAgent: String? = nil,
                     waitSeconds: Double = 20, stableSeconds: Double = 6) async -> PlayOutcome {
        let running = await ecp.activeAppId(ip) == Self.devAppId
        guard await ecp.post(ip, Self.requestPath(running: running, url: url, format: format, referer: referer, userAgent: userAgent)) else {
            return PlayOutcome(.sendFailed)
        }
        var last: PlayerInfo?
        var playingSince: Date?
        let end = Date().addingTimeInterval(waitSeconds)
        while Date() < end {
            if let info = await ecp.playerInfo(ip) {
                last = info
                if !info.error && info.state == "play" { playingSince = Date(); break }
            }
            try? await Task.sleep(nanoseconds: 1_000_000_000)
        }
        guard let since = playingSince else {
            let open = await ecp.activeAppId(ip) == Self.devAppId
            return PlayOutcome(open ? .receiverOpenNotConfirmed : .notStarted, last: last)
        }
        while Date().timeIntervalSince(since) < stableSeconds {
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            guard let info = await ecp.playerInfo(ip) else { continue }
            if ["play", "buffer", "pause"].contains(info.state) && !info.error { last = info; continue }
            return PlayOutcome(.stoppedEarly, last: last ?? info, playedSeconds: Int(Date().timeIntervalSince(since)))
        }
        return PlayOutcome(.playing, last: last)
    }

    /// A photo has no player state. Returns whether the Roku accepted the command; the caller confirms by seeing the Roku fetch the file.
    public func showPhoto(ip: String, url: String) async -> Bool {
        let running = await ecp.activeAppId(ip) == Self.devAppId
        return await ecp.post(ip, Self.requestPath(running: running, url: url, format: Self.photoFormat, mediaType: Self.photoMediaType))
    }
}

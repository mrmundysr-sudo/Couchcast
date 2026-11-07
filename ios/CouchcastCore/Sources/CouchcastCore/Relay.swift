import Foundation

public struct RelaySpec {
    /// The phone's own Wi-Fi address (what the Roku is told to connect to).
    public var bindHost: String
    /// The only client allowed to fetch (the Roku).
    public var clientIP: String
    /// The stream address to relay.
    public var sourceUrl: String
    /// What the user pasted. A private-network source is only allowed when the user themself pointed at it.
    public var userEnteredUrl: String
    public var referer: String?, userAgent: String?
    public var cookies: CookieSource
    /// Cookies are attached only when true (the user chose a signed-in/session relay) and only to `credentialHosts`.
    public var attachCookies: Bool
    public var credentialHosts: Set<String>
    /// hls or mp4. DASH and DRM are not relayed.
    public var format: String
    public init(bindHost: String, clientIP: String, sourceUrl: String, userEnteredUrl: String, referer: String?, userAgent: String?,
                cookies: CookieSource, attachCookies: Bool, credentialHosts: Set<String>, format: String) {
        self.bindHost = bindHost; self.clientIP = clientIP; self.sourceUrl = sourceUrl; self.userEnteredUrl = userEnteredUrl
        self.referer = referer; self.userAgent = userAgent; self.cookies = cookies; self.attachCookies = attachCookies
        self.credentialHosts = credentialHosts; self.format = format
    }
}

public enum RelayOpen: Equatable {
    case ready(token: String, playUrl: String, format: String)
    case refused(String)
}

/// The web addresses a relay session may fetch, by small id. Anything not registered here is never fetched.
public struct RelayTable {
    public static let maxRedirects = 5
    public static let maxPlaylist = 5 * 1024 * 1024
    private var byId: [Int: String] = [:]
    private var next = 0
    public init() {}

    /// Registers `url` (or finds it) and returns its id and a short safe file extension such as "m3u8" or "ts".
    public mutating func register(_ url: String) -> (id: Int, ext: String?) {
        let ext = Self.extension(of: url)
        if let hit = byId.first(where: { $0.value == url }) { return (hit.key, ext) }
        let id = next; next += 1
        byId[id] = url
        return (id, ext)
    }

    public func url(for id: Int) -> String? { byId[id] }

    static func `extension`(of url: String) -> String? {
        let path = url.components(separatedBy: "?")[0].components(separatedBy: "#")[0]
        let last = path.components(separatedBy: "/").last ?? ""
        guard let dot = last.lastIndex(of: ".") else { return nil }
        let e = String(last[last.index(after: dot)...]).lowercased()
        return (2...5).contains(e.count) && e.allSatisfy({ $0.isLetter || $0.isNumber }) ? e : nil
    }
}

/// Counters only; no URLs, tokens or cookies. Safe to show in diagnostics.
public final class RelayStats: @unchecked Sendable {
    private let lock = NSLock()
    private var _requests = 0, _playlists = 0
    private var _bytes: Int64 = 0
    private var _status: [Int: Int] = [:]
    private var _drm: String?, _lastError: String?
    public init() {}

    func add(requests n: Int) { lock.lock(); _requests += n; lock.unlock() }
    func add(playlists n: Int) { lock.lock(); _playlists += n; lock.unlock() }
    func add(bytes n: Int64) { lock.lock(); _bytes += n; lock.unlock() }
    func count(_ status: Int) { lock.lock(); _status[status, default: 0] += 1; lock.unlock() }
    func set(drm: String) { lock.lock(); _drm = drm; lock.unlock() }
    func set(lastError: String) { lock.lock(); _lastError = lastError; lock.unlock() }

    public var requests: Int { lock.lock(); defer { lock.unlock() }; return _requests }
    public var bytes: Int64 { lock.lock(); defer { lock.unlock() }; return _bytes }
    public var rewrittenPlaylists: Int { lock.lock(); defer { lock.unlock() }; return _playlists }
    public var upstreamStatus: [Int: Int] { lock.lock(); defer { lock.unlock() }; return _status }
    public var drmDetected: String? { lock.lock(); defer { lock.unlock() }; return _drm }
    public var lastError: String? { lock.lock(); defer { lock.unlock() }; return _lastError }

    public func summary() -> String {
        "requests=\(requests), bytes=\(bytes), upstream=" + upstreamStatus.sorted { $0.key < $1.key }.map { "\($0.key)x\($0.value)" }.joined(separator: ", ")
    }
}

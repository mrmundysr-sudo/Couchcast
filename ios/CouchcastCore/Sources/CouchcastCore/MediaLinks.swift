import Foundation

/// Finding playable video addresses on a web page (ordinary video only; no DRM, no protected sites).
public enum MediaLinks {
    private static let media = try! NSRegularExpression(pattern: "^https?://[^?#\\s]*\\.(m3u8|mpd|mp4|m4v|mov|mkv)(?:[?#].*)?$", options: [.caseInsensitive])
    private static let adaptive = try! NSRegularExpression(pattern: "\\.(m3u8|mpd)$", options: [.caseInsensitive])

    public static func isMediaUrl(_ url: String) -> Bool {
        let t = url.trimmingCharacters(in: .whitespacesAndNewlines)
        return media.firstMatch(in: t, range: NSRange(location: 0, length: (t as NSString).length)) != nil
    }

    private static func isAdaptive(_ url: String) -> Bool {
        let p = url.components(separatedBy: "?")[0]
        return adaptive.firstMatch(in: p, range: NSRange(location: 0, length: (p as NSString).length)) != nil
    }

    /// Distinct playable links; streaming playlists first (main videos are usually HLS/DASH; loose .mp4 are more often previews and ads).
    public static func clean(_ urls: [String]) -> [String] {
        var seen = Set<String>()
        let cleaned = urls.map { $0.trimmingCharacters(in: .whitespacesAndNewlines).components(separatedBy: "#")[0] }
            .filter { isMediaUrl($0) && seen.insert($0).inserted }
        return cleaned.filter(isAdaptive) + cleaned.filter { !isAdaptive($0) }
    }

    public static func label(_ url: String) -> String {
        let noQuery = url.components(separatedBy: "?")[0].components(separatedBy: "#")[0]
        let rest = noQuery.components(separatedBy: "://").last ?? noQuery
        let host = rest.components(separatedBy: "/")[0]
        let file = rest.components(separatedBy: "/").last ?? ""
        let shown = file.count > 28 ? String(file.prefix(25)) + "…" : file
        return (shown.isEmpty || file == host) ? host : "\(host) / \(shown)"
    }

    /// Video addresses mentioned in a page's HTML or embedded JSON, made absolute against [base].
    public static func extract(html: String, base: URL) -> [String] {
        let text = html.replacingOccurrences(of: "\\/", with: "/").replacingOccurrences(of: "&amp;", with: "&")
        var found = Rx.all("(https?://[^\"'\\s<>\\\\)]+)", in: text).compactMap { $0.first }
        let ext = "m3u8|mpd|mp4|m4v|mov|mkv"
        for g in Rx.all("(?:src|href|content|file|url)\\s*[=:]\\s*[\"']([^\"']+\\.(?:\(ext))[^\"']*)[\"']", in: text) {
            guard let raw = g.first else { continue }
            if let abs = URL(string: raw, relativeTo: base)?.absoluteURL.absoluteString { found.append(abs) }
        }
        return clean(found)
    }
}

/// Loads a page the way a browser would and returns the videos found in it.
public struct PageScanner {
    public static let userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    public init() {}

    public func scan(_ url: URL) async -> [String] {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 12
        var req = URLRequest(url: url)
        req.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        guard let (data, _) = try? await URLSession(configuration: c).data(for: req) else { return [] }
        let html = String(decoding: data.prefix(3_000_000), as: UTF8.self)
        return MediaLinks.extract(html: html, base: url)
    }
}

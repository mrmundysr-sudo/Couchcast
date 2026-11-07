import Foundation

/// Small, pure HLS helpers: DRM detection, codec listing and rewriting every URI so it goes through the relay.
public enum HlsPlaylist {
    private static let uriTags = ["#EXT-X-KEY", "#EXT-X-SESSION-KEY", "#EXT-X-MAP", "#EXT-X-MEDIA", "#EXT-X-I-FRAME-STREAM-INF",
                                  "#EXT-X-PART", "#EXT-X-PRELOAD-HINT", "#EXT-X-RENDITION-REPORT"]

    public static func looksLikePlaylist(_ body: String) -> Bool {
        body.trimmingCharacters(in: CharacterSet(charactersIn: "\u{FEFF} \n\r\t")).hasPrefix("#EXTM3U")
    }

    public static func isMaster(_ text: String) -> Bool { text.components(separatedBy: "\n").contains { $0.hasPrefix("#EXT-X-STREAM-INF") } }

    public static func attr(_ line: String, _ name: String) -> String? {
        guard let m = Rx.all("(?:^|[,:])\(name)=(\"([^\"]*)\"|[^,]*)", in: line).first, m.count >= 2 else { return nil }
        return m[1].isEmpty ? m[0].trimmingCharacters(in: CharacterSet(charactersIn: "\"")) : m[1]
    }

    /// Non-nil (the key system) when the playlist uses real DRM. Plain AES-128 with an `identity` key is not DRM.
    public static func drmFinding(_ text: String) -> String? {
        for raw in text.components(separatedBy: "\n") {
            let line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard line.hasPrefix("#EXT-X-KEY") || line.hasPrefix("#EXT-X-SESSION-KEY") else { continue }
            let method = attr(line, "METHOD"), format = attr(line, "KEYFORMAT")
            if let m = method, m.lowercased().hasPrefix("sample-aes") { return m }
            if let f = format, !f.isEmpty, f.lowercased() != "identity" { return f }
        }
        return nil
    }

    public static func codecs(_ text: String) -> [String] {
        var seen = Set<String>(), out: [String] = []
        for line in text.components(separatedBy: "\n") where line.hasPrefix("#EXT-X-STREAM-INF") {
            for c in (attr(line, "CODECS") ?? "").split(separator: ",") {
                let t = c.trimmingCharacters(in: .whitespaces)
                if !t.isEmpty, seen.insert(t).inserted { out.append(t) }
            }
        }
        return out
    }

    public static func resolve(_ base: String, _ ref: String) -> String? {
        let r = ref.trimmingCharacters(in: .whitespacesAndNewlines)
        if r.isEmpty { return nil }
        if let abs = URL(string: r), abs.scheme != nil, abs.host != nil { return abs.absoluteString }
        guard let b = URL(string: base) else { return nil }
        return URL(string: r, relativeTo: b)?.absoluteURL.absoluteString
    }

    /// The first media/variant reference in the playlist, resolved against `base`.
    public static func firstReference(_ text: String, _ base: String) -> String? {
        text.components(separatedBy: "\n").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .first { !$0.isEmpty && !$0.hasPrefix("#") }.flatMap { resolve(base, $0) }
    }

    /// Replaces each URI with the value `register` returns for its absolute form. `data:` URIs are left alone.
    public static func rewrite(_ text: String, base: String, register: (String) -> String) -> String {
        guard let rx = try? NSRegularExpression(pattern: "URI=\"([^\"]*)\"") else { return text }
        return text.components(separatedBy: "\n").map { raw -> String in
            let line = raw.trimmingCharacters(in: CharacterSet(charactersIn: "\r"))
            if line.trimmingCharacters(in: .whitespaces).isEmpty { return line }
            if line.hasPrefix("#") {
                guard uriTags.contains(where: { line.hasPrefix($0) }), line.contains("URI=\"") else { return line }
                let ns = line as NSString
                var out = "", last = 0
                for m in rx.matches(in: line, range: NSRange(location: 0, length: ns.length)) {
                    out += ns.substring(with: NSRange(location: last, length: m.range.location - last))
                    let ref = ns.substring(with: m.range(at: 1))
                    if ref.lowercased().hasPrefix("data:") { out += ns.substring(with: m.range) }
                    else if let abs = resolve(base, ref) { out += "URI=\"\(register(abs))\"" }
                    else { out += ns.substring(with: m.range) }
                    last = m.range.location + m.range.length
                }
                return out + ns.substring(from: last)
            }
            return resolve(base, line).map(register) ?? line
        }.joined(separator: "\n")
    }
}

/// Keeps secrets (tokens, cookies, signed query strings) out of logs, diagnostics and the UI.
public enum Redactor {
    /// scheme://host/…/last-segment, with userinfo, query and fragment removed.
    public static func url(_ raw: String) -> String {
        guard let m = Rx.all("^(https?)://(?:[^/@]*@)?([^/:?#]+)(?::(\\d+))?([^?#]*)", in: raw.trimmingCharacters(in: .whitespacesAndNewlines)).first, m.count == 4 else { return "(link)" }
        let (scheme, host, port, path) = (m[0], m[1], m[2], m[3])
        let last = path.components(separatedBy: "/").last ?? ""
        let trimmed = path.drop(while: { $0 == "/" })
        let shortPath: String
        if path.isEmpty || path == "/" { shortPath = "" }
        else if !trimmed.contains("/") { shortPath = "/" + last }
        else { shortPath = "/…/" + last }
        return "\(scheme.lowercased())://\(host)\(port.isEmpty ? "" : ":" + port)\(shortPath)\(raw.contains("?") ? "?…" : "")"
    }

    /// Removes any occurrence of the given secret values from free text.
    public static func text(_ text: String, secrets: [String]) -> String {
        secrets.filter { $0.count >= 4 }.reduce(text) { $0.replacingOccurrences(of: $1, with: "•••") }
    }

    /// "name=value; other=value" -> "name=•••; other=•••" (names only, safe for diagnostics).
    public static func cookieHeader(_ cookies: String?) -> String {
        (cookies ?? "").split(separator: ";").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
            .map { ($0.components(separatedBy: "=").first ?? "") + "=•••" }.joined(separator: "; ")
    }
}

/// Where session cookies come from. The app uses the WKWebView cookie store; tests use a map. Values must never be logged.
public protocol CookieSource: Sendable {
    /// "a=b; c=d" for a URL, honouring the cookies' own domain/path/secure scoping, or nil.
    func cookies(for url: String) async -> String?
}

public struct NoCookies: CookieSource {
    public init() {}
    public func cookies(for url: String) async -> String? { nil }
}

/// Stops the relay from becoming an open proxy into the user's network. Public internet hosts only,
/// except a host the user themself gave as the source (e.g. their own media server).
public enum UpstreamPolicy {
    public static func isBlockedIPv4(_ o: [Int]) -> Bool {
        guard o.count == 4 else { return true }
        let (a, b) = (o[0], o[1])
        if a == 10 || a == 127 || a == 0 || a >= 224 { return true }       // private, loopback, "this" network, multicast and reserved
        if a == 172 && (16...31).contains(b) { return true }
        if a == 192 && b == 168 { return true }
        if a == 169 && b == 254 { return true }                              // link-local
        if a == 100 && (64...127).contains(b) { return true }                // carrier-grade NAT
        if a == 192 && b == 0 { return true }                                // IETF protocol assignments / TEST-NET-1 family
        return false
    }

    public static func parseIPv4(_ host: String) -> [Int]? {
        let p = host.split(separator: ".", omittingEmptySubsequences: false).map { Int($0) }
        guard p.count == 4, p.allSatisfy({ $0 != nil && $0! >= 0 && $0! <= 255 }) else { return nil }
        return p.map { $0! }
    }

    public static func isBlockedIPv6(_ host: String) -> Bool {
        let h = host.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        if h == "::" || h == "::1" { return true }
        if h.hasPrefix("::ffff:"), let v4 = parseIPv4(String(h.dropFirst(7))) { return isBlockedIPv4(v4) }   // IPv4-mapped
        if h.hasPrefix("fe8") || h.hasPrefix("fe9") || h.hasPrefix("fea") || h.hasPrefix("feb") { return true } // link-local fe80::/10
        if h.hasPrefix("fc") || h.hasPrefix("fd") { return true }                                              // unique local fc00::/7
        if h.hasPrefix("ff") { return true }                                                                   // multicast
        return false
    }

    public static func isIpLiteral(_ host: String) -> Bool { parseIPv4(host) != nil || host.contains(":") }

    public static func hostOf(_ url: String) -> String? {
        Rx.first("^https?://(?:[^/@]*@)?([^/:?#\\[]+|\\[[^\\]]+\\])", in: url.trimmingCharacters(in: .whitespacesAndNewlines))?.lowercased()
    }

    /// `privateOk` are hosts the user explicitly chose as the source. Returns a reason when blocked, else nil.
    public static func checkUrl(_ url: String, privateOk: Set<String>) -> String? {
        let t = url.trimmingCharacters(in: .whitespacesAndNewlines)
        let lower = t.lowercased()
        guard lower.hasPrefix("http://") || lower.hasPrefix("https://") else { return "only http/https" }
        guard let host = hostOf(t), !host.isEmpty else { return "not a web address" }
        if privateOk.contains(host) { return nil }
        if host == "localhost" || host.hasSuffix(".local") || host.hasSuffix(".internal") { return "local network address" }
        if let v4 = parseIPv4(host) { return isBlockedIPv4(v4) ? "private or local network address" : nil }
        if host.contains(":") { return isBlockedIPv6(host) ? "private or local network address" : nil }
        return nil
    }

    /// Resolves a name and refuses it when any answer is private/local, so a public name can't point inside the home network.
    public static func checkResolved(host: String, privateOk: Set<String>) -> String? {
        let h = host.lowercased()
        if privateOk.contains(h) || isIpLiteral(h) { return nil }
        var hints = addrinfo(); hints.ai_socktype = SOCK_STREAM
        var res: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(h, nil, &hints, &res) == 0, let first = res else { return "name did not resolve" }
        defer { freeaddrinfo(res) }
        var p: UnsafeMutablePointer<addrinfo>? = first
        while let a = p {
            var buf = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if let sa = a.pointee.ai_addr, getnameinfo(sa, a.pointee.ai_addrlen, &buf, socklen_t(buf.count), nil, 0, NI_NUMERICHOST) == 0 {
                let ip = String(cString: buf)
                if let v4 = parseIPv4(ip) { if isBlockedIPv4(v4) { return "resolves to a private or local address" } }
                else if isBlockedIPv6(ip) { return "resolves to a private or local address" }
            }
            p = a.pointee.ai_next
        }
        return nil
    }
}

public enum NetworkAddress {
    /// Is this Roku on the same /24 as the phone? Used only to explain "Roku can't reach the phone" cases.
    public static func sameSubnet24(_ a: String, _ b: String) -> Bool {
        let pa = a.split(separator: "."), pb = b.split(separator: ".")
        return pa.count == 4 && pb.count == 4 && pa[0...2] == pb[0...2]
    }
}

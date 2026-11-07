import Foundation

/// Never follows redirects on its own, so every hop can be checked against the upstream policy.
final class NoRedirect: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

/// Phone-side "why won't it play?" check. Fetches a few bytes of the stream, its playlist and its first segment
/// three ways and compares the answers. Never logs or returns header or cookie values.
public struct StreamProbe {
    private enum Mode { case bare, context, session }
    public init() {}

    private func session() -> URLSession {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 8; c.timeoutIntervalForResource = 20
        c.httpCookieStorage = nil; c.httpShouldSetCookies = false
        return URLSession(configuration: c, delegate: NoRedirect(), delegateQueue: nil)
    }

    public func probe(url: String, referer: String?, userAgent: String?, cookies: CookieSource, privateOk: Set<String> = []) async -> ProbeResult {
        let s = session(); defer { s.finishTasksAndInvalidate() }
        let pathOnly = url.components(separatedBy: "?")[0]
        let isDash = pathOnly.lowercased().hasSuffix(".mpd")
        let hasCookies = !((await cookies.cookies(for: url)) ?? "").isEmpty

        func ladder(_ u: String, whole: Bool) async -> (Ladder, String?) {
            var body: String?
            func go(_ mode: Mode) async -> Attempt {
                if mode == .session, ((await cookies.cookies(for: u)) ?? "").isEmpty { return Attempt(status: nil, error: "no-cookies") }
                let (a, b) = await fetch(s, u, mode, referer, userAgent, cookies, whole, privateOk)
                if a.ok && body == nil { body = b }
                return a
            }
            let bare = await go(.bare), ctx = await go(.context)
            let hereHasCookies = !(((await cookies.cookies(for: u)) ?? "").isEmpty)
            let ses: Attempt? = (hasCookies || hereHasCookies) ? await go(.session) : nil
            return (Ladder(bare: bare, context: ctx, session: ses), body)
        }

        let (manifest, body) = await ladder(url, whole: !isDash && pathOnly.lowercased().hasSuffix(".m3u8"))
        var drm: String?, codecs: [String] = [], segLadder: Ladder?, hls = false
        if let body = body, HlsPlaylist.looksLikePlaylist(body) {
            hls = true
            drm = HlsPlaylist.drmFinding(body)
            codecs = HlsPlaylist.codecs(body)
            var mediaText = body, mediaUrl = url
            if HlsPlaylist.isMaster(body), let variant = HlsPlaylist.firstReference(body, url) {
                let (_, vBody) = await ladder(variant, whole: true)
                if let v = vBody { mediaText = v; mediaUrl = variant }
            }
            drm = drm ?? HlsPlaylist.drmFinding(mediaText)
            if drm == nil, !HlsPlaylist.isMaster(mediaText), let seg = HlsPlaylist.firstReference(mediaText, mediaUrl) {
                segLadder = await ladder(seg, whole: false).0
            }
        }
        return ProbeResult(isHls: hls, drm: drm, codecs: codecs, manifest: manifest, segment: segLadder, hadSessionCookies: hasCookies, isDash: isDash)
    }

    private func fetch(_ s: URLSession, _ start: String, _ mode: Mode, _ referer: String?, _ ua: String?, _ cookies: CookieSource,
                       _ whole: Bool, _ privateOk: Set<String>) async -> (Attempt, String?) {
        var url = start, hops = 0
        while true {
            if UpstreamPolicy.checkUrl(url, privateOk: privateOk) != nil { return (Attempt(status: nil, error: "blocked", hops: hops), nil) }
            if let h = UpstreamPolicy.hostOf(url), UpstreamPolicy.checkResolved(host: h, privateOk: privateOk) != nil { return (Attempt(status: nil, error: "blocked", hops: hops), nil) }
            guard let u = URL(string: url) else { return (Attempt(status: nil, error: "bad-url", hops: hops), nil) }
            var req = URLRequest(url: u)
            if !whole { req.setValue("bytes=0-1023", forHTTPHeaderField: "Range") }
            if mode != .bare {
                if let ua = ua, !ua.isEmpty, !ua.contains("\n") { req.setValue(ua, forHTTPHeaderField: "User-Agent") }
                if let r = referer, !r.isEmpty, !r.contains("\n") {
                    req.setValue(r, forHTTPHeaderField: "Referer"); req.setValue(Self.origin(of: r), forHTTPHeaderField: "Origin")
                }
            }
            if mode == .session, let c = await cookies.cookies(for: url), !c.isEmpty { req.setValue(c, forHTTPHeaderField: "Cookie") }
            do {
                let (data, resp) = try await s.data(for: req)
                guard let http = resp as? HTTPURLResponse else { return (Attempt(status: nil, error: "no-response", hops: hops), nil) }
                if (301...308).contains(http.statusCode), let loc = http.value(forHTTPHeaderField: "Location"), hops < 5 {
                    guard let next = HlsPlaylist.resolve(url, loc) else { return (Attempt(status: http.statusCode, error: "bad-redirect", hops: hops), nil) }
                    url = next; hops += 1; continue
                }
                let text = ((200..<300).contains(http.statusCode) && whole) ? String(decoding: data.prefix(512 * 1024), as: UTF8.self) : nil
                return (Attempt(status: http.statusCode, error: nil, hops: hops), text)
            } catch {
                return (Attempt(status: nil, error: String(describing: type(of: error)), hops: hops), nil)
            }
        }
    }

    static func origin(of url: String) -> String {
        guard let m = Rx.all("^(https?://[^/]+)", in: url).first?.first else { return url }
        return m
    }
}

import Foundation
import Network

/// A file on this device that can be served to the Roku.
public protocol LocalFile: AnyObject {
    var name: String { get }
    var mime: String { get }
    var length: Int64 { get }
    func read(offset: Int64, count: Int) throws -> Data
}

public final class FileSource: LocalFile {
    public let name: String
    public let mime: String
    public let length: Int64
    private let url: URL

    public init(url: URL, mime: String, name: String? = nil) throws {
        self.url = url
        self.mime = mime
        self.name = name ?? url.lastPathComponent
        let attrs = try FileManager.default.attributesOfItem(atPath: url.path)
        self.length = (attrs[.size] as? NSNumber)?.int64Value ?? 0
    }

    public func read(offset: Int64, count: Int) throws -> Data {
        let h = try FileHandle(forReadingFrom: url)
        defer { try? h.close() }
        try h.seek(toOffset: UInt64(offset))
        return try h.read(upToCount: count) ?? Data()
    }
}

public struct ServedFile {
    public let token: String
    public let url: String
}

/// Local-network file server for casting from the phone. It serves only files that were published, only to the Roku's
/// address, only under an unguessable token. It is not a general web server: unknown paths, wrong tokens and other devices get nothing.
public final class LocalServer: @unchecked Sendable {
    private struct Session {
        let clientIP: String
        var files: [Int: LocalFile] = [:]
        var requests = 0
        var bytes: Int64 = 0
        /// Set for a web relay (a stream address the phone fetches on the Roku's behalf).
        var relay: RelayInfo?
        var lastActivity = Date()
        let createdAt = Date()
    }

    /// What a web relay needs: the addresses it may fetch (by id), how to look like the page's browser, and where it is served from.
    private struct RelayInfo {
        var table = RelayTable()
        let host: String
        let referer: String?, userAgent: String?
        let cookies: CookieSource
        let attachCookies: Bool
        let credentialHosts: Set<String>
        let privateOk: Set<String>
        let stats = RelayStats()
    }

    private let queue = DispatchQueue(label: "couchcast.localserver")
    private let lock = NSLock()
    private var sessions: [String: Session] = [:]
    private var nextId = 0
    private var listener: NWListener?
    public private(set) var port: UInt16 = 0

    public init() {}

    private static let maxSessions = 2
    public static let idleSeconds: TimeInterval = 10 * 60
    public static let maxSeconds: TimeInterval = 12 * 60 * 60

    public func start() async throws {
        if listener != nil { return }
        let l = try NWListener(using: .tcp)
        l.newConnectionHandler = { [weak self] c in self?.accept(c) }
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            var done = false
            l.stateUpdateHandler = { state in
                if done { return }
                switch state {
                case .ready: done = true; cont.resume()
                case .failed(let e): done = true; cont.resume(throwing: e)
                case .cancelled: done = true; cont.resume(throwing: NWError.posix(.ECANCELED))
                default: break
                }
            }
            l.start(queue: queue)
        }
        port = l.port?.rawValue ?? 0
        listener = l
    }

    public func stop() {
        listener?.cancel()
        listener = nil
        lock.lock(); sessions.removeAll(); lock.unlock()
    }

    /// Makes [file] fetchable by [clientIP] (the Roku) and returns the address to give it.
    public func publish(file: LocalFile, clientIP: String, host: String, ext: String) -> ServedFile {
        let token = Self.newToken()
        lock.lock()
        let id = nextId; nextId += 1
        var s = Session(clientIP: LocalServer.normalizeIP(clientIP))
        s.files[id] = file
        sessions[token] = s
        lock.unlock()
        let cleanExt = ext.lowercased().filter { $0.isLetter || $0.isNumber }
        return ServedFile(token: token, url: "http://\(host):\(port)/r/\(token)/\(id)" + (cleanExt.isEmpty ? "" : ".\(cleanExt)"))
    }

    public func close(token: String) {
        lock.lock(); sessions[token] = nil; lock.unlock()
    }

    /// Ends relays that have been idle too long or running too long. Returns how many were ended.
    @discardableResult
    public func expire(idle: TimeInterval = LocalServer.idleSeconds, max: TimeInterval = LocalServer.maxSeconds) -> Int {
        lock.lock(); defer { lock.unlock() }
        let now = Date()
        let dead = sessions.filter { now.timeIntervalSince($0.value.lastActivity) > idle || now.timeIntervalSince($0.value.createdAt) > max }.map { $0.key }
        dead.forEach { sessions[$0] = nil }
        return dead.count
    }

    /// Starts a web relay: the Roku is given a phone address, and the phone fetches the real stream for it.
    /// Serves only addresses registered here, only to this Roku, only under the unguessable token.
    public func openRelay(_ spec: RelaySpec) -> RelayOpen {
        guard spec.format == "hls" || spec.format == "mp4" else { return .refused("The phone relay supports MP4 and HLS (.m3u8) streams only.") }
        let userHost = UpstreamPolicy.hostOf(spec.userEnteredUrl)
        // A private-network source is only allowed when the user themself pointed at it.
        let privateOk: Set<String> = {
            guard let h = userHost, UpstreamPolicy.checkUrl("http://\(h)/", privateOk: []) != nil else { return [] }
            return [h]
        }()
        if let why = UpstreamPolicy.checkUrl(spec.sourceUrl, privateOk: privateOk) { return .refused("The relay won't fetch that address (\(why)).") }
        lock.lock()
        if sessions.count >= Self.maxSessions { lock.unlock(); return .refused("Another relay is already running. Stop it first.") }
        let token = Self.newToken()
        var s = Session(clientIP: LocalServer.normalizeIP(spec.clientIP))
        s.relay = RelayInfo(host: spec.bindHost, referer: spec.referer, userAgent: spec.userAgent, cookies: spec.cookies,
                            attachCookies: spec.attachCookies, credentialHosts: spec.credentialHosts, privateOk: privateOk)
        let url = relayURL(&s, token: token, port: port, for: spec.sourceUrl)
        sessions[token] = s
        lock.unlock()
        return .ready(token: token, playUrl: url, format: spec.format)
    }

    public func relayStats(token: String) -> RelayStats? {
        lock.lock(); defer { lock.unlock() }
        return sessions[token]?.relay?.stats
    }

    private static func newToken() -> String {
        (0..<16).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined()
    }

    private func relayURL(_ s: inout Session, token: String, port: UInt16, for url: String) -> String {
        let (id, ext) = s.relay!.table.register(url)
        return "http://\(s.relay!.host):\(port)/r/\(token)/\(id)" + (ext.map { ".\($0)" } ?? "")
    }

    public func stats(token: String) -> (requests: Int, bytes: Int64)? {
        lock.lock(); defer { lock.unlock() }
        guard let s = sessions[token] else { return nil }
        return (s.requests, s.bytes)
    }

    public var activeCount: Int { lock.lock(); defer { lock.unlock() }; return sessions.count }

    /// "192.168.1.2", "::ffff:192.168.1.2", "fe80::1%en0" -> plain address text.
    public static func normalizeIP(_ raw: String) -> String {
        var s = raw.trimmingCharacters(in: .whitespaces)
        if let i = s.firstIndex(of: "%") { s = String(s[s.startIndex..<i]) }
        if s.lowercased().hasPrefix("::ffff:") { s = String(s.dropFirst(7)) }
        return s
    }

    // MARK: connections

    private func accept(_ c: NWConnection) {
        c.start(queue: queue)
        receiveHead(c, Data())
    }

    private func receiveHead(_ c: NWConnection, _ buffer: Data) {
        c.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] data, _, isComplete, error in
            guard let self = self else { c.cancel(); return }
            var b = buffer
            if let d = data { b.append(d) }
            if let r = b.range(of: Data("\r\n\r\n".utf8)) {
                self.handle(c, head: String(decoding: b[b.startIndex..<r.lowerBound], as: UTF8.self))
            } else if error != nil || isComplete || b.count > 16_384 {
                c.cancel()
            } else {
                self.receiveHead(c, b)
            }
        }
    }

    private func remoteIP(_ c: NWConnection) -> String {
        if case let .hostPort(host, _) = c.endpoint { return LocalServer.normalizeIP("\(host)") }
        return ""
    }

    private func handle(_ c: NWConnection, head: String) {
        let lines = head.components(separatedBy: "\r\n")
        let parts = (lines.first ?? "").split(separator: " ")
        guard parts.count >= 2 else { return reply(c, 400, "Bad Request") }
        let method = String(parts[0]).uppercased()
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            if let i = line.firstIndex(of: ":") {
                headers[line[line.startIndex..<i].lowercased()] = line[line.index(after: i)...].trimmingCharacters(in: .whitespaces)
            }
        }
        let path = String(parts[1]).components(separatedBy: "?")[0]
        let seg = path.split(separator: "/").map(String.init)
        guard seg.count == 3, seg[0] == "r" else { return reply(c, 404, "Not Found") }
        let token = seg[1]
        let id = Int(seg[2].components(separatedBy: ".")[0])

        lock.lock()
        let session = sessions[token]
        let file = id.flatMap { session?.files[$0] }
        let upstream = id.flatMap { session?.relay?.table.url(for: $0) }
        lock.unlock()
        // The same answer for "no such session" and "no such file": reveal nothing.
        guard let s = session, file != nil || upstream != nil else { return reply(c, 404, "Not Found") }
        guard remoteIP(c) == s.clientIP else { return reply(c, 403, "Forbidden") }
        guard method == "GET" || method == "HEAD" else { return reply(c, 405, "Method Not Allowed") }

        lock.lock(); sessions[token]?.requests += 1; sessions[token]?.lastActivity = Date(); lock.unlock()
        if let f = file {
            serve(c, token: token, file: f, head: method == "HEAD", rangeHeader: headers["range"])
        } else if let u = upstream {
            s.relay?.stats.add(requests: 1)
            let isHead = method == "HEAD", range = headers["range"]
            Task { await self.relayUpstream(c, token: token, first: u, head: isHead, rangeHeader: range) }
        }
    }

    private func serve(_ c: NWConnection, token: String, file: LocalFile, head: Bool, rangeHeader: String?) {
        let total = file.length
        var status = 200, text = "OK"
        var start: Int64 = 0, count = total
        var extra: [String] = []
        switch ByteRange.parse(rangeHeader, length: total) {
        case .unsatisfiable:
            return reply(c, 416, "Range Not Satisfiable", extra: ["Content-Range: bytes */\(total)"])
        case .partial(let s, let e):
            status = 206; text = "Partial Content"
            start = s; count = e - s + 1
            extra.append("Content-Range: bytes \(s)-\(e)/\(total)")
        case .full:
            break
        }
        let header = "HTTP/1.1 \(status) \(text)\r\nContent-Type: \(file.mime)\r\nContent-Length: \(count)\r\nAccept-Ranges: bytes\r\n"
            + extra.map { $0 + "\r\n" }.joined() + "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        if head || count == 0 {
            c.send(content: Data(header.utf8), contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { _ in c.cancel() })
            return
        }
        c.send(content: Data(header.utf8), completion: .contentProcessed { [weak self] err in
            if err != nil { c.cancel(); return }
            self?.stream(c, token: token, file: file, offset: start, remaining: count)
        })
    }

    private func stream(_ c: NWConnection, token: String, file: LocalFile, offset: Int64, remaining: Int64) {
        let n = Int(min(remaining, 256 * 1024))
        guard let data = try? file.read(offset: offset, count: n), !data.isEmpty else { c.cancel(); return }
        let left = remaining - Int64(data.count)
        let last = left <= 0
        c.send(content: data, contentContext: last ? .finalMessage : .defaultMessage, isComplete: last, completion: .contentProcessed { [weak self] err in
            guard let self = self else { c.cancel(); return }
            self.lock.lock(); self.sessions[token]?.bytes += Int64(data.count); self.lock.unlock()
            if err != nil || last { c.cancel(); return }
            self.stream(c, token: token, file: file, offset: offset + Int64(data.count), remaining: left)
        })
    }

    private func reply(_ c: NWConnection, _ code: Int, _ text: String, extra: [String] = []) {
        let body = Data(text.utf8)
        let header = "HTTP/1.1 \(code) \(text)\r\nContent-Type: text/plain\r\nContent-Length: \(body.count)\r\n"
            + extra.map { $0 + "\r\n" }.joined() + "Connection: close\r\n\r\n"
        c.send(content: Data(header.utf8) + body, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { _ in c.cancel() })
    }

    // MARK: web relay

    private func sendAsync(_ c: NWConnection, _ data: Data, final: Bool = false) async -> Bool {
        await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
            c.send(content: data, contentContext: final ? .finalMessage : .defaultMessage, isComplete: final,
                   completion: .contentProcessed { cont.resume(returning: $0 == nil) })
        }
    }

    private func upstreamSession() -> URLSession {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 30; cfg.timeoutIntervalForResource = 6 * 60 * 60
        cfg.httpCookieStorage = nil; cfg.httpShouldSetCookies = false
        return URLSession(configuration: cfg, delegate: NoRedirect(), delegateQueue: nil)
    }

    private func relayUpstream(_ c: NWConnection, token: String, first: String, head: Bool, rangeHeader: String?) async {
        lock.lock(); let info = sessions[token]?.relay; lock.unlock()
        guard let info = info else { return reply(c, 404, "Not Found") }
        let stats = info.stats
        let session = upstreamSession(); defer { session.finishTasksAndInvalidate() }

        var url = first
        for _ in 0...RelayTable.maxRedirects {
            if let why = UpstreamPolicy.checkUrl(url, privateOk: info.privateOk) { stats.set(lastError: "blocked: \(why)"); return reply(c, 403, "Blocked") }
            if let h = UpstreamPolicy.hostOf(url), UpstreamPolicy.checkResolved(host: h, privateOk: info.privateOk) != nil {
                stats.set(lastError: "blocked: resolves to a private address"); return reply(c, 403, "Blocked")
            }
            guard let u = URL(string: url) else { return reply(c, 502, "Bad Gateway") }
            var req = URLRequest(url: u)
            req.httpMethod = head ? "HEAD" : "GET"
            req.setValue("*/*", forHTTPHeaderField: "Accept"); req.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
            if let ua = info.userAgent, !ua.isEmpty, !ua.contains("\n"), !ua.contains("\r") { req.setValue(ua, forHTTPHeaderField: "User-Agent") }
            if let r = info.referer, !r.isEmpty, !r.contains("\n"), !r.contains("\r") {
                req.setValue(r, forHTTPHeaderField: "Referer"); req.setValue(StreamProbe.origin(of: r), forHTTPHeaderField: "Origin")
            }
            if let rg = rangeHeader, rg.hasPrefix("bytes="), !rg.contains("\n") { req.setValue(rg, forHTTPHeaderField: "Range") }
            // Cookies only when the user opted in, only to the source's own hosts, scoped by the cookie store itself.
            if info.attachCookies, let h = UpstreamPolicy.hostOf(url), info.credentialHosts.contains(h),
               let ck = await info.cookies.cookies(for: url), !ck.isEmpty { req.setValue(ck, forHTTPHeaderField: "Cookie") }

            let bytes: URLSession.AsyncBytes, resp: URLResponse
            do { (bytes, resp) = try await session.bytes(for: req) } catch {
                stats.set(lastError: String(describing: type(of: error))); return reply(c, 502, "Bad Gateway")
            }
            guard let http = resp as? HTTPURLResponse else { return reply(c, 502, "Bad Gateway") }
            stats.count(http.statusCode)
            if (301...308).contains(http.statusCode), let loc = http.value(forHTTPHeaderField: "Location") {
                guard let next = HlsPlaylist.resolve(url, loc) else { return reply(c, 502, "Bad Gateway") }
                url = next; continue
            }
            await deliver(c, token: token, info: info, http: http, bytes: bytes, url: url, head: head)
            return
        }
        reply(c, 508, "Too many redirects")
    }

    private func deliver(_ c: NWConnection, token: String, info: RelayInfo, http: HTTPURLResponse, bytes: URLSession.AsyncBytes, url: String, head: Bool) async {
        let stats = info.stats
        let ctype = http.value(forHTTPHeaderField: "Content-Type") ?? ""
        let ok = (200..<300).contains(http.statusCode)
        let isPlaylist = !head && ok && (ctype.lowercased().contains("mpegurl") || url.components(separatedBy: "?")[0].lowercased().hasSuffix(".m3u8"))
        if isPlaylist {
            var body = Data()
            do { for try await b in bytes { body.append(b); if body.count > RelayTable.maxPlaylist { break } } } catch {}
            let text = String(decoding: body, as: UTF8.self)
            if HlsPlaylist.looksLikePlaylist(text) {
                if let drm = HlsPlaylist.drmFinding(text) { stats.set(drm: drm); return reply(c, 403, "Protected stream") }
                let rewritten = HlsPlaylist.rewrite(text, base: url) { abs in
                    if UpstreamPolicy.checkUrl(abs, privateOk: info.privateOk) != nil { return abs }
                    return self.registerRelay(token: token, abs) ?? abs
                }
                stats.add(playlists: 1)
                let data = Data(rewritten.utf8)
                let h = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: \(data.count)\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
                _ = await sendAsync(c, Data(h.utf8) + data, final: true); c.cancel(); return
            }
            let h = "HTTP/1.1 \(http.statusCode) OK\r\nContent-Type: \(ctype.isEmpty ? "text/plain" : ctype)\r\nContent-Length: \(body.count)\r\nConnection: close\r\n\r\n"
            _ = await sendAsync(c, Data(h.utf8) + body, final: true); c.cancel(); return
        }

        let length = http.value(forHTTPHeaderField: "Content-Length").flatMap { Int64($0) }
        var h = "HTTP/1.1 \(http.statusCode) \(HTTPURLResponse.localizedString(forStatusCode: http.statusCode).capitalized)\r\nContent-Type: \(ctype.isEmpty ? "application/octet-stream" : ctype)\r\n"
        for name in ["Content-Range", "Accept-Ranges", "Last-Modified"] { if let v = http.value(forHTTPHeaderField: name), !v.contains("\n") { h += "\(name): \(v)\r\n" } }
        if let l = length { h += "Content-Length: \(l)\r\n" } else if !head { h += "Transfer-Encoding: chunked\r\n" }
        h += "Connection: close\r\n\r\n"
        if head { _ = await sendAsync(c, Data(h.utf8), final: true); c.cancel(); return }
        guard await sendAsync(c, Data(h.utf8)) else { c.cancel(); return }
        var buf = Data()
        buf.reserveCapacity(64 * 1024)
        func flush(_ last: Bool) async -> Bool {
            if !buf.isEmpty {
                stats.add(bytes: Int64(buf.count))
                let chunk = length == nil ? Data("\(String(buf.count, radix: 16))\r\n".utf8) + buf + Data("\r\n".utf8) : buf
                buf.removeAll(keepingCapacity: true)
                if !(await sendAsync(c, chunk)) { return false }
            }
            if last, length == nil { return await sendAsync(c, Data("0\r\n\r\n".utf8), final: true) }
            return true
        }
        do {
            for try await b in bytes {
                buf.append(b)
                if buf.count >= 64 * 1024 { if !(await flush(false)) { c.cancel(); return } }
            }
        } catch { stats.set(lastError: String(describing: type(of: error))) }
        _ = await flush(true)
        c.cancel()
    }

    /// Gives a nested address (a playlist's segment or key) a relay URL on this same session.
    private func registerRelay(token: String, _ url: String) -> String? {
        lock.lock(); defer { lock.unlock() }
        guard var s = sessions[token], s.relay != nil else { return nil }
        let out = relayURL(&s, token: token, port: port, for: url)
        sessions[token] = s
        return out
    }

}

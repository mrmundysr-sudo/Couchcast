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
    }

    private let queue = DispatchQueue(label: "couchcast.localserver")
    private let lock = NSLock()
    private var sessions: [String: Session] = [:]
    private var nextId = 0
    private var listener: NWListener?
    public private(set) var port: UInt16 = 0

    public init() {}

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
        var bytes = [UInt8](repeating: 0, count: 16)
        for i in bytes.indices { bytes[i] = UInt8.random(in: 0...255) }
        let token = bytes.map { String(format: "%02x", $0) }.joined()
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
        lock.unlock()
        // The same answer for "no such session" and "no such file": reveal nothing.
        guard let s = session, let f = file else { return reply(c, 404, "Not Found") }
        guard remoteIP(c) == s.clientIP else { return reply(c, 403, "Forbidden") }
        guard method == "GET" || method == "HEAD" else { return reply(c, 405, "Method Not Allowed") }

        lock.lock(); sessions[token]?.requests += 1; lock.unlock()
        serve(c, token: token, file: f, head: method == "HEAD", rangeHeader: headers["range"])
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
}

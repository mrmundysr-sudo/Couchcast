import Foundation
import CryptoKit

/// RFC 2617 HTTP Digest, which is what the Roku development installer uses.
public enum DigestAuth {
    public struct Challenge: Equatable {
        public let realm: String, nonce: String
        public let qop: String?, opaque: String?
        public init(realm: String, nonce: String, qop: String?, opaque: String?) { self.realm = realm; self.nonce = nonce; self.qop = qop; self.opaque = opaque }
    }

    public static func parseChallenge(_ header: String?) -> Challenge? {
        guard let header = header, header.trimmingCharacters(in: .whitespaces).lowercased().hasPrefix("digest") else { return nil }
        guard let rx = try? NSRegularExpression(pattern: "(\\w+)=(?:\"([^\"]*)\"|([^,\\s]+))") else { return nil }
        let ns = header as NSString
        var params: [String: String] = [:]
        for m in rx.matches(in: header, range: NSRange(location: 0, length: ns.length)) {
            let key = ns.substring(with: m.range(at: 1)).lowercased()
            let quoted = m.range(at: 2).location != NSNotFound ? ns.substring(with: m.range(at: 2)) : ""
            params[key] = quoted.isEmpty && m.range(at: 3).location != NSNotFound ? ns.substring(with: m.range(at: 3)) : quoted
        }
        guard let realm = params["realm"], let nonce = params["nonce"] else { return nil }
        let qop = params["qop"]?.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.first { $0 == "auth" }
        return Challenge(realm: realm, nonce: nonce, qop: qop, opaque: params["opaque"])
    }

    public static func md5Hex(_ s: String) -> String {
        Insecure.MD5.hash(data: Data(s.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    public static func response(user: String, password: String, method: String, uri: String, challenge ch: Challenge, nc: String, cnonce: String) -> String {
        let ha1 = md5Hex("\(user):\(ch.realm):\(password)")
        let ha2 = md5Hex("\(method):\(uri)")
        if let qop = ch.qop { return md5Hex("\(ha1):\(ch.nonce):\(nc):\(cnonce):\(qop):\(ha2)") }
        return md5Hex("\(ha1):\(ch.nonce):\(ha2)")
    }

    public static func authorization(user: String, password: String, method: String, uri: String, challenge ch: Challenge,
                                     nc: String = "00000001", cnonce: String = randomCnonce()) -> String {
        let resp = response(user: user, password: password, method: method, uri: uri, challenge: ch, nc: nc, cnonce: cnonce)
        var s = "Digest username=\"\(user)\", realm=\"\(ch.realm)\", nonce=\"\(ch.nonce)\", uri=\"\(uri)\""
        if let qop = ch.qop { s += ", qop=\(qop), nc=\(nc), cnonce=\"\(cnonce)\"" }
        s += ", response=\"\(resp)\""
        if let o = ch.opaque { s += ", opaque=\"\(o)\"" }
        return s
    }

    public static func randomCnonce() -> String { (0..<8).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined() }
}

/// The Roku receiver channel as a ZIP sideload package (built from /receiver by tools/package_receiver.py).
/// This is NOT the iPhone app; it is what gets uploaded to the Roku.
public struct ReceiverPackage: Equatable {
    public let zip: Data
    public let title: String
    public let version: [Int]
    public init(zip: Data, title: String, version: [Int]) { self.zip = zip; self.title = title; self.version = version }

    /// `info` is the small JSON written next to the zip: {"title": "...", "version": [1,0,5]}.
    public init?(zip: Data, infoJSON: Data) {
        guard let o = try? JSONSerialization.jsonObject(with: infoJSON) as? [String: Any],
              let t = o["title"] as? String, let v = o["version"] as? [Int], !v.isEmpty else { return nil }
        self.init(zip: zip, title: t, version: v)
    }

    public var versionName: String { version.map(String.init).joined(separator: ".") }

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
}

public enum Reachability: Equatable { case networkDown, rokuUnreachable, ecpBlocked, devModeOff, installerReady }
public enum InstallState: Equatable { case none, current, outdated, otherDevApp, unknown }
public enum InstallStep: String, Equatable { case pick, checking, problem, devMode, restart, credentials, installing, verifying, done }

public enum InstallPlanner {
    /// The Roku keeps exactly one sideloaded channel, always with app id "dev".
    public static let devAppId = "dev"

    public static func classify(_ apps: [RokuApp]?, _ pkg: ReceiverPackage) -> InstallState {
        guard let apps = apps else { return .unknown }
        guard let dev = apps.first(where: { $0.id == devAppId }) else { return .none }
        if dev.name != pkg.title { return .otherDevApp }
        guard let installed = ReceiverPackage.parseVersion(dev.version) else { return .outdated }
        return ReceiverPackage.compare(installed, pkg.version) >= 0 ? .current : .outdated
    }

    public static func decide(_ reach: Reachability, restartPending: Bool, receiver: InstallState) -> InstallStep {
        switch reach {
        case .networkDown, .ecpBlocked: return .problem
        case .rokuUnreachable: return restartPending ? .restart : .problem
        case .devModeOff: return restartPending ? .restart : .devMode
        case .installerReady: return receiver == .current ? .done : .credentials
        }
    }
}

public enum InstallOutcome: Equatable {
    /// Roku said it installed the package. Not trusted until verified through ECP.
    case accepted(identical: Bool)
    case badPassword
    case compileFailed(String)
    case rejected(String)
    case installerClosed
    case unreachable(String)
    /// The upload was sent but the Roku dropped the connection before replying. It may still have installed.
    case responseLost(String)
    case unknown(String)
}

public enum InstallerProbe: Equatable { case open, closed }

/// Talks to the Roku Development Application Installer (HTTP, port 80, Digest user "rokudev").
public final class RokuDevClient: @unchecked Sendable {
    private let installerPort: Int
    private let ecpPort: Int

    public init(installerPort: Int = 80, ecpPort: Int = 8060) { self.installerPort = installerPort; self.ecpPort = ecpPort }

    // The Roku's installer hangs up idle keep-alive sockets, so every request gets its own session and asks the server to close.
    private func session(timeout: TimeInterval) -> URLSession {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = timeout
        c.timeoutIntervalForResource = max(timeout * 2, 10)
        c.httpShouldUsePipelining = false
        c.httpCookieStorage = nil
        c.urlCredentialStorage = nil
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: c)
    }

    private func installerURL(_ ip: String, _ path: String) -> URL? { URL(string: "http://\(ip):\(installerPort)\(path)") }

    public func probeInstaller(_ ip: String) async -> InstallerProbe { await probeInstallerDetail(ip).0 }

    /// Same probe, plus a short human-readable reason so a failure can be shown instead of hidden.
    public func probeInstallerDetail(_ ip: String) async -> (InstallerProbe, String) {
        guard let url = installerURL(ip, "/") else { return (.closed, "bad address") }
        var req = URLRequest(url: url); req.setValue("close", forHTTPHeaderField: "Connection")
        let s = session(timeout: 3); defer { s.finishTasksAndInvalidate() }
        do {
            let (_, resp) = try await s.data(for: req)
            return (.open, "answered HTTP \((resp as? HTTPURLResponse)?.statusCode ?? 0)")
        } catch let e as URLError {
            switch e.code {
            case .cannotConnectToHost: return (.closed, "port \(installerPort) refused the connection")
            case .timedOut: return (.closed, "port \(installerPort) did not answer in time")
            case .notConnectedToInternet: return (.closed, "this iPhone isn't allowed to reach local devices (Settings → Privacy & Security → Local Network → Couchcast)")
            default: return (.closed, "\(e.code.rawValue): \(e.localizedDescription)".prefix(80).description)
            }
        } catch { return (.closed, String(describing: error).prefix(80).description) }
    }

    public func install(ip: String, user: String, password: String, zip: Data) async -> InstallOutcome {
        var uploadStarted = false
        do {
            guard let challenge = try await fetchChallenge(ip) else {
                return .unknown("The Roku did not ask for a password the way a Roku installer should.")
            }
            guard let url = installerURL(ip, "/plugin_install") else { return .unreachable("bad address") }
            let boundary = "----CouchcastForm" + String(UInt64(Date().timeIntervalSince1970 * 1000), radix: 16)
            var req = URLRequest(url: url, timeoutInterval: 90)
            req.httpMethod = "POST"
            req.setValue(DigestAuth.authorization(user: user, password: password, method: "POST", uri: "/plugin_install", challenge: challenge), forHTTPHeaderField: "Authorization")
            req.setValue("close", forHTTPHeaderField: "Connection")
            req.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
            let s = session(timeout: 90); defer { s.finishTasksAndInvalidate() }
            uploadStarted = true
            let (data, resp) = try await s.upload(for: req, from: Self.multipartBody(zip: zip, boundary: boundary))
            return Self.parseResponse(code: (resp as? HTTPURLResponse)?.statusCode ?? 0, html: String(decoding: data, as: UTF8.self))
        } catch let e as URLError {
            if e.code == .cannotConnectToHost && !uploadStarted { return .installerClosed }
            if uploadStarted { return .responseLost(e.localizedDescription) }
            if e.code == .timedOut { return .unreachable("The Roku stopped answering.") }
            return .unreachable(e.localizedDescription)
        } catch {
            return uploadStarted ? .responseLost(error.localizedDescription) : .unreachable(error.localizedDescription)
        }
    }

    /// Asks for a Digest challenge. A couple of quick retries on a dropped socket (the Roku is single-threaded and flaky here).
    private func fetchChallenge(_ ip: String) async throws -> DigestAuth.Challenge? {
        guard let url = installerURL(ip, "/plugin_install") else { return nil }
        var last: Error?
        for _ in 0..<3 {
            var req = URLRequest(url: url, timeoutInterval: 4); req.setValue("close", forHTTPHeaderField: "Connection")
            let s = session(timeout: 4); defer { s.finishTasksAndInvalidate() }
            do {
                let (_, resp) = try await s.data(for: req)
                return DigestAuth.parseChallenge((resp as? HTTPURLResponse)?.value(forHTTPHeaderField: "WWW-Authenticate"))
            } catch let e as URLError where e.code == .cannotConnectToHost {
                throw e
            } catch {
                last = error
                try? await Task.sleep(nanoseconds: 400_000_000)
            }
        }
        throw last ?? URLError(.unknown)
    }

    public func launchDevApp(_ ip: String) async -> Bool { await ECPClient().launch(ip, InstallPlanner.devAppId) }
    public func activeAppId(_ ip: String) async -> String? { await ECPClient().activeAppId(ip) }

    /// Roku's installer uses a very strict hand-written form parser, so the form is written byte-for-byte like
    /// `curl -F mysubmit=Replace -F archive=@x.zip`: only Content-Disposition (plus Content-Type on the file part).
    public static func multipartBody(zip: Data, boundary: String) -> Data {
        var out = Data()
        func w(_ t: String) { out.append(Data(t.utf8)) }
        w("--\(boundary)\r\nContent-Disposition: form-data; name=\"mysubmit\"\r\n\r\nReplace\r\n")
        w("--\(boundary)\r\nContent-Disposition: form-data; name=\"archive\"; filename=\"couchcast-receiver.zip\"\r\n")
        w("Content-Type: application/zip\r\n\r\n")
        out.append(zip)
        w("\r\n--\(boundary)--\r\n")
        return out
    }

    /// Classifies the installer's HTML reply. Pure, so it is unit tested.
    public static func parseResponse(code: Int, html: String) -> InstallOutcome {
        if code == 401 { return .badPassword }
        var messages: [String] = []
        if let rx = try? NSRegularExpression(pattern: "<font[^>]*color=\"red\"[^>]*>(.*?)</font>", options: [.dotMatchesLineSeparators, .caseInsensitive]),
           let tags = try? NSRegularExpression(pattern: "<[^>]+>") {
            let ns = html as NSString
            for m in rx.matches(in: html, range: NSRange(location: 0, length: ns.length)) {
                let inner = ns.substring(with: m.range(at: 1))
                let clean = tags.stringByReplacingMatches(in: inner, range: NSRange(location: 0, length: (inner as NSString).length), withTemplate: "")
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                if !clean.isEmpty { messages.append(clean) }
            }
        }
        let joined = messages.joined(separator: " ")
        if joined.range(of: "Compilation Failed", options: .caseInsensitive) != nil { return .compileFailed(joined) }
        if joined.range(of: "Identical to previous version", options: .caseInsensitive) != nil { return .accepted(identical: true) }
        if joined.range(of: "Install Success", options: .caseInsensitive) != nil { return .accepted(identical: false) }
        if joined.range(of: "Failure", options: .caseInsensitive) != nil { return .rejected(joined) }
        if (200..<300).contains(code) { return .unknown("The Roku answered but did not say whether the install worked.") }
        return .rejected("The Roku installer answered with HTTP \(code).")
    }
}

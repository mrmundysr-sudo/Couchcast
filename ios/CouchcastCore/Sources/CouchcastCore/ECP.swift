import Foundation

/// Roku External Control Protocol (HTTP, port 8060). Short timeouts, one retry, no connection reuse (the Roku drops idle sockets).
public final class ECPClient: @unchecked Sendable {
    private let session: URLSession
    private let attempts: Int

    public init(timeout: TimeInterval = 2, attempts: Int = 2) {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = timeout
        c.timeoutIntervalForResource = timeout * 3
        c.httpShouldUsePipelining = false
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        session = URLSession(configuration: c)
        self.attempts = max(1, attempts)
    }

    public static func iconURL(ip: String, appId: String) -> URL? {
        URL(string: "http://\(ip):8060/query/icon/\(appId)")
    }

    private func send(_ ip: String, _ path: String, method: String) async -> String? {
        guard let url = URL(string: "http://\(ip):8060\(path)") else { return nil }
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.setValue("close", forHTTPHeaderField: "Connection")
        if method == "POST" { req.httpBody = Data() }
        for _ in 0..<attempts {
            if Task.isCancelled { return nil }
            do {
                let (data, resp) = try await session.data(for: req)
                guard let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { return nil }
                return String(decoding: data, as: UTF8.self)
            } catch {
                continue
            }
        }
        return nil
    }

    public func post(_ ip: String, _ path: String) async -> Bool { await send(ip, path, method: "POST") != nil }
    public func key(_ ip: String, _ key: String) async -> Bool { await post(ip, "/keypress/\(key)") }
    public func launch(_ ip: String, _ appId: String) async -> Bool { await post(ip, "/launch/\(appId)") }

    public func typeText(_ ip: String, _ text: String) async {
        let ok = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        for ch in text {
            let enc = String(ch).addingPercentEncoding(withAllowedCharacters: ok) ?? ""
            if !enc.isEmpty { _ = await post(ip, "/keypress/Lit_\(enc)") }
        }
    }

    public func deviceInfo(_ ip: String) async -> DeviceInfo? {
        await send(ip, "/query/device-info", method: "GET").map(RokuParse.deviceInfo)
    }

    /// nil when the Roku could not be reached.
    public func apps(_ ip: String) async -> [RokuApp]? {
        await send(ip, "/query/apps", method: "GET").map(RokuParse.apps)
    }

    public func activeAppId(_ ip: String) async -> String? {
        await send(ip, "/query/active-app", method: "GET").flatMap(RokuParse.activeAppId)
    }

    public func playerInfo(_ ip: String) async -> PlayerInfo? {
        await send(ip, "/query/media-player", method: "GET").flatMap(RokuParse.player)
    }
}

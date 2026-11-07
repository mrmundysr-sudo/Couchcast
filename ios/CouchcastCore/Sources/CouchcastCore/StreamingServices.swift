import Foundation

/// How (and whether) a service's Roku channel can be sent to a specific title. Best effort only.
public enum DeepLink: Equatable { case none, youtubeVideo, netflixTitle }

public struct StreamingService: Equatable {
    public let id: String
    public let displayName: String
    /// Host names (and their subdomains) that identify this service.
    public let hosts: [String]
    /// Names the Roku lists the official channel under. Matched against the Roku's own app list; no channel IDs are hardcoded.
    public let channelNames: [String]
    public let deepLink: DeepLink
    /// For shared hosts (e.g. amazon.com) only paths containing one of these count. Empty = any path.
    public let pathContains: [String]
    public init(_ id: String, _ displayName: String, hosts: [String], channels: [String], deepLink: DeepLink = .none, pathContains: [String] = []) {
        self.id = id; self.displayName = displayName; self.hosts = hosts; self.channelNames = channels; self.deepLink = deepLink; self.pathContains = pathContains
    }
}

public struct ServiceMatch: Equatable {
    public let service: StreamingService
    public let contentId: String?
    public init(service: StreamingService, contentId: String?) { self.service = service; self.contentId = contentId }
}

public enum StreamingServices {
    public static let all: [StreamingService] = [
        StreamingService("netflix", "Netflix", hosts: ["netflix.com"], channels: ["Netflix"], deepLink: .netflixTitle),
        StreamingService("youtube", "YouTube", hosts: ["youtube.com", "youtu.be", "youtube-nocookie.com"], channels: ["YouTube"], deepLink: .youtubeVideo),
        StreamingService("youtubetv", "YouTube TV", hosts: ["tv.youtube.com"], channels: ["YouTube TV"]),
        StreamingService("hulu", "Hulu", hosts: ["hulu.com"], channels: ["Hulu"]),
        StreamingService("disney", "Disney+", hosts: ["disneyplus.com"], channels: ["Disney+", "Disney Plus"]),
        StreamingService("prime", "Prime Video", hosts: ["primevideo.com"], channels: ["Prime Video", "Amazon Prime Video"]),
        StreamingService("prime-amazon", "Prime Video", hosts: ["amazon.com"], channels: ["Prime Video", "Amazon Prime Video"],
                         pathContains: ["/gp/video", "/Prime-Video", "/video/detail"]),
        StreamingService("peacock", "Peacock", hosts: ["peacocktv.com"], channels: ["Peacock TV", "Peacock"]),
        StreamingService("paramount", "Paramount+", hosts: ["paramountplus.com"], channels: ["Paramount+", "Paramount Plus"]),
        StreamingService("max", "Max", hosts: ["max.com", "hbomax.com"], channels: ["Max", "HBO Max"]),
        StreamingService("appletv", "Apple TV", hosts: ["tv.apple.com"], channels: ["Apple TV", "Apple TV+"]),
        StreamingService("tubi", "Tubi", hosts: ["tubitv.com"], channels: ["Tubi", "Tubi TV"]),
        StreamingService("pluto", "Pluto TV", hosts: ["pluto.tv"], channels: ["Pluto TV"]),
        StreamingService("twitch", "Twitch", hosts: ["twitch.tv"], channels: ["Twitch"]),
        StreamingService("sling", "Sling TV", hosts: ["sling.com"], channels: ["Sling TV", "Sling"]),
        StreamingService("fubo", "Fubo", hosts: ["fubo.tv"], channels: ["fuboTV", "Fubo"]),
        StreamingService("crunchyroll", "Crunchyroll", hosts: ["crunchyroll.com"], channels: ["Crunchyroll"]),
        StreamingService("plex", "Plex", hosts: ["plex.tv"], channels: ["Plex"]),
        StreamingService("rokuchannel", "The Roku Channel", hosts: ["therokuchannel.roku.com"], channels: ["The Roku Channel"]),
    ]

    /// (host, path, query) of an http(s) link, or nil.
    static func split(_ raw: String) -> (host: String, path: String, query: String)? {
        let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let m = Rx.all("^https?://(?:[^/@]*@)?([^/:?#]+)(?::\\d+)?([^?#]*)(?:\\?([^#]*))?", in: t).first, m.count >= 3 else { return nil }
        return (m[0].lowercased(), m[1], m[2])
    }

    private static func hostMatches(_ host: String, _ h: String) -> Bool { host == h || host.hasSuffix("." + h) }

    /// Recognises a streaming-service link. Returns nil for ordinary pages and direct media links.
    public static func recognize(_ raw: String) -> ServiceMatch? {
        guard let parts = split(raw) else { return nil }
        let host = parts.host.hasPrefix("www.") ? String(parts.host.dropFirst(4)) : parts.host
        let candidates = all
            .filter { s in s.hosts.contains { hostMatches(host, $0) } }
            .filter { s in s.pathContains.isEmpty || s.pathContains.contains { parts.path.range(of: $0, options: .caseInsensitive) != nil } }
        // most specific host wins (tv.youtube.com over youtube.com)
        func specificity(_ s: StreamingService) -> Int { s.hosts.filter { hostMatches(host, $0) }.map { $0.count }.max() ?? 0 }
        guard let service = candidates.max(by: { specificity($0) < specificity($1) }) else { return nil }
        return ServiceMatch(service: service, contentId: contentId(service, host, parts.path, parts.query))
    }

    private static func contentId(_ s: StreamingService, _ host: String, _ path: String, _ query: String) -> String? {
        switch s.deepLink {
        case .youtubeVideo:
            let fromQuery = query.split(separator: "&").map(String.init).first { $0.hasPrefix("v=") }.map { String($0.dropFirst(2)) }
            let fromPath: String? = host == "youtu.be"
                ? path.trimmingCharacters(in: CharacterSet(charactersIn: "/")).components(separatedBy: "/")[0]
                : Rx.first("^/(?:shorts|embed|live|v)/([^/?#]+)", in: path)
            return [fromQuery, fromPath].compactMap { $0 }.first { Rx.first("^([A-Za-z0-9_-]{11})$", in: $0) != nil }
        case .netflixTitle: return Rx.first("/(?:title|watch)/(\\d+)", in: path)
        case .none: return nil
        }
    }
}

/// Finds a service's official channel in the Roku's own installed-app list. Identifiers always come from the device.
public enum ChannelMatcher {
    public static func normalize(_ name: String) -> String { String(name.lowercased().filter { $0.isLetter || $0.isNumber || $0 == "+" }) }

    public static func find(_ service: StreamingService, _ apps: [RokuApp]) -> RokuApp? {
        let wanted = Set(service.channelNames.map(normalize))
        return apps.filter { $0.id != "dev" }.first { wanted.contains(normalize($0.name)) }
    }

    /// Every installed channel that belongs to a known service (for the manual "Open a streaming channel" list).
    public static func installedServices(_ apps: [RokuApp]) -> [(StreamingService, RokuApp)] {
        var seen = Set<String>()
        return StreamingServices.all.filter { seen.insert($0.displayName).inserted }.compactMap { s in find(s, apps).map { (s, $0) } }
    }
}

public enum ChannelLaunch {
    /// The Roku reports the channel is the active app. `titleLinkSent` only says a title link was sent, not that it was honoured.
    case opened(RokuApp, titleLinkSent: Bool)
    /// The command was accepted but the Roku has not reported the channel opening.
    case notConfirmed(RokuApp)
    case notInstalled(StreamingService)
    case failed(String)
}

/// Opens an official Roku channel through ECP using the channel id the Roku itself reports.
public struct RokuChannelLauncher {
    private let ecp: ECPClient
    public init(ecp: ECPClient = ECPClient()) { self.ecp = ecp }

    public func open(ip: String, match: ServiceMatch, apps: [RokuApp]? = nil, confirmSeconds: Double = 8) async -> ChannelLaunch {
        let list: [RokuApp]
        if let a = apps { list = a } else if let a = await ecp.apps(ip) { list = a } else {
            return .failed("Couldn't reach the Roku. Check that the TV is on and on the same Wi-Fi.")
        }
        if list.isEmpty { return .failed("The Roku didn't return its channel list. Check network control is allowed on the TV.") }
        guard let app = ChannelMatcher.find(match.service, list) else { return .notInstalled(match.service) }

        let deep = match.contentId != nil && match.service.deepLink != .none
        let path = deep ? Self.deepLinkPath(channelId: app.id, match: match) : "/launch/\(app.id)"
        if await !ecp.post(ip, path) { return .failed("The Roku didn't accept the command to open \(match.service.displayName).") }
        if await confirm(ip, app.id, confirmSeconds) { return .opened(app, titleLinkSent: deep) }
        // A rejected deep link can leave the channel closed; fall back to a plain launch once.
        if deep, await ecp.post(ip, "/launch/\(app.id)"), await confirm(ip, app.id, confirmSeconds) { return .opened(app, titleLinkSent: false) }
        return .notConfirmed(app)
    }

    private func confirm(_ ip: String, _ id: String, _ seconds: Double) async -> Bool {
        let end = Date().addingTimeInterval(seconds)
        while Date() < end {
            if await ecp.activeAppId(ip) == id { return true }
            try? await Task.sleep(nanoseconds: 700_000_000)
        }
        return false
    }

    /// Roku deep-linking takes contentId + mediaType. Whether a channel honours them is up to the channel (best effort).
    public static func deepLinkPath(channelId: String, match: ServiceMatch) -> String {
        let mediaType = match.service.deepLink == .youtubeVideo ? "shortFormVideo" : "movie"
        return "/launch/\(channelId)?contentId=\(ReceiverPlayer.enc(match.contentId ?? ""))&mediaType=\(mediaType)"
    }
}

import Foundation

public enum FailureKind: Equatable {
    case drmProtected, needsRequestContext, needsSession, signInRequired, accessDenied
    case notFound, network, dashOnly, rokuCouldNotPlay, unknown
}

/// The fallbacks Couchcast may offer. Direct playback is never in this list: it is always tried first.
public enum Offer: Equatable { case openChannel, mirror, relay, signIn }

public struct Diagnosis: Equatable {
    public let kind: FailureKind
    public let headline: String
    public let detail: String
    public let offers: [Offer]
}

/// One request's outcome. `status` is nil when the network failed; `hops` counts redirects followed.
public struct Attempt: Equatable {
    public let status: Int?
    public let error: String?
    public let hops: Int
    public init(status: Int?, error: String? = nil, hops: Int = 0) { self.status = status; self.error = error; self.hops = hops }
    public var ok: Bool { status.map { (200..<300).contains($0) } ?? false }
}

/// The same resource requested three ways: as a bare client, as a browser from the page, and with the signed-in session.
public struct Ladder: Equatable {
    public let bare: Attempt, context: Attempt, session: Attempt?
    public init(bare: Attempt, context: Attempt, session: Attempt?) { self.bare = bare; self.context = context; self.session = session }
}

public struct ProbeResult: Equatable {
    public let isHls: Bool
    public let drm: String?
    public let codecs: [String]
    public let manifest: Ladder
    public let segment: Ladder?
    public let hadSessionCookies: Bool
    public let isDash: Bool
    public init(isHls: Bool, drm: String?, codecs: [String], manifest: Ladder, segment: Ladder?, hadSessionCookies: Bool, isDash: Bool = false) {
        self.isHls = isHls; self.drm = drm; self.codecs = codecs; self.manifest = manifest; self.segment = segment
        self.hadSessionCookies = hadSessionCookies; self.isDash = isDash
    }
}

public struct DiagnoseInput {
    public var probe: ProbeResult?
    /// What the Roku itself reported when it stopped, if anything.
    public var rokuDrm: String?
    public var service: ServiceMatch?
    /// True when the phone's browser store already has cookies for the source's site.
    public var hasSiteSession: Bool
    public init(probe: ProbeResult?, rokuDrm: String? = nil, service: ServiceMatch? = nil, hasSiteSession: Bool = false) {
        self.probe = probe; self.rokuDrm = rokuDrm; self.service = service; self.hasSiteSession = hasSiteSession
    }
}

public enum FailureClassifier {
    private enum V { case open, needsContext, needsSession, unauthorized, forbidden, notFound, network, other }

    private static func verdict(_ l: Ladder) -> V {
        let all = [l.bare, l.context] + (l.session.map { [$0] } ?? [])
        if l.bare.ok { return .open }
        if l.context.ok { return .needsContext }
        if l.session?.ok == true { return .needsSession }
        if all.contains(where: { $0.status == 401 }) { return .unauthorized }
        if all.contains(where: { $0.status == 404 || $0.status == 410 }) { return .notFound }
        if all.contains(where: { $0.status == 403 || $0.status == 451 }) { return .forbidden }
        if all.allSatisfy({ $0.status == nil }) { return .network }
        return .other
    }

    private static let riskyCodecs = ["av01", "vp09", "vp9", "hvc1", "hev1", "dvh1", "dvhe"]

    public static func diagnose(_ i: DiagnoseInput) -> Diagnosis {
        let chan: [Offer] = i.service != nil ? [.openChannel] : []
        var drm: String? = nil
        if let r = i.rokuDrm, !r.trimmingCharacters(in: .whitespaces).isEmpty, r.lowercased() != "none" { drm = r } else { drm = i.probe?.drm }
        if let d = drm {
            return Diagnosis(kind: .drmProtected, headline: "Unsupported protected stream",
                detail: "This video is copy-protected (\(d)). Couchcast won't try to get around that." +
                    (i.service.map { " \($0.service.displayName)'s own Roku channel can play it." } ?? ""),
                offers: chan + [.mirror])
        }
        guard let p = i.probe else {
            return Diagnosis(kind: .unknown, headline: "The Roku couldn't play this",
                detail: "Couchcast couldn't check the stream from the phone either, so the reason isn't known.", offers: chan + [.relay, .mirror])
        }
        if p.isDash && !p.manifest.bare.ok && !p.manifest.context.ok {
            return Diagnosis(kind: .unknown, headline: "Couldn't open the stream", detail: "The server didn't answer the phone either.", offers: chan)
        }
        if p.isDash {
            return Diagnosis(kind: .dashOnly, headline: "This is a DASH (.mpd) stream",
                detail: "The phone relay can't carry DASH streams, and the Roku couldn't play this one directly.", offers: chan + [.mirror])
        }
        let m = verdict(p.manifest)
        let s = p.segment.map(verdict) ?? .open
        let v: V = m != .open ? m : (s != .open ? s : .open)
        switch v {
        case .needsContext:
            return Diagnosis(kind: .needsRequestContext, headline: "The site only serves this to a browser",
                detail: "The server refuses a bare request but accepts one that looks like it came from the page. The phone relay can make the request for the Roku.",
                offers: [.relay] + chan + [.mirror])
        case .needsSession:
            return Diagnosis(kind: .needsSession, headline: "The site needs your session",
                detail: i.hasSiteSession ? "The server only answers when your signed-in session is included. The phone relay can use it for this one playback."
                    : "The server only answers with a session cookie from the site. Sign in or visit the site in Couchcast first, then try the phone relay.",
                offers: i.hasSiteSession ? [.relay, .mirror] : [.signIn, .relay, .mirror])
        case .unauthorized:
            return Diagnosis(kind: .signInRequired, headline: "Sign-in required",
                detail: "The site says you must be signed in to watch this.", offers: [.signIn] + chan + [.mirror])
        case .forbidden:
            return Diagnosis(kind: .accessDenied,
                headline: i.hasSiteSession ? "The site refused access" : "The site refused access (a sign-in may be needed)",
                detail: i.hasSiteSession ? "Even with your session the server refuses this stream. It may be region-locked, paid, or protected."
                    : "The server refuses every kind of request from here. Signing in may help if the site needs an account.",
                offers: (i.hasSiteSession ? [] : [.signIn]) + chan + [.mirror])
        case .notFound:
            return Diagnosis(kind: .notFound, headline: "This stream link no longer works",
                detail: "The site says it can't find the video. The link may have expired. Reload the page to find a fresh one.", offers: chan)
        case .network:
            return Diagnosis(kind: .network, headline: "Couldn't reach the stream",
                detail: "The phone couldn't reach the video server either. Check your internet connection.", offers: chan)
        case .open, .other:
            let hop = p.manifest.bare.hops > 0 ? " The link also redirects through other servers, which some players handle badly; the relay follows redirects for the Roku." : ""
            let risky = p.codecs.filter { c in riskyCodecs.contains { c.lowercased().hasPrefix($0) } }
            let codec = risky.isEmpty ? "" : " It uses \(risky.joined(separator: ", ")), which some Rokus can't decode."
            return Diagnosis(kind: .rokuCouldNotPlay, headline: "The stream is reachable, but the Roku couldn't play it",
                detail: "The server answers normally, so the problem is on the Roku's side (format or network handling).\(codec)\(hop) The phone relay may help; if not, mirroring will.",
                offers: [.relay] + chan + [.mirror])
        }
    }
}

/// The plain-English state shown to the user.
public enum PlaybackStatus: Equatable {
    case idle, checking, playingDirect, openingChannel, channelOpen, channelNotConfirmed, mirroring, mirrorNotConfirmed
    case showingPhoto, startingRelay, playingRelay, signInRequired, unsupportedProtected, failed

    public var label: String {
        switch self {
        case .idle: return ""
        case .checking: return "Checking…"
        case .playingDirect: return "Playing directly on TV"
        case .openingChannel: return "Opening Roku channel"
        case .channelOpen: return "Roku channel is open"
        case .channelNotConfirmed: return "Sent to the Roku channel (not confirmed)"
        case .mirroring: return "Screen mirroring"
        case .mirrorNotConfirmed: return "Mirroring not confirmed"
        case .showingPhoto: return "Showing photo on TV"
        case .startingRelay: return "Starting phone relay…"
        case .playingRelay: return "Playing through phone relay"
        case .signInRequired: return "Sign-in required"
        case .unsupportedProtected: return "Unsupported protected stream"
        case .failed: return "Couldn't play"
        }
    }
}

public enum Support: Equatable { case yes, likely, unknown, no }

public struct ReceiverCapabilities: Equatable {
    public let directPlay: Support, channelLinks: Support, mirroring: Support, relay: Support
    public let notes: [String]

    /// `receiverInstalled`: true/false once the Roku's app list was read, nil if unknown.
    public static func roku(receiverInstalled: Bool?) -> ReceiverCapabilities {
        let s: Support = receiverInstalled == true ? .yes : (receiverInstalled == false ? .no : .unknown)
        // ECP does not report mirroring support, so this can't be verified from the phone.
        return ReceiverCapabilities(directPlay: s, channelLinks: .yes, mirroring: .likely, relay: s,
            notes: ["Screen mirroring support depends on the Roku model; check Settings > System > Screen mirroring on the TV."])
    }
}

public enum PlaybackPlanner {
    public static func status(for d: Diagnosis) -> PlaybackStatus {
        switch d.kind {
        case .drmProtected: return .unsupportedProtected
        case .signInRequired: return .signInRequired
        default: return .failed
        }
    }

    /// Offers the platform can actually carry out.
    public static func usable(_ offers: [Offer], _ caps: ReceiverCapabilities) -> [Offer] {
        var seen: [Offer] = []
        for o in offers {
            let ok: Bool
            switch o {
            case .openChannel: ok = caps.channelLinks != .no
            case .mirror: ok = caps.mirroring != .no
            case .relay: ok = caps.relay != .no
            case .signIn: ok = true
            }
            if ok && !seen.contains(o) { seen.append(o) }
        }
        return seen
    }
}

/// Plain-English reason a relayed playback failed, from the relay's own counters (no URLs, headers or cookies).
public enum RelayFailure {
    public static func explain(requests: Int, upstream: [Int: Int], drm: String?, playing: Bool, lastError: String?) -> String {
        if let d = drm { return "The stream turned out to be copy-protected (\(d)). Couchcast won't relay protected video." }
        if requests == 0 {
            return "The Roku never contacted your phone, so the relay itself wasn't reached. The phone and the Roku must be on the same Wi-Fi network (not a guest network or one with device isolation), and Couchcast must be allowed under Settings → Privacy & Security → Local Network. Direct playback is unaffected."
        }
        if lastError?.hasPrefix("blocked") == true { return "The relay refused an address outside the public internet, as designed." }
        if upstream.keys.contains(where: { $0 == 401 || $0 == 403 }) {
            return "The site refused the phone's request too, even with ordinary browser headers. It may need a sign-in, or it restricts who can watch."
        }
        if upstream.keys.contains(where: { $0 == 404 || $0 == 410 }) {
            return "The site says the video no longer exists. The link may have expired; reload the page to find a fresh one."
        }
        if upstream.keys.contains(where: { (200..<300).contains($0) }) && !playing {
            return "The relay delivered the video to the Roku, but the Roku couldn't play it. That usually means a format this Roku can't decode. Try mirroring your iPhone screen instead."
        }
        return "The relay couldn't start this stream. The Roku didn't report playing."
    }
}

/// Decides when Roku playback through the relay is over, from the Roku's own media-player reports.
/// Nothing is "over" before playback has been seen, except after a start grace period.
public final class PlaybackEndDetector {
    private let maxMisses: Int, graceSeconds: TimeInterval
    private let now: () -> Date
    private let startedAt: Date
    private var seenActive = false
    private var misses = 0
    public init(maxMisses: Int = 3, graceSeconds: TimeInterval = 60, now: @escaping () -> Date = Date.init) {
        self.maxMisses = maxMisses; self.graceSeconds = graceSeconds; self.now = now; self.startedAt = now()
    }
    /// `state` is the Roku's player state ("play", "buffer", "pause", "close", "none"...) or nil if it couldn't be read.
    public func onReport(_ state: String?) -> Bool {
        if let s = state, ["play", "buffer", "pause"].contains(s) { seenActive = true; misses = 0; return false }
        if !seenActive { return now().timeIntervalSince(startedAt) > graceSeconds }
        misses += 1
        return misses >= maxMisses
    }
}

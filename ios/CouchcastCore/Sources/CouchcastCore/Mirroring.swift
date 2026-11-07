import Foundation

public struct GuideStep: Equatable { public let `where`: String, text: String }

public struct MirrorGuide: Equatable {
    public let support: Support
    public let headline: String
    public let steps: [GuideStep]
    public let caveat: String
}

/// What the user must do to mirror to a Roku. iOS has no API for an app to start screen mirroring (only Control Center can), so this guides.
public enum MirroringGuide {
    public static let protectedCaveat = "Protected apps (Netflix, Disney+, Hulu and similar) usually show a black picture when mirrored. That is the app's copy protection, not a Couchcast fault."

    public static func forRoku(support: Support = .likely) -> MirrorGuide {
        if support == .no { return unsupported() }
        return MirrorGuide(support: support, headline: "Mirror your iPhone screen to the Roku", steps: [
            GuideStep(where: "On the TV", text: "Press Home, then open Settings > Apple AirPlay and HomeKit (on some models: System > Screen mirroring). Turn AirPlay on."),
            GuideStep(where: "On the TV", text: "If you don't see an AirPlay or Screen mirroring item, this Roku doesn't support mirroring from an iPhone."),
            GuideStep(where: "On your iPhone", text: "Open Control Center (swipe down from the top-right corner), tap Screen Mirroring, then choose your Roku."),
            GuideStep(where: "On the TV", text: "If the Roku shows a code, type it on the iPhone. If it asks to allow the connection, choose Accept."),
        ], caveat: protectedCaveat)
    }

    private static func unsupported() -> MirrorGuide {
        MirrorGuide(support: .no, headline: "This TV doesn't support screen mirroring",
            steps: [GuideStep(where: "Options", text: "Use a direct video link, open the streaming service's own Roku channel, or try the phone relay for ordinary video streams.")], caveat: "")
    }
}

/// What iOS itself reports. Mirroring is only called connected when the screen is being captured or mirrored.
public enum MirrorState: Equatable { case notDetected, connected }

public enum MirrorStatus {
    public static func fromCaptured(_ isCaptured: Bool) -> MirrorState { isCaptured ? .connected : .notDetected }

    public static func message(_ state: MirrorState) -> String {
        switch state {
        case .connected: return "iOS reports this screen is being mirrored or recorded. Check the TV to confirm your screen is showing."
        case .notDetected: return "Mirroring not confirmed yet. Check the TV."
        }
    }
}

/// Pure rules for website sign-in sessions. The actual cookie store is the WKWebView's.
public enum SiteSession {
    /// Cookie domains that may hold this site's session, e.g. video.example.com -> [video.example.com, .video.example.com, example.com, .example.com].
    public static func cookieDomains(_ host: String) -> [String] {
        let labels = host.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: ".")).split(separator: ".").map(String.init)
        if labels.count < 2 { return [host] }
        var out: [String] = []
        for i in 0...(labels.count - 2) {
            let d = labels[i...].joined(separator: ".")
            out.append(d); out.append("." + d)
        }
        return out
    }

    /// Sign-in must start on an https page on a public host. Returns a reason when it can't.
    public static func startProblem(_ url: String) -> String? {
        let t = url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let m = Rx.all("^(\\w+)://(?:[^/@]*@)?([^/:?#]+)", in: t).first, m.count >= 2 else { return "Enter a full web address." }
        if m[0].lowercased() != "https" { return "Sign-in pages must use https:// so your password is protected." }
        return UpstreamPolicy.checkUrl(t, privateOk: []) != nil ? "That address isn't a public website." : nil
    }

    /// The sign-in browser only follows web pages; scripts, files and app links are refused.
    public static func navigationAllowed(_ url: String) -> Bool {
        let scheme = url.components(separatedBy: ":")[0].lowercased()
        return (url.contains(":") && (scheme == "http" || scheme == "https")) || url == "about:blank"
    }

    public static func hostOf(_ url: String) -> String? { UpstreamPolicy.hostOf(url) }
}

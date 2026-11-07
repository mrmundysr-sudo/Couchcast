import Foundation

enum Rx {
    static func all(_ pattern: String, in s: String) -> [[String]] {
        guard let re = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive, .dotMatchesLineSeparators]) else { return [] }
        let ns = s as NSString
        return re.matches(in: s, range: NSRange(location: 0, length: ns.length)).map { m in
            (1..<max(m.numberOfRanges, 1)).map { i in
                let r = m.range(at: i)
                return r.location == NSNotFound ? "" : ns.substring(with: r)
            }
        }
    }

    static func first(_ pattern: String, in s: String) -> String? {
        all(pattern, in: s).first?.first
    }
}

public enum RokuParse {
    static func tag(_ xml: String, _ name: String) -> String {
        (Rx.first("<\(name)>([^<]*)</\(name)>", in: xml) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func attr(_ tag: String, _ name: String) -> String? {
        Rx.first("\\b\(name)=\"([^\"]*)\"", in: tag)
    }

    public static func unescape(_ s: String) -> String {
        s.replacingOccurrences(of: "&lt;", with: "<").replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"").replacingOccurrences(of: "&apos;", with: "'")
            .replacingOccurrences(of: "&amp;", with: "&")
    }

    public static func deviceInfo(_ xml: String) -> DeviceInfo {
        let user = unescape(tag(xml, "user-device-name"))
        let friendly = unescape(tag(xml, "friendly-device-name"))
        let name = !user.isEmpty ? user : (!friendly.isEmpty ? friendly : "Roku")
        return DeviceInfo(name: name, model: unescape(tag(xml, "model-name")),
                          isTv: tag(xml, "is-tv").lowercased() == "true", powerMode: tag(xml, "power-mode"))
    }

    public static func apps(_ xml: String) -> [RokuApp] {
        Rx.all("<app\\s+([^>]*)>([^<]*)</app>", in: xml).compactMap { g in
            guard g.count == 2, let id = attr(g[0], "id") else { return nil }
            return RokuApp(id: id, name: unescape(g[1].trimmingCharacters(in: .whitespacesAndNewlines)),
                           version: attr(g[0], "version") ?? "", type: attr(g[0], "type") ?? "appl")
        }
    }

    public static func activeAppId(_ xml: String) -> String? {
        Rx.first("<app\\s[^>]*\\bid=\"([^\"]+)\"", in: xml)
    }

    public static func player(_ xml: String) -> PlayerInfo? {
        guard let tag = Rx.first("(<player\\b[^>]*>)", in: xml), let state = attr(tag, "state") else { return nil }
        let fmt = Rx.first("(<format\\b[^>]*>)", in: xml)
        return PlayerInfo(state: state, error: tag.contains("error=\"true\""),
                          video: fmt.flatMap { attr($0, "video") }, audio: fmt.flatMap { attr($0, "audio") },
                          drm: fmt.flatMap { attr($0, "drm") }, resolution: fmt.flatMap { attr($0, "video_res") })
    }
}

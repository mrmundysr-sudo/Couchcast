import Foundation

public struct Room: Codable, Identifiable, Equatable, Hashable {
    public var id: String { ip }
    public var ip: String
    public var name: String
    public var model: String
    public var isTv: Bool

    public init(ip: String, name: String, model: String = "", isTv: Bool = false) {
        self.ip = ip; self.name = name; self.model = model; self.isTv = isTv
    }
}

public struct DeviceInfo: Equatable {
    public let name: String
    public let model: String
    public let isTv: Bool
    public let powerMode: String
}

public struct RokuApp: Equatable, Identifiable {
    public let id: String
    public let name: String
    public let version: String
    public let type: String

    public init(id: String, name: String, version: String = "", type: String = "appl") {
        self.id = id; self.name = name; self.version = version; self.type = type
    }
}

/// What the Roku's own media player reports (GET /query/media-player).
public struct PlayerInfo: Equatable {
    public let state: String
    public let error: Bool
    public let video: String?
    public let audio: String?
    public let drm: String?
    public let resolution: String?

    public init(state: String, error: Bool, video: String? = nil, audio: String? = nil, drm: String? = nil, resolution: String? = nil) {
        self.state = state; self.error = error; self.video = video; self.audio = audio; self.drm = drm; self.resolution = resolution
    }
}

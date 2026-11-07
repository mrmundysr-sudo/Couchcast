import Foundation
#if canImport(Darwin)
import Darwin
#endif

public enum LocalNetwork {
    public static func isPrivate(_ ip: String) -> Bool {
        let p = ip.split(separator: ".").compactMap { Int($0) }
        guard p.count == 4 else { return false }
        return p[0] == 10 || (p[0] == 172 && (16...31).contains(p[1])) || (p[0] == 192 && p[1] == 168)
    }

    /// This device's Wi-Fi IPv4 address (the one a Roku on the same network can reach), or nil when not on a private network.
    public static func wifiIPv4() -> String? {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return nil }
        defer { freeifaddrs(head) }
        var fallback: String?
        for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let ifa = ptr.pointee
            guard let addr = ifa.ifa_addr, addr.pointee.sa_family == sa_family_t(AF_INET) else { continue }
            let name = String(cString: ifa.ifa_name)
            guard name.hasPrefix("en") else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
            let ip = String(cString: host)
            guard isPrivate(ip) else { continue }
            if name == "en0" { return ip }
            fallback = fallback ?? ip
        }
        return fallback
    }

    /// The other 253 addresses on the same /24.
    public static func neighbors(of ip: String) -> [String] {
        let p = ip.split(separator: ".")
        guard p.count == 4, let own = Int(p[3]) else { return [] }
        let prefix = p[0...2].joined(separator: ".")
        return (1...254).filter { $0 != own }.map { "\(prefix).\($0)" }
    }
}

/// iOS apps can't use SSDP multicast without a special Apple entitlement, so Rokus are found by asking each address
/// on the local /24 whether it answers Roku's control port.
public struct RokuScanner {
    public init() {}

    public func scan(localIP: String) async -> [Room] {
        let ecp = ECPClient(timeout: 1.0, attempts: 1)
        var rooms: [Room] = []
        let candidates = LocalNetwork.neighbors(of: localIP)
        var index = 0
        while index < candidates.count {
            let batch = Array(candidates[index..<min(index + 42, candidates.count)])
            index += 42
            await withTaskGroup(of: Room?.self) { group in
                for ip in batch {
                    group.addTask {
                        guard let info = await ecp.deviceInfo(ip) else { return nil }
                        return Room(ip: ip, name: info.name, model: info.model, isTv: info.isTv)
                    }
                }
                for await room in group { if let room = room { rooms.append(room) } }
            }
        }
        return rooms.sorted { $0.ip.localizedStandardCompare($1.ip) == .orderedAscending }
    }
}

import SwiftUI
import CouchcastCore

@main
struct CouchcastApp: App {
    @StateObject private var state = AppState()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(state)
                .preferredColorScheme(.dark)
        }
    }
}

/// The Rokus the user has added, and which one is selected. Kept on the device only.
@MainActor
final class AppState: ObservableObject {
    @Published var rooms: [Room] = [] { didSet { save() } }
    @Published var selectedIP: String? { didSet { UserDefaults.standard.set(selectedIP, forKey: "selectedIP") } }
    let ecp = ECPClient()

    var selected: Room? { rooms.first(where: { $0.ip == selectedIP }) ?? rooms.first }

    init() {
        if let data = UserDefaults.standard.data(forKey: "rooms"), let saved = try? JSONDecoder().decode([Room].self, from: data) { rooms = saved }
        selectedIP = UserDefaults.standard.string(forKey: "selectedIP")
    }

    func add(_ room: Room) {
        if let i = rooms.firstIndex(where: { $0.ip == room.ip }) { rooms[i] = room } else { rooms.append(room) }
        selectedIP = room.ip
    }

    func remove(_ room: Room) {
        rooms.removeAll { $0.ip == room.ip }
        if selectedIP == room.ip { selectedIP = rooms.first?.ip }
    }

    private func save() {
        if let data = try? JSONEncoder().encode(rooms) { UserDefaults.standard.set(data, forKey: "rooms") }
    }
}

struct RootView: View {
    @EnvironmentObject var state: AppState

    var body: some View {
        TabView {
            RemoteView().tabItem { Label("Remote", systemImage: "gamecontroller") }
            ChannelsView().tabItem { Label("Channels", systemImage: "tv") }
            PlayView().tabItem { Label("Play", systemImage: "play.rectangle") }
            CastView().tabItem { Label("Cast", systemImage: "tv.and.mediabox") }
            RoomsView().tabItem { Label("Rooms", systemImage: "house") }
        }
    }
}

/// Shown on a tab that needs a Roku before it can do anything.
struct NoRoomView: View {
    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: "tv.slash").font(.largeTitle)
            Text("Add a Roku in the Rooms tab to start.").foregroundColor(.secondary).multilineTextAlignment(.center)
        }.padding()
    }
}

struct RoomHeader: View {
    let room: Room
    var body: some View {
        HStack { Circle().fill(Color.green).frame(width: 10, height: 10); Text(room.name).font(.title3) }
    }
}

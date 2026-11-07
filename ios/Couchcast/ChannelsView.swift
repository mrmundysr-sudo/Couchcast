import SwiftUI
import CouchcastCore

struct ChannelsView: View {
    @EnvironmentObject var state: AppState
    @State private var apps: [RokuApp]?
    @State private var failed = false

    var body: some View {
        if let room = state.selected {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    RoomHeader(room: room)
                    if let apps = apps {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 100), spacing: 12)], spacing: 14) {
                            ForEach(apps) { app in
                                Button { Task { _ = await state.ecp.launch(room.ip, app.id) } } label: {
                                    VStack {
                                        AsyncImage(url: ECPClient.iconURL(ip: room.ip, appId: app.id)) { img in img.resizable().scaledToFit() } placeholder: { Color(white: 0.16) }
                                            .frame(width: 96, height: 72).clipShape(RoundedRectangle(cornerRadius: 8))
                                        Text(app.name).font(.caption).lineLimit(1).foregroundColor(.primary)
                                    }
                                }.buttonStyle(.plain)
                            }
                        }
                    } else if failed {
                        Text("Couldn't read the channel list. Check that the TV is on and on this Wi-Fi.").foregroundColor(.secondary)
                    } else { ProgressView() }
                }.padding()
            }
            .task(id: room.ip) { await load(room) }
            .refreshable { await load(room) }
        } else { NoRoomView() }
    }

    private func load(_ room: Room) async {
        failed = false
        let list = await state.ecp.apps(room.ip)
        apps = list?.filter { $0.type != "menu" }
        failed = list == nil
    }
}

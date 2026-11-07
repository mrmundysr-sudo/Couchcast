import SwiftUI
import CouchcastCore

struct RemoteView: View {
    @EnvironmentObject var state: AppState
    @State private var failed = false

    var body: some View {
        if let room = state.selected {
            ScrollView {
                VStack(spacing: 18) {
                    RoomHeader(room: room)
                    HStack(spacing: 14) { key("chevron.backward", "Back", room); key("house", "Home", room); key("power", "PowerOff", room) }
                    VStack(spacing: 8) {
                        key("chevron.up", "Up", room)
                        HStack(spacing: 8) { key("chevron.left", "Left", room); okKey(room); key("chevron.right", "Right", room) }
                        key("chevron.down", "Down", room)
                    }
                    HStack(spacing: 14) { key("arrow.counterclockwise", "InstantReplay", room); key("asterisk", "Info", room) }
                    HStack(spacing: 14) { key("backward.fill", "Rev", room); key("playpause.fill", "Play", room); key("forward.fill", "Fwd", room) }
                    if room.isTv {
                        HStack(spacing: 14) { key("speaker.minus", "VolumeDown", room); key("speaker.slash", "VolumeMute", room); key("speaker.plus", "VolumeUp", room) }
                    }
                    if failed { Text("The Roku didn't answer. Check that the TV is on and on this Wi-Fi.").font(.footnote).foregroundColor(.secondary) }
                }.padding()
            }
        } else { NoRoomView() }
    }

    private func send(_ key: String, _ room: Room) {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        Task { failed = !(await state.ecp.key(room.ip, key)) }
    }

    private func key(_ symbol: String, _ name: String, _ room: Room) -> some View {
        Button { send(name, room) } label: {
            Image(systemName: symbol).font(.title2).frame(width: 76, height: 56)
                .background(Color(white: 0.16)).clipShape(RoundedRectangle(cornerRadius: 14))
        }.buttonStyle(.plain).accessibilityLabel(name)
    }

    private func okKey(_ room: Room) -> some View {
        Button { send("Select", room) } label: {
            Text("OK").font(.title3.bold()).frame(width: 76, height: 56)
                .background(Color.accentColor).clipShape(RoundedRectangle(cornerRadius: 14))
        }.buttonStyle(.plain)
    }
}

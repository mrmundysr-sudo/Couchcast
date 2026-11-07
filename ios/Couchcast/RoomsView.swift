import SwiftUI
import CouchcastCore

struct RoomsView: View {
    @EnvironmentObject var state: AppState
    @State private var scanning = false
    @State private var message: String?
    @State private var ip = ""

    var body: some View {
        NavigationView {
            List {
                Section("Your Rokus") {
                    if state.rooms.isEmpty { Text("None yet. Find them on your Wi-Fi, or add one by its address.").foregroundColor(.secondary) }
                    ForEach(state.rooms) { room in
                        Button { state.selectedIP = room.ip } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(room.name).foregroundColor(.primary)
                                    Text("\(room.model.isEmpty ? "Roku" : room.model) · \(room.ip)").font(.caption).foregroundColor(.secondary)
                                }
                                Spacer()
                                if state.selected?.ip == room.ip { Image(systemName: "checkmark.circle.fill").foregroundColor(.accentColor) }
                            }
                        }
                    }.onDelete { offsets in offsets.map { state.rooms[$0] }.forEach { state.remove($0) } }
                }
                Section("Find a Roku") {
                    Button {
                        Task { await scan() }
                    } label: {
                        HStack { Text(scanning ? "Looking on your Wi-Fi…" : "Find Rokus on this Wi-Fi"); if scanning { Spacer(); ProgressView() } }
                    }.disabled(scanning)
                    HStack {
                        TextField("Roku address, e.g. 192.168.1.20", text: $ip)
                            .keyboardType(.numbersAndPunctuation).textInputAutocapitalization(.never).autocorrectionDisabled()
                        Button("Add") { Task { await addByIP() } }.disabled(ip.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                    if let m = message { Text(m).font(.footnote).foregroundColor(.secondary) }
                    Text("The first time, iPhone asks to let Couchcast find devices on your local network. Tap Allow. On the Roku, Settings → System → Advanced → Control by mobile apps must be on.")
                        .font(.footnote).foregroundColor(.secondary)
                }
            }
            .navigationTitle("Rooms")
        }.navigationViewStyle(.stack)
    }

    private func scan() async {
        scanning = true; message = nil
        defer { scanning = false }
        guard let mine = LocalNetwork.wifiIPv4() else { message = "Connect your iPhone to the same Wi-Fi as the Roku first."; return }
        let found = await RokuScanner().scan(localIP: mine)
        found.forEach { state.add($0) }
        message = found.isEmpty
            ? "No Roku answered. Check the TV is on and on this Wi-Fi, and that Couchcast is allowed under Settings → Privacy & Security → Local Network."
            : "Found \(found.count) Roku\(found.count == 1 ? "" : "s")."
    }

    private func addByIP() async {
        let address = ip.trimmingCharacters(in: .whitespaces)
        message = nil
        guard let info = await state.ecp.deviceInfo(address) else {
            message = "Couldn't reach a Roku at \(address). Check the address, that the TV is on, and Local Network access for Couchcast."
            return
        }
        state.add(Room(ip: address, name: info.name, model: info.model, isTv: info.isTv))
        ip = ""
        message = "Added \(info.name)."
    }
}

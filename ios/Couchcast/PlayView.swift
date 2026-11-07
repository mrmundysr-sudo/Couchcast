import SwiftUI
import PhotosUI
import CouchcastCore

private enum PlayScreen { case main, channels, mirror, sites, signIn }

struct PlayView: View {
    @EnvironmentObject var state: AppState
    @StateObject private var model = PlayModel()
    @State private var screen: PlayScreen = .main
    @State private var signInUrl = ""

    var body: some View {
        if let room = state.selected {
            Group {
                switch screen {
                case .main: PlayMain(model: model, room: room, go: { screen = $0 })
                case .channels: ChannelsPicker(model: model, room: room, back: { screen = .main })
                case .mirror: MirrorView(roomName: room.name, back: { screen = .main })
                case .sites:
                    SitesView(model: model, startUrl: model.signInStartUrl(),
                              signIn: { signInUrl = $0; screen = .signIn }, back: { screen = .main })
                case .signIn:
                    SignInScreen(startUrl: signInUrl, back: { screen = .sites }) { host in
                        if let h = host { model.signedIn(h) }
                        screen = .main
                        model.rescan(room: room, signedInHost: host, ecp: state.ecp)
                    }
                }
            }
        } else { NoRoomView() }
    }
}

private struct PlayMain: View {
    @EnvironmentObject var state: AppState
    @ObservedObject var model: PlayModel
    let room: Room
    let go: (PlayScreen) -> Void
    @State private var link = ""
    @State private var pick: PhotosPickerItem?
    @State private var more = false
    @State private var showRelayInfo = false
    @State private var showDetails = false
    @State private var showInstall = false
    @State private var canUseSession = false
    @State private var useSession = false
    @FocusState private var typing: Bool
    @Environment(\.scenePhase) private var scenePhase

    private var trimmedEmpty: Bool { link.trimmingCharacters(in: .whitespaces).isEmpty }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Play a video on \(room.name)").font(.title3)
                Text("Paste a video link or a web page that has a video on it, or cast a video or photo from this iPhone. The app finds the video and plays it through the Couchcast Receiver on your Roku. Sites that copy-protect their video (Netflix, Hulu, Disney+, YouTube and similar) can't be played this way, but the app can open their own Roku channel.")
                    .font(.footnote).foregroundColor(.secondary)
                TextField("Video link or web page", text: $link)
                    .textFieldStyle(.roundedBorder).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    .focused($typing).submitLabel(.go)
                    .onSubmit { typing = false; model.play(room: room, raw: link, ecp: state.ecp) }
                    .onChange(of: link) { v in model.onLinkChanged(v, room: room, ecp: state.ecp) }
                HStack {
                    Button("Paste") { typing = false; if let s = UIPasteboard.general.string { link = s.trimmingCharacters(in: .whitespacesAndNewlines) } }.buttonStyle(.bordered)
                    Button("Play on Roku") { typing = false; model.play(room: room, raw: link, ecp: state.ecp) }
                        .buttonStyle(.borderedProminent).frame(maxWidth: .infinity).disabled(model.busy || trimmedEmpty)
                }
                PhotosPicker(selection: $pick, matching: .any(of: [.images, .videos])) {
                    Text("Cast a video or photo from this iPhone").frame(maxWidth: .infinity)
                }.buttonStyle(.bordered).disabled(model.busy)

                if let m = model.link.service { serviceCard(m) }

                if model.status != .idle && !model.status.label.isEmpty { Text(model.status.label).font(.subheadline).foregroundColor(statusColor) }
                if model.busy {
                    HStack { ProgressView(); Text(model.message).font(.subheadline); Spacer(); Button("Cancel") { model.cancel() } }
                } else if !model.message.isEmpty {
                    Text(model.message).font(.subheadline).foregroundColor(messageColor)
                }
                if let c = model.chosen {
                    HStack {
                        Text("Video: " + MediaLinks.label(c)).font(.caption).foregroundColor(.secondary).lineLimit(1)
                        Spacer()
                        Button("Copy link") { UIPasteboard.general.string = c }.font(.caption)
                    }
                }
                phaseActions

                if model.diagnosing { HStack { ProgressView(); Text("Checking why it didn't play…").font(.footnote).foregroundColor(.secondary) } }
                if let d = model.diagnosis { diagnosisCard(d) }

                Button(more ? "Hide other ways to play" : "More ways to play") { more.toggle() }.font(.subheadline)
                if more { moreCard }

                if !model.details.isEmpty {
                    Button(showDetails ? "Hide details" : "Troubleshooting details") { showDetails.toggle() }.font(.subheadline)
                    if showDetails {
                        card {
                            ForEach(Array(model.details.enumerated()), id: \.offset) { _, line in Text(line).font(.caption).foregroundColor(.secondary) }
                            Text("Details never include cookies, passwords or full video addresses.").font(.caption2).foregroundColor(.secondary)
                        }
                    }
                }
                Text("Tip: use the Remote tab for pause, play and fast-forward. For a video from this iPhone or the phone relay, keep Couchcast open on screen until it ends.")
                    .font(.footnote).foregroundColor(.secondary)
            }.padding()
        }
        .scrollDismissesKeyboard(.interactively)
        .toolbar { ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("Done") { typing = false } } }
        .onChange(of: pick) { item in
            guard let item = item else { return }
            pick = nil
            model.cast(item: item, room: room, ecp: state.ecp)
        }
        .onChange(of: scenePhase) { phase in model.scenePhaseChanged(phase) }
        .sheet(isPresented: $showInstall) { NavigationView { InstallView(defaultRoom: room) }.environmentObject(state) }
        .alert("Play through your iPhone?", isPresented: $showRelayInfo) {
            Button("Start relay") { model.setUseSession(useSession); model.tryRelay(room: room, ecp: state.ecp) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("The iPhone fetches the video the way a browser would and hands it to the Roku. This helps when a site only answers browsers.\n\nWhile it plays, keep Couchcast open on screen, on the same Wi-Fi as the Roku. If the iPhone locks or leaves the Wi-Fi, the video stops.\n\nIt works with ordinary MP4 and HLS (.m3u8) video only. It won't touch copy-protected video, and it ends when the video ends or you tap Stop.")
        }
    }

    // MARK: pieces

    private func card<C: View>(@ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 8) { content() }
            .padding(14).frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 14).fill(Color(white: 0.12)))
    }

    private var statusColor: Color {
        switch model.status {
        case .playingDirect, .playingRelay, .showingPhoto, .channelOpen, .mirroring: return .green
        case .failed, .signInRequired, .unsupportedProtected: return .red
        default: return .secondary
        }
    }

    private var messageColor: Color {
        switch model.phase {
        case .playing: return .green
        case .idle, .notConfirmed, .choose: return .secondary
        default: return .red
        }
    }

    private func serviceCard(_ m: ServiceMatch) -> some View {
        let name = m.service.displayName
        return card {
            Text("\(name) link detected").font(.headline)
            Text("\(name) protects its video, so the app can't send it to the receiver. Its own Roku channel can play it.").font(.footnote).foregroundColor(.secondary)
            switch model.link.channel {
            case .installed: Text("✔ \(name) is installed on your Roku.").font(.footnote).foregroundColor(.green)
            case .missing: Text("\(name) isn't installed on your Roku. Add it from Streaming Channels on the Roku Home screen.").font(.footnote).foregroundColor(.secondary)
            case .unknown: Text("Checking your Roku…").font(.footnote).foregroundColor(.secondary)
            }
            Button { model.openChannel(room: room, match: m, ecp: state.ecp) } label: { Text("Open \(name) on Roku").frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent).disabled(model.busy || model.link.channel == .missing)
            if m.contentId != nil { Text("A title link will be sent, but whether \(name) jumps to that title is up to its channel.").font(.caption).foregroundColor(.secondary) }
            Text("Or tap Play on Roku above to look for an ordinary playable stream on the page.").font(.caption).foregroundColor(.secondary)
        }
    }

    @ViewBuilder private var phaseActions: some View {
        switch model.phase {
        case .needsReceiver:
            Button("Open the receiver installer") { showInstall = true }.buttonStyle(.borderedProminent)
        case .noVideo:
            VStack(alignment: .leading) {
                Button("Try the link itself as an MP4") { model.play(room: room, raw: link, forceMp4: true, ecp: state.ecp) }
                Button("Members-only page? Sign in to this site") { go(.sites) }
                Button("Mirror iPhone screen instead") { go(.mirror) }
            }.font(.subheadline)
        case .choose:
            ForEach(model.candidates, id: \.self) { c in
                Button { model.playCandidate(room: room, url: c, ecp: state.ecp) } label: { Text(MediaLinks.label(c)).lineLimit(1).frame(maxWidth: .infinity) }.buttonStyle(.bordered)
            }
        case .playing, .notConfirmed:
            Button(model.relayActive ? "Stop (ends the phone relay)" : "Stop") { model.stop(room: room, ecp: state.ecp) }.buttonStyle(.bordered)
        default: EmptyView()
        }
    }

    private func diagnosisCard(_ d: Diagnosis) -> some View {
        card {
            Text(d.headline).font(.headline).foregroundColor(.red)
            Text(d.detail).font(.footnote)
            ForEach(model.offers, id: \.self) { o in
                switch o {
                case .openChannel:
                    if let m = model.link.service {
                        Button { model.openChannel(room: room, match: m, ecp: state.ecp) } label: { Text("Open \(m.service.displayName) on Roku").frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent)
                    }
                case .mirror: Button { go(.mirror) } label: { Text("Mirror iPhone screen").frame(maxWidth: .infinity) }.buttonStyle(.bordered)
                case .relay: Button { askRelay() } label: { Text("Try phone relay").frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent)
                case .signIn: Button { go(.sites) } label: { Text("Sign in to this site").frame(maxWidth: .infinity) }.buttonStyle(.bordered)
                }
            }
        }
    }

    private var moreCard: some View {
        card {
            Text("Direct playback is tried first. These are the other options:").font(.footnote).foregroundColor(.secondary)
            Button { model.loadChannels(room: room, ecp: state.ecp); go(.channels) } label: { Text("Open a streaming channel on Roku").frame(maxWidth: .infinity) }.buttonStyle(.bordered)
            Button { go(.mirror) } label: { Text("Mirror iPhone screen").frame(maxWidth: .infinity) }.buttonStyle(.bordered)
            Button { askRelay() } label: { Text("Play the chosen video through the iPhone").frame(maxWidth: .infinity) }
                .buttonStyle(.bordered).disabled(model.chosen == nil || model.busy)
            if model.chosen == nil { Text("Find a video first, then the phone relay can carry it.").font(.caption).foregroundColor(.secondary) }
            Button { go(.sites) } label: { Text("Sign in to a site / manage sign-ins").frame(maxWidth: .infinity) }.buttonStyle(.bordered)
        }
    }

    private func askRelay() {
        Task { canUseSession = await model.hasSessionForChosen(); useSession = false; showRelayInfo = true }
    }
}

private struct ChannelsPicker: View {
    @EnvironmentObject var state: AppState
    @ObservedObject var model: PlayModel
    let room: Room
    let back: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text("Open a streaming channel").font(.title3)
                Text("Channels installed on \(room.name) that Couchcast recognises. It opens the channel; you pick what to watch.").font(.footnote).foregroundColor(.secondary)
                if let list = model.channels {
                    if list.isEmpty { Text("No supported streaming channels were found on this Roku, or it couldn't be reached.").foregroundColor(.secondary) }
                    ForEach(Array(list.enumerated()), id: \.offset) { _, pair in
                        Button {
                            model.openChannel(room: room, match: ServiceMatch(service: pair.0, contentId: nil), ecp: state.ecp); back()
                        } label: { Text("Open \(pair.0.displayName)").frame(maxWidth: .infinity) }.buttonStyle(.bordered)
                    }
                } else { HStack { ProgressView(); Text("Reading your Roku's channels…") } }
                Button("Back") { back() }.buttonStyle(.bordered)
            }.padding()
        }
    }
}

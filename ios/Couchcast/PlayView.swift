import SwiftUI
import PhotosUI
import CouchcastCore

struct PlayView: View {
    @EnvironmentObject var state: AppState
    @StateObject private var model = PlayModel()
    @State private var link = ""
    @State private var pick: PhotosPickerItem?
    @FocusState private var typing: Bool
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        if let room = state.selected {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Play a video on \(room.name)").font(.title3)
                    Text("Paste a video link or a web page that has a video on it, or cast a video or photo from this iPhone. Sites that copy-protect their video (Netflix, Hulu, Disney+, YouTube and similar) can't be played this way; open their channel from the Channels tab instead.")
                        .font(.footnote).foregroundColor(.secondary)
                    TextField("Video link or web page", text: $link)
                        .textFieldStyle(.roundedBorder).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                        .focused($typing).submitLabel(.go)
                        .onSubmit { typing = false; model.playLink(room: room, raw: link, ecp: state.ecp) }
                    HStack {
                        Button("Paste") { typing = false; if let s = UIPasteboard.general.string { link = s.trimmingCharacters(in: .whitespacesAndNewlines) } }.buttonStyle(.bordered)
                        Button("Play on Roku") { typing = false; model.playLink(room: room, raw: link, ecp: state.ecp) }
                            .buttonStyle(.borderedProminent).frame(maxWidth: .infinity)
                            .disabled(model.busy || link.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                    PhotosPicker(selection: $pick, matching: .any(of: [.images, .videos])) {
                        Text("Cast a video or photo from this iPhone").frame(maxWidth: .infinity)
                    }.buttonStyle(.bordered).disabled(model.busy)

                    if model.busy { HStack { ProgressView(); Text(model.message).font(.subheadline) } }
                    else if !model.message.isEmpty { Text(model.message).font(.subheadline).foregroundColor(model.color) }

                    if !model.candidates.isEmpty {
                        ForEach(model.candidates, id: \.self) { c in
                            Button(MediaLinks.label(c)) { model.playCandidate(room: room, url: c, ecp: state.ecp) }.buttonStyle(.bordered).frame(maxWidth: .infinity)
                        }
                    }
                    if model.active { Button("Stop") { model.stop(room: room, ecp: state.ecp) }.buttonStyle(.bordered) }
                    Text("Tip: use the Remote tab for pause, play and fast-forward. For a video from this iPhone, keep Couchcast open on screen until it ends.")
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
        } else { NoRoomView() }
    }
}

@MainActor
final class PlayModel: ObservableObject {
    @Published var message = ""
    @Published var color: Color = .secondary
    @Published var busy = false
    @Published var active = false
    @Published var candidates: [String] = []

    private let server = LocalServer()
    private var token: String?
    private var work: Task<Void, Never>?
    private var watcher: Task<Void, Never>?
    private var tempFiles: [URL] = []
    private var backgroundTask = UIBackgroundTaskIdentifier.invalid

    // MARK: links

    func playLink(room: Room, raw: String, ecp: ECPClient) {
        candidates = []
        guard let url = Self.normalize(raw) else { return fail("Enter a link that starts with http:// or https://.") }
        work?.cancel()
        work = Task {
            if let format = ReceiverPlayer.formatFor(url) { await send(room, url, format, ecp); return }
            busy = true; message = "Looking for video on that page… this can take a few seconds."; color = .secondary
            let found = await PageScanner().scan(URL(string: url)!)
            switch found.count {
            case 0: fail("No playable video found on that page. Sites like YouTube, Netflix, Hulu and Disney+ protect their video, so it can't be sent to a Roku this way. Try a page with an ordinary video, or a direct .mp4 / .m3u8 link.")
            case 1: await send(room, found[0], ReceiverPlayer.formatFor(found[0]) ?? "mp4", ecp)
            default: busy = false; message = "Found \(found.count) videos on that page. Pick one:"; candidates = found
            }
        }
    }

    func playCandidate(room: Room, url: String, ecp: ECPClient) {
        candidates = []
        work?.cancel()
        work = Task { await send(room, url, ReceiverPlayer.formatFor(url) ?? "mp4", ecp) }
    }

    private func send(_ room: Room, _ url: String, _ format: String, _ ecp: ECPClient) async {
        busy = true; color = .secondary
        guard await receiverReady(room, ecp) else { return }
        message = "Sending the video to your Roku…"
        let out = await ReceiverPlayer(ecp: ecp).play(ip: room.ip, url: url, format: format)
        busy = false
        switch out.result {
        case .playing: ok("Confirmed: the Roku reports it is playing.", active: true)
        case .stoppedEarly: fail("The video started, then the Roku stopped it after about \(out.playedSeconds) seconds. The link may have expired, need a sign-in, or use a format this Roku can't play.")
        case .receiverOpenNotConfirmed: warn("The receiver opened, but the Roku hasn't reported playing (it says: \(out.last?.state ?? "nothing")\(out.last?.error == true ? ", error" : "")). Look at the TV: it shows the reason if the video failed.\nLink sent: \(url)")
        case .notStarted: fail("The Roku didn't start the receiver. Make sure the TV is on and try again.")
        case .sendFailed: fail("The Roku didn't accept the command. Check Wi-Fi, and that Control by mobile apps is on in the Roku's settings.")
        }
    }

    private func receiverReady(_ room: Room, _ ecp: ECPClient) async -> Bool {
        message = "Checking the receiver on your Roku…"
        switch ReceiverCheck.classify(await ecp.apps(room.ip)) {
        case .current: return true
        case .unknown: fail("Couldn't reach the Roku. Check that the TV is on and on the same Wi-Fi, and that Couchcast is allowed under Settings → Privacy & Security → Local Network.")
        case .notInstalled: fail("The Couchcast Receiver isn't installed on this Roku yet. Install it from the Rooms tab → Install Couchcast Receiver.")
        case .outdated: fail("The receiver on this Roku is out of date (the app needs \(ReceiverCheck.requiredVersion.map(String.init).joined(separator: "."))). Update it from the Rooms tab → Install Couchcast Receiver.")
        case .otherDevApp: fail("A different sideloaded channel is on this Roku. Install the Couchcast Receiver from the Rooms tab (it replaces that channel).")
        }
        return false
    }

    // MARK: cast from this iPhone

    func cast(item: PhotosPickerItem, room: Room, ecp: ECPClient) {
        candidates = []
        work?.cancel()
        let isVideo = item.supportedContentTypes.contains { $0.conforms(to: .movie) }
        work = Task {
            await endSession(room: nil, ecp: ecp)
            busy = true; color = .secondary; message = "Checking the receiver on your Roku…"
            guard let phoneIP = LocalNetwork.wifiIPv4() else { return fail("Connect your iPhone to the same Wi-Fi as the Roku to cast from the phone.") }
            guard await receiverReady(room, ecp) else { return }
            do { try await server.start() } catch { return fail("Couldn't start the sharing service on this iPhone. Allow Couchcast under Settings → Privacy & Security → Local Network.") }
            if isVideo { await castVideo(item, room, phoneIP, ecp) } else { await castPhoto(item, room, phoneIP, ecp) }
        }
    }

    private func castVideo(_ item: PhotosPickerItem, _ room: Room, _ phoneIP: String, _ ecp: ECPClient) async {
        do {
            message = "Reading the video…"
            guard let movie = try await item.loadTransferable(type: PickedMovie.self) else { return fail("Couldn't read that video.") }
            tempFiles.append(movie.url)
            message = "Preparing the video for the Roku… long videos take a while."
            let mp4 = try await VideoPrep.playableMP4(from: movie.url)
            if mp4 != movie.url { tempFiles.append(mp4) }
            let src = try FileSource(url: mp4, mime: "video/mp4")
            let served = server.publish(file: src, clientIP: room.ip, host: phoneIP, ext: "mp4")
            token = served.token
            message = "Sending the video to your Roku…"
            setKeepAwake(true)
            let out = await ReceiverPlayer(ecp: ecp).play(ip: room.ip, url: served.url, format: "mp4")
            let requests = server.stats(token: served.token)?.requests ?? 0
            busy = false
            switch out.result {
            case .playing:
                ok("Playing from your iPhone. Keep Couchcast open on screen until the video ends. Remote pause and seek work as usual.", active: true)
                watch(room, ecp)
            case .stoppedEarly:
                await endSession(room: nil, ecp: ecp)
                fail("The video started, then the Roku stopped it after about \(out.playedSeconds) seconds. Some recordings aren't supported by every Roku.")
            case .sendFailed:
                await endSession(room: nil, ecp: ecp); fail("The Roku didn't accept the command. Check Wi-Fi, and that Control by mobile apps is on in the Roku's settings.")
            case .notStarted:
                await endSession(room: nil, ecp: ecp); fail("The Roku didn't start the receiver. Make sure the TV is on and try again.")
            case .receiverOpenNotConfirmed:
                await endSession(room: nil, ecp: ecp)
                fail(requests == 0 ? Self.noContact : "The Roku asked your iPhone for the video but hasn't started playing it. Look at the TV: it shows the reason if the video failed.")
            }
        } catch is CancellationError {
            busy = false
        } catch {
            await endSession(room: nil, ecp: ecp)
            fail("Couldn't prepare that video (\(error.localizedDescription)).")
        }
    }

    private func castPhoto(_ item: PhotosPickerItem, _ room: Room, _ phoneIP: String, _ ecp: ECPClient) async {
        message = "Preparing the photo…"
        guard let raw = try? await item.loadTransferable(type: Data.self), let jpeg = PhotoPrep.jpegData(from: raw) else {
            return fail("Couldn't read that picture. Try another one, or a JPEG or PNG.")
        }
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("photo-\(UUID().uuidString).jpg")
        do { try jpeg.write(to: file) } catch { return fail("Couldn't prepare that picture.") }
        tempFiles.append(file)
        guard let src = try? FileSource(url: file, mime: "image/jpeg") else { return fail("Couldn't prepare that picture.") }
        let served = server.publish(file: src, clientIP: room.ip, host: phoneIP, ext: "jpg")
        token = served.token
        message = "Sending the photo to your Roku…"
        let sent = await ReceiverPlayer(ecp: ecp).showPhoto(ip: room.ip, url: served.url)
        // A photo has no player state to read. The proof is the Roku downloading the whole file from the phone.
        var fetched = false
        if sent {
            let deadline = Date().addingTimeInterval(20)
            while Date() < deadline {
                if (server.stats(token: served.token)?.bytes ?? 0) >= src.length { fetched = true; break }
                try? await Task.sleep(nanoseconds: 400_000_000)
            }
        }
        await endSession(room: nil, ecp: ecp)    // the Roku holds the picture now; the phone no longer has to stay open
        busy = false
        if !sent { fail("The Roku didn't accept the command. Check Wi-Fi, and that Control by mobile apps is on in the Roku's settings.") }
        else if fetched { ok("Sent. The Roku has the photo and should be showing it; the TV shows a reason if it couldn't.", active: true) }
        else { warn("The photo was sent, but the Roku never downloaded it. " + Self.noContact) }
    }

    private static let noContact = "Check that the iPhone and the Roku are on the same Wi-Fi, that Couchcast is allowed under Settings → Privacy & Security → Local Network, and that the router isn't isolating devices (guest networks often do)."

    // MARK: stop and housekeeping

    func stop(room: Room, ecp: ECPClient) {
        work?.cancel()
        Task {
            _ = await ecp.key(room.ip, "Back")
            await endSession(room: nil, ecp: ecp)
            active = false; busy = false; message = ""
        }
    }

    /// Ends the local file service and deletes the temporary copies (a private photo shouldn't linger).
    private func endSession(room: Room?, ecp: ECPClient) async {
        watcher?.cancel(); watcher = nil
        if let t = token { server.close(token: t); token = nil }
        setKeepAwake(false)
        tempFiles.forEach { try? FileManager.default.removeItem(at: $0) }
        tempFiles.removeAll()
    }

    /// Ends the relay when the Roku's player reports the video is over.
    private func watch(_ room: Room, _ ecp: ECPClient) {
        watcher?.cancel()
        watcher = Task {
            var seenActive = false, misses = 0
            let started = Date()
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                let state = (await ecp.playerInfo(room.ip))?.state
                if let s = state, ["play", "buffer", "pause"].contains(s) { seenActive = true; misses = 0; continue }
                if !seenActive { if Date().timeIntervalSince(started) > 60 { break } else { continue } }
                misses += 1
                if misses >= 3 { break }
            }
            if Task.isCancelled { return }
            await endSession(room: nil, ecp: ecp)
            active = false; message = "Finished."; color = .secondary
        }
    }

    func scenePhaseChanged(_ phase: ScenePhase) {
        // iOS suspends apps shortly after they leave the screen. Ask for a little extra time so a quick app switch doesn't cut the video.
        if phase == .background, token != nil, backgroundTask == .invalid {
            backgroundTask = UIApplication.shared.beginBackgroundTask { [weak self] in self?.endBackgroundTask() }
        } else if phase == .active { endBackgroundTask() }
    }

    private func endBackgroundTask() {
        if backgroundTask != .invalid { UIApplication.shared.endBackgroundTask(backgroundTask); backgroundTask = .invalid }
    }

    private func setKeepAwake(_ on: Bool) { UIApplication.shared.isIdleTimerDisabled = on }

    private func ok(_ m: String, active a: Bool) { message = m; color = .green; active = a }
    private func warn(_ m: String) { message = m; color = .secondary; busy = false }
    private func fail(_ m: String) { message = m; color = .red; busy = false; active = false }

    static func normalize(_ raw: String) -> String? {
        var t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        // A pasted link often drags extra words along ("https://… no b"): use the first word that is a web link.
        let words = t.split(whereSeparator: { $0.isWhitespace }).map(String.init)
        if words.count > 1, let first = words.first(where: { $0.lowercased().hasPrefix("http://") || $0.lowercased().hasPrefix("https://") }) { t = first }
        if t.isEmpty || t.contains(where: { $0.isWhitespace }) { return nil }
        let u: String
        if t.lowercased().hasPrefix("http://") || t.lowercased().hasPrefix("https://") { u = t }
        else if t.contains("://") { return nil } else { u = "https://" + t }
        guard let url = URL(string: u), let host = url.host, !host.isEmpty else { return nil }
        return u
    }
}

import SwiftUI
import CouchcastCore
#if canImport(GoogleCast)
import GoogleCast
#endif

// Cast to a Chromecast, Google TV, Android TV or a TV with Cast built in. Separate from the Roku tabs.
// The Google Cast SDK is a separate download (see docs/IOS.md, "Google Cast"). Without it this tab explains that it is not included.

enum CastPhase { case idle, scanning, sending, playing, notConfirmed, failed, noVideo, choose }

#if canImport(GoogleCast)
/// The real Google Cast session. Cast calls run on the main thread. UNVERIFIED on hardware: written against the Cast iOS SDK 4.8 API.
final class GoogleCastTransport: NSObject, CastTransport, GCKRequestDelegate {
    private var pending: CheckedContinuation<Bool, Never>?

    private func client() -> GCKRemoteMediaClient? {
        guard let s = GCKCastContext.sharedInstance().sessionManager.currentCastSession, s.connectionState == .connected else { return nil }
        return s.remoteMediaClient
    }

    func isConnected() -> Bool { client() != nil }

    @MainActor func loadMain(_ url: String, _ contentType: String) async -> Bool {
        guard let c = client(), let u = URL(string: url) else { return false }
        let b = GCKMediaInformationBuilder(contentURL: u)
        b.streamType = .buffered
        b.contentType = contentType
        let opts = GCKMediaLoadOptions(); opts.autoplay = true
        return await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
            pending = cont
            let req = c.loadMedia(b.build(), with: opts)
            req.delegate = self
            DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak self] in self?.finish(false) }
        }
    }

    private func finish(_ ok: Bool) { pending?.resume(returning: ok); pending = nil }
    func requestDidComplete(_ request: GCKRequest) { finish(true) }
    func request(_ request: GCKRequest, didFailWithError error: GCKError) { finish(false) }
    func request(_ request: GCKRequest, didAbortWith abortReason: GCKRequestAbortReason) { finish(false) }

    func load(url: String, contentType: String) async -> Bool { await loadMain(url, contentType) }

    @MainActor func snapshotMain() -> CastSnapshot? {
        guard let st = client()?.mediaStatus else { return nil }
        let idle = st.playerState == .idle
        let state: CastPlayerState
        switch st.playerState {
        case .playing: state = .playing
        case .buffering: state = .buffering
        case .paused: state = .paused
        case .loading: state = .loading
        case .idle: state = .idle
        default: state = .unknown
        }
        return CastSnapshot(state, error: idle && st.idleReason == .error, finished: idle && st.idleReason == .finished)
    }

    func snapshot() async -> CastSnapshot? { await snapshotMain() }

    @MainActor func stopMain() -> Bool { guard let c = client() else { return false }; c.stop(); return true }
    func stop() async -> Bool { await stopMain() }
}

struct CastButton: UIViewRepresentable {
    func makeUIView(context: Context) -> GCKUICastButton { let b = GCKUICastButton(frame: CGRect(x: 0, y: 0, width: 36, height: 36)); b.tintColor = .white; return b }
    func updateUIView(_ uiView: GCKUICastButton, context: Context) {}
}
#endif

@MainActor
final class CastModel: NSObject, ObservableObject {
    @Published var available = false
    @Published var connected = false
    @Published var deviceName: String?
    @Published var phase: CastPhase = .idle
    @Published var message = ""
    @Published var candidates: [String] = []
    @Published var chosen: String?

    private var work: Task<Void, Never>?
    #if canImport(GoogleCast)
    private let transport = GoogleCastTransport()
    #endif

    override init() {
        super.init()
        #if canImport(GoogleCast)
        available = true
        GCKCastContext.sharedInstance().sessionManager.add(self)
        refresh()
        #else
        message = "Google Cast isn't included in this build yet. The Roku tabs are unaffected."
        #endif
    }

    var busy: Bool { phase == .sending || phase == .scanning }

    func refresh() {
        #if canImport(GoogleCast)
        let s = GCKCastContext.sharedInstance().sessionManager.currentCastSession
        connected = s?.connectionState == .connected
        deviceName = connected ? s?.device.friendlyName : nil
        #endif
    }

    func play(_ raw: String) {
        guard let url = CastLinks.normalize(raw) else { return fail("Enter a link that starts with http:// or https://.") }
        guard connected else { return fail(CastMessages.forOutcome(PlayOutcome(.sendFailed))) }
        if let format = ReceiverPlayer.formatFor(url) { send(url, format); return }
        work?.cancel()
        work = Task {
            phase = .scanning; message = "Looking for video on that page… this can take up to 15 seconds."; candidates = []; chosen = nil
            let found = await WebPageScanner().scan(url).urls
            if Task.isCancelled { return }
            switch found.count {
            case 0: phase = .noVideo; message = "No playable video found on that page. Sites that protect their video (Netflix, Hulu, Disney+, YouTube and similar) can't be cast this way. Try a page with an ordinary video, or a direct .mp4 / .m3u8 link."
            case 1: send(found[0], ReceiverPlayer.formatFor(found[0]) ?? "mp4")
            default: phase = .choose; message = "Found \(found.count) videos on that page. Pick one:"; candidates = found
            }
        }
    }

    func playCandidate(_ url: String) { send(url, ReceiverPlayer.formatFor(url) ?? "mp4") }

    private func send(_ url: String, _ format: String) {
        work?.cancel()
        work = Task {
            phase = .sending; message = "Sending the video to \(deviceName ?? "your TV")…"; chosen = url; candidates = []
            #if canImport(GoogleCast)
            // Request headers (Referer, User-Agent) can't be sent by the Cast media receiver, so none are passed.
            let out = await CastPlayback(transport).play(url: url, format: format)
            #else
            let out = PlayOutcome(.sendFailed)
            #endif
            if Task.isCancelled { return }
            phase = out.result == .playing ? .playing : (out.result == .receiverOpenNotConfirmed ? .notConfirmed : .failed)
            message = CastMessages.forOutcome(out)
        }
    }

    func stop() {
        work?.cancel()
        Task {
            #if canImport(GoogleCast)
            _ = await transport.stop()
            #endif
            phase = .idle; message = ""; chosen = nil; candidates = []
        }
    }

    func cancel() { work?.cancel(); phase = .idle; message = ""; candidates = [] }
    private func fail(_ m: String) { phase = .failed; message = m }
}

#if canImport(GoogleCast)
extension CastModel: GCKSessionManagerListener {
    func sessionManager(_ sessionManager: GCKSessionManager, didStart session: GCKCastSession) { refresh() }
    func sessionManager(_ sessionManager: GCKSessionManager, didResumeCastSession session: GCKCastSession) { refresh() }
    func sessionManager(_ sessionManager: GCKSessionManager, didEnd session: GCKCastSession, withError error: Error?) { refresh() }
    func sessionManager(_ sessionManager: GCKSessionManager, didFailToStart session: GCKCastSession, withError error: Error) { refresh() }
    func sessionManager(_ sessionManager: GCKSessionManager, didSuspend session: GCKCastSession, with reason: GCKConnectionSuspendReason) { refresh() }
}
#endif

struct CastView: View {
    @StateObject private var model = CastModel()
    @State private var link = ""
    @FocusState private var typing: Bool

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text("Cast to a Chromecast or Google TV").font(.title3)
                    Spacer()
                    #if canImport(GoogleCast)
                    CastButton().frame(width: 36, height: 36)
                    #endif
                }
                Text(model.connected ? "Connected to \(model.deviceName ?? "your TV")." : "Tap the Cast button and choose your TV. Nothing needs to be installed on it.")
                    .font(.footnote).foregroundColor(model.connected ? .green : .secondary)
                if !model.available {
                    Text(model.message).font(.subheadline).foregroundColor(.red)
                } else {
                    TextField("Video link or web page", text: $link)
                        .textFieldStyle(.roundedBorder).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                        .focused($typing).submitLabel(.go).onSubmit { typing = false; model.play(link) }
                    HStack {
                        Button("Paste") { typing = false; if let s = UIPasteboard.general.string { link = s.trimmingCharacters(in: .whitespacesAndNewlines) } }.buttonStyle(.bordered)
                        Button("Play on TV") { typing = false; model.play(link) }.buttonStyle(.borderedProminent).frame(maxWidth: .infinity)
                            .disabled(model.busy || link.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                    if model.busy {
                        HStack { ProgressView(); Text(model.message).font(.subheadline); Spacer(); Button("Cancel") { model.cancel() } }
                    } else if !model.message.isEmpty {
                        Text(model.message).font(.subheadline).foregroundColor(model.phase == .playing ? .green : ([.idle, .notConfirmed, .choose].contains(model.phase) ? .secondary : .red))
                    }
                    if let c = model.chosen { Text("Video: " + MediaLinks.label(c)).font(.caption).foregroundColor(.secondary).lineLimit(1) }
                    if model.phase == .choose {
                        ForEach(model.candidates, id: \.self) { c in
                            Button { model.playCandidate(c) } label: { Text(MediaLinks.label(c)).lineLimit(1).frame(maxWidth: .infinity) }.buttonStyle(.bordered)
                        }
                    }
                    if model.phase == .playing || model.phase == .notConfirmed { Button("Stop") { model.stop() }.buttonStyle(.bordered) }
                }
                Text("Cast is separate from the Roku tabs, which are unchanged. Netflix, Hulu, Disney+ and other copy-protected services can't be cast this way; use their own apps' Cast button.")
                    .font(.footnote).foregroundColor(.secondary)
            }.padding()
        }
        .scrollDismissesKeyboard(.interactively)
        .toolbar { ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("Done") { typing = false } } }
    }
}

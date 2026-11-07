import SwiftUI
import PhotosUI
import CouchcastCore

enum PlayPhase { case idle, scanning, sending, playing, notConfirmed, failed, needsReceiver, noVideo, choose }
enum ChannelState { case unknown, installed, missing }
struct LinkInfo { var service: ServiceMatch?; var channel: ChannelState = .unknown }

@MainActor
final class PlayModel: ObservableObject {
    @Published var phase: PlayPhase = .idle
    @Published var message = ""
    @Published var candidates: [String] = []
    @Published var chosen: String?
    @Published var status: PlaybackStatus = .idle
    @Published var diagnosing = false
    @Published var diagnosis: Diagnosis?
    @Published var offers: [Offer] = []
    @Published var relayActive = false
    /// Redacted, secret-free lines for troubleshooting (direct vs relay).
    @Published var details: [String] = []
    @Published var link = LinkInfo()
    @Published var channels: [(StreamingService, RokuApp)]?
    @Published var sites: [String] = SignInStore().hosts()

    private let server = LocalServer()
    private let signIn = SignInStore()
    private let cookies = WebCookieSource()
    private var token: String?
    private var work: Task<Void, Never>?
    private var diagWork: Task<Void, Never>?
    private var watcher: Task<Void, Never>?
    private var tempFiles: [URL] = []
    private var backgroundTask = UIBackgroundTaskIdentifier.invalid
    private var lastScan: ScanResult?
    private var lastEntered: String?
    private var lastRokuDrm: String?
    private var receiverInstalled: Bool?
    private var useSession = false

    var busy: Bool { phase == .sending || phase == .scanning }
    var capabilities: ReceiverCapabilities { .roku(receiverInstalled: receiverInstalled) }

    // MARK: links

    /// A direct video link plays straight away; a web page is scanned for video first.
    func play(room: Room, raw: String, forceMp4: Bool = false, ecp: ECPClient) {
        guard let url = Self.normalize(raw) else { return fail("Enter a link that starts with http:// or https://.") }
        lastEntered = url
        diagWork?.cancel()
        let format = ReceiverPlayer.formatFor(url) ?? (forceMp4 ? "mp4" : nil)
        if let format = format { lastScan = nil; send(room, url, format, nil, nil, ecp); return }
        work?.cancel()
        work = Task {
            reset(.scanning, "Looking for video on that page… this can take up to 15 seconds.")
            var scan = await WebPageScanner().scan(url)
            // A plain page fetch sometimes sees links the live page hides; combine both.
            if let u = URL(string: url) {
                let plain = await PageScanner().scan(u)
                scan.urls = MediaLinks.clean(scan.urls + plain)
            }
            if Task.isCancelled { return }
            lastScan = scan
            switch scan.urls.count {
            case 0:
                reset(.noVideo, "No playable video found on that page. Sites like YouTube, Netflix, Hulu and Disney+ protect their video or hide it inside their own player, so it can't be sent to a Roku this way. Try a page with an ordinary video, or a direct .mp4 / .m3u8 link.")
            case 1:
                await sendNow(room, scan.urls[0], ReceiverPlayer.formatFor(scan.urls[0]) ?? "mp4", scan.pageUrl, scan.userAgent, ecp)
            default:
                reset(.choose, "Found \(scan.urls.count) videos on that page. Pick one:"); candidates = scan.urls
            }
        }
    }

    func playCandidate(room: Room, url: String, ecp: ECPClient) {
        send(room, url, ReceiverPlayer.formatFor(url) ?? "mp4", lastScan?.pageUrl, lastScan?.userAgent, ecp)
    }

    func cancel() { work?.cancel(); reset(.idle, "") }

    private func send(_ room: Room, _ url: String, _ format: String, _ referer: String?, _ ua: String?, _ ecp: ECPClient) {
        work?.cancel()
        work = Task { await sendNow(room, url, format, referer, ua, ecp) }
    }

    private func sendNow(_ room: Room, _ url: String, _ format: String, _ referer: String?, _ ua: String?, _ ecp: ECPClient) async {
        reset(.sending, "Checking the receiver on your Roku…"); chosen = url; status = .checking
        guard await receiverReady(room, ecp) else { return }
        message = "Sending the video to your Roku…"
        let out = await ReceiverPlayer(ecp: ecp).play(ip: room.ip, url: url, format: format, referer: referer, userAgent: ua)
        if Task.isCancelled { return }
        lastRokuDrm = out.last?.drm
        switch out.result {
        case .playing: finish(.playing, "Confirmed: the Roku reports it is playing." + Self.formatNote(out.last), .playingDirect)
        case .stoppedEarly: finish(.failed, Self.stoppedMessage(out), .idle)
        case .receiverOpenNotConfirmed:
            finish(.notConfirmed, "The receiver opened, but the Roku hasn't reported playing (it says: \(out.last?.state ?? "nothing")\(out.last?.error == true ? ", error" : "")). Look at the TV: it shows the reason if the video failed. The link may need sign-in, or be a format the Roku can't play.", .idle)
        case .notStarted: finish(.failed, "The Roku didn't start the receiver. Make sure the TV is on and try again.", .idle)
        case .sendFailed: finish(.failed, "The Roku didn't accept the command. Check Wi-Fi, and that Control by mobile apps is on in the Roku's settings.", .idle)
        }
        chosen = url
        details.append("Direct playback: \(out.result) (\(Redactor.url(url)))")
        if out.result == .stoppedEarly || out.result == .receiverOpenNotConfirmed { diagnose(url, referer, ua) }
    }

    /// True when the current receiver is on the Roku; otherwise puts the reason (and the installer offer) on screen.
    private func receiverReady(_ room: Room, _ ecp: ECPClient) async -> Bool {
        let apps = await ecp.apps(room.ip)
        let state = ReceiverCheck.classify(apps)
        receiverInstalled = apps == nil ? nil : state == .current
        switch state {
        case .current: return true
        case .unknown: fail("Couldn't reach the Roku. Check that the TV is on and on the same Wi-Fi, and that Couchcast is allowed under Settings → Privacy & Security → Local Network.")
        case .notInstalled: needsReceiver("The Couchcast Receiver isn't installed on this Roku yet.")
        case .outdated: needsReceiver("The receiver on this Roku is out of date. Update it first.")
        case .otherDevApp: needsReceiver("A different sideloaded channel is on this Roku. Install the receiver first.")
        }
        return false
    }

    // MARK: Roku channel links

    /// Called as the user types/pastes: recognises streaming-service links and checks the channel is installed.
    func onLinkChanged(_ raw: String, room: Room?, ecp: ECPClient) {
        let match = StreamingServices.recognize(raw)
        if match?.service.id == link.service?.service.id && match?.contentId == link.service?.contentId { return }
        link = LinkInfo(service: match, channel: .unknown)
        guard let match = match, let room = room else { return }
        Task {
            guard let apps = await ecp.apps(room.ip), link.service?.service.id == match.service.id else { return }
            link.channel = ChannelMatcher.find(match.service, apps) != nil ? .installed : .missing
        }
    }

    func loadChannels(room: Room, ecp: ECPClient) {
        Task { channels = await ecp.apps(room.ip).map { ChannelMatcher.installedServices($0) } }
    }

    func openChannel(room: Room, match: ServiceMatch? = nil, ecp: ECPClient) {
        guard let match = match ?? link.service else { return }
        work?.cancel()
        work = Task {
            reset(.sending, "Opening \(match.service.displayName) on your Roku…"); status = .openingChannel
            let name = match.service.displayName
            switch await RokuChannelLauncher(ecp: ecp).open(ip: room.ip, match: match) {
            case .opened(_, let titleLinkSent):
                finish(.playing, "\(name) is open on your TV." + (titleLinkSent
                    ? " A link to the title was sent, but only the channel can open it. If you see its home screen instead, choose the title with your remote."
                    : " Choose what to watch with your remote or the Remote tab."), .channelOpen)
            case .notConfirmed:
                finish(.notConfirmed, "The command to open \(name) was accepted, but the Roku hasn't reported it opening. Look at the TV.", .channelNotConfirmed)
            case .notInstalled:
                finish(.failed, "\(name) isn't installed on this Roku. On the Roku, open Streaming Channels from the Home screen, search for \(name), and add it. Then try again.", .failed)
            case .failed(let why): finish(.failed, why, .failed)
            }
        }
    }

    // MARK: diagnostics

    private func diagnose(_ url: String, _ referer: String?, _ ua: String?) {
        diagWork?.cancel()
        diagnosing = true
        let service = link.service ?? lastEntered.flatMap { StreamingServices.recognize($0) }
        let entered = lastEntered
        diagWork = Task {
            let userHost = entered.flatMap { UpstreamPolicy.hostOf($0) }
            let privateOk: Set<String> = {
                guard let h = userHost, UpstreamPolicy.checkUrl("http://\(h)/", privateOk: []) != nil else { return [] }
                return [h]
            }()
            let p = await StreamProbe().probe(url: url, referer: referer ?? lastScan?.pageUrl, userAgent: ua ?? lastScan?.userAgent, cookies: cookies, privateOk: privateOk)
            if Task.isCancelled { return }
            let d = FailureClassifier.diagnose(DiagnoseInput(probe: p, rokuDrm: lastRokuDrm, service: service, hasSiteSession: await SiteSessions.hasSession(url)))
            diagnosing = false; diagnosis = d
            offers = PlaybackPlanner.usable(d.offers, capabilities)
            status = PlaybackPlanner.status(for: d)
            var line = "Phone check: \(d.kind) (no headers: \(p.manifest.bare.status.map(String.init) ?? "none"), browser-like: \(p.manifest.context.status.map(String.init) ?? "none")"
            if let s = p.manifest.session { line += ", with your session: \(s.status.map(String.init) ?? "none")" }
            details.append(line + ")")
        }
    }

    // MARK: phone relay

    func setUseSession(_ use: Bool) { useSession = use }

    func tryRelay(room: Room, ecp: ECPClient) {
        guard let url = chosen else { return fail("Pick a video first, then try the phone relay.") }
        guard let phoneIP = LocalNetwork.wifiIPv4() else { return fail("Connect your iPhone to the same Wi-Fi as the Roku to use the phone relay.") }
        let format = ReceiverPlayer.formatFor(url) ?? "mp4"
        let keep = details
        let scan = lastScan, entered = lastEntered
        let needSession = diagnosis?.kind == .needsSession || useSession
        work?.cancel()
        work = Task {
            reset(.sending, "Starting the phone relay…"); status = .startingRelay; chosen = url; details = keep
            do { try await server.start() } catch { return fail("Couldn't start the sharing service on this iPhone. Allow Couchcast under Settings → Privacy & Security → Local Network.") }
            let hosts = Set(([scan?.pageUrl, url] + (scan?.urls.map { Optional($0) } ?? [])).compactMap { $0 }.compactMap { UpstreamPolicy.hostOf($0) })
            let hasSession = await SiteSessions.hasSession(url)
            let spec = RelaySpec(bindHost: phoneIP, clientIP: room.ip, sourceUrl: url, userEnteredUrl: entered ?? url,
                                 referer: scan?.pageUrl, userAgent: scan?.userAgent, cookies: cookies,
                                 attachCookies: needSession && hasSession, credentialHosts: hosts, format: format)
            switch server.openRelay(spec) {
            case .refused(let why): finish(.failed, why, .failed); chosen = url; details = keep
            case .ready(let tok, let playUrl, let fmt):
                token = tok
                setKeepAwake(true)
                message = "Sending the relay link to your Roku…"
                let out = await ReceiverPlayer(ecp: ecp).play(ip: room.ip, url: playUrl, format: fmt)
                let stats = server.relayStats(token: tok)
                let line = "Phone relay: \(out.result) (\(stats?.summary() ?? ""))"
                if out.result == .playing {
                    finish(.playing, "Playing through your iPhone." + Self.formatNote(out.last) + " Keep Couchcast open on screen, on the same Wi-Fi, until the video ends. Remote pause and seek work as usual.", .playingRelay)
                    relayActive = true; chosen = url; details = keep + [line]
                    watch(room, ecp)
                } else {
                    let why = RelayFailure.explain(requests: stats?.requests ?? 0, upstream: stats?.upstreamStatus ?? [:], drm: stats?.drmDetected, playing: false, lastError: stats?.lastError)
                    await endSession()
                    finish(.failed, "Phone relay didn't work. \(why)", stats?.drmDetected != nil ? .unsupportedProtected : .failed)
                    offers = [.mirror]; chosen = url; details = keep + [line]
                }
            }
        }
    }

    func hasSessionForChosen() async -> Bool {
        if let c = chosen, await SiteSessions.hasSession(c) { return true }
        if let p = lastScan?.pageUrl, await SiteSessions.hasSession(p) { return true }
        return false
    }

    // MARK: cast from this iPhone

    func cast(item: PhotosPickerItem, room: Room, ecp: ECPClient) {
        diagWork?.cancel(); lastScan = nil; lastEntered = nil
        work?.cancel()
        let isVideo = item.supportedContentTypes.contains { $0.conforms(to: .movie) }
        work = Task {
            await endSession()
            reset(.sending, "Checking the receiver on your Roku…"); status = .checking
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
            message = "Sending the video to your Roku…"; status = .startingRelay
            setKeepAwake(true)
            let out = await ReceiverPlayer(ecp: ecp).play(ip: room.ip, url: served.url, format: "mp4")
            let requests = server.stats(token: served.token)?.requests ?? 0
            let line = "Phone video: \(out.result) (requests=\(requests))"
            switch out.result {
            case .playing:
                finish(.playing, "Playing from your iPhone. Keep Couchcast open on screen until the video ends. Remote pause and seek work as usual." + Self.formatNote(out.last), .playingRelay)
                relayActive = true; details = [line]
                watch(room, ecp)
            case .stoppedEarly:
                await endSession(); finish(.failed, "The video started, then the Roku stopped it after about \(out.playedSeconds) seconds. Some recordings aren't supported by every Roku.", .failed); details = [line]
            case .sendFailed:
                await endSession(); finish(.failed, "The Roku didn't accept the command. Check Wi-Fi, and that Control by mobile apps is on in the Roku's settings.", .failed); details = [line]
            case .notStarted:
                await endSession(); finish(.failed, "The Roku didn't start the receiver. Make sure the TV is on and try again.", .failed); details = [line]
            case .receiverOpenNotConfirmed:
                await endSession()
                finish(.notConfirmed, requests == 0 ? Self.noContact : "The Roku asked your iPhone for the video but hasn't started playing it. Look at the TV: it shows the reason if the video failed.", .failed); details = [line]
            }
        } catch is CancellationError {
            phase = .idle
        } catch {
            await endSession()
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
        // A photo has no player state. The proof is the Roku downloading the whole file from the phone.
        var fetched = false
        if sent {
            let deadline = Date().addingTimeInterval(20)
            while Date() < deadline {
                if (server.stats(token: served.token)?.bytes ?? 0) >= src.length { fetched = true; break }
                try? await Task.sleep(nanoseconds: 400_000_000)
            }
        }
        let line = "Phone photo: \(!sent ? "not accepted" : fetched ? "downloaded by the Roku" : "not downloaded")"
        await endSession()    // the Roku holds the picture now; the phone no longer has to stay open
        if !sent { finish(.failed, "The Roku didn't accept the command. Check Wi-Fi, and that Control by mobile apps is on in the Roku's settings.", .idle) }
        else if fetched { finish(.playing, "Sent. The Roku has the photo and should be showing it; the TV shows a reason if it couldn't.", .showingPhoto) }
        else { finish(.notConfirmed, "The photo was sent, but the Roku never downloaded it. " + Self.noContact, .idle) }
        details = [line]
    }

    private static let noContact = "Check that the iPhone and the Roku are on the same Wi-Fi, that Couchcast is allowed under Settings → Privacy & Security → Local Network, and that the router isn't isolating devices (guest networks often do)."

    // MARK: sites and sign-in

    /// Where sign-in should start: the page the video came from, else what the user pasted.
    func signInStartUrl() -> String? { lastScan?.pageUrl ?? lastEntered }
    func signedIn(_ host: String) { signIn.add(host); sites = signIn.hosts() }
    func logOut(_ host: String) { Task { await SiteSessions.clearSite(host); signIn.remove(host); sites = signIn.hosts() } }
    func clearAllSites() { Task { await SiteSessions.clearAll(); signIn.clear(); sites = [] } }

    /// After signing in, look again with the new session.
    func rescan(room: Room, signedInHost: String?, ecp: ECPClient) {
        guard let entered = lastEntered, let h = signedInHost else { return }
        let hosts = [UpstreamPolicy.hostOf(entered), lastScan.flatMap { UpstreamPolicy.hostOf($0.pageUrl) }].compactMap { $0 }
        // never replay an unrelated link just because the user signed in somewhere
        if hosts.contains(where: { $0 == h || $0.hasSuffix("." + h) || h.hasSuffix("." + $0) }) { play(room: room, raw: entered, ecp: ecp) }
    }

    // MARK: stop and housekeeping

    func stop(room: Room, ecp: ECPClient) {
        work?.cancel()
        Task {
            _ = await ecp.key(room.ip, "Back")
            await endSession()
            reset(.idle, "")
        }
    }

    /// Ends the local file/relay service and deletes the temporary copies (a private photo shouldn't linger).
    private func endSession() async {
        watcher?.cancel(); watcher = nil
        if let t = token { server.close(token: t); token = nil }
        setKeepAwake(false)
        tempFiles.forEach { try? FileManager.default.removeItem(at: $0) }
        tempFiles.removeAll()
        relayActive = false
    }

    /// Ends the relay when the Roku's player reports the video is over.
    private func watch(_ room: Room, _ ecp: ECPClient) {
        watcher?.cancel()
        watcher = Task {
            let detector = PlaybackEndDetector()
            var ticks = 0
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                ticks += 1
                if ticks % 12 == 0 { server.expire() }
                if detector.onReport((await ecp.playerInfo(room.ip))?.state) { break }
            }
            if Task.isCancelled { return }
            await endSession()
            phase = .idle; message = "Finished."; status = .idle
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

    // MARK: state helpers

    private func reset(_ p: PlayPhase, _ m: String) {
        phase = p; message = m; candidates = []; chosen = nil; status = .idle
        diagnosing = false; diagnosis = nil; offers = []; details = []
    }

    private func finish(_ p: PlayPhase, _ m: String, _ s: PlaybackStatus) { phase = p; message = m; status = s }
    private func fail(_ m: String) { reset(.failed, m) }
    private func needsReceiver(_ m: String) { reset(.needsReceiver, m) }

    static func formatNote(_ i: PlayerInfo?) -> String {
        guard let i = i else { return "" }
        let bits = [i.video.flatMap { $0.isEmpty ? nil : "video \($0)" }, i.resolution.flatMap { $0.isEmpty ? nil : $0 }, i.audio.flatMap { $0.isEmpty ? nil : "audio \($0)" }].compactMap { $0 }
        return bits.isEmpty ? "" : " (\(bits.joined(separator: ", ")))"
    }

    /// Plain-English reason from what the Roku itself reported.
    static func stoppedMessage(_ out: PlayOutcome) -> String {
        let i = out.last
        let noVideo = i != nil && (i!.video ?? "").isEmpty && !(i!.audio ?? "").isEmpty
        let drm = i?.drm.flatMap { $0.isEmpty || $0.lowercased() == "none" ? nil : $0 }
        let why: String
        if let d = drm { why = "The Roku says this video is copy-protected (\(d)), which the receiver can't play." }
        else if noVideo { why = "The Roku played the sound but could not decode the picture, which usually means the video uses a format this Roku doesn't support." }
        else { why = "The link may have expired, need a sign-in, or use a format this Roku can't play." }
        var seen = formatNote(i).trimmingCharacters(in: .whitespaces)
        if seen.hasPrefix("(") { seen.removeFirst() }
        if seen.hasSuffix(")") { seen.removeLast() }
        return "The video started, then the Roku stopped it after about \(out.playedSeconds) seconds. \(why)" + (seen.isEmpty ? "" : " The Roku reported: \(seen).") + " The TV screen shows more detail."
    }

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

import SwiftUI
import Security
import CouchcastCore

/// Optional "remember on this iPhone". The developer password lives in the iOS Keychain (this device only, never synced or backed up).
struct DevPasswordStore {
    private let service = "com.mundymorning.couchcast.devpassword"

    private func query(_ ip: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: ip]
    }

    func has(_ ip: String) -> Bool {
        var q = query(ip); q[kSecReturnData as String] = false
        return SecItemCopyMatching(q as CFDictionary, nil) == errSecSuccess
    }

    func save(_ ip: String, _ password: String) {
        clear(ip)
        var q = query(ip)
        q[kSecValueData as String] = Data(password.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        SecItemAdd(q as CFDictionary, nil)
    }

    func load(_ ip: String) -> String? {
        var q = query(ip); q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }

    func clear(_ ip: String) { SecItemDelete(query(ip) as CFDictionary) }
}

/// Wizard progress that survives leaving the screen. Never holds a password.
struct InstallStore {
    private let d = UserDefaults.standard
    var ip: String? { get { d.string(forKey: "inst.ip") } nonmutating set { d.set(newValue, forKey: "inst.ip") } }
    var roomName: String { get { d.string(forKey: "inst.name") ?? "" } nonmutating set { d.set(newValue, forKey: "inst.name") } }
    var step: InstallStep { get { InstallStep(rawValue: d.string(forKey: "inst.step") ?? "") ?? .pick } nonmutating set { d.set(newValue.rawValue, forKey: "inst.step") } }
    /// Set when the user says the TV is restarting after the password was set.
    var restartPendingSince: Double { get { d.double(forKey: "inst.restart") } nonmutating set { d.set(newValue, forKey: "inst.restart") } }
    func clear() { ["inst.ip", "inst.name", "inst.step", "inst.restart"].forEach { d.removeObject(forKey: $0) } }
}

enum CheckStatus { case pending, ok, failed }
struct CheckRow: Identifiable { let id = UUID(); var label: String; var status: CheckStatus; var note = "" }
struct InstallProblem { let title: String; let body: String }

@MainActor
final class InstallModel: ObservableObject {
    @Published var step: InstallStep = .pick
    @Published var ip: String?
    @Published var roomName = ""
    @Published var checks: [CheckRow] = []
    @Published var problem: InstallProblem?
    @Published var receiver: InstallState = .unknown
    @Published var installedVersion: String?
    @Published var busy: String?
    @Published var error: String?
    @Published var confirmReplace = false
    @Published var hasSavedPassword = false
    @Published var restartWaitSeconds = 0
    @Published var verified = false
    @Published var testNote: String?
    @Published var resumeAvailable = false

    let package: ReceiverPackage?
    private let store = InstallStore()
    private let pwStore = DevPasswordStore()
    private let dev = RokuDevClient()
    private let ecp = ECPClient()
    private var job: Task<Void, Never>?
    private var pendingPassword: (String, Bool)?

    var bundledVersion: String { package?.versionName ?? "" }

    init(defaultRoom: Room?) {
        if let zipURL = Bundle.main.url(forResource: "couchcast-receiver", withExtension: "zip"),
           let infoURL = Bundle.main.url(forResource: "receiver-info", withExtension: "json"),
           let zip = try? Data(contentsOf: zipURL), let info = try? Data(contentsOf: infoURL) {
            package = ReceiverPackage(zip: zip, infoJSON: info)
        } else { package = nil }
        ip = store.ip ?? defaultRoom?.ip
        roomName = store.ip != nil ? store.roomName : (defaultRoom?.name ?? "")
        resumeAvailable = store.ip != nil && ![.pick, .done].contains(store.step)
    }

    // MARK: selection

    func choose(_ room: Room) { ip = room.ip; roomName = room.name; error = nil }

    func chooseManual(_ raw: String, onVerified: @escaping (Room) -> Void) {
        let address = raw.trimmingCharacters(in: .whitespaces)
        guard !address.isEmpty else { return }
        job?.cancel()
        job = Task {
            busy = "Looking for a Roku at \(address)…"; error = nil
            let info = await ecp.deviceInfo(address)
            busy = nil
            guard let info = info else { error = "Couldn't reach a Roku at \(address). Check the address and that your iPhone is on the same Wi-Fi."; return }
            let room = Room(ip: address, name: info.name, model: info.model, isTv: info.isTv)
            onVerified(room); choose(room)
        }
    }

    // MARK: navigation

    func start() {
        guard let ip = ip else { return }
        store.ip = ip; store.roomName = roomName
        runCheck()
    }

    func resume() {
        if let s = store.ip { ip = s; roomName = store.roomName }
        start()
    }

    func startOver() { job?.cancel(); store.clear(); step = .pick; resumeAvailable = false; checks = []; problem = nil; error = nil; busy = nil }

    /// Leaves the wizard but keeps progress so it can be resumed.
    func cancel() { job?.cancel(); busy = nil }

    /// - Returns: false if already at the first screen (the caller should close the wizard).
    func back() -> Bool {
        job?.cancel()
        let next: InstallStep
        switch step {
        case .pick: return false
        case .devMode, .problem, .done, .checking: next = .pick
        case .restart, .credentials: next = .devMode
        case .installing, .verifying: return true   // uploading; do not interrupt
        }
        setStep(next); return true
    }

    private func setStep(_ s: InstallStep, error e: String? = nil, busy b: String? = nil, problem p: InstallProblem? = nil) {
        store.step = s
        step = s; error = e; busy = b; problem = p; confirmReplace = false
    }

    // MARK: checks

    func runCheck() {
        guard let ip = ip else { return }
        job?.cancel()
        job = Task {
            setStep(.checking)
            checks = [CheckRow(label: "iPhone is on Wi-Fi", status: .pending),
                      CheckRow(label: "Roku answers on your network", status: .pending),
                      CheckRow(label: "Roku developer installer is available", status: .pending)]
            @MainActor func show(_ i: Int, _ s: CheckStatus, _ note: String = "") { checks[i].status = s; checks[i].note = note }
            let pending = store.restartPendingSince > 0

            guard LocalNetwork.wifiIPv4() != nil else {
                show(0, .failed, "not on Wi-Fi"); finishCheck(.networkDown, .unknown, pending); return
            }
            show(0, .ok)
            guard let info = await ecp.deviceInfo(ip) else {
                // Either the Roku is off/moved, "Control by mobile apps" is off, or Local Network access was refused.
                show(1, .failed, "no answer on port 8060"); finishCheck(.rokuUnreachable, .unknown, pending); return
            }
            show(1, .ok)
            if roomName.isEmpty { roomName = info.name }

            let (probe, why) = await dev.probeInstallerDetail(ip)
            if probe == .closed { show(2, .failed, why); finishCheck(.devModeOff, .unknown, pending); return }
            show(2, .ok)
            let apps = await ecp.apps(ip)
            installedVersion = apps?.first(where: { $0.id == InstallPlanner.devAppId })?.version
            finishCheck(.installerReady, package.map { InstallPlanner.classify(apps, $0) } ?? .unknown, pending)
        }
    }

    private func finishCheck(_ reach: Reachability, _ rcv: InstallState, _ pending: Bool) {
        if reach == .installerReady { store.restartPendingSince = 0 }
        let next = InstallPlanner.decide(reach, restartPending: pending, receiver: rcv)
        receiver = rcv
        hasSavedPassword = ip.map { pwStore.has($0) } ?? false
        switch next {
        case .problem: setStep(.problem, problem: problemFor(reach))
        case .restart: setStep(.restart); waitForRestart()
        case .done: verified = true; setStep(.done)   // the Roku lists it itself: the strongest check there is
        default: setStep(next)
        }
    }

    private func problemFor(_ r: Reachability) -> InstallProblem {
        switch r {
        case .networkDown: return InstallProblem(title: "Your iPhone isn't on a home network", body: "Connect your iPhone to the same Wi-Fi network as your Roku, then try again.")
        case .ecpBlocked: return InstallProblem(title: "The Roku is refusing network control", body: "On the TV open Settings > System > Advanced system settings > Control by mobile apps, set Network access to Default or Permissive, then try again.")
        default: return InstallProblem(title: "Can't find your Roku", body: "Make sure the TV is on and your iPhone is on the same Wi-Fi network. Check that Couchcast is allowed under Settings → Privacy & Security → Local Network, and that Control by mobile apps is on in the Roku's settings. If the Roku's address changed, go back and add it by its current IP address (Settings > Network > About on the TV).")
        }
    }

    // MARK: developer mode + restart

    /// The user says they finished the on-TV steps and the Roku is restarting.
    func passwordSet() { store.restartPendingSince = Date().timeIntervalSince1970; setStep(.restart); waitForRestart() }

    private func waitForRestart() {
        guard let ip = ip else { return }
        job?.cancel()
        job = Task {
            let started = Date()
            while !Task.isCancelled && Date().timeIntervalSince(started) < 240 {
                restartWaitSeconds = Int(Date().timeIntervalSince(started))
                if await ecp.deviceInfo(ip) != nil, await dev.probeInstaller(ip) == .open {
                    store.restartPendingSince = 0   // the follow-up check must land on a stable screen, never loop back here
                    runCheck(); return
                }
                try? await Task.sleep(nanoseconds: 4_000_000_000)
            }
            if !Task.isCancelled { error = "Still waiting. Make sure the TV is on and finished restarting, then tap Check again." }
        }
    }

    // MARK: install

    func install(_ typed: String, remember: Bool, replaceConfirmed: Bool = false) {
        // A stray space from a keyboard suggestion or paste is the easiest way to get "wrong password".
        let password = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let ip = ip, let pkg = package else { error = "This build doesn't contain the receiver package."; return }
        if password.isEmpty { error = "Enter the developer password you chose on the TV."; return }
        if receiver == .otherDevApp && !replaceConfirmed { pendingPassword = (password, remember); confirmReplace = true; return }
        job?.cancel()
        job = Task {
            setStep(.installing, busy: "Uploading the receiver to your Roku…")
            let out = await dev.install(ip: ip, user: "rokudev", password: password, zip: pkg.zip)
            let save = { if remember { self.pwStore.save(ip, password) } else { self.pwStore.clear(ip) } }
            switch out {
            case .accepted: save(); await verifyInstalled()
            case .badPassword:
                pwStore.clear(ip); hasSavedPassword = false
                setStep(.credentials, error: "The Roku didn't accept that password. It is the one you set in the Developer Settings screen on the TV (not your Roku account password). Passwords are case-sensitive.")
            case .compileFailed(let d): setStep(.credentials, error: "The Roku couldn't start the receiver package: \(d). This is a problem with the app's receiver, not your TV. Please report it.")
            case .rejected(let d): setStep(.credentials, error: "The Roku refused the install: \(d)")
            case .installerClosed: store.restartPendingSince = 0; runCheck()
            case .responseLost(let d):
                // The Roku may have installed and restarted its web server before replying. Only its own app list can say.
                await verifyInstalled(lostReply: d, onVerified: save)
            case .unreachable(let d): setStep(.credentials, error: "Lost contact with the Roku: \(d) Check that the TV is on and on the same Wi-Fi, then retry.")
            case .unknown(let d): setStep(.credentials, error: "\(d) Nothing has been confirmed, so please retry.")
            }
        }
    }

    func confirmReplaceAndInstall() { if let (p, r) = pendingPassword { install(p, remember: r, replaceConfirmed: true) }; pendingPassword = nil }

    func installWithSavedPassword() {
        guard let ip = ip else { return }
        guard let pw = pwStore.load(ip) else { hasSavedPassword = false; error = "The saved password could not be read. Please enter it again."; return }
        install(pw, remember: true)
    }

    func forgetSavedPassword() { if let ip = ip { pwStore.clear(ip) }; hasSavedPassword = false }

    /// Success is only claimed when the Roku itself lists the receiver.
    func verifyInstalled(lostReply: String? = nil, onVerified: (() -> Void)? = nil) async {
        guard let ip = ip, let pkg = package else { return }
        setStep(.verifying, busy: lostReply != nil ? "The Roku didn't send a reply. Checking whether it installed anyway…" : "Checking that the Roku lists the receiver…")
        for _ in 0..<8 {
            if Task.isCancelled { return }
            let apps = await ecp.apps(ip)
            if InstallPlanner.classify(apps, pkg) == .current {
                onVerified?()
                receiver = .current; installedVersion = apps?.first(where: { $0.id == InstallPlanner.devAppId })?.version
                verified = true; testNote = nil; setStep(.done); return
            }
            try? await Task.sleep(nanoseconds: 2_500_000_000)
        }
        verified = false
        setStep(.credentials, error: lostReply != nil
            ? "The Roku dropped the connection during the install (\(lostReply!)) and is not listing the receiver, so it did not install. Check that the TV is on, then retry."
            : "The Roku accepted the file, but it isn't listing the receiver yet, so we can't confirm the install. Tap Check again, or retry the install.")
    }

    /// Reinstall or update even when the receiver is already current.
    func reinstall() { verified = false; setStep(.credentials) }

    // MARK: test

    func launchReceiver() {
        guard let ip = ip else { return }
        Task {
            testNote = "Starting the receiver…"
            let sent = await dev.launchDevApp(ip)
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            let active = await dev.activeAppId(ip)
            testNote = active == InstallPlanner.devAppId
                ? "Confirmed: the Roku reports the Couchcast Receiver is running. Playing video through it still needs to be tried from the Play tab."
                : (!sent ? "The Roku didn't accept the launch command." : "Launch was sent, but the Roku isn't reporting the receiver as running. Look at the TV to check.")
        }
    }
}

import SwiftUI
import CouchcastCore

struct InstallView: View {
    @EnvironmentObject var state: AppState
    @StateObject private var model: InstallModel
    @Environment(\.dismiss) private var dismiss
    @State private var manual = ""
    @State private var password = ""
    @State private var remember = false
    @State private var showPassword = false
    @FocusState private var typing: Bool

    init(defaultRoom: Room?) { _model = StateObject(wrappedValue: InstallModel(defaultRoom: defaultRoom)) }

    var body: some View {
        let locked = model.step == .installing || model.step == .verifying
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Installs the receiver channel on your Roku (not the iPhone app). Takes a few minutes and needs only this iPhone and your Roku remote.")
                    .font(.footnote).foregroundColor(.secondary)
                if let b = model.busy { HStack { ProgressView(); Text(b).font(.subheadline) } }
                if let e = model.error { Text(e).font(.subheadline).foregroundColor(.red) }
                switch model.step {
                case .pick: pick
                case .checking: Text("2. Checking your Roku").font(.headline); card { checkRows }
                case .problem: problem
                case .devMode: devMode
                case .restart: restart
                case .credentials: credentials
                case .installing, .verifying: Text("Please keep this screen open until it finishes.").foregroundColor(.secondary)
                case .done: done
                }
                HStack {
                    Button(model.step == .pick ? "Close" : "Back") { if !model.back() { dismiss() } }.disabled(locked)
                    Spacer()
                    Button("Cancel (keeps your progress)") { model.cancel(); dismiss() }.disabled(locked)
                }.font(.footnote).padding(.top, 8)
            }.padding()
        }
        .scrollDismissesKeyboard(.interactively)
        .toolbar { ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("Done") { typing = false } } }
        .navigationTitle("Install receiver").navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(locked)
        .alert("Replace the sideloaded channel?", isPresented: $model.confirmReplace) {
            Button("Replace it") { model.confirmReplaceAndInstall(); password = "" }
            Button("Don't install", role: .cancel) { model.runCheck() }
        } message: { Text("Your Roku already has a different sideloaded channel. Roku keeps only one, so it will be replaced by the Couchcast Receiver.") }
    }

    // MARK: pieces

    private func card<C: View>(@ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 8) { content() }
            .padding(14).frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 14).fill(Color(white: 0.12)))
    }

    private var checkRows: some View {
        ForEach(model.checks) { c in
            HStack(alignment: .top) {
                Text(c.status == .ok ? "✔" : c.status == .failed ? "✖" : "…").foregroundColor(c.status == .ok ? .green : c.status == .failed ? .red : .secondary)
                Text(c.label + (c.note.isEmpty ? "" : " (\(c.note))")).font(.subheadline)
            }
        }
    }

    private var pick: some View {
        VStack(alignment: .leading, spacing: 12) {
            if model.resumeAvailable {
                card {
                    Text("You have an install in progress").font(.headline)
                    Text("Pick up where you left off for \(model.roomName.isEmpty ? (model.ip ?? "") : model.roomName).").font(.footnote).foregroundColor(.secondary)
                    HStack { Button("Resume") { model.resume() }.buttonStyle(.borderedProminent); Button("Start over") { model.startOver() }.buttonStyle(.bordered) }
                }
            }
            Text("1. Choose your Roku").font(.headline)
            if state.rooms.isEmpty { Text("No saved Rokus yet. Add one by address below, or use Find Rokus on the Rooms tab.").font(.footnote).foregroundColor(.secondary) }
            ForEach(state.rooms) { r in
                Button { model.choose(r) } label: {
                    HStack {
                        Image(systemName: model.ip == r.ip ? "largecircle.fill.circle" : "circle")
                        VStack(alignment: .leading) {
                            Text(r.name).foregroundColor(.primary)
                            Text([r.model, r.ip].filter { !$0.isEmpty }.joined(separator: " • ")).font(.caption).foregroundColor(.secondary)
                        }
                        Spacer()
                    }.padding(8).background(RoundedRectangle(cornerRadius: 12).fill(Color(white: 0.12)))
                }
            }
            TextField("Or enter an address, e.g. 192.168.1.50", text: $manual)
                .textFieldStyle(.roundedBorder).keyboardType(.numbersAndPunctuation).textInputAutocapitalization(.never).autocorrectionDisabled().focused($typing)
            Button("Use this address") { typing = false; model.chooseManual(manual) { state.add($0) }; manual = "" }.buttonStyle(.bordered).disabled(manual.trimmingCharacters(in: .whitespaces).isEmpty)
            Button { model.start() } label: {
                Text(model.ip == nil ? "Choose a Roku first" : "Check \(model.roomName.isEmpty ? (model.ip ?? "") : model.roomName)").frame(maxWidth: .infinity)
            }.buttonStyle(.borderedProminent).disabled(model.ip == nil || model.busy != nil)
            Text("Next we check developer access, then install the receiver.").font(.footnote).foregroundColor(.secondary)
        }
    }

    private var problem: some View {
        VStack(alignment: .leading, spacing: 12) {
            card { checkRows }
            if let p = model.problem { card { Text(p.title).font(.headline).foregroundColor(.red); Text(p.body).font(.subheadline) } }
            Button("Retry") { model.runCheck() }.buttonStyle(.borderedProminent)
        }
    }

    private var devMode: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("3. Turn on Roku Developer Mode").font(.headline)
            Text("Roku only allows this from the physical remote, so the app can't do it for you. Use your Roku remote (the app's remote won't work for this step).").font(.footnote).foregroundColor(.secondary)
            card {
                Text("On the TV").font(.subheadline).foregroundColor(.secondary)
                ForEach(Array(["Press Home 3 times, Up 2 times, then Right, Left, Right, Left, Right.",
                               "The Developer Settings screen appears. Choose “Enable installer and restart”.",
                               "Accept the license agreement.",
                               "Choose a developer password and write it down. You'll type it in this app. It is not your Roku account password.",
                               "Choose “Set password and reboot”. The Roku restarts (about a minute)."].enumerated()), id: \.offset) { i, t in
                    Text("\(i + 1). \(t)").font(.subheadline)
                }
            }
            if !model.checks.isEmpty { card { Text("What the last check found").font(.subheadline).foregroundColor(.secondary); checkRows } }
            Text("Already did this earlier? Tap “Check again”.").font(.footnote).foregroundColor(.secondary)
            Button { model.passwordSet() } label: { Text("I set the password. The Roku is restarting").frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent)
            Button { model.runCheck() } label: { Text("Check again").frame(maxWidth: .infinity) }.buttonStyle(.bordered)
        }
    }

    private var restart: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("4. Waiting for your Roku to restart").font(.headline)
            card {
                HStack { ProgressView(); Text("Reconnecting… \(model.restartWaitSeconds)s") }
                Text("This screen keeps checking automatically. You can leave and come back later.").font(.footnote).foregroundColor(.secondary)
            }
            if !model.checks.isEmpty { card { checkRows } }
            Button("Check again") { model.runCheck() }.buttonStyle(.bordered)
            Button("Show the Developer Mode steps again") { _ = model.back() }.font(.footnote)
        }
    }

    private var credentials: some View {
        let who = model.roomName.isEmpty ? (model.ip ?? "") : model.roomName
        return VStack(alignment: .leading, spacing: 12) {
            Text("5. Install the receiver").font(.headline)
            Text("Developer Mode is ready on \(who). Roku only keeps one sideloaded channel; installing replaces any sideloaded channel already there.").font(.footnote).foregroundColor(.secondary)
            if model.receiver == .outdated { Text("An older Couchcast Receiver (\(model.installedVersion ?? "unknown version")) is installed. This will update it to \(model.bundledVersion).").font(.footnote).foregroundColor(.secondary) }
            if model.receiver == .otherDevApp { Text("A different sideloaded channel is on this Roku. Installing will replace it.").font(.footnote).foregroundColor(.red) }
            if model.hasSavedPassword {
                Button { model.installWithSavedPassword() } label: { Text("Install using saved password").frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent).disabled(model.busy != nil)
                Button("Forget saved password") { model.forgetSavedPassword() }.font(.footnote)
                Text("Or enter it again:").font(.footnote).foregroundColor(.secondary)
            }
            HStack {
                Group {
                    if showPassword { TextField("Developer password (username: rokudev)", text: $password) }
                    else { SecureField("Developer password (username: rokudev)", text: $password) }
                }.textFieldStyle(.roundedBorder).textInputAutocapitalization(.never).autocorrectionDisabled().focused($typing)
                    .submitLabel(.go).onSubmit { typing = false }
                Button(showPassword ? "Hide" : "Show") { showPassword.toggle() }
            }
            Toggle("Remember on this iPhone (Keychain)", isOn: $remember).font(.subheadline)
            Button {
                typing = false; model.install(password, remember: remember); if !(model.receiver == .otherDevApp) { password = "" }
            } label: { Text(model.receiver == .outdated ? "Update receiver" : "Install receiver").frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent).disabled(model.busy != nil || password.trimmingCharacters(in: .whitespaces).isEmpty)
            Button("Check again") { model.runCheck() }.buttonStyle(.bordered).disabled(model.busy != nil)
        }
    }

    private var done: some View {
        VStack(alignment: .leading, spacing: 12) {
            card {
                Text("✔ Receiver installed and confirmed").font(.headline).foregroundColor(.green)
                Text("The Roku lists “Couchcast Receiver” \(model.installedVersion.map { "(version \($0)) " } ?? "")as a sideloaded channel.").font(.subheadline)
                Text("What we have not tested: playing a video through the receiver. Try that from the Play tab.").font(.footnote).foregroundColor(.secondary)
            }
            if let n = model.testNote { Text(n).font(.footnote).foregroundColor(.secondary) }
            Button { model.launchReceiver() } label: { Text("Launch the receiver on the TV").frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent)
            Button { model.runCheck() } label: { Text("Verify again").frame(maxWidth: .infinity) }.buttonStyle(.bordered)
            Button { model.reinstall() } label: { Text("Reinstall / update receiver").frame(maxWidth: .infinity) }.buttonStyle(.bordered)
        }
    }
}

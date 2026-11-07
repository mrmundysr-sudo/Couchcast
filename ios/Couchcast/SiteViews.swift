import SwiftUI
import CouchcastCore

struct MirrorView: View {
    let roomName: String
    let back: () -> Void
    @State private var state: MirrorState = MirrorStatus.fromCaptured(UIScreen.main.isCaptured)

    var body: some View {
        let g = MirroringGuide.forRoku()
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(g.headline).font(.title3)
                Text("TV: \(roomName)").font(.footnote).foregroundColor(.secondary)
                Text("Most Rokus support this, but it depends on the model. The first TV step shows whether yours does.").font(.footnote).foregroundColor(.secondary)
                ForEach(Array(g.steps.enumerated()), id: \.offset) { i, s in
                    VStack(alignment: .leading, spacing: 2) {
                        Text("\(i + 1). \(s.where)").font(.caption).foregroundColor(.secondary)
                        Text(s.text).font(.subheadline)
                    }
                }
                Text("iOS only lets Control Center start mirroring, so Couchcast can't open it for you.").font(.footnote).foregroundColor(.secondary)
                Text(MirrorStatus.message(state)).font(.subheadline).foregroundColor(state == .connected ? .green : .secondary)
                if !g.caveat.isEmpty { Text(g.caveat).font(.footnote).foregroundColor(.secondary) }
                Button("Back") { back() }.buttonStyle(.bordered).frame(maxWidth: .infinity)
            }.padding()
        }
        .onReceive(NotificationCenter.default.publisher(for: UIScreen.capturedDidChangeNotification)) { _ in
            state = MirrorStatus.fromCaptured(UIScreen.main.isCaptured)
        }
    }
}

struct SitesView: View {
    @ObservedObject var model: PlayModel
    let startUrl: String?
    let signIn: (String) -> Void
    let back: () -> Void
    @State private var url = ""
    @State private var confirm = false
    @FocusState private var typing: Bool

    var body: some View {
        let problem = url.trimmingCharacters(in: .whitespaces).isEmpty ? nil : SiteSession.startProblem(url)
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Sign in to a site").font(.title3)
                Text("Some sites only show their video to signed-in members. You sign in on the site's own page, so Couchcast never sees or stores your password. The login stays in this app's private browser on this iPhone, and is used only if you choose the phone relay for that site. It is never sent to the Roku. Ordinary public videos don't need this.")
                    .font(.footnote).foregroundColor(.secondary)
                TextField("Site's sign-in page (https://…)", text: $url)
                    .textFieldStyle(.roundedBorder).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled().focused($typing)
                if let p = problem { Text(p).font(.footnote).foregroundColor(.red) }
                Button { typing = false; signIn(url.trimmingCharacters(in: .whitespaces)) } label: { Text("Open sign-in browser").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent).disabled(url.trimmingCharacters(in: .whitespaces).isEmpty || problem != nil)
                Text("Signed-in sites").font(.headline).padding(.top, 8)
                if model.sites.isEmpty { Text("None yet.").font(.footnote).foregroundColor(.secondary) }
                ForEach(model.sites, id: \.self) { host in
                    HStack { Text(host); Spacer(); Button("Log out") { model.logOut(host) } }
                }
                Button("Clear all sign-in data") { confirm = true }.buttonStyle(.bordered).frame(maxWidth: .infinity)
                Button("Back") { back() }.buttonStyle(.bordered).frame(maxWidth: .infinity)
            }.padding()
        }
        .scrollDismissesKeyboard(.interactively)
        .onAppear { if url.isEmpty, let s = startUrl, s.hasPrefix("https://") { url = s } }
        .alert("Clear all sign-in data?", isPresented: $confirm) {
            Button("Clear everything", role: .destructive) { model.clearAllSites() }
            Button("Cancel", role: .cancel) {}
        } message: { Text("This logs you out of every site in Couchcast's browser and deletes its cookies and stored site data.") }
    }
}

struct SignInScreen: View {
    let startUrl: String
    let back: () -> Void
    let done: (String?) -> Void
    @State private var host: String
    @State private var secure: Bool

    init(startUrl: String, back: @escaping () -> Void, done: @escaping (String?) -> Void) {
        self.startUrl = startUrl; self.back = back; self.done = done
        _host = State(initialValue: SiteSession.hostOf(startUrl) ?? "")
        _secure = State(initialValue: startUrl.hasPrefix("https://"))
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button("Back") { back() }
                Text(secure ? "🔒 \(host)" : "⚠ \(host) (not secure)").font(.subheadline).foregroundColor(secure ? .green : .red).lineLimit(1)
                Spacer()
                Button("Done") { done(host.isEmpty ? nil : host) }
            }.padding(.horizontal).padding(.vertical, 8)
            SignInBrowser(startUrl: startUrl, host: $host, secure: $secure)
        }
    }
}

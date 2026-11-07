import SwiftUI
import WebKit
import CouchcastCore

/// The in-app browser's cookie store. Cookies stay on the iPhone and are never shown, logged or sent to the Roku.
struct WebCookieSource: CookieSource {
    @MainActor static func matching(_ url: String) async -> [HTTPCookie] {
        guard let u = URL(string: url), let host = u.host?.lowercased() else { return [] }
        let all = await WKWebsiteDataStore.default().httpCookieStore.allCookies()
        return all.filter { c in
            var d = c.domain.lowercased()
            let dotted = d.hasPrefix(".")
            if dotted { d.removeFirst() }
            let hostOK = dotted ? (host == d || host.hasSuffix("." + d)) : host == d
            let pathOK = u.path.isEmpty ? c.path == "/" : (u.path.hasPrefix(c.path) || c.path == "/")
            let secureOK = !c.isSecure || u.scheme == "https"
            return hostOK && pathOK && secureOK && (c.expiresDate.map { $0 > Date() } ?? true)
        }
    }

    func cookies(for url: String) async -> String? {
        let found = await WebCookieSource.matching(url)
        if found.isEmpty { return nil }
        return found.map { "\($0.name)=\($0.value)" }.joined(separator: "; ")
    }
}

/// Which sites the user signed in to inside Couchcast. Hostnames only: never passwords, cookies or tokens.
struct SignInStore {
    private let key = "signin.hosts"
    func hosts() -> [String] { (UserDefaults.standard.stringArray(forKey: key) ?? []).sorted() }
    func add(_ host: String) { UserDefaults.standard.set(Array(Set(hosts() + [host])), forKey: key) }
    func remove(_ host: String) { UserDefaults.standard.set(hosts().filter { $0 != host }, forKey: key) }
    func clear() { UserDefaults.standard.removeObject(forKey: key) }
}

/// Clear-site-data controls for the in-app browser.
@MainActor
enum SiteSessions {
    static func hasSession(_ url: String) async -> Bool { !(await WebCookieSource.matching(url)).isEmpty }

    /// Log out of one site: delete its cookies and website data. Best effort.
    static func clearSite(_ host: String) async {
        let store = WKWebsiteDataStore.default()
        let domains = SiteSession.cookieDomains(host).map { $0.hasPrefix(".") ? String($0.dropFirst()) : $0 }
        let cookies = await store.httpCookieStore.allCookies()
        for c in cookies {
            let d = c.domain.hasPrefix(".") ? String(c.domain.dropFirst()) : c.domain
            if domains.contains(d.lowercased()) { await store.httpCookieStore.deleteCookie(c) }
        }
        let types = WKWebsiteDataStore.allWebsiteDataTypes()
        let records = await store.dataRecords(ofTypes: types)
        let mine = records.filter { r in domains.contains { r.displayName.lowercased() == $0 || r.displayName.lowercased().hasSuffix("." + $0) } }
        await store.removeData(ofTypes: types, for: mine)
    }

    /// Remove every cookie and all website data held by the in-app browser.
    static func clearAll() async {
        let store = WKWebsiteDataStore.default()
        await store.removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: Date(timeIntervalSince1970: 0))
    }
}

/// Videos found on a page, plus what a browser would have sent when asking for them.
struct ScanResult {
    var urls: [String]
    var pageUrl: String
    var userAgent: String
}

/// Opens a web page in a hidden WKWebView and collects direct video links two ways: the resources the page's player
/// actually requested (.m3u8/.mpd/.mp4) and the page's own video tags. It does not look at, bypass or decrypt copy-protected video.
@MainActor
final class WebPageScanner: NSObject {
    private static let script = """
    (function(){const out=[];const add=u=>{try{u=new URL(u,location.href).href;if(/^https?:/i.test(u)&&!out.includes(u))out.push(u)}catch(e){}};
    document.querySelectorAll('video,video source,source,audio').forEach(e=>add(e.currentSrc||e.src||e.getAttribute('src')));
    document.querySelectorAll('meta[property="og:video"],meta[property="og:video:url"],meta[property="og:video:secure_url"],meta[name="twitter:player:stream"]').forEach(e=>add(e.content));
    document.querySelectorAll('a[href]').forEach(e=>{if(/\\.(m3u8|mp4|mpd)(?:[?#]|$)/i.test(e.href))add(e.href)});
    try{performance.getEntriesByType('resource').forEach(e=>{if(/\\.(m3u8|mpd|mp4)(?:[?#]|$)/i.test(e.name))add(e.name)})}catch(e){}
    try{(document.documentElement.innerHTML.replace(/\\\\\\//g,'/').match(/https?:\\/\\/[^"'\\s<>\\\\]+?\\.(?:m3u8|mpd|mp4)[^"'\\s<>\\\\]*/gi)||[]).forEach(add)}catch(e){}
    return JSON.stringify(out)})()
    """

    func scan(_ pageUrl: String, totalSeconds: Double = 15, settleSeconds: Double = 3) async -> ScanResult {
        let cfg = WKWebViewConfiguration()
        cfg.mediaTypesRequiringUserActionForPlayback = []   // lets a page's player start so its stream URL is requested
        cfg.allowsInlineMediaPlayback = true
        let web = WKWebView(frame: CGRect(x: 0, y: 0, width: 360, height: 640), configuration: cfg)
        web.alpha = 0.01
        let host = UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first
        host?.addSubview(web)
        defer { web.stopLoading(); web.removeFromSuperview() }
        if let u = URL(string: pageUrl) { web.load(URLRequest(url: u)) }

        let start = Date()
        var firstFound: Date?
        var found: [String] = []
        while Date().timeIntervalSince(start) < totalSeconds && !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            if let raw = try? await web.evaluateJavaScript(Self.script) as? String,
               let data = raw.data(using: .utf8), let arr = try? JSONSerialization.jsonObject(with: data) as? [String] {
                found = MediaLinks.clean(arr)
            }
            if !found.isEmpty && firstFound == nil { firstFound = Date() }
            if let f = firstFound, Date().timeIntervalSince(f) >= settleSeconds { break }
        }
        let ua = (try? await web.evaluateJavaScript("navigator.userAgent") as? String) ?? PageScanner.userAgent
        let finalUrl = web.url.map { $0.absoluteString }.flatMap { $0.hasPrefix("http") ? $0 : nil } ?? pageUrl
        return ScanResult(urls: found, pageUrl: finalUrl, userAgent: ua)
    }
}

/// The sign-in browser: only web pages, https preferred, no scripts/files/app links, passwords go straight to the site.
struct SignInBrowser: UIViewRepresentable {
    let startUrl: String
    @Binding var host: String
    @Binding var secure: Bool

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> WKWebView {
        let cfg = WKWebViewConfiguration()
        cfg.websiteDataStore = WKWebsiteDataStore.default()
        let web = WKWebView(frame: .zero, configuration: cfg)
        web.navigationDelegate = context.coordinator
        if let u = URL(string: startUrl) { web.load(URLRequest(url: u)) }
        return web
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    static func dismantleUIView(_ uiView: WKWebView, coordinator: Coordinator) { uiView.stopLoading() }

    final class Coordinator: NSObject, WKNavigationDelegate {
        let parent: SignInBrowser
        init(_ p: SignInBrowser) { parent = p }

        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            let url = action.request.url?.absoluteString ?? ""
            decisionHandler(SiteSession.navigationAllowed(url) ? .allow : .cancel)
        }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            guard let url = webView.url?.absoluteString else { return }
            if let h = SiteSession.hostOf(url) { parent.host = h }
            parent.secure = url.hasPrefix("https://")
        }
    }
}

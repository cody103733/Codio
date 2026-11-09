import UIKit
import WebKit

/// The Codio web UI runs hidden in a WKWebView as the app's engine: it keeps the account, addons and library
/// (Stremio core), finds sources and runs Smart auto-play and its source fallback. The native screens drive it
/// through the same hooks the Android apps use (codio-android.js), and VLC plays what it picks.
@MainActor
final class Engine: NSObject, ObservableObject, WKScriptMessageHandler, WKNavigationDelegate, WKUIDelegate {
    enum Overlay: Equatable {
        case none
        case loading
        case player
    }

    struct SourcesRequest: Identifiable {
        let meta: Meta
        let videoId: String
        var id: String { meta.id + "|" + videoId }
    }

    let store = Store()
    let playerState = PlayerState()
    let videoView = UIView()
    private(set) var webView: WKWebView!
    private(set) var shell: VlcShell!

    @Published var overlay: Overlay = .none
    @Published var loadingText = "Finding the best source…"
    @Published var sourcesRequest: SourcesRequest?
    @Published var toast: String?
    @Published private(set) var playMeta: Meta?
    @Published private(set) var playVideo: String?

    /// A web page (player, source search) is driving instead of the native screens.
    private var webShown = false
    private var sawPlayer = false
    /// The "finding your stream" screen is up.
    private var playOverlay = false
    private var prefetchRoute: String?
    private var revealTask: Task<Void, Never>?
    private var toastTask: Task<Void, Never>?
    private var storageTask: Task<Void, Never>?

    static let version = (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "?"

    override init() {
        super.init()
        videoView.backgroundColor = .black

        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        let controller = WKUserContentController()
        controller.add(WeakHandler(self), name: "codio")
        LocalServer.shared.shim = Engine.shimSource() // goes into index.html's <head>, before the web UI's scripts
        config.userContentController = controller

        webView = WKWebView(frame: CGRect(x: 0, y: 0, width: 1280, height: 720), configuration: config)
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        webView.alpha = 0 // never seen: the native screens and VLC are what the viewer sees
        webView.isUserInteractionEnabled = false
        webView.navigationDelegate = self
        webView.uiDelegate = self
        if #available(iOS 16.4, *) { webView.isInspectable = true }

        shell = VlcShell(view: videoView)
        shell.signal = { [weak self] name, arg in self?.playerSignal(name, arg) }
        shell.onActiveChanged = { playing in UIApplication.shared.isIdleTimerDisabled = playing }
        shell.onLoad = { [weak self] in self?.playerState.reset() }
    }

    private static func shimSource() -> String {
        let prelude = """
        (function () {
            var h = window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.codio;
            if (!h) return;
            var call = function (fn) {
                return function () { h.postMessage({ fn: fn, args: Array.prototype.slice.call(arguments) }); };
            };
            window.__codioNativeUi = true;
            window.__codioHasMpv = true;
            window.__codioIos = true;
            window.CodioNative = {
                postMessage: call('postMessage'), streams: call('streams'), userPick: call('userPick'),
                coreEvent: call('coreEvent'), playStatus: call('playStatus'), route: call('route'),
                requestExit: call('requestExit'), exitApp: call('exitApp'), showKeyboard: call('showKeyboard'),
                profileChanged: call('profileChanged'),
                info: function () { return JSON.stringify({ version: 'ios', mpv: true, engine: 'vlc', hwdec: 'videotoolbox' }); }
            };
            // First run: Smart auto-play on with sources hidden, bitrate cap 70 Mbit/s (changeable in Settings).
            try {
                var ap = JSON.parse(localStorage.getItem('autoPlaySettings') || 'null');
                if (!ap) ap = { enabled: true, hideSources: true };
                if (typeof ap.maxBitrateMbps !== 'number') ap.maxBitrateMbps = 70;
                localStorage.setItem('autoPlaySettings', JSON.stringify(ap));
            } catch (e) {}
        })();
        """
        var shim = ""
        if let url = Bundle.main.url(forResource: "codio-engine", withExtension: "js"),
           let js = try? String(contentsOf: url, encoding: .utf8) {
            shim = js
        }
        return prelude + "\n" + shim
    }

    func start() {
        LocalServer.shared.ensureRunning()
        webView.load(URLRequest(url: LocalServer.baseURL))
    }

    // MARK: - JavaScript

    private func js(_ code: String, _ done: ((Any?) -> Void)? = nil) {
        webView.evaluateJavaScript(code) { value, _ in done?(value) }
    }

    func readStorage(after seconds: Double = 0) {
        storageTask?.cancel()
        storageTask = Task { [weak self] in
            if seconds > 0 { try? await Task.sleep(nanoseconds: UInt64(seconds * 1e9)) }
            guard let self = self, !Task.isCancelled else { return }
            self.js("window.__codioNativeStorage ? window.__codioNativeStorage() : null") { [weak self] value in
                guard let self = self, let s = value as? String, let o = parseJSON(s) as? JSON else { return }
                self.store.update(storage: o)
            }
        }
    }

    // MARK: - page -> app

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any], let fn = body["fn"] as? String else { return }
        let args = (body["args"] as? [Any]) ?? []
        let a0 = args.first as? String
        switch fn {
        case "postMessage":
            if let s = a0 { shellMessage(s) }
        case "streams":
            if let s = a0, let o = parseJSON(s) as? JSON { store.sources = Sources.parse(o) }
        case "userPick":
            shell.userPickAt = Date()
        case "coreEvent":
            coreEvent(a0 ?? "", args.count > 1 ? args[1] as? String : nil)
        case "profileChanged":
            readStorage(after: 0.3)
        case "playStatus":
            playStatus(a0 ?? "none", args.count > 1 ? (args[1] as? String ?? "") : "")
        case "route":
            if let h = a0 { webRoute(h) }
        default:
            break // requestExit / exitApp / showKeyboard: nothing to do on iPhone
        }
    }

    /// The Windows shell protocol, for the web player (Stremio's ShellVideo).
    private func shellMessage(_ text: String) {
        guard let obj = parseJSON(text) as? JSON else { return }
        let type = jint(obj, "type") ?? 0
        if type == 3 {
            func prop(_ n: String, _ v: String) -> [String] { ["", n, "", v] }
            let props: [Any] = [[String](), prop("shellVersion", "5.0.27"), prop("gpuVideoProcessing", "false"),
                                prop("cacheDirectoryPicker", "false"), prop("streamingServerUrl", ""),
                                prop("nativeInterfaceScale", "false"), prop("nativeAssSubtitles", "true")]
            sendShell(["id": 0, "object": "transport", "type": 3,
                       "data": ["transport": ["properties": props, "signals": [Any](), "methods": [["onEvent", ""]]]]])
            // The window is "visible" (the web UI pauses playback when it thinks the window was closed).
            sendShell(["id": 1, "object": "transport", "type": 1,
                       "args": ["win-visibility-changed", ["visible": true, "visibility": 1, "isFullscreen": true]]])
            return
        }
        guard type == 6, let args = obj["args"] as? [Any], let method = args.first as? String else { return }
        shell.handle(method: method, params: args.count > 1 ? args[1] : nil)
    }

    private func sendShell(_ message: JSON) {
        let json = toJSONString(message)
        js("window.__codioShellMessage && window.__codioShellMessage(\(jsQuote(json)))")
    }

    /// VLC -> the web player, and to the native player controls.
    private func playerSignal(_ name: String, _ arg: Any) {
        sendShell(["id": 1, "object": "transport", "type": 1, "args": [name, arg]])
        guard let o = arg as? JSON else { return }
        switch name {
        case "mpv-prop-change":
            if let n = jstr(o, "name"), let d = o["data"] { playerState.prop(n, d) }
            if jstr(o, "name") == "time-pos", let t = o["data"] as? NSNumber, t.doubleValue > 0.3, playOverlay {
                revealPlayer() // the picture is moving: take the loading screen away
            }
        case "mpv-event-ended":
            if let err = o["error"] as? JSON, jbool(err, "critical") == true {
                playerState.error = jstr(err, "message") ?? "This video could not be played."
            }
        default:
            break
        }
    }

    private func coreEvent(_ name: String, _ message: String?) {
        if name.hasPrefix("Error:") {
            let ev = String(name.dropFirst(6))
            if !["UserPulledFromAPI", "LibrarySyncWithAPIPlanned", "UserAddonsLocked"].contains(ev) {
                show(toast: (message?.isEmpty == false ? message! : "Something went wrong"))
            }
            return
        }
        switch name {
        case "AddonInstalled": show(toast: "Addon installed")
        case "AddonUninstalled": show(toast: "Addon removed")
        case "UserAuthenticated": show(toast: "Signed in")
        case "UserLoggedOut": show(toast: "Signed out")
        default: break
        }
        readStorage(after: 0.8)
    }

    func show(toast text: String) {
        toast = text
        toastTask?.cancel()
        toastTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            if !Task.isCancelled { self?.toast = nil }
        }
    }

    // MARK: - native screens -> engine

    private static func detailRoute(_ m: Meta, _ vid: String?) -> String {
        "#/detail/" + urlEnc(m.type) + "/" + urlEnc(m.id) + "/" + urlEnc(vid ?? m.id)
    }

    /// Play with Smart auto-play: Codio's loading screen stays up while the engine searches and VLC starts.
    func play(_ m: Meta, _ vid: String?) {
        playMeta = m
        playVideo = vid ?? m.id
        loadingText = "Finding the best source…"
        playOverlay = true
        overlay = .loading
        showWeb(Engine.detailRoute(m, vid) + "?autoplay=1")
    }

    /// Play this source from the native source list.
    func play(_ m: Meta, _ vid: String, source: Source) {
        playMeta = m
        playVideo = vid
        loadingText = "Starting" + (source.qualityLabel.map { " in " + $0 } ?? "") + "…"
        playOverlay = true
        overlay = .loading
        showWeb(source.href)
    }

    func cancelPlay() {
        playOverlay = false
        showNative()
    }

    /// Load this movie/episode's sources in the background (the movie page runs hidden).
    func prefetch(_ m: Meta, _ vid: String?) {
        guard !webShown else { return }
        let route = Engine.detailRoute(m, vid)
        guard route != prefetchRoute else { return }
        prefetchRoute = route
        js("window.__codioNativeGo && window.__codioNativeGo(\(jsQuote(route + "?prefetch=1")))")
    }

    func prefetchEnd() {
        guard prefetchRoute != nil, !webShown else { return }
        prefetchRoute = nil
        park()
    }

    /// Leave the player (back to the movie page).
    func stopPlayer() {
        js("window.__codioBack ? window.__codioBack() : history.back()")
        // If the page doesn't react, still return to the native screens.
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            guard let self = self, self.overlay == .player else { return }
            self.shell.handle(method: "mpv-command", params: ["stop"])
            self.showNative()
        }
    }

    /// A command for the video player (mpv-set-prop / mpv-command with their params).
    func player(_ method: String, _ params: [Any]) {
        shell.handle(method: method, params: params)
    }

    func userPicked() { shell.userPickAt = Date() }

    /// Send an action to Codio's core, e.g. ["action": "Ctx", "args": ["action": "AddToLibrary", "args": ...]].
    func ctx(_ action: String, _ args: Any? = nil) {
        var inner: JSON = ["action": action]
        if let a = args { inner["args"] = a }
        let json = toJSONString(["action": "Ctx", "args": inner] as JSON)
        js("window.__codioDispatch ? window.__codioDispatch(\(jsQuote(json))) : 'no-shim'") { value in
            NSLog("Codio action \(action): \(value ?? "nil")")
        }
        readStorage(after: 1.2)
    }

    func saveAutoPlay() {
        let json = toJSONString(store.autoPlay)
        js("window.__codioNativeSetAutoPlay && window.__codioNativeSetAutoPlay(\(jsQuote(json)))")
    }

    // MARK: - web routes

    private func park() {
        js("window.__codioNativePark && window.__codioNativePark()")
    }

    private func showWeb(_ route: String) {
        webShown = true
        sawPlayer = false
        prefetchRoute = nil
        js("window.__codioNativeGo && window.__codioNativeGo(\(jsQuote(route)))")
    }

    private func showNative() {
        webShown = false
        playOverlay = false
        prefetchRoute = nil
        sawPlayer = false
        revealTask?.cancel()
        park()
        overlay = .none
        // Progress is saved a moment after the player closes.
        readStorage(after: 1.5)
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            self?.readStorage()
        }
    }

    /// Where the web UI went: once playback has ended, or it reaches a page the native screens show, take over.
    private func webRoute(_ hash: String) {
        if !webShown {
            // Nothing may play behind the native screens (e.g. an auto-play the viewer backed out of).
            if hash.hasPrefix("#/player") || (hash.hasPrefix("#/detail/") && prefetchRoute == nil) { park() }
            return
        }
        if hash.hasPrefix("#/player") {
            sawPlayer = true
            let parts = (hash.split(separator: "?").first.map(String.init) ?? hash).components(separatedBy: "/")
            if parts.count >= 8 {
                let type = parts[5].removingPercentEncoding ?? parts[5]
                let id = parts[6].removingPercentEncoding ?? parts[6]
                let vid = parts[7].removingPercentEncoding ?? parts[7]
                if playMeta?.id != id { playMeta = Meta(id: id, type: type) }
                playVideo = vid
            }
            if playOverlay {
                loadingText = "Starting…"
                // The player shows its own message if the video never starts.
                revealTask?.cancel()
                revealTask = Task { [weak self] in
                    try? await Task.sleep(nanoseconds: 25_000_000_000)
                    if !Task.isCancelled { self?.revealPlayer() }
                }
            } else {
                overlay = .player // e.g. the next episode started by itself
            }
            return
        }
        let nativePage = hash.hasPrefix("#/codio-native") || hash == "#/" || hash.hasPrefix("#/?")
            || hash.hasPrefix("#/discover") || hash.hasPrefix("#/search") || hash.hasPrefix("#/library")
            || (hash.hasPrefix("#/detail/") && !hash.contains("autoplay=1") && !hash.contains("sources=1"))
        if sawPlayer || nativePage { showNative() }
    }

    private func revealPlayer() {
        revealTask?.cancel()
        guard playOverlay, webShown else { return }
        playOverlay = false
        overlay = .player
    }

    private func playStatus(_ kind: String, _ text: String) {
        guard playOverlay else { return }
        if kind == "manual" || kind == "list" {
            // Auto-play is off (or found nothing that fits): the viewer picks from Codio's own source list.
            let m = playMeta, vid = playVideo
            showNative()
            if let m = m { sourcesRequest = SourcesRequest(meta: m, videoId: vid ?? m.id) }
            return
        }
        if kind == "failed" {
            loadingText = text.isEmpty ? "No source could be played." : text
            return
        }
        if kind != "none", !text.isEmpty { loadingText = text }
    }

    // MARK: - navigation

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        park()
        readStorage(after: 1.0)
        readStorage(after: 4.0)
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url else { return decisionHandler(.allow) }
        let local = url.host == "127.0.0.1" || ["about", "blob", "data"].contains(url.scheme ?? "")
        if !local, navigationAction.targetFrame?.isMainFrame == true {
            UIApplication.shared.open(url)
            return decisionHandler(.cancel)
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let url = navigationAction.request.url { UIApplication.shared.open(url) }
        return nil
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        // iOS can stop the page's process to save memory while Codio is in the background.
        start()
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        if (error as NSError).code == NSURLErrorCancelled { return }
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            self?.start()
        }
    }
}

/// WKUserContentController keeps its handlers alive; this keeps it from keeping the engine alive.
final class WeakHandler: NSObject, WKScriptMessageHandler {
    weak var target: WKScriptMessageHandler?
    init(_ target: WKScriptMessageHandler) { self.target = target }
    func userContentController(_ c: WKUserContentController, didReceive message: WKScriptMessage) {
        target?.userContentController(c, didReceive: message)
    }
}

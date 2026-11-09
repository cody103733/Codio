import UIKit
import WebKit

/// The VLC video sits at the back; the Codio web page sits on top with a see-through background.
/// The page talks to the player through window.chrome.webview, the same interface Stremio's Windows
/// shell gives it (see codio-ios.js), so the Windows build of the Codio web UI runs unchanged.
final class MainViewController: UIViewController, WKScriptMessageHandler, WKNavigationDelegate, WKUIDelegate {
    private let videoView = UIView()
    private var webView: WKWebView!
    private var shell: VlcShell!

    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .landscape }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        videoView.backgroundColor = .black
        videoView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(videoView)

        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        let controller = WKUserContentController()
        controller.add(WeakHandler(self), name: "codio")
        if let url = Bundle.main.url(forResource: "codio-ios", withExtension: "js"),
           let js = try? String(contentsOf: url, encoding: .utf8) {
            controller.addUserScript(WKUserScript(source: js, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        }
        config.userContentController = controller

        webView = WKWebView(frame: .zero, configuration: config)
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.scrollView.bounces = false
        webView.allowsBackForwardNavigationGestures = true // swipe from the left edge = back
        webView.navigationDelegate = self
        webView.uiDelegate = self
        if #available(iOS 16.4, *) { webView.isInspectable = true }
        webView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(webView)

        let safe = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            videoView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            videoView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            videoView.topAnchor.constraint(equalTo: view.topAnchor),
            videoView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            // Keep the page clear of the camera notch / Dynamic Island; the video still fills the screen.
            webView.leadingAnchor.constraint(equalTo: safe.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: safe.trailingAnchor),
            webView.topAnchor.constraint(equalTo: view.topAnchor),
            webView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])

        shell = VlcShell(view: videoView)
        shell.signal = { [weak self] name, arg in self?.sendSignal(name, arg) }
        shell.onActiveChanged = { playing in UIApplication.shared.isIdleTimerDisabled = playing }

        load()
    }

    private func load() {
        LocalServer.shared.ensureRunning()
        webView.load(URLRequest(url: LocalServer.baseURL))
    }

    // MARK: - page -> app

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let text = message.body as? String,
              let obj = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any] else { return }
        let type = obj["type"] as? Int ?? 0
        if type == 3 { // INIT: the page asks what this shell can do
            sendRaw(handshake())
            return
        }
        guard type == 6, let args = obj["args"] as? [Any], let method = args.first as? String else { return }
        let params: Any? = args.count > 1 ? args[1] : nil
        switch method {
        case "open-external":
            if let s = params as? String, let url = URL(string: s) { UIApplication.shared.open(url) }
        case "quit", "app-ready", "win-set-visibility", "toggle-fullscreen", "app-error", "autoupdater-notif-clicked":
            break
        default:
            shell.handle(method: method, params: params)
        }
    }

    private func handshake() -> [String: Any] {
        func prop(_ name: String, _ value: String) -> [String] { ["", name, "", value] }
        let props: [Any] = [
            [] as [String],
            prop("shellVersion", "5.0.27"),
            prop("gpuVideoProcessing", "false"),
            prop("cacheDirectoryPicker", "false"),
            prop("streamingServerUrl", ""),
            prop("nativeInterfaceScale", "false"),
            prop("nativeAssSubtitles", "true"),
        ]
        return ["id": 0, "object": "transport", "type": 3,
                "data": ["transport": ["properties": props, "signals": [] as [Any], "methods": [["onEvent", ""]]]]]
    }

    private func sendSignal(_ name: String, _ arg: Any) {
        sendRaw(["id": 1, "object": "transport", "type": 1, "args": [name, arg]])
    }

    /// Hands one shell message to the page (as a JSON string, the way WebView2 delivers it).
    private func sendRaw(_ message: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: message),
              let json = String(data: data, encoding: .utf8),
              let literal = try? JSONSerialization.data(withJSONObject: [json]),
              let wrapped = String(data: literal, encoding: .utf8) else { return }
        // wrapped is ["..."]; [0] gives the page the string itself.
        webView.evaluateJavaScript("window.__codioShellMessage && window.__codioShellMessage(\(wrapped)[0])", completionHandler: nil)
    }

    // MARK: - navigation

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url else { return decisionHandler(.allow) }
        let local = url.host == "127.0.0.1" || ["about", "blob", "data"].contains(url.scheme ?? "")
        if !local, navigationAction.targetFrame?.isMainFrame == true {
            // Links that leave Codio (sign-in pages, addon sites, IMDb...) open in Safari.
            UIApplication.shared.open(url)
            return decisionHandler(.cancel)
        }
        decisionHandler(.allow)
    }

    // target=_blank links
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let url = navigationAction.request.url { UIApplication.shared.open(url) }
        return nil
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        // iOS can stop the page's process to save memory while Codio is in the background.
        load()
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        if (error as NSError).code != NSURLErrorCancelled { retrySoon() }
    }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        if (error as NSError).code != NSURLErrorCancelled { retrySoon() }
    }

    private func retrySoon() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
            guard let self = self, !self.webView.isLoading else { return }
            self.load()
        }
    }
}

/// WKUserContentController keeps its handlers alive; this keeps it from keeping the view controller alive.
private final class WeakHandler: NSObject, WKScriptMessageHandler {
    weak var target: WKScriptMessageHandler?
    init(_ target: WKScriptMessageHandler) { self.target = target }
    func userContentController(_ c: WKUserContentController, didReceive message: WKScriptMessage) {
        target?.userContentController(c, didReceive: message)
    }
}

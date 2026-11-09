import AVFoundation
import Combine
import SwiftUI
import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        // Sound plays even with the silent switch on, like any video app.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)

        LocalServer.shared.start()

        let window = UIWindow(frame: UIScreen.main.bounds)
        window.backgroundColor = .black
        window.rootViewController = RootViewController()
        window.makeKeyAndVisible()
        self.window = window
        return true
    }

    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String,
                     completionHandler: @escaping () -> Void) {
        Downloads.shared.backgroundCompletion = completionHandler
    }

    func applicationWillEnterForeground(_ application: UIApplication) {
        // iOS may have closed the listening socket while Codio was in the background.
        LocalServer.shared.ensureRunning()
    }
}

/// VLC's picture at the back, the hidden engine page, and the native SwiftUI screens on top.
/// Browsing works in any orientation; the player turns the phone to landscape.
@MainActor
final class RootViewController: UIViewController {
    private let engine = Engine()
    private var host: UIHostingController<AnyView>!
    private var bag = Set<AnyCancellable>()
    private var landscape = false

    override var prefersStatusBarHidden: Bool { landscape }
    override var prefersHomeIndicatorAutoHidden: Bool { landscape }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        landscape ? .landscape : (UIDevice.current.userInterfaceIdiom == .pad ? .all : .allButUpsideDown)
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        for v in [engine.videoView, engine.webView!] as [UIView] {
            v.frame = view.bounds
            v.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            view.addSubview(v)
        }

        host = UIHostingController(rootView: AnyView(RootView().environmentObject(engine).environmentObject(engine.store)))
        host.view.backgroundColor = .clear
        addChild(host)
        host.view.frame = view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(host.view)
        host.didMove(toParent: self)

        engine.$overlay
            .map { $0 != .none }
            .removeDuplicates()
            .sink { [weak self] on in self?.setLandscape(on) }
            .store(in: &bag)

        engine.start()
    }

    private func setLandscape(_ on: Bool) {
        guard on != landscape else { return }
        landscape = on
        setNeedsStatusBarAppearanceUpdate()
        setNeedsUpdateOfHomeIndicatorAutoHidden()
        if #available(iOS 16.0, *) {
            setNeedsUpdateOfSupportedInterfaceOrientations()
            view.window?.windowScene?.requestGeometryUpdate(.iOS(interfaceOrientations: on ? .landscape : .portrait)) { _ in }
        }
    }
}

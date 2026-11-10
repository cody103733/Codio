import SwiftUI

/// Checks the public SideStore source for a newer Codio build and opens SideStore to install it.
@MainActor
final class Updater: ObservableObject {
    static let shared = Updater()
    static let source = "https://raw.githubusercontent.com/cody103733/codio-releases/main/apps.json"

    struct Available: Equatable {
        let version: String
        let build: Int
        let url: String
    }

    @Published var available: Available?
    @Published var dismissed = false
    private var lastCheck: Date?

    static var currentBuild: Int { Int(Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "") ?? 0 }

    func forceCheck() async {
        lastCheck = nil
        await check()
    }

    func check() async {
        if let l = lastCheck, Date().timeIntervalSince(l) < 3600 { return }
        lastCheck = Date()
        guard let src = try? await Net.json(Self.source + "?t=\(Int(Date().timeIntervalSince1970))") as? JSON,
              let app = (src["apps"] as? [JSON])?.first,
              let v = (app["versions"] as? [JSON])?.first,
              let ver = jstr(v, "version"), let url = jstr(v, "downloadURL") else { return }
        let build = Int(jstr(v, "buildVersion") ?? "") ?? jint(v, "buildVersion") ?? 0
        let newer = build > Self.currentBuild || ver.compare(Engine.version, options: .numeric) == .orderedDescending
        available = newer ? Available(version: ver, build: build, url: url) : nil
    }

    /// SideStore installs over the current app (same bundle), keeping its data.
    func install() {
        guard let a = available else { return }
        let enc = a.url.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? a.url
        if let u = URL(string: "sidestore://install?url=" + enc) {
            UIApplication.shared.open(u) { ok in
                if !ok, let page = URL(string: "https://github.com/cody103733/codio-releases/releases/latest") {
                    UIApplication.shared.open(page)
                }
            }
        }
    }
}

/// The red "Update available" strip at the top of Home.
struct UpdateBanner: View {
    @ObservedObject var updater = Updater.shared

    var body: some View {
        if let a = updater.available, !updater.dismissed {
            HStack(spacing: 10) {
                Image(systemName: "arrow.down.app.fill").font(.title3)
                VStack(alignment: .leading, spacing: 1) {
                    Text("Codio \(a.version) is out").font(.subheadline.weight(.semibold))
                    Text("Tap to update with SideStore").font(.caption).opacity(0.85)
                }
                Spacer()
                Button { updater.dismissed = true } label: {
                    Image(systemName: "xmark").font(.caption.weight(.bold)).padding(8)
                }
            }
            .foregroundColor(.white)
            .padding(.horizontal, 14).padding(.vertical, 10)
            .background(RoundedRectangle(cornerRadius: 8).fill(Theme.red))
            .contentShape(Rectangle())
            .onTapGesture { updater.install() }
            .padding(.horizontal, 16)
        }
    }
}

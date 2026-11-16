import SwiftUI

struct RootView: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store
    /// Launching with "-start_tab 3" opens that tab first (used to take pictures of the screens).
    @State private var tab = UserDefaults.standard.integer(forKey: "start_tab")
    @ObservedObject private var profiles = Profiles.shared
    @ObservedObject private var newEpisodes = NewEpisodes.shared
    @State private var homePath = NavigationPath()

    init() {
        // Codio draws its own tab bar.
        UITabBar.appearance().isHidden = true
    }

    var body: some View {
        ZStack {
            if engine.overlay == .none {
                VStack(spacing: 0) {
                    TabView(selection: $tab) {
                        NavigationStack(path: $homePath) { HomeView().withDetail() }.tag(0)
                        NavigationStack { SearchView().withDetail() }.tag(1)
                        NavigationStack { LibraryView().withDetail() }.tag(2)
                        NavigationStack { SettingsView().noSystemTabBar() }.tag(3)
                    }
                    TabBar(tab: $tab) { if tab == 0 { homePath = NavigationPath() } }
                }
                .ignoresSafeArea(.keyboard)
                .tint(.white)
                .transition(.opacity)
            }
            if engine.overlay == .loading {
                LoadingView().transition(.opacity)
            }
            if engine.overlay == .player {
                PlayerView().environmentObject(engine.playerState)
            }
            if profiles.showPicker, engine.overlay == .none {
                ProfilePicker().transition(.opacity).zIndex(2)
            }
            if engine.overlay == .none, let m = engine.askRating {
                ZStack(alignment: .bottom) {
                    Color.black.opacity(0.5).ignoresSafeArea().onTapGesture { engine.askRating = nil }
                    RateCard(meta: m).padding(.bottom, 70)
                }
                .transition(.opacity)
            }
            if let t = engine.toast {
                VStack { Spacer(); ToastView(text: t).padding(.bottom, 90) }
                    .allowsHitTesting(false)
                    .transition(.opacity)
            }
        }
        .animation(.easeOut(duration: 0.2), value: engine.overlay)
        .preferredColorScheme(.dark)
        .environment(\.font, Theme.font(17))
        // A tapped "New episode" notification opens the show's page on Home.
        .onChange(of: newEpisodes.openShow) { _ in openNotifiedShow() }
        .onChange(of: store.profileLoaded) { _ in openNotifiedShow() }
        .onAppear { openNotifiedShow() }
        .sheet(item: $engine.sourcesRequest) { r in
            SourcesView(meta: r.meta, videoId: r.videoId)
                .environmentObject(engine)
                .environmentObject(engine.store)
        }
    }
}

private extension RootView {
    /// Opens the show a notification was tapped for, once the library (its name and poster) has been read.
    func openNotifiedShow() {
        guard let id = newEpisodes.openShow, store.profileLoaded, engine.overlay == .none else { return }
        newEpisodes.openShow = nil
        let meta = store.entry(id)?.meta ?? Meta.parse(["id": id, "type": "series", "name": newEpisodes.alert(id)?.name ?? ""])
        tab = 0
        homePath = NavigationPath()
        homePath.append(meta)
    }
}

private extension View {
    func withDetail() -> some View {
        navigationDestination(for: Meta.self) { DetailView(meta: $0) }
            .navigationDestination(for: DownloadedShow.self) { DownloadedShowView(show: $0) }
            .navigationDestination(for: DiscoverTarget.self) { DiscoverView(target: $0) }
            .noSystemTabBar()
    }
}

extension View {
    /// Codio draws its own tab bar (the same one as on Android), so the system's stays hidden.
    func noSystemTabBar() -> some View {
        toolbar(.hidden, for: .tabBar)
    }
}

/// The bar along the bottom: Home, Search, Library, Settings. White with a red line when chosen, grey otherwise;
/// the icons are the same line drawings as on Codio for Android.
private struct TabBar: View {
    @Binding var tab: Int
    var again: () -> Void
    private let labels = ["Home", "Search", "Library", "Settings"]

    var body: some View {
        HStack(spacing: 0) {
            ForEach(0..<4, id: \.self) { i in
                Button {
                    if tab == i { again() } else { tab = i }
                } label: {
                    VStack(spacing: 5) {
                        TabIcon(kind: i, color: tab == i ? .white : Theme.faint).frame(width: 24, height: 24)
                        Text(labels[i]).font(Theme.font(11))
                    }
                    .foregroundColor(tab == i ? .white : Theme.faint)
                    .frame(maxWidth: .infinity).frame(height: 58)
                    .overlay(alignment: .top) {
                        Capsule().fill(tab == i ? Theme.red : Color.clear).frame(width: 28, height: 2)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(labels[i])
            }
        }
        .background(Theme.bg.ignoresSafeArea(edges: .bottom))
    }
}

private struct TabIcon: View {
    let kind: Int
    let color: Color

    var body: some View {
        Canvas { ctx, size in
            let cx = size.width / 2, cy = size.height / 2, s: CGFloat = 10
            var p = Path()
            switch kind {
            case 0:
                p.move(to: CGPoint(x: cx - s, y: cy)); p.addLine(to: CGPoint(x: cx, y: cy - s)); p.addLine(to: CGPoint(x: cx + s, y: cy))
                p.move(to: CGPoint(x: cx - s * 0.75, y: cy - s * 0.2)); p.addLine(to: CGPoint(x: cx - s * 0.75, y: cy + s))
                p.addLine(to: CGPoint(x: cx + s * 0.75, y: cy + s)); p.addLine(to: CGPoint(x: cx + s * 0.75, y: cy - s * 0.2))
            case 1:
                p.addEllipse(in: CGRect(x: cx - s * 0.85, y: cy - s * 0.85, width: s * 1.3, height: s * 1.3))
                p.move(to: CGPoint(x: cx + s * 0.28, y: cy + s * 0.28)); p.addLine(to: CGPoint(x: cx + s * 0.9, y: cy + s * 0.9))
            case 2:
                p.addRoundedRect(in: CGRect(x: cx - s, y: cy - s * 0.55, width: s * 1.55, height: s * 1.55), cornerSize: CGSize(width: s * 0.2, height: s * 0.2))
                p.move(to: CGPoint(x: cx - s * 0.55, y: cy - s)); p.addLine(to: CGPoint(x: cx + s, y: cy - s))
                p.addLine(to: CGPoint(x: cx + s, y: cy + s * 0.55))
            default:
                p.addEllipse(in: CGRect(x: cx - s * 0.35, y: cy - s * 0.35, width: s * 0.7, height: s * 0.7))
                for i in 0..<8 {
                    let a = Double.pi * Double(i) / 4
                    p.move(to: CGPoint(x: cx + CGFloat(cos(a)) * s * 0.65, y: cy + CGFloat(sin(a)) * s * 0.65))
                    p.addLine(to: CGPoint(x: cx + CGFloat(cos(a)) * s, y: cy + CGFloat(sin(a)) * s))
                }
            }
            ctx.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
        }
    }
}

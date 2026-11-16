import SwiftUI

struct RootView: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store
    /// Launching with "-start_tab 3" opens that tab first (used to take pictures of the screens).
    @State private var tab = UserDefaults.standard.integer(forKey: "start_tab")
    @ObservedObject private var profiles = Profiles.shared
    @ObservedObject private var newEpisodes = NewEpisodes.shared
    @State private var homePath = NavigationPath()

    var body: some View {
        ZStack {
            if engine.overlay == .none {
                TabView(selection: $tab) {
                    NavigationStack(path: $homePath) { HomeView().withDetail() }
                        .tabItem { Label("Home", systemImage: "house.fill") }.tag(0)
                    NavigationStack { SearchView().withDetail() }
                        .tabItem { Label("Search", systemImage: "magnifyingglass") }.tag(1)
                    NavigationStack { LibraryView().withDetail() }
                        .tabItem { Label("Library", systemImage: "rectangle.stack.fill") }.tag(2)
                    NavigationStack { SettingsView().blackTabBar() }
                        .tabItem { Label("Settings", systemImage: "gearshape.fill") }.tag(3)
                }
                .tint(Theme.red)
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
            .blackTabBar()
    }
}

extension View {
    func blackTabBar() -> some View {
        toolbarBackground(Color.black, for: .tabBar).toolbarBackground(.visible, for: .tabBar)
    }
}

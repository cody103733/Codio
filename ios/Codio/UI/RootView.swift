import SwiftUI

struct RootView: View {
    @EnvironmentObject var engine: Engine
    @State private var tab = 0

    var body: some View {
        ZStack {
            if engine.overlay == .none {
                TabView(selection: $tab) {
                    NavigationStack { HomeView().withDetail() }
                        .tabItem { Label("Home", systemImage: "house.fill") }.tag(0)
                    NavigationStack { SearchView().withDetail() }
                        .tabItem { Label("Search", systemImage: "magnifyingglass") }.tag(1)
                    NavigationStack { LibraryView().withDetail() }
                        .tabItem { Label("My List", systemImage: "rectangle.stack.fill") }.tag(2)
                    NavigationStack { SettingsView() }
                        .tabItem { Label("Settings", systemImage: "gearshape.fill") }.tag(3)
                }
                .tint(.white)
                .transition(.opacity)
            }
            if engine.overlay == .loading {
                LoadingView().transition(.opacity)
            }
            if engine.overlay == .player {
                PlayerView().environmentObject(engine.playerState)
            }
            if let t = engine.toast {
                VStack { Spacer(); ToastView(text: t).padding(.bottom, 90) }
                    .allowsHitTesting(false)
                    .transition(.opacity)
            }
        }
        .animation(.easeOut(duration: 0.2), value: engine.overlay)
        .preferredColorScheme(.dark)
        .sheet(item: $engine.sourcesRequest) { r in
            SourcesView(meta: r.meta, videoId: r.videoId)
                .environmentObject(engine)
                .environmentObject(engine.store)
        }
    }
}

private extension View {
    func withDetail() -> some View {
        navigationDestination(for: Meta.self) { DetailView(meta: $0) }
    }
}

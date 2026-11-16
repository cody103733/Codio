import SwiftUI

/// Home: the featured title with the top bar over it, Continue Watching, then a row per addon catalog.
/// Laid out the same as Home on Codio for Android.
private struct HomeRow: Identifiable {
    let catalog: Catalog
    var items: [Meta]
    var id: String { catalog.id }
}

struct HomeView: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store
    @ObservedObject private var recommender = Recommender.shared
    @ObservedObject private var ratings = Ratings.shared
    @ObservedObject private var profiles = Profiles.shared
    @ObservedObject private var newEpisodes = NewEpisodes.shared
    @State private var rows: [HomeRow] = []
    @State private var loadedFor = -1
    @State private var loading = false

    private var filled: [HomeRow] { rows.filter { !$0.items.isEmpty } }
    private var featured: Meta? { filled.first?.items.first }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Hero(meta: featured).overlay(alignment: .top) { topBar }
                UpdateBanner().padding(.top, 12)
                // Shows with a new episode out come first (new episode alerts).
                let continuing = newEpisodes.ordered(store.continueWatching, library: store.library)
                if !continuing.isEmpty {
                    PosterRow(title: "Continue Watching", items: continuing, showProgress: true)
                }
                ForEach(recommender.rows) { r in PosterRow(title: r.title, items: r.items) }
                ForEach(filled) { row in PosterRow(title: rowTitle(row.catalog), items: row.items) }
                if filled.isEmpty && store.continueWatching.isEmpty {
                    PageHint(text: store.addonDescriptors.isEmpty && store.profileLoaded
                             ? "No addons yet. Add some under Settings \u{2192} Addons." : "Loading\u{2026}")
                        .padding(.top, -24)
                }
            }
            .padding(.bottom, 16)
        }
        .background(Color.black.ignoresSafeArea())
        .navigationBarHidden(true)
        .task(id: store.generation) { await load() }
        .task { await Updater.shared.check() }
        // Personal rows: rebuilt when ratings or the watch history change.
        .task(id: "\(ratings.all.values.map { $0.stars }.reduce(0, +))|\(ratings.all.count)|\(store.library.count)|\(store.continueWatching.first?.id ?? "")") {
            await recommender.refresh(store)
        }
        .refreshable {
            // SwiftUI cancels the pull-to-refresh task as soon as the list redraws, which used to abort
            // the catalog downloads halfway (black featured poster, only a few rows). Run it detached.
            await Task { @MainActor in
                loadedFor = -1
                await load()
                recommender.invalidate()
                await recommender.refresh(store)
            }.value
        }
    }

    /// "Popular" from a movie catalog reads "Popular Movies"; a name that already says its type stays as it is.
    private func rowTitle(_ c: Catalog) -> String {
        let type = typeLabel(c.type)
        return type.isEmpty || c.name.lowercased().contains(type.lowercased()) ? c.name : c.name + " " + type
    }

    /// The wordmark, Movies and Shows, and who's watching, over the top of the featured picture.
    private var topBar: some View {
        HStack(spacing: 0) {
            BrandWordmark(height: 26)
            Spacer()
            NavigationLink(value: DiscoverTarget(type: "movie")) { topLink("Movies") }
            NavigationLink(value: DiscoverTarget(type: "series")) { topLink("Shows") }
            ProfileButton(size: 34).padding(.leading, 8).padding(.trailing, 6)
        }
        .buttonStyle(.plain)
        .padding(.leading, 14).padding(.trailing, 8).padding(.vertical, 12)
    }

    private func topLink(_ text: String) -> some View {
        Text(text).font(Theme.font(15, .bold)).foregroundColor(.white)
            .shadow(color: .black, radius: 4)
            .padding(.horizontal, 10).padding(.vertical, 6)
            .contentShape(Rectangle())
    }

    private func load() async {
        guard loadedFor != store.generation, !loading else { return }
        loading = true
        defer { loading = false }
        let gen = store.generation
        let cats = Array(store.homeCatalogs().prefix(20))
        // Keep what's on screen while the fresh lists load, so a refresh never blanks Home.
        let old = Dictionary(rows.map { ($0.id, $0.items) }, uniquingKeysWith: { a, _ in a })
        var out: [HomeRow] = cats.map { HomeRow(catalog: $0, items: old[$0.id] ?? []) }
        rows = out
        // A few catalogs at a time, top rows first.
        let st = store
        for start in stride(from: 0, to: cats.count, by: 4) {
            let end = min(start + 4, cats.count)
            let got = await withTaskGroup(of: (Int, [Meta]).self, returning: [(Int, [Meta])].self) { group in
                for i in start..<end {
                    let c = cats[i]
                    group.addTask { @MainActor in (i, await st.load(c)) }
                }
                var r: [(Int, [Meta])] = []
                for await x in group { r.append(x) }
                return r
            }
            for (i, metas) in got where !metas.isEmpty || out[i].items.isEmpty { out[i].items = metas }
            rows = out
        }
        loadedFor = gen
    }
}

/// The featured title: its poster edge to edge, fading to black, with its logo, genres, Play and Info.
private struct Hero: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store
    let meta: Meta?

    var body: some View {
        let w = UIScreen.main.bounds.width
        let h = (min(w, 600) * 1.2).rounded()
        ZStack(alignment: .bottom) {
            if let m = meta {
                HeroArt(meta: m).frame(width: w, height: h).clipped()
            } else {
                Color.black
            }
            LinearGradient(stops: [.init(color: .black.opacity(0.67), location: 0), .init(color: .clear, location: 0.33),
                                   .init(color: .clear, location: 0.66), .init(color: .black, location: 1)],
                           startPoint: .top, endPoint: .bottom)
                .allowsHitTesting(false)
            if let m = meta { HeroInfo(meta: m) }
        }
        .frame(width: w, height: h)
    }
}

private struct HeroArt: View {
    @EnvironmentObject var store: Store
    @ObservedObject var meta: Meta
    var body: some View {
        // Portrait phones: the poster fills the banner better than the wide background.
        NavigationLink(value: meta) { RemoteImage(url: meta.poster ?? meta.background) }
            .buttonStyle(.plain)
            .task(id: meta.id) { if !meta.full { await store.loadMeta(meta) } }
    }
}

private struct HeroInfo: View {
    @EnvironmentObject var engine: Engine
    @ObservedObject var meta: Meta

    var body: some View {
        VStack(spacing: 0) {
            if let logo = meta.logo {
                RemoteImage(url: logo, contentMode: .fit).frame(width: 240, height: 80)
            } else {
                Text(meta.name).font(Theme.font(28, .bold)).foregroundColor(.white)
                    .multilineTextAlignment(.center).lineLimit(2)
            }
            Text(meta.genres.joined(separator: "  \u{00B7}  "))
                .font(Theme.font(13)).foregroundColor(Theme.body).lineLimit(1)
                .padding(.top, 6).padding(.bottom, 10)
            HStack(spacing: 12) {
                if meta.type == "movie" {
                    Button { engine.play(meta, nil) } label: { heroButton("Play", "play.fill", primary: true) }
                } else {
                    NavigationLink(value: meta) { heroButton("Play", "play.fill", primary: true) }
                }
                NavigationLink(value: meta) { heroButton("Info", "info.circle", primary: false) }
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 6)
        }
        .padding(.horizontal, 16).padding(.bottom, 4)
    }

    private func heroButton(_ text: String, _ icon: String, primary: Bool) -> some View {
        HStack(spacing: 8) {
            Image(systemName: icon).font(.system(size: 14, weight: .bold))
            Text(text).font(Theme.font(15, .bold))
        }
        .foregroundColor(primary ? .black : .white)
        .frame(maxWidth: .infinity).padding(.vertical, 11)
        .background(RoundedRectangle(cornerRadius: 6).fill(primary ? Color.white : Theme.glass))
    }
}

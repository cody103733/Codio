import SwiftUI

/// Home, Netflix style: logo bar with Shows / Movies pills, a big featured card, then rows
/// (Continue Watching, a Top 10, and one per addon catalog).
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
    @State private var rows: [HomeRow] = []
    @State private var loadedFor = -1
    @State private var loading = false

    private var filled: [HomeRow] { rows.filter { !$0.items.isEmpty } }
    private var featured: Meta? { filled.first?.items.first }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 26) {
                header
                UpdateBanner()
                if let f = featured { FeaturedCard(meta: f, rank: filled.first.map { "#1 in " + typeLabel($0.catalog.type) + " Today" }).padding(.horizontal, 22) } else { Color.clear.frame(height: 420) }
                if !store.continueWatching.isEmpty {
                    ContinueRow(items: store.continueWatching)
                }
                ForEach(recommender.rows) { r in PosterRow(title: r.title, items: r.items) }
                ForEach(Array(filled.enumerated()), id: \.element.id) { i, row in
                    if i == 0 {
                        TopTenRow(title: "Top 10 " + typeLabel(row.catalog.type) + " Today", items: Array(row.items.dropFirst().prefix(10)))
                    } else {
                        PosterRow(title: rowTitle(row.catalog), items: row.items)
                    }
                }
                if rows.isEmpty || (filled.isEmpty && loading) {
                    HStack { Spacer(); ProgressView().tint(Theme.red); Spacer() }.padding(.top, 20)
                }
            }
            .padding(.bottom, 30)
        }
        .background(alignment: .top) { backdrop }
        .background(Color.black.ignoresSafeArea())
        .navigationBarHidden(true)
        .task(id: store.generation) { await load() }
        .task { await Updater.shared.check() }
        // Personal rows: rebuilt when ratings or the watch history change.
        .task(id: "\(ratings.all.values.map { $0.stars }.reduce(0, +))|\(ratings.all.count)|\(store.library.count)|\(store.continueWatching.first?.id ?? "")") {
            await recommender.refresh(store)
        }
        .refreshable {
            recommender.invalidate()
            await recommender.refresh(store)
            loadedFor = -1
            await load()
        }
    }

    private func rowTitle(_ c: Catalog) -> String {
        let generic = ["Popular", "Top", "Featured", "New", "Trending"].contains { c.name.hasPrefix($0) }
        return generic ? c.name + " " + typeLabel(c.type) : c.name
    }

    /// The featured poster, blurred, tints the top of Home the way Netflix colors it from the artwork.
    private var backdrop: some View {
        ZStack {
            if let f = featured {
                Color.clear.overlay(RemoteImage(url: f.poster ?? f.background).blur(radius: 60).opacity(0.75))
            }
            LinearGradient(colors: [.clear, .black], startPoint: .top, endPoint: .bottom)
        }
        .frame(height: 620)
        .clipped()
        .ignoresSafeArea()
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                BrandWordmark(height: 26)
                Spacer()
                if let p = profiles.current {
                    Button { profiles.showPicker = true } label: { ProfileAvatar(profile: p, size: 30) }
                        .buttonStyle(.plain)
                }
            }
            HStack(spacing: 8) {
                NavigationLink(value: DiscoverTarget(type: "series")) { Pill(text: "Shows") }
                NavigationLink(value: DiscoverTarget(type: "movie")) { Pill(text: "Movies") }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
    }

    private func load() async {
        guard loadedFor != store.generation, !loading else { return }
        loading = true
        defer { loading = false }
        let gen = store.generation
        let cats = Array(store.homeCatalogs().prefix(20))
        var out: [HomeRow] = cats.map { HomeRow(catalog: $0, items: []) }
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
            for (i, metas) in got { out[i].items = metas }
            rows = out
        }
        loadedFor = gen
    }
}

struct Pill: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.subheadline.weight(.medium))
            .foregroundColor(.white)
            .padding(.horizontal, 16)
            .padding(.vertical, 7)
            .overlay(Capsule().stroke(Color.white.opacity(0.6), lineWidth: 1))
    }
}

/// The big rounded featured card with Play and Library.
private struct FeaturedCard: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store
    @ObservedObject var meta: Meta
    var rank: String?

    private var playLabel: some View {
        Label("Play", systemImage: "play.fill").font(.headline).foregroundColor(.black)
            .frame(maxWidth: .infinity).padding(.vertical, 10)
            .background(RoundedRectangle(cornerRadius: 4).fill(Color.white))
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            NavigationLink(value: meta) {
                RemoteImage(url: meta.poster ?? meta.background)
                    .aspectRatio(2 / 3, contentMode: .fit)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.plain)
            LinearGradient(colors: [.clear, .clear, .black.opacity(0.85)], startPoint: .top, endPoint: .bottom)
                .allowsHitTesting(false)
            VStack(spacing: 12) {
                if let logo = meta.logo {
                    RemoteImage(url: logo, contentMode: .fit).frame(maxWidth: 220, maxHeight: 70)
                }
                if let r = rank {
                    HStack(spacing: 6) {
                        Top10Badge()
                        Text(r).font(.subheadline.weight(.bold)).foregroundColor(.white)
                    }
                }
                if !meta.genres.isEmpty {
                    Text(meta.genres.prefix(3).joined(separator: " • ")).font(.footnote.weight(.medium)).foregroundColor(.white)
                }
                HStack(spacing: 10) {
                    if meta.type == "movie" {
                        Button { engine.play(meta, nil) } label: { playLabel }
                    } else {
                        NavigationLink(value: meta) { playLabel }
                    }
                    Button {
                        let inLib = store.inLibrary(meta.id)
                        if inLib { engine.ctx("RemoveFromLibrary", meta.id) } else { engine.ctx("AddToLibrary", Store.preview(meta)) }
                        store.markInLibrary(meta, !inLib)
                        engine.show(toast: inLib ? "Removed from Library" : "Added to Library")
                    } label: {
                        Label("Library", systemImage: store.inLibrary(meta.id) ? "checkmark" : "plus").font(.headline).foregroundColor(.white)
                            .frame(maxWidth: .infinity).padding(.vertical, 10)
                            .background(RoundedRectangle(cornerRadius: 4).fill(Color(white: 0.25).opacity(0.9)))
                    }
                }
                .buttonStyle(.plain)
            }
            .padding(14)
        }
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color.white.opacity(0.12), lineWidth: 1))
        .shadow(color: .black.opacity(0.6), radius: 18, y: 8)
        .task { if !meta.full { await store.loadMeta(meta) } }
    }
}

/// Continue Watching: posters with a red progress bar and a play overlay that resumes straight away.
private struct ContinueRow: View {
    @EnvironmentObject var engine: Engine
    let items: [Meta]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Continue Watching").font(.headline.weight(.bold)).foregroundColor(.white).padding(.horizontal, 16)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 8) {
                    ForEach(items) { m in
                        VStack(spacing: 0) {
                            Button { engine.play(m, m.lastVideoId) } label: {
                                ZStack {
                                    RemoteImage(url: m.poster).frame(width: 110, height: 160).clipped()
                                    Image(systemName: "play.circle").font(.system(size: 40, weight: .light)).foregroundColor(.white)
                                        .shadow(radius: 4)
                                }
                            }
                            .buttonStyle(.plain)
                            GeometryReader { g in
                                ZStack(alignment: .leading) {
                                    Rectangle().fill(Color(white: 0.35))
                                    Rectangle().fill(Theme.red).frame(width: g.size.width * m.progress)
                                }
                            }
                            .frame(width: 110, height: 3)
                            HStack {
                                NavigationLink(value: m) { Image(systemName: "info.circle") }
                                Spacer()
                                Text(m.lastVideoId.flatMap(episodeTag) ?? "").font(.caption2)
                            }
                            .foregroundColor(Theme.dim)
                            .padding(.horizontal, 8)
                            .frame(width: 110, height: 30)
                            .background(Color(white: 0.11))
                        }
                        .clipShape(RoundedRectangle(cornerRadius: 4))
                    }
                }
                .padding(.horizontal, 16)
            }
        }
    }

    private func episodeTag(_ vid: String) -> String? {
        let p = vid.split(separator: ":")
        guard p.count >= 3, let s = Int(p[p.count - 2]), let e = Int(p[p.count - 1]) else { return nil }
        return "S\(s):E\(e)"
    }
}

/// Top 10 with the big outlined numbers next to each poster.
private struct TopTenRow: View {
    let title: String
    let items: [Meta]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.headline.weight(.bold)).foregroundColor(.white).padding(.horizontal, 16)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 4) {
                    ForEach(Array(items.enumerated()), id: \.element.id) { i, m in
                        NavigationLink(value: m) {
                            HStack(alignment: .bottom, spacing: -14) {
                                Text("\(i + 1)")
                                    .font(.system(size: 120, weight: .black))
                                    .foregroundColor(.black)
                                    .shadow(color: Color(white: 0.75), radius: 0, x: 1.5, y: 1.5)
                                    .shadow(color: Color(white: 0.75), radius: 0, x: -1.5, y: -1.5)
                                    .shadow(color: Color(white: 0.75), radius: 0, x: 1.5, y: -1.5)
                                    .shadow(color: Color(white: 0.75), radius: 0, x: -1.5, y: 1.5)
                                    .offset(y: 22)
                                PosterCard(meta: m, width: 100)
                                    .overlay(alignment: .topTrailing) { Top10Badge(small: true) }
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 16)
            }
        }
    }
}

/// Netflix's little red TOP 10 square.
struct Top10Badge: View {
    var small = false
    var body: some View {
        VStack(spacing: -2) {
            Text("TOP").font(.system(size: small ? 6 : 8, weight: .heavy))
            Text("10").font(.system(size: small ? 11 : 15, weight: .black))
        }
        .foregroundColor(.white)
        .frame(width: small ? 18 : 26, height: small ? 22 : 30)
        .background(Rectangle().fill(Theme.red))
    }
}

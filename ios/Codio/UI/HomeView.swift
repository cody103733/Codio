import SwiftUI

/// Home: a featured title, Continue Watching, then a row per addon catalog.
private struct HomeRow: Identifiable {
    let catalog: Catalog
    var items: [Meta]
    var id: String { catalog.id }
}

struct HomeView: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store
    @State private var rows: [HomeRow] = []
    @State private var loadedFor = -1
    @State private var loading = false

    private var featured: Meta? { rows.first(where: { !$0.items.isEmpty })?.items.first }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                if let f = featured { Hero(meta: f) } else { Color.clear.frame(height: 90) }
                if !store.continueWatching.isEmpty {
                    PosterRow(title: "Continue Watching", items: store.continueWatching, showProgress: true)
                }
                ForEach(rows) { row in
                    if !row.items.isEmpty {
                        PosterRow(title: row.catalog.name + (row.catalog.type == "movie" || row.catalog.type == "series" ? " · " + typeLabel(row.catalog.type) : ""),
                                  items: row.items)
                    }
                }
                if rows.isEmpty {
                    HStack { Spacer(); ProgressView().tint(.white); Spacer() }.padding(.top, 60)
                }
            }
            .padding(.bottom, 24)
        }
        .background(Theme.bg.ignoresSafeArea())
        .overlay(alignment: .top) {
            HStack(spacing: 14) {
                BrandWordmark(height: 24)
                Spacer()
                NavigationLink(value: DiscoverTarget(type: "movie")) { Text("Movies") }
                NavigationLink(value: DiscoverTarget(type: "series")) { Text("Series") }
            }
            .font(.subheadline.weight(.semibold))
            .foregroundColor(.white)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 8)
            .background(LinearGradient(colors: [.black.opacity(0.75), .clear], startPoint: .top, endPoint: .bottom).ignoresSafeArea())
        }
        .navigationBarHidden(true)
        .task(id: store.generation) { await load() }
        .refreshable {
            loadedFor = -1
            await load()
        }
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

private struct Hero: View {
    @EnvironmentObject var engine: Engine
    @ObservedObject var meta: Meta

    var body: some View {
        ZStack(alignment: .bottom) {
            RemoteImage(url: meta.background ?? meta.poster)
                .frame(maxWidth: .infinity)
                .frame(height: 460)
                .clipped()
            LinearGradient(colors: [.black.opacity(0.55), .clear, .clear, Theme.bg], startPoint: .top, endPoint: .bottom)
            VStack(spacing: 10) {
                if let logo = meta.logo {
                    RemoteImage(url: logo, contentMode: .fit).frame(maxWidth: 260, maxHeight: 90)
                } else {
                    Text(meta.name).font(.largeTitle.bold()).foregroundColor(.white).multilineTextAlignment(.center)
                }
                if !meta.metaLine.isEmpty {
                    Text(meta.metaLine).font(.footnote).foregroundColor(Color(white: 0.85))
                }
                HStack(spacing: 10) {
                    Button {
                        engine.play(meta, nil)
                    } label: {
                        Label("Play", systemImage: "play.fill")
                    }
                    .buttonStyle(RedButtonStyle())
                    .disabled(meta.type != "movie")
                    .opacity(meta.type == "movie" ? 1 : 0)
                    .overlay {
                        if meta.type != "movie" {
                            NavigationLink(value: meta) { Label("Episodes", systemImage: "list.bullet") }
                                .buttonStyle(RedButtonStyle())
                        }
                    }
                    NavigationLink(value: meta) { Label("More info", systemImage: "info.circle") }
                        .buttonStyle(RedButtonStyle(filled: false))
                }
                .padding(.horizontal, 40)
            }
            .padding(.bottom, 8)
        }
        .frame(height: 460)
    }
}

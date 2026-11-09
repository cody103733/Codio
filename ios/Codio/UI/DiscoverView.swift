import SwiftUI

struct DiscoverTarget: Hashable {
    let type: String
}

/// Movies / Series: browse an addon catalog by genre, with more titles loading as you scroll.
struct DiscoverView: View {
    @EnvironmentObject var store: Store
    let target: DiscoverTarget
    @State private var catalogIndex = 0
    @State private var genre: String?
    @State private var items: [Meta] = []
    @State private var loading = false
    @State private var more = true

    private var catalogs: [Catalog] { store.discoverCatalogs(target.type) }
    private var catalog: Catalog? { catalogs.indices.contains(catalogIndex) ? catalogs[catalogIndex] : catalogs.first }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 8) {
                    if catalogs.count > 1 {
                        Menu {
                            ForEach(catalogs.indices, id: \.self) { i in
                                Button(catalogs[i].name) { catalogIndex = i; genre = nil }
                            }
                        } label: { chip(catalog?.name ?? "Catalog") }
                    }
                    if let c = catalog, !c.genres.isEmpty {
                        Menu {
                            if !c.genreRequired { Button("All genres") { genre = nil } }
                            ForEach(c.genres, id: \.self) { g in Button(g) { genre = g } }
                        } label: { chip(genre ?? (c.genreRequired ? c.genres.first ?? "Genre" : "All genres")) }
                    }
                    Spacer()
                }
                .padding(.horizontal, 16)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 104, maximum: 140), spacing: 8)], spacing: 12) {
                    ForEach(items) { m in
                        NavigationLink(value: m) { PosterCard(meta: m, width: 108) }
                            .buttonStyle(.plain)
                            .onAppear { if m.id == items.last?.id { Task { await loadMore() } } }
                    }
                }
                .padding(.horizontal, 12)
                if loading { HStack { Spacer(); ProgressView().tint(.white); Spacer() }.padding() }
            }
            .padding(.vertical, 10)
        }
        .background(Theme.bg.ignoresSafeArea())
        .navigationTitle(typeLabel(target.type))
        .task(id: "\(catalogIndex)|\(genre ?? "")|\(store.generation)") { await reload() }
    }

    private func chip(_ t: String) -> some View {
        HStack(spacing: 4) {
            Text(t).lineLimit(1)
            Image(systemName: "chevron.down").font(.caption2)
        }
        .font(.subheadline.weight(.semibold)).foregroundColor(.white)
        .padding(.horizontal, 14).padding(.vertical, 8)
        .background(Capsule().stroke(Color.white.opacity(0.5)))
    }

    private func reload() async {
        items = []
        more = true
        await loadMore()
    }

    private func loadMore() async {
        guard let c = catalog, !loading, more else { return }
        loading = true
        let got = await store.load(c, genre: genre, skip: items.count)
        loading = false
        let known = Set(items.map { $0.id })
        let fresh = got.filter { !known.contains($0.id) }
        items += fresh
        more = c.skip && !fresh.isEmpty
    }
}

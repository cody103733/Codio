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
                if catalogs.count > 1 {
                    ChipStrip(options: catalogs.indices.map { (catalogs[$0].name, $0) },
                              selection: Binding(get: { catalogIndex }, set: { catalogIndex = $0; genre = nil }))
                }
                if let c = catalog, !c.genres.isEmpty {
                    ChipStrip(options: genreOptions(c),
                              selection: Binding(get: { genre ?? (c.genreRequired ? c.genres.first ?? "" : "") },
                                                 set: { genre = $0.isEmpty ? nil : $0 }))
                }
                PosterGrid(items: items) { Task { await loadMore() } }
                if loading { HStack { Spacer(); ProgressView().tint(Theme.red); Spacer() }.padding() }
            }
            .padding(.vertical, 10)
        }
        .background(Color.black.ignoresSafeArea())
        .navigationTitle(typeLabel(target.type))
        .navigationBarTitleDisplayMode(.inline)
        .profileToolbar()
        .task(id: "\(catalogIndex)|\(genre ?? "")|\(store.generation)") { await reload() }
    }

    private func genreOptions(_ c: Catalog) -> [(String, String)] {
        let all: [(String, String)] = c.genreRequired ? [] : [("All genres", "")]
        return all + c.genres.map { ($0, $0) }
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

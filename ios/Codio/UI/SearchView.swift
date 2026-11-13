import SwiftUI

struct SearchView: View {
    @EnvironmentObject var store: Store
    @State private var query = ""
    @State private var results: [Meta] = []
    @State private var searching = false
    @State private var searchTask: Task<Void, Never>?
    @State private var browseType = "movie"
    @State private var browse: [Meta] = []

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if query.trimmingCharacters(in: .whitespaces).isEmpty {
                    Picker("", selection: $browseType) {
                        Text("Movies").tag("movie")
                        Text("Series").tag("series")
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal, 16)
                    PosterGrid(items: browse)
                } else if searching && results.isEmpty {
                    HStack { Spacer(); ProgressView().tint(Theme.red); Spacer() }.padding(.top, 40)
                } else if results.isEmpty {
                    Text("Nothing found for \u{201C}\(query)\u{201D}").foregroundColor(Theme.dim).frame(maxWidth: .infinity).padding(.top, 40)
                } else {
                    PosterGrid(items: results)
                }
            }
            .padding(.vertical, 10)
        }
        .background(Theme.bg.ignoresSafeArea())
        .navigationTitle("Search")
        .profileToolbar()
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Movies and shows")
        .onChange(of: query) { _ in scheduleSearch() }
        .task(id: browseType + "\(store.generation)") { await loadBrowse() }
    }

    private func loadBrowse() async {
        guard let cat = store.discoverCatalogs(browseType).first else { return }
        browse = await store.load(cat)
    }

    private func scheduleSearch() {
        searchTask?.cancel()
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else {
            results = []
            return
        }
        searchTask = Task {
            try? await Task.sleep(nanoseconds: 400_000_000)
            if Task.isCancelled { return }
            searching = true
            var all: [Meta] = []
            var seen = Set<String>()
            for cat in store.searchCatalogs().prefix(6) {
                let found = await store.load(cat, query: q)
                if Task.isCancelled { return }
                for m in found where !seen.contains(m.id) {
                    seen.insert(m.id)
                    all.append(m)
                }
                results = all
            }
            searching = false
        }
    }
}

struct LibraryView: View {
    @EnvironmentObject var store: Store
    @State private var type: String? = nil

    var body: some View {
        let items = store.libraryItems(type)
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Picker("", selection: $type) {
                    Text("All").tag(String?.none)
                    Text("Movies").tag(String?.some("movie"))
                    Text("Shows").tag(String?.some("series"))
                    Text("Downloads").tag(String?.some("downloads"))
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 16)
                if type == "downloads" {
                    DownloadsListView()
                } else if !store.profileLoaded {
                    HStack { Spacer(); ProgressView().tint(Theme.red); Spacer() }.padding(.top, 40)
                } else if items.isEmpty {
                    Text("Titles you add to your Library show up here.")
                        .foregroundColor(Theme.dim).frame(maxWidth: .infinity).padding(.top, 40)
                } else {
                    PosterGrid(items: items, showProgress: true)
                }
            }
            .padding(.vertical, 10)
        }
        .background(Theme.bg.ignoresSafeArea())
        .navigationTitle("Library")
        .profileToolbar()
    }
}

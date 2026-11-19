import SwiftUI

struct SearchView: View {
    @EnvironmentObject var store: Store
    @State private var query = ""
    @State private var results: [Meta] = []
    @State private var searching = false
    @State private var searchTask: Task<Void, Never>?
    /// Movies / Shows / Both filter for search (and the titles shown before typing). Starts on Both.
    @State private var kind = "both"
    @State private var browse: [Meta] = []
    /// Recent searches (newest first), listed while nothing is typed. A search is kept once it found something.
    @State private var recent: [String] = UserDefaults.standard.stringArray(forKey: "recent_searches") ?? []

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Picker("", selection: $kind) {
                    Text("Both").tag("both")
                    Text("Movies").tag("movie")
                    Text("Shows").tag("series")
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 16)
                if query.trimmingCharacters(in: .whitespaces).isEmpty {
                    if !recent.isEmpty { recentList }
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
        .onSubmit(of: .search) { remember() }
        .onDisappear { remember() }
        .onChange(of: query) { _ in scheduleSearch() }
        .onChange(of: kind) { _ in scheduleSearch(now: true) }
        .task(id: kind + "\(store.generation)") { await loadBrowse() }
    }

    private var recentList: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Recent searches").font(.headline).padding(.horizontal, 16).padding(.bottom, 6)
            ForEach(recent, id: \.self) { q in
                Button { query = q } label: { recentRow(q, .white) }
            }
            Button { saveRecent([]) } label: { recentRow("Clear recent searches", Theme.dim) }
        }
    }

    private func recentRow(_ text: String, _ color: Color) -> some View {
        Text(text).foregroundColor(color).lineLimit(1)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16).padding(.vertical, 10)
    }

    private func saveRecent(_ list: [String]) {
        recent = list
        UserDefaults.standard.set(list, forKey: "recent_searches")
    }

    private func remember() {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty, !results.isEmpty else { return }
        saveRecent(Array(([q] + recent.filter { $0.caseInsensitiveCompare(q) != .orderedSame }).prefix(8)))
    }

    private func wanted(_ type: String) -> Bool { kind == "both" || type == kind }

    private func loadBrowse() async {
        let types = kind == "both" ? ["movie", "series"] : [kind]
        var lists: [[Meta]] = []
        for t in types {
            guard let cat = store.discoverCatalogs(t).first else { continue }
            lists.append(await store.load(cat))
        }
        // Both: alternate movies and series so neither fills the top.
        var mixed: [Meta] = []
        for i in 0..<(lists.map(\.count).max() ?? 0) {
            for l in lists where i < l.count { mixed.append(l[i]) }
        }
        browse = mixed
    }

    private func scheduleSearch(now: Bool = false) {
        searchTask?.cancel()
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else {
            results = []
            return
        }
        searchTask = Task {
            if !now { try? await Task.sleep(nanoseconds: 400_000_000) }
            if Task.isCancelled { return }
            searching = true
            results = []
            var all: [Meta] = []
            var seen = Set<String>()
            for cat in store.searchCatalogs().filter({ wanted($0.type) }).prefix(6) {
                let found = await store.load(cat, query: q)
                if Task.isCancelled { return }
                for m in found where wanted(m.type) && !seen.contains(m.id) {
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
                if type != "downloads", store.profileLoaded { CalendarLink() }
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

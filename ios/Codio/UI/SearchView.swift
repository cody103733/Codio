import SwiftUI

struct SearchView: View {
    @EnvironmentObject var store: Store
    @State private var query = ""
    @State private var results: [Meta] = []
    @State private var searching = false
    @State private var searchTask: Task<Void, Never>?
    /// Movies / Shows / Both filter for search. Starts on Both.
    @State private var kind = "both"
    /// Recent searches (newest first), listed while nothing is typed. A search is kept once it found something.
    @State private var recent: [String] = UserDefaults.standard.stringArray(forKey: "recent_searches") ?? []

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                TextField("", text: $query, prompt: Text("Movies, shows\u{2026}").foregroundColor(Theme.faint))
                    .font(Theme.font(16)).foregroundColor(.white)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .submitLabel(.search)
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .background(RoundedRectangle(cornerRadius: 8).fill(Theme.raised))
                ProfileButton(size: 34)
            }
            .padding(.leading, 16).padding(.trailing, 14).padding(.top, 12).padding(.bottom, 8)
            ChipStrip(options: [("Both", "both"), ("Movies", "movie"), ("Shows", "series")], selection: $kind)
                .padding(.bottom, 8)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if query.trimmingCharacters(in: .whitespaces).isEmpty {
                        if recent.isEmpty { PageHint(text: "Search your addons for movies and shows.").padding(.top, -8) } else { recentList }
                    } else if results.isEmpty {
                        PageHint(text: searching ? "Searching\u{2026}" : "Nothing found.").padding(.top, -8)
                    } else {
                        PosterGrid(items: results)
                    }
                }
                .padding(.bottom, 16)
            }
            .scrollDismissesKeyboard(.immediately)
        }
        .background(Color.black.ignoresSafeArea())
        .navigationBarHidden(true)
        .onSubmit { remember() }
        .onDisappear { remember() }
        .onChange(of: query) { _ in scheduleSearch() }
        .onChange(of: kind) { _ in scheduleSearch(now: true) }
    }

    private var recentList: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionTitle(text: "Recent searches")
            ForEach(recent, id: \.self) { q in
                Button { query = q } label: { recentRow(q, .white) }
            }
            Button { saveRecent([]) } label: { recentRow("Clear recent searches", Theme.dim) }
        }
    }

    private func recentRow(_ text: String, _ color: Color) -> some View {
        Text(text).font(Theme.font(15)).foregroundColor(color).lineLimit(1)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16).padding(.vertical, 12)
            .contentShape(Rectangle())
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

    private func scheduleSearch(now: Bool = false) {
        searchTask?.cancel()
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else {
            results = []
            return
        }
        searching = true
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
    @State private var type = "all"

    var body: some View {
        let items = store.libraryItems(type == "all" ? nil : type)
        VStack(spacing: 0) {
            PageTitle(text: "Library")
            ChipStrip(options: [("All", "all"), ("Movies", "movie"), ("Shows", "series"), ("Downloads", "downloads")], selection: $type)
                .padding(.top, 4).padding(.bottom, 10)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if type == "downloads" {
                        DownloadsListView()
                    } else if !store.profileLoaded {
                        PageHint(text: "Loading your library\u{2026}")
                    } else if items.isEmpty {
                        PageHint(text: "Nothing here yet. Tap \u{201C}+ Library\u{201D} or \u{201C}Download\u{201D} on a movie or show.")
                    } else {
                        PosterGrid(items: items, showProgress: true)
                    }
                }
                .padding(.bottom, 16)
            }
        }
        .background(Color.black.ignoresSafeArea())
        .navigationBarHidden(true)
    }
}

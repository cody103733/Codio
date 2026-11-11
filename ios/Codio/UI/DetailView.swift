import SwiftUI

/// A movie or show: backdrop, Play / Resume, Library, sources, episodes.
struct DetailView: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store
    @ObservedObject var meta: Meta
    @State private var season: Int = 1
    @State private var loaded = false
    @ObservedObject private var dl = Downloads.shared
    @ObservedObject private var ratings = Ratings.shared
    @State private var ask: DownloadAsk?
    @State private var askingFor: String?
    @State private var confirmDelete: Downloads.Entry?

    private var isSeries: Bool { meta.type == "series" || (!meta.videos.isEmpty && meta.type != "movie") }

    /// What Play starts: the episode last watched (or the next one), or the first episode.
    private var playTarget: String {
        guard isSeries else { return meta.id }
        if let e = store.entry(meta.id), let v = e.videoId, meta.video(v) != nil {
            if e.duration > 0, e.offset / e.duration > 0.92, let n = meta.next(after: v) { return n.id }
            return v
        }
        return meta.videos.first(where: { $0.season > 0 })?.id ?? meta.videos.first?.id ?? meta.id
    }

    private var resumeText: String? {
        guard let e = store.entry(meta.id), e.offset > 30 else { return nil }
        if isSeries ? e.videoId != playTarget : (e.videoId != nil && e.videoId != meta.id) { return nil }
        if e.duration > 0, e.offset / e.duration > 0.92 { return nil }
        if isSeries, let v = meta.video(playTarget) { return "Resume S\(v.season) E\(v.episode)" }
        return "Resume"
    }

    private var playLabel: String {
        if let r = resumeText { return r }
        if isSeries, let v = meta.video(playTarget) { return "Play S\(v.season) E\(v.episode)" }
        return "Play"
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                header
                VStack(alignment: .leading, spacing: 10) {
                    buttons
                    sourceStatus
                    if let d = meta.description {
                        Text(d).font(.subheadline).foregroundColor(.white).fixedSize(horizontal: false, vertical: true)
                    }
                    if !meta.cast.isEmpty {
                        Text("Starring: " + meta.cast.prefix(4).joined(separator: ", ")).font(.caption).foregroundColor(Theme.dim).lineLimit(2)
                    }
                    if !meta.genres.isEmpty {
                        Text("Genres: " + meta.genres.joined(separator: ", ")).font(.caption).foregroundColor(Theme.dim).lineLimit(1)
                    }
                    actionIcons
                    rating
                }
                .padding(.horizontal, 16)
                CastRow(meta: meta)
                if isSeries { episodes }
            }
            .padding(.bottom, 30)
        }
        .background(Theme.bg.ignoresSafeArea())
        .ignoresSafeArea(edges: .top)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar)
        .task {
            if !meta.full { await store.loadMeta(meta) }
            if !loaded {
                loaded = true
                if isSeries, let v = meta.video(playTarget) { season = v.season }
                else if let first = meta.seasons.first { season = first }
            }
            engine.prefetch(meta, playTarget)
        }
        .onChange(of: meta.full) { _ in
            if isSeries, let v = meta.video(playTarget) { season = v.season }
            engine.prefetch(meta, playTarget)
        }
        .onDisappear { engine.prefetchEnd() }
    }

    /// Netflix mobile: a wide picture at the top, then the title and the info line under it.
    private var header: some View {
        VStack(alignment: .leading, spacing: 10) {
            ZStack(alignment: .bottomLeading) {
                // The picture fills a fixed box; on its own a .fill image widens the page past the screen.
                Color.clear
                    .frame(maxWidth: .infinity)
                    .frame(height: 290)
                    .overlay(RemoteImage(url: meta.background ?? meta.poster))
                    .clipped()
                LinearGradient(colors: [.black.opacity(0.55), .clear, .clear, .black], startPoint: .top, endPoint: .bottom)
                if let logo = meta.logo {
                    RemoteImage(url: logo, contentMode: .fit).frame(maxWidth: 220, maxHeight: 70, alignment: .leading)
                        .padding(.horizontal, 16).padding(.bottom, 4)
                }
            }
            .frame(height: 290)
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 6) {
                    BrandWordmark(height: 14)
                    Text(isSeries ? "SERIES" : "FILM").font(.caption2.weight(.bold)).kerning(2).foregroundColor(Theme.red)
                }
                Text(meta.name).font(.title2.bold()).foregroundColor(.white)
                if !meta.metaLine.isEmpty {
                    Text(meta.metaLine).font(.footnote).foregroundColor(Theme.dim)
                }
            }
            .padding(.horizontal, 16)
        }
    }

    /// Your stars for this title (they feed Top Picks for You).
    private var rating: some View {
        HStack(spacing: 12) {
            Text(ratings.stars(meta.id) > 0 ? "Your rating" : "Rate this").font(.caption).foregroundColor(Theme.dim)
            StarRating(stars: ratings.stars(meta.id), size: 22) { s in
                ratings.set(meta, s)
                if s > 0 { engine.show(toast: "Rated \(s) star" + (s == 1 ? "" : "s")) }
            }
        }
        .padding(.top, 10)
    }

    /// Library and Sources as Netflix's small icon buttons under the description.
    private var actionIcons: some View {
        HStack(spacing: 34) {
            iconButton(store.inLibrary(meta.id) ? "checkmark" : "plus", "Library") {
                let inLib = store.inLibrary(meta.id)
                if inLib { engine.ctx("RemoveFromLibrary", meta.id) } else { engine.ctx("AddToLibrary", Store.preview(meta)) }
                store.markInLibrary(meta, !inLib)
                engine.show(toast: inLib ? "Removed from Library" : "Added to Library")
            }
            iconButton("list.bullet", "Sources") {
                engine.sourcesRequest = Engine.SourcesRequest(meta: meta, videoId: playTarget)
            }
            if let share = URL(string: "https://www.imdb.com/title/" + (SkipTimes.imdb(of: meta, nil) ?? "")), SkipTimes.imdb(of: meta, nil) != nil {
                ShareLink(item: share) { iconLabel("paperplane", "Share") }.buttonStyle(.plain)
            }
        }
        .padding(.top, 8)
        .padding(.leading, 8)
    }

    private func iconButton(_ icon: String, _ text: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { iconLabel(icon, text) }.buttonStyle(.plain)
    }

    private func iconLabel(_ icon: String, _ text: String) -> some View {
        VStack(spacing: 6) {
            Image(systemName: icon).font(.system(size: 22, weight: .regular))
            Text(text).font(.caption2).foregroundColor(Theme.dim)
        }
        .foregroundColor(.white)
        .frame(minWidth: 50)
    }

    private var buttons: some View {
        VStack(spacing: 8) {
            Button {
                engine.play(meta, isSeries ? playTarget : nil)
            } label: {
                Label(playLabel, systemImage: "play.fill")
            }
            .buttonStyle(RedButtonStyle())
            downloadButton
        }
        .confirmationDialog(askTitle, isPresented: Binding(get: { ask != nil }, set: { if !$0 { ask = nil } }), titleVisibility: .visible) {
            if let a = ask {
                if let vid = a.videoId {
                    ForEach(a.options) { o in
                        Button(o.label) {
                            dl.start(meta, vid, option: o)
                            engine.show(toast: Downloads.wifiOnly ? "Downloading (on Wi-Fi)" : "Downloading")
                        }
                    }
                } else if let s = a.season {
                    ForEach(DownloadPick.qualities, id: \.self) { q in
                        Button(q == "2160p" ? "4K (2160p)" : q == "480p" ? "480p (smallest)" : q) {
                            dl.queueSeason(meta, season: s, quality: q)
                            engine.show(toast: "Downloading Season \(s)")
                        }
                    }
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            if let a = ask, a.season != nil {
                Text("If an episode isn't available in that quality, Codio takes the closest lower one.")
            }
        }
        .alert("Delete this download?", isPresented: Binding(get: { confirmDelete != nil }, set: { if !$0 { confirmDelete = nil } })) {
            Button("Delete", role: .destructive) { if let e = confirmDelete { dl.remove(e) } }
            Button("Keep", role: .cancel) {}
        }
    }

    private var askTitle: String {
        if let s = ask?.season { return "Download Season \(s) in which quality?" }
        return "Download in which quality?"
    }

    @ViewBuilder private var downloadButton: some View {
        if isSeries {
            let eps = meta.videos.filter { $0.season == season }
            let got = eps.filter { dl.entry(meta.type, meta.id, $0.id)?.state == .done }.count
            let busy = eps.filter { [.downloading, .waiting, .finding].contains(dl.entry(meta.type, meta.id, $0.id)?.state) }.count
            Button {
                ask = DownloadAsk(videoId: nil, season: season, options: [])
            } label: {
                Label(busy > 0 ? "Downloading Season \(season) (\(got)/\(eps.count))"
                      : got == eps.count && got > 0 ? "Season \(season) downloaded" : "Download Season \(season)",
                      systemImage: "arrow.down.to.line")
            }
            .buttonStyle(RedButtonStyle(filled: false))
            .disabled(eps.isEmpty)
        } else if let e = dl.entry(meta.type, meta.id, meta.id) {
            Button {
                if e.state == .failed { dl.retry(e) } else { confirmDelete = e }
            } label: {
                Label(downloadStatusText(e), systemImage: e.state == .done ? "checkmark.circle.fill" : "arrow.down.circle")
            }
            .buttonStyle(RedButtonStyle(filled: false))
        } else {
            Button { askQuality(meta.id) } label: {
                Label(askingFor == meta.id ? "Finding sources…" : "Download", systemImage: "arrow.down.to.line")
            }
            .buttonStyle(RedButtonStyle(filled: false))
            .disabled(askingFor != nil)
        }
    }

    /// Look the sources up, then offer one per quality (with sizes).
    func askQuality(_ vid: String) {
        guard askingFor == nil else { return }
        askingFor = vid
        Task {
            var s = store.sourcesFor(meta, vid)
            if s == nil || s!.loading { s = await engine.findSources(meta, vid) }
            askingFor = nil
            let opts = s.map { DownloadPick.options($0) } ?? []
            if opts.isEmpty {
                engine.show(toast: engine.webBusy ? "Try again after the video" : "Can't download this (only debrid and direct links can be downloaded)")
                return
            }
            ask = DownloadAsk(videoId: vid, season: nil, options: opts)
            engine.prefetch(meta, playTarget)
        }
    }

    @ViewBuilder private var sourceStatus: some View {
        if let s = store.sourcesFor(meta, playTarget) {
            if let b = s.best {
                Text("Ready" + (b.qualityLabel.map { " in " + $0 } ?? "") + (b.cached ? " · instant" : ""))
                    .font(.caption).foregroundColor(.green)
            } else if s.loading {
                Text("Finding sources…").font(.caption).foregroundColor(Theme.dim)
            } else if s.list.isEmpty {
                Text("No sources found for this yet").font(.caption).foregroundColor(Theme.dim)
            }
        }
    }

    private var episodes: some View {
        VStack(alignment: .leading, spacing: 10) {
            let seasons = meta.seasons
            VStack(alignment: .leading, spacing: 0) {
                Rectangle().fill(Theme.red).frame(width: 80, height: 4)
                Text("EPISODES").font(.subheadline.weight(.bold)).foregroundColor(.white).padding(.top, 8)
            }
            .padding(.horizontal, 16)
            .padding(.top, 6)
            if seasons.count > 1 {
                Menu {
                    ForEach(seasons, id: \.self) { s in
                        Button(s == 0 ? "Specials" : "Season \(s)") { season = s }
                    }
                } label: {
                    HStack(spacing: 6) {
                        Text(season == 0 ? "Specials" : "Season \(season)")
                        Image(systemName: "chevron.down").font(.caption)
                    }
                    .font(.subheadline.weight(.semibold)).foregroundColor(.white)
                    .padding(.horizontal, 14).padding(.vertical, 9)
                    .background(RoundedRectangle(cornerRadius: 4).fill(Color(white: 0.17)))
                }
                .padding(.horizontal, 16)
            }
            LazyVStack(alignment: .leading, spacing: 14) {
                ForEach(meta.videos.filter { $0.season == season }) { v in
                    EpisodeRow(meta: meta, video: v, entry: dl.entry(meta.type, meta.id, v.id), finding: askingFor == v.id,
                               download: {
                                   if let e = dl.entry(meta.type, meta.id, v.id) {
                                       if e.state == .failed { dl.retry(e) } else { confirmDelete = e }
                                   } else { askQuality(v.id) }
                               })
                }
            }
            .padding(.horizontal, 16)
        }
    }
}

private struct EpisodeRow: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store
    let meta: Meta
    let video: Video
    let entry: Downloads.Entry?
    let finding: Bool
    let download: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 6) {
            episodeButton
            Button(action: download) {
                Group {
                    if finding { ProgressView().tint(Theme.red) } else { DownloadBadge(entry: entry) }
                }
                .frame(width: 40, height: 73)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }

    private var episodeButton: some View {
        Button {
            engine.play(meta, video.id)
        } label: {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .center, spacing: 12) {
                    ZStack {
                        RemoteImage(url: video.thumbnail ?? meta.background).frame(width: 130, height: 73).clipped()
                        Image(systemName: "play.circle").font(.system(size: 30, weight: .light)).foregroundColor(.white).shadow(radius: 3)
                    }
                    .frame(width: 130, height: 73)
                    .clipShape(RoundedRectangle(cornerRadius: 4))
                    VStack(alignment: .leading, spacing: 3) {
                        Text("\(video.episode). " + (video.title.isEmpty ? "Episode \(video.episode)" : video.title))
                            .font(.subheadline.weight(.medium)).foregroundColor(.white).lineLimit(2)
                        if let e = entry {
                            Text(downloadStatusText(e)).font(.caption2).foregroundColor(e.state == .done ? .white : Theme.dim).lineLimit(1)
                        } else if store.entry(meta.id)?.videoId == video.id {
                            Text("Last watched").font(.caption2.bold()).foregroundColor(Theme.red)
                        }
                    }
                    Spacer(minLength: 0)
                }
                if let o = video.overview {
                    Text(o).font(.caption).foregroundColor(Theme.dim).lineLimit(3)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button("Choose a source") { engine.sourcesRequest = Engine.SourcesRequest(meta: meta, videoId: video.id) }
        }
    }
}

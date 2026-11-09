import SwiftUI

/// A movie or show: backdrop, Play / Resume, My List, sources, episodes.
struct DetailView: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store
    @ObservedObject var meta: Meta
    @State private var season: Int = 1
    @State private var loaded = false

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
                VStack(alignment: .leading, spacing: 12) {
                    buttons
                    sourceStatus
                    if let d = meta.description {
                        Text(d).font(.subheadline).foregroundColor(Color(white: 0.85)).fixedSize(horizontal: false, vertical: true)
                    }
                    if !meta.cast.isEmpty {
                        Text("Cast: " + meta.cast.joined(separator: ", ")).font(.footnote).foregroundColor(Theme.dim)
                    }
                    if !meta.genres.isEmpty {
                        Text(meta.genres.joined(separator: "  ·  ")).font(.footnote).foregroundColor(Theme.dim)
                    }
                }
                .padding(.horizontal, 16)
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

    private var header: some View {
        ZStack(alignment: .bottomLeading) {
            RemoteImage(url: meta.background ?? meta.poster)
                .frame(maxWidth: .infinity)
                .frame(height: 300)
                .clipped()
            LinearGradient(colors: [.black.opacity(0.5), .clear, Theme.bg], startPoint: .top, endPoint: .bottom)
            VStack(alignment: .leading, spacing: 6) {
                if let logo = meta.logo {
                    RemoteImage(url: logo, contentMode: .fit).frame(maxWidth: 240, maxHeight: 80, alignment: .leading)
                } else {
                    Text(meta.name).font(.title.bold()).foregroundColor(.white)
                }
                if !meta.metaLine.isEmpty {
                    Text(meta.metaLine).font(.footnote).foregroundColor(Color(white: 0.85))
                }
            }
            .padding(.horizontal, 16)
        }
        .frame(height: 300)
    }

    private var buttons: some View {
        VStack(spacing: 8) {
            Button {
                engine.play(meta, isSeries ? playTarget : nil)
            } label: {
                Label(playLabel, systemImage: "play.fill")
            }
            .buttonStyle(RedButtonStyle())

            HStack(spacing: 8) {
                Button {
                    let inLib = store.inLibrary(meta.id)
                    if inLib { engine.ctx("RemoveFromLibrary", meta.id) } else { engine.ctx("AddToLibrary", Store.preview(meta)) }
                    store.markInLibrary(meta, !inLib)
                    engine.show(toast: inLib ? "Removed from My List" : "Added to My List")
                } label: {
                    Label("My List", systemImage: store.inLibrary(meta.id) ? "checkmark" : "plus")
                }
                .buttonStyle(RedButtonStyle(filled: false))

                Button {
                    engine.sourcesRequest = Engine.SourcesRequest(meta: meta, videoId: playTarget)
                } label: {
                    Label("Sources", systemImage: "list.bullet")
                }
                .buttonStyle(RedButtonStyle(filled: false))
            }
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
            if seasons.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(seasons, id: \.self) { s in
                            Button(s == 0 ? "Specials" : "Season \(s)") { season = s }
                                .font(.subheadline.weight(.semibold))
                                .padding(.horizontal, 14).padding(.vertical, 8)
                                .background(Capsule().fill(s == season ? Color.white : Color(white: 0.18)))
                                .foregroundColor(s == season ? .black : .white)
                        }
                    }
                    .padding(.horizontal, 16)
                }
            }
            LazyVStack(alignment: .leading, spacing: 14) {
                ForEach(meta.videos.filter { $0.season == season }) { v in
                    EpisodeRow(meta: meta, video: v)
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

    var body: some View {
        Button {
            engine.play(meta, video.id)
        } label: {
            HStack(alignment: .top, spacing: 12) {
                ZStack {
                    RemoteImage(url: video.thumbnail ?? meta.background).frame(width: 136, height: 76).clipped()
                    Image(systemName: "play.circle.fill").font(.title2).foregroundColor(.white.opacity(0.9))
                }
                .frame(width: 136, height: 76)
                .clipShape(RoundedRectangle(cornerRadius: 5))
                VStack(alignment: .leading, spacing: 4) {
                    Text("\(video.episode). " + (video.title.isEmpty ? "Episode \(video.episode)" : video.title))
                        .font(.subheadline.weight(.semibold)).foregroundColor(.white).lineLimit(2)
                    if let o = video.overview {
                        Text(o).font(.caption).foregroundColor(Theme.dim).lineLimit(3)
                    }
                    if store.entry(meta.id)?.videoId == video.id {
                        Text("Last watched").font(.caption2.bold()).foregroundColor(Theme.red)
                    }
                }
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button("Choose a source") { engine.sourcesRequest = Engine.SourcesRequest(meta: meta, videoId: video.id) }
        }
    }
}

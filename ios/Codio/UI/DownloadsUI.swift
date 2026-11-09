import SwiftUI

/// What a Download tap is asking: the quality for one video (with the real sources), or for a whole season.
struct DownloadAsk: Identifiable {
    let videoId: String?
    let season: Int?
    let options: [DownloadPick.Option]
    var id: String { (videoId ?? "season") + "\(season ?? -1)" }
}

/// Download state for one video: a ring while downloading, a red check when it's on the phone.
struct DownloadBadge: View {
    let entry: Downloads.Entry?

    var body: some View {
        if let e = entry {
            switch e.state {
            case .done:
                Image(systemName: "checkmark.circle.fill").foregroundStyle(Color.white, Theme.red).font(.title3)
            case .downloading:
                ZStack {
                    Circle().stroke(Color.white.opacity(0.25), lineWidth: 3)
                    Circle().trim(from: 0, to: max(0.03, e.progress)).stroke(Color.white, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                }
                .frame(width: 22, height: 22)
            case .waiting, .finding:
                Image(systemName: "clock").foregroundColor(Theme.dim)
            case .failed:
                Image(systemName: "exclamationmark.circle").foregroundColor(.orange)
            }
        } else {
            Image(systemName: "arrow.down.to.line").foregroundColor(.white)
        }
    }
}

func downloadStatusText(_ e: Downloads.Entry) -> String {
    let size = e.bytes > 0 ? ByteCountFormatter.string(fromByteCount: e.bytes, countStyle: .file) : nil
    let q = e.quality == "2160p" ? "4K" : e.quality
    switch e.state {
    case .done: return ["On this phone", q, size].compactMap { $0 }.joined(separator: " · ")
    case .downloading:
        return "Downloading \(Int(e.progress * 100))%" + (q.map { " · " + $0 } ?? "") + (size.map { " of " + $0 } ?? "")
            + (Downloads.wifiOnly ? " · waits for Wi-Fi" : "")
    case .waiting: return "Waiting"
    case .finding: return "Finding a source…"
    case .failed: return e.error ?? "Download failed"
    }
}

/// Library > Downloads: one row per title; a show opens its downloaded episodes.
struct DownloadsListView: View {
    @EnvironmentObject var engine: Engine
    @ObservedObject private var dl = Downloads.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Using " + ByteCountFormatter.string(fromByteCount: dl.usedBytes, countStyle: .file)
                 + (Downloads.freeBytes > 0 ? " · " + ByteCountFormatter.string(fromByteCount: Downloads.freeBytes, countStyle: .file) + " free" : "")
                 + (Downloads.wifiOnly ? " · Wi-Fi only" : ""))
                .font(.caption).foregroundColor(Theme.dim).padding(.horizontal, 16)
            if dl.titles.isEmpty {
                Text("Download movies and episodes from their page to watch them without internet.")
                    .foregroundColor(Theme.dim).frame(maxWidth: .infinity).padding(.top, 30).padding(.horizontal, 24)
                    .multilineTextAlignment(.center)
            }
            ForEach(dl.titles) { t in
                if t.isEpisode {
                    NavigationLink(value: DownloadedShow(metaId: t.metaId)) { titleRow(t) }.buttonStyle(.plain)
                } else {
                    Button { play(t) } label: { titleRow(t) }.buttonStyle(.plain)
                        .contextMenu { Button("Delete download", role: .destructive) { dl.remove(t) } }
                }
            }
        }
    }

    private func titleRow(_ t: Downloads.Entry) -> some View {
        let eps = dl.forMeta(t.metaId)
        let sub: String = t.isEpisode
            ? "\(eps.filter { $0.state == .done }.count) of \(eps.count) episodes downloaded"
            : downloadStatusText(t)
        return HStack(spacing: 12) {
            RemoteImage(url: t.poster).frame(width: 64, height: 96).clipShape(RoundedRectangle(cornerRadius: 5))
            VStack(alignment: .leading, spacing: 4) {
                Text(t.name).font(.headline).foregroundColor(.white).lineLimit(2)
                Text(sub).font(.caption).foregroundColor(Theme.dim).lineLimit(2)
            }
            Spacer()
            if t.isEpisode { Image(systemName: "chevron.right").foregroundColor(Theme.dim) } else { DownloadBadge(entry: t) }
        }
        .padding(.horizontal, 16)
        .contentShape(Rectangle())
    }

    private func play(_ e: Downloads.Entry) {
        guard e.state == .done else {
            if e.state == .failed { dl.retry(e) }
            return
        }
        let m = Meta(id: e.metaId, type: e.type, name: e.name)
        m.poster = e.poster
        m.background = e.background
        engine.play(m, e.videoId)
    }
}

struct DownloadedShow: Hashable {
    let metaId: String
}

struct DownloadedShowView: View {
    @EnvironmentObject var engine: Engine
    @ObservedObject private var dl = Downloads.shared
    let show: DownloadedShow

    var body: some View {
        let eps = dl.forMeta(show.metaId)
        List {
            ForEach(eps) { e in
                Button {
                    if e.state == .done {
                        let m = Meta(id: e.metaId, type: e.type, name: e.name)
                        m.poster = e.poster
                        m.background = e.background
                        engine.play(m, e.videoId)
                    } else if e.state == .failed { dl.retry(e) }
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("S\(e.season) E\(e.episode)" + (e.episodeTitle.map { " · " + $0 } ?? "")).foregroundColor(.white)
                            Text(downloadStatusText(e)).font(.caption).foregroundColor(Theme.dim)
                        }
                        Spacer()
                        DownloadBadge(entry: e)
                    }
                }
                .listRowBackground(Color(white: 0.08))
                .swipeActions { Button("Delete", role: .destructive) { dl.remove(e) } }
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.bg.ignoresSafeArea())
        .navigationTitle(eps.first?.name ?? "Downloads")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("Delete all", role: .destructive) { dl.removeAll(show.metaId) }
            }
        }
    }
}

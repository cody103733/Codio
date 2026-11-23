import SwiftUI

struct ForYouTarget: Hashable {}

/// For You (the "For You" pill on Home): the day's first pick big, with why it's there, then rows
/// (Top picks, Because you watched …, Something different, More you might like). DESIGN.md "For You".
struct ForYouView: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store
    @ObservedObject private var forYou = ForYou.shared
    @ObservedObject private var ratings = Ratings.shared

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 26) {
                if let first = forYou.rows.first?.items.first {
                    PickCard(meta: first, reason: forYou.reasons[first.id]).padding(.horizontal, 16)
                    ForEach(forYou.rows) { r in PosterRow(title: r.title, items: r.items) }
                    Button("New picks") { Task { await forYou.refresh(store, force: true) } }
                        .font(.subheadline.weight(.semibold)).foregroundColor(Theme.red)
                        .frame(maxWidth: .infinity)
                    Text("This product uses the TMDB API but is not endorsed or certified by TMDB.")
                        .font(.caption2).foregroundColor(Theme.faint).multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity).padding(.horizontal, 24)
                } else {
                    message
                }
            }
            .padding(.top, 6)
            .padding(.bottom, 30)
        }
        .background(Color.black.ignoresSafeArea())
        .navigationTitle("For You")
        .profileToolbar()
        .refreshable { await Task { @MainActor in await forYou.refresh(store, force: true) }.value }
        .task(id: "\(ratings.all.count)|\(store.library.count)") { await forYou.refresh(store) }
    }

    @ViewBuilder private var message: some View {
        VStack(spacing: 16) {
            switch forYou.state {
            case .loading:
                ProgressView().tint(Theme.red)
                Text("Finding titles that fit what you watch…").foregroundColor(Theme.dim)
            case .noKey:
                Text("For You uses TMDB to find titles like the ones you watch. Add your free TMDB key in Settings to turn it on.")
                    .foregroundColor(Theme.dim)
            case .noHistory:
                Text("Watch a few movies or shows, or rate them, and your picks show up here.").foregroundColor(Theme.dim)
            case .failed, .ok:
                Text("Couldn't reach TMDB for your picks. Check the connection and try again.").foregroundColor(Theme.dim)
                Button("Try again") { Task { await forYou.refresh(store, force: true) } }
                    .font(.headline).foregroundColor(Theme.red)
            }
        }
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 24)
        .padding(.top, 60)
    }
}

/// The day's first pick: its wide picture, why it's here, Play and More info.
private struct PickCard: View {
    @EnvironmentObject var engine: Engine
    @ObservedObject var meta: Meta
    let reason: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            NavigationLink(value: meta) {
                ZStack(alignment: .bottomLeading) {
                    RemoteImage(url: meta.background ?? meta.poster)
                        .aspectRatio(16 / 9, contentMode: .fill)
                        .frame(maxWidth: .infinity)
                        .clipped()
                    LinearGradient(colors: [.clear, .clear, .black.opacity(0.85)], startPoint: .top, endPoint: .bottom)
                    Text(meta.name).font(.title2.bold()).foregroundColor(.white).lineLimit(2).padding(12)
                }
                .aspectRatio(16 / 9, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 10))
            }
            .buttonStyle(.plain)
            if let r = reason, !r.isEmpty {
                Text(r).font(.subheadline.weight(.semibold)).foregroundColor(Theme.red).padding(.top, 6)
            }
            let line = ([meta.releaseInfo, meta.imdbRating.map { "★ " + $0 }].compactMap { $0 } + meta.genres).joined(separator: "  ·  ")
            if !line.isEmpty { Text(line).font(.footnote).foregroundColor(Theme.dim) }
            if let d = meta.description { Text(d).font(.subheadline).foregroundColor(Theme.dim).lineLimit(3).padding(.top, 2) }
            HStack(spacing: 10) {
                if meta.type == "movie" {
                    Button { engine.play(meta, nil) } label: { label("Play", "play.fill", primary: true) }
                } else {
                    NavigationLink(value: meta) { label("Play", "play.fill", primary: true) }
                }
                NavigationLink(value: meta) { label("More info", "info.circle", primary: false) }
            }
            .buttonStyle(.plain)
            .padding(.top, 8)
        }
    }

    private func label(_ t: String, _ icon: String, primary: Bool) -> some View {
        Label(t, systemImage: icon).font(.headline).foregroundColor(primary ? .black : .white)
            .frame(maxWidth: .infinity).padding(.vertical, 10)
            .background(RoundedRectangle(cornerRadius: 4).fill(primary ? Color.white : Theme.raised.opacity(0.9)))
    }
}

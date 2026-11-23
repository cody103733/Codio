import SwiftUI

/// Your 1 to 5 star ratings, kept on this iPhone.
@MainActor
final class Ratings: ObservableObject {
    static let shared = Ratings()

    struct Rated: Codable {
        var stars: Int
        var type: String
        var name: String
        var poster: String?
        var date: Date
    }

    @Published private(set) var all: [String: Rated] = [:]
    /// Titles we already asked "How was it?" about, so we only ask once.
    private var asked: Set<String> = []
    private var key = "ratings_v1"
    private var loadedFor: String?

    init() {
        if let d = UserDefaults.standard.data(forKey: key), let r = try? JSONDecoder().decode([String: Rated].self, from: d) { all = r }
        asked = Set(UserDefaults.standard.stringArray(forKey: "ratings_asked") ?? [])
    }

    /// Each profile has its own ratings. The first account keeps the ratings made before profiles existed.
    func load(for email: String) {
        let e = email.lowercased()
        guard loadedFor != e else { return }
        loadedFor = e
        let k = "ratings_v1|" + e
        if UserDefaults.standard.data(forKey: k) == nil, let old = UserDefaults.standard.data(forKey: "ratings_v1"),
           !UserDefaults.standard.bool(forKey: "ratings_migrated") {
            UserDefaults.standard.set(old, forKey: k)
            UserDefaults.standard.set(true, forKey: "ratings_migrated")
        }
        key = k
        all = UserDefaults.standard.data(forKey: k).flatMap { try? JSONDecoder().decode([String: Rated].self, from: $0) } ?? [:]
        ForYou.shared.reset()
    }

    func stars(_ id: String) -> Int { all[id]?.stars ?? 0 }

    func set(_ m: Meta, _ stars: Int) {
        if stars <= 0 { all[m.id] = nil } else {
            all[m.id] = Rated(stars: stars, type: m.type, name: m.name, poster: m.poster, date: Date())
        }
        if let d = try? JSONEncoder().encode(all) { UserDefaults.standard.set(d, forKey: key) }
        ForYou.shared.invalidate()
    }

    func shouldAsk(_ id: String) -> Bool { all[id] == nil && !asked.contains(id) }

    func markAsked(_ id: String) {
        asked.insert(id)
        UserDefaults.standard.set(Array(asked), forKey: "ratings_asked")
    }
}

/// Five tappable stars. Tapping the current rating again clears it.
struct StarRating: View {
    let stars: Int
    var size: CGFloat = 26
    let onSet: (Int) -> Void

    var body: some View {
        HStack(spacing: size * 0.3) {
            ForEach(1...5, id: \.self) { i in
                Button { onSet(i == stars ? 0 : i) } label: {
                    Image(systemName: i <= stars ? "star.fill" : "star")
                        .font(.system(size: size))
                        .foregroundColor(i <= stars ? Theme.red : Theme.faint)
                }
                .buttonStyle(.plain)
            }
        }
    }
}

/// "How was it?" after a movie or a show's last episode.
struct RateCard: View {
    @EnvironmentObject var engine: Engine
    let meta: Meta
    @State private var picked = 0

    var body: some View {
        VStack(spacing: 14) {
            HStack(alignment: .top, spacing: 12) {
                RemoteImage(url: meta.poster).frame(width: 60, height: 90).clipShape(RoundedRectangle(cornerRadius: 4))
                VStack(alignment: .leading, spacing: 4) {
                    Text("How was it?").font(.headline).foregroundColor(.white)
                    Text(meta.name).font(.subheadline).foregroundColor(Theme.dim).lineLimit(2)
                    Text("Your stars tune For You.").font(.caption).foregroundColor(Theme.dim)
                }
                Spacer()
            }
            StarRating(stars: picked, size: 32) { s in
                picked = s
                guard s > 0 else { return }
                Ratings.shared.set(meta, s)
                Task {
                    try? await Task.sleep(nanoseconds: 450_000_000)
                    engine.askRating = nil
                    engine.show(toast: "Thanks! Your picks will update.")
                }
            }
            Button("Not now") { engine.askRating = nil }
                .font(.subheadline).foregroundColor(Theme.dim)
        }
        .padding(18)
        .background(RoundedRectangle(cornerRadius: 14).fill(Theme.card))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.red.opacity(0.5), lineWidth: 1))
        .padding(.horizontal, 16)
        .onAppear { Ratings.shared.markAsked(meta.id) }
    }
}

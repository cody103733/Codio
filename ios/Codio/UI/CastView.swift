import SwiftUI

/// Actors with photos and character names, from TMDB (looked up by the title's IMDb id).
enum TMDB {
    struct Person: Identifiable {
        let id: Int
        let name: String
        let character: String
        let photo: String?
    }

    /// The key from the build (GitHub secret TMDB_KEY) or the one pasted in Settings.
    static var key: String? {
        if let k = UserDefaults.standard.string(forKey: "tmdb_key"), !k.isEmpty { return k }
        if let k = Bundle.main.infoDictionary?["CodioTMDBKey"] as? String, !k.isEmpty, !k.hasPrefix("$(") { return k }
        return nil
    }

    @MainActor private static var cache: [String: [Person]] = [:]

    @MainActor
    static func cast(for meta: Meta) async -> [Person]? {
        guard let key = key, let imdb = SkipTimes.imdb(of: meta, nil) else { return nil }
        if let c = cache[imdb] { return c }
        let base = "https://api.themoviedb.org/3"
        guard let found = try? await Net.json("\(base)/find/\(imdb)?api_key=\(key)&external_source=imdb_id") as? JSON else { return nil }
        let isTV: Bool
        let tmdbId: Int
        if let m = (found["movie_results"] as? [JSON])?.first, let id = jint(m, "id") {
            isTV = false
            tmdbId = id
        } else if let t = (found["tv_results"] as? [JSON])?.first, let id = jint(t, "id") {
            isTV = true
            tmdbId = id
        } else { return [] }
        let url = isTV ? "\(base)/tv/\(tmdbId)/aggregate_credits?api_key=\(key)" : "\(base)/movie/\(tmdbId)/credits?api_key=\(key)"
        guard let credits = try? await Net.json(url) as? JSON else { return nil }
        let people: [Person] = ((credits["cast"] as? [JSON]) ?? []).prefix(40).compactMap { p in
            guard let id = jint(p, "id"), let name = jstr(p, "name") else { return nil }
            let character = jstr(p, "character") ?? ((p["roles"] as? [JSON])?.first.flatMap { jstr($0, "character") }) ?? ""
            return Person(id: id, name: name, character: character,
                          photo: jstr(p, "profile_path").map { "https://image.tmdb.org/t/p/w185" + $0 })
        }
        cache[imdb] = people
        return people
    }
}

/// A sideways row of actor photos (movie/show page).
struct CastRow: View {
    @ObservedObject var meta: Meta
    @State private var people: [TMDB.Person] = []

    var body: some View {
        Group {
            if !people.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Cast").font(.headline).foregroundColor(.white).padding(.horizontal, 16)
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(alignment: .top, spacing: 12) {
                            ForEach(people) { p in PersonCard(person: p) }
                        }
                        .padding(.horizontal, 16)
                    }
                    Text("This product uses the TMDB API but is not endorsed or certified by TMDB.")
                        .font(.caption2).foregroundColor(Theme.dim).padding(.horizontal, 16)
                }
            }
        }
        .task(id: meta.id) { people = await TMDB.cast(for: meta) ?? [] }
    }
}

private struct PersonCard: View {
    let person: TMDB.Person
    var body: some View {
        VStack(spacing: 4) {
            RemoteImage(url: person.photo).frame(width: 76, height: 76).clipShape(Circle())
            Text(person.name).font(.caption.weight(.semibold)).foregroundColor(.white).lineLimit(2).multilineTextAlignment(.center)
            if !person.character.isEmpty {
                Text(person.character).font(.caption2).foregroundColor(Theme.dim).lineLimit(2).multilineTextAlignment(.center)
            }
        }
        .frame(width: 86)
    }
}

/// The player's actors panel.
struct CastSheet: View {
    @Environment(\.dismiss) private var dismiss
    let meta: Meta
    @State private var people: [TMDB.Person]?

    var body: some View {
        NavigationStack {
            Group {
                if TMDB.key == nil {
                    Text("Add your TMDB key in Settings to see the cast.").foregroundColor(Theme.dim).padding()
                } else if let p = people {
                    if p.isEmpty {
                        Text("No cast found for this title.").foregroundColor(Theme.dim)
                    } else {
                        List(p) { person in
                            HStack(spacing: 12) {
                                RemoteImage(url: person.photo).frame(width: 52, height: 52).clipShape(Circle())
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(person.name).foregroundColor(.white)
                                    if !person.character.isEmpty { Text(person.character).font(.caption).foregroundColor(Theme.dim) }
                                }
                            }
                            .listRowBackground(Color(white: 0.1))
                        }
                        .scrollContentBackground(.hidden)
                    }
                } else {
                    ProgressView().tint(Theme.red)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Theme.bg.ignoresSafeArea())
            .navigationTitle("Cast")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
        }
        .preferredColorScheme(.dark)
        .task { people = await TMDB.cast(for: meta) ?? [] }
    }
}

import Foundation

// Music: the shapes the Music screens share. Same fields as the Windows app (windows/src/routes/Codio/music/types.ts).

struct MusicTrack: Codable, Hashable {
    /// The id the track has where it came from: an addon's own id, or "mbid:<recording id>" from MusicBrainz.
    var id: String
    var title: String
    var artist: String
    var album: String?
    var durationMs: Int?
    var artworkURL: String?
    var isrc: String?
    /// MusicBrainz recording id, when known.
    var mbid: String?
    /// MusicBrainz id of the (first) artist, when known; discovery uses it.
    var artistMbid: String?
    /// The addon (its base link) this track came from. Playing asks that addon first.
    var addon: String?
    /// A direct audio link the addon gave with the track.
    var streamURL: String?

    var key: String { (addon ?? "") + "|" + id }
}

struct MusicArtist: Codable, Hashable {
    var id: String
    var name: String
    var detail: String?
    var artworkURL: String?
    var addon: String?
}

struct MusicAlbum: Codable, Hashable {
    var id: String
    var title: String
    var artist: String
    var year: String?
    var kind: String?
    var artworkURL: String?
    var addon: String?
    /// MusicBrainz id of the (first) artist, when known.
    var artistMbid: String?
}

struct MusicResults {
    var artists: [MusicArtist] = []
    var albums: [MusicAlbum] = []
    var tracks: [MusicTrack] = []
    var isEmpty: Bool { artists.isEmpty && albums.isEmpty && tracks.isEmpty }
}

/// A music addon: a plain HTTP server that follows Eclipse Music's addon protocol (https://eclipsemusic.app/docs).
/// The list lives on this phone, and goes locked to your other devices with the sync passcode (Store/Vault.swift).
struct MusicAddon: Codable, Hashable {
    /// The addon's base link, without manifest.json. A personal token in the link is part of it.
    var url: String
    /// The manifest, as JSON text.
    var manifestJSON: String

    var manifest: JSON { (parseJSON(manifestJSON) as? JSON) ?? [:] }
    var id: String { jstr(manifest, "id") ?? url }
    var name: String { jstr(manifest, "name") ?? "Addon" }
    var version: String? { jstr(manifest, "version") }
    func has(_ resource: String) -> Bool {
        ((manifest["resources"] as? [Any]) ?? []).contains { ($0 as? String) == resource }
    }
    var catalogs: [JSON] { ((manifest["catalogs"] as? [Any]) ?? []).compactMap { $0 as? JSON } }
    /// The values the addon's own settings default to; Eclipse sends them with every call.
    var settingDefaults: [String: String] {
        var out: [String: String] = [:]
        for s in ((manifest["settings"] as? [Any]) ?? []).compactMap({ $0 as? JSON }) {
            if let k = jstr(s, "key"), let d = s["default"], !(d is NSNull) { out[k] = "\(d)" }
        }
        return out
    }
}

/// A short helper: a non-empty string, or nil.
func mstr(_ v: Any?) -> String? {
    if let s = v as? String, !s.isEmpty { return s }
    return nil
}

func marr(_ v: Any?) -> [JSON] {
    ((v as? [Any]) ?? []).compactMap { $0 as? JSON }
}

func mfirst(_ v: Any?) -> String? {
    if let a = v as? [Any], let s = a.first as? String { return s }
    return nil
}

func mint(_ v: Any?) -> Int? {
    if let n = v as? NSNumber { return n.intValue }
    return nil
}

let mbidPattern = try! NSRegularExpression(pattern: "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
func isMbid(_ s: String?) -> Bool {
    guard let s = s else { return false }
    return mbidPattern.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) != nil
}

/// Percent-encoding for a query value.
func menc(_ s: String) -> String {
    s.addingPercentEncoding(withAllowedCharacters: CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~")) ?? s
}

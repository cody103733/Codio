import Foundation

/// Songs kept on the iPhone to play offline (DESIGN.md "Music downloads", same rules as
/// android/.../music/MusicDownloads.java). One song downloads at a time: its audio (found like playing finds it), its
/// cover and its lyrics, into Application Support/MusicDownloads. Downloads follow the "Download over" setting (Wi-Fi
/// only by default: they wait for Wi-Fi) and pick up again the next time Codio opens. The player plays a downloaded song
/// from the file.
@MainActor
final class MusicDownloads: ObservableObject {
    static let shared = MusicDownloads()

    enum State: String, Codable { case queued, downloading, done, failed }

    struct Entry: Codable, Identifiable {
        var track: MusicTrack
        var key: String
        var state: State = .queued
        /// File names inside the downloads folder (the folder's path changes when the app updates).
        var file: String?
        var cover: String?
        var lyrics: String?
        var error: String?
        var bytes: Int64 = 0
        var added = Date()
        var id: String { key }
    }

    @Published private(set) var entries: [Entry] = []
    private let indexKey = "music_downloads_v1"
    private var running = false
    private var current: Task<Void, Never>?

    static let dir: URL = {
        let d = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("MusicDownloads", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        var v = URLResourceValues()
        v.isExcludedFromBackup = true
        var u = d
        try? u.setResourceValues(v)
        return d
    }()

    init() {
        if let data = UserDefaults.standard.data(forKey: indexKey), let l = try? JSONDecoder().decode([Entry].self, from: data) {
            entries = l.map { e in
                var e = e
                // a download cut off when Codio closed starts again
                if e.state == .downloading { e.state = .queued }
                if e.state == .done && !Self.exists(e.file) { e.state = .queued }
                return e
            }
        }
        pump()
    }

    private static func exists(_ name: String?) -> Bool {
        guard let n = name else { return false }
        return FileManager.default.fileExists(atPath: dir.appendingPathComponent(n).path)
    }

    private func save() {
        if let data = try? JSONEncoder().encode(entries) { UserDefaults.standard.set(data, forKey: indexKey) }
    }

    // MARK: what the screens and the player ask

    func entry(_ t: MusicTrack) -> Entry? {
        if let e = entries.first(where: { $0.key == t.key }) { return e }
        // the same song reached another way (an album page vs a search) counts too
        guard !MusicMatch.normalise(t.title).isEmpty else { return nil }
        return entries.first { MusicMatch.isSameRecording($0.track, t) }
    }

    func isDone(_ e: Entry) -> Bool { e.state == .done && Self.exists(e.file) }

    /// The audio file of a downloaded song, or nil.
    func fileURL(_ t: MusicTrack) -> URL? {
        guard let e = entry(t), isDone(e), let f = e.file else { return nil }
        return Self.dir.appendingPathComponent(f)
    }

    /// The track to play or show: with the saved cover when there is one.
    func playable(_ e: Entry) -> MusicTrack {
        var t = e.track
        if let c = e.cover, Self.exists(c) { t.artworkURL = Self.dir.appendingPathComponent(c).absoluteString }
        return t
    }

    /// The finished downloads as tracks to play, newest first.
    var doneTracks: [MusicTrack] { entries.reversed().filter { isDone($0) }.map { playable($0) } }

    /// Lyrics saved with a downloaded song: nil when not downloaded, empty when it has none.
    func savedLyrics(_ t: MusicTrack) -> SongLyrics? {
        guard let e = entry(t), isDone(e), let f = e.lyrics else { return nil }
        guard let data = try? Data(contentsOf: Self.dir.appendingPathComponent(f)),
              let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return SongLyrics() }
        return SongLyrics(json: o)
    }

    /// How many of these songs are downloaded, and how many are still on their way.
    func count(_ tracks: [MusicTrack]) -> (done: Int, pending: Int) {
        var done = 0, pending = 0
        for t in tracks {
            guard let e = entry(t) else { continue }
            if isDone(e) { done += 1 } else if e.state != .failed { pending += 1 }
        }
        return (done, pending)
    }

    /// What the downloads are doing, in a few words, or nil.
    var status: String? {
        let going = entries.filter { $0.state == .queued || $0.state == .downloading }.count
        let failed = entries.filter { $0.state == .failed }.count
        if going > 0 { return "Downloading \(going) song\(going == 1 ? "" : "s")" + (Downloads.wifiOnly ? " (on Wi-Fi)" : "") }
        if failed > 0 { return "\(failed) download\(failed == 1 ? "" : "s") failed" }
        return nil
    }

    var usedBytes: Int64 {
        entries.filter { isDone($0) }.reduce(0) { n, e in
            let attrs = try? FileManager.default.attributesOfItem(atPath: Self.dir.appendingPathComponent(e.file ?? "").path)
            let size = (attrs?[.size] as? NSNumber)?.int64Value ?? 0
            return n + size
        }
    }

    /// Queues these songs (ones already downloaded or on their way are skipped; failed ones are tried again).
    func add(_ tracks: [MusicTrack]) {
        for t in tracks {
            if let existing = entry(t), let i = entries.firstIndex(where: { $0.key == existing.key }) {
                if entries[i].state == .failed {
                    entries[i].state = .queued
                    entries[i].error = nil
                }
                continue
            }
            entries.append(Entry(track: t, key: t.key))
        }
        save()
        pump()
    }

    func remove(_ tracks: [MusicTrack]) {
        for t in tracks {
            guard let e = entry(t) else { continue }
            entries.removeAll { $0.key == e.key }
            deleteFiles(e)
        }
        save()
    }

    func removeAll() {
        for e in entries { deleteFiles(e) }
        entries = []
        current?.cancel()
        save()
    }

    private func deleteFiles(_ e: Entry) {
        for f in [e.file, e.cover, e.lyrics].compactMap({ $0 }) {
            try? FileManager.default.removeItem(at: Self.dir.appendingPathComponent(f))
        }
    }

    // MARK: the worker

    private static let session: URLSession = {
        let c = URLSessionConfiguration.default
        // Wi-Fi only waits for Wi-Fi instead of failing
        c.waitsForConnectivity = true
        c.timeoutIntervalForRequest = 60
        c.timeoutIntervalForResource = 3 * 3600
        return URLSession(configuration: c)
    }()

    private func pump() {
        guard !running, let next = entries.first(where: { $0.state == .queued }) else { return }
        running = true
        let key = next.key
        if let i = entries.firstIndex(where: { $0.key == key }) { entries[i].state = .downloading }
        current = Task { [weak self] in
            guard let self = self else { return }
            let result = await Self.fetch(next.track, name: Self.fileName(key), addons: MusicAddons.shared.list)
            if let i = self.entries.firstIndex(where: { $0.key == key }) {
                if let files = result.files {
                    self.entries[i].state = .done
                    self.entries[i].file = files.audio
                    self.entries[i].cover = files.cover
                    self.entries[i].lyrics = files.lyrics
                    self.entries[i].bytes = files.bytes
                } else {
                    self.entries[i].state = .failed
                    self.entries[i].error = result.error ?? "Download failed"
                }
            } else if let files = result.files {
                // removed while it downloaded
                for f in [files.audio, files.cover, files.lyrics].compactMap({ $0 }) { try? FileManager.default.removeItem(at: Self.dir.appendingPathComponent(f)) }
            }
            self.running = false
            self.save()
            self.pump()
        }
    }

    private static func fileName(_ key: String) -> String {
        var h: UInt64 = 1469598103934665603
        for b in key.utf8 { h = (h ^ UInt64(b)) &* 1099511628211 }
        return String(h, radix: 16)
    }

    struct Files {
        var audio: String
        var cover: String?
        var lyrics: String?
        var bytes: Int64
    }

    private static func ext(_ response: URLResponse?, url: URL, format: String?) -> String {
        let type = (response?.mimeType ?? "").lowercased()
        if type.contains("flac") { return "flac" }
        if type.contains("mpeg") || type.contains("mp3") { return "mp3" }
        if type.contains("mp4") || type.contains("m4a") || type.contains("aac") { return "m4a" }
        if type.contains("ogg") { return "ogg" }
        if type.contains("opus") { return "opus" }
        if type.contains("webm") { return "webm" }
        if type.contains("wav") { return "wav" }
        let f = (format ?? "").lowercased()
        if ["flac", "mp3", "m4a", "ogg", "opus", "webm", "wav"].contains(f) { return f }
        if f == "aac" { return "m4a" }
        let e = url.pathExtension.lowercased()
        return e.isEmpty || e.count > 4 ? "audio" : e
    }

    struct Outcome {
        var files: Files?
        var error: String?
    }

    nonisolated private static func fail(_ text: String) -> Outcome { Outcome(files: nil, error: text) }

    private static func fetch(_ t: MusicTrack, name: String, addons: [MusicAddon]) async -> Outcome {
        if addons.isEmpty { return fail("No music addon installed") }
        let lookup: (() async -> String?)? = t.mbid.map { m in { await MB.recordingIsrc(m) } }
        guard let found = await MusicMatch.findAudio(t, addons: addons, isrcLookup: lookup) else { return fail("No source available") }
        guard let url = URL(string: found.url) ?? URL(string: found.url.addingPercentEncoding(withAllowedCharacters: .urlFragmentAllowed) ?? "") else { return fail("Bad link") }
        var request = URLRequest(url: url)
        request.allowsCellularAccess = !Downloads.wifiOnly
        request.setValue(MusicNet.agent, forHTTPHeaderField: "User-Agent")
        do {
            let (temp, response) = try await session.download(for: request)
            if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
                try? FileManager.default.removeItem(at: temp)
                return fail("The source answered \(http.statusCode)")
            }
            let size = ((try? FileManager.default.attributesOfItem(atPath: temp.path)[.size]) as? NSNumber)?.int64Value ?? 0
            if size < 16 * 1024 {
                try? FileManager.default.removeItem(at: temp)
                return fail("The source sent no song")
            }
            let audio = name + "." + ext(response, url: response.url ?? url, format: found.format)
            let target = dir.appendingPathComponent(audio)
            try? FileManager.default.removeItem(at: target)
            try FileManager.default.moveItem(at: temp, to: target)
            var files = Files(audio: audio, cover: nil, lyrics: nil, bytes: size)
            // the cover, so it shows offline
            if let a = t.artworkURL, a.hasPrefix("http"), let au = URL(string: a) {
                do {
                    let (data, r) = try await MusicNet.session.data(from: au)
                    let cover = name + ".jpg"
                    if (r as? HTTPURLResponse)?.statusCode == 200, !data.isEmpty, (try? data.write(to: dir.appendingPathComponent(cover))) != nil {
                        files.cover = cover
                    }
                } catch {}
            }
            // the lyrics, so they show offline too (empty when LRCLIB has none)
            do {
                let l = try await Lyrics.find(t, durationMs: t.durationMs ?? 0)
                let lyrics = name + ".lyrics.json"
                let json: [String: Any] = l?.json ?? [:]
                if let data = try? JSONSerialization.data(withJSONObject: json), (try? data.write(to: dir.appendingPathComponent(lyrics))) != nil {
                    files.lyrics = lyrics
                }
            } catch {}
            return Outcome(files: files, error: nil)
        } catch {
            return fail(Task.isCancelled ? "Cancelled" : "Download failed: check your connection")
        }
    }
}

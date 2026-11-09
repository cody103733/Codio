import Foundation

/// Picks what to download from a source list: one source per quality (same rules as the Android phone app).
enum DownloadPick {
    static let qualities = ["2160p", "1440p", "1080p", "720p", "480p"]

    struct Option: Identifiable {
        var quality: String?
        var bytes: Int64
        var source: Source
        var id: String { source.href }

        var label: String {
            let q = quality == "2160p" ? "4K (2160p)" : (quality ?? "Best available")
            return bytes > 0 ? q + "  ·  " + ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file) : q
        }
    }

    private static let patterns: [NSRegularExpression] = [
        "(^|[^a-z0-9])(2160p|4k|uhd)([^a-z0-9]|$)",
        "(^|[^a-z0-9])(1440p|2k|qhd)([^a-z0-9]|$)",
        "(^|[^a-z0-9])(1080p|1080i|fhd)([^a-z0-9]|$)",
        "(^|[^a-z0-9])(720p)([^a-z0-9]|$)",
        "(^|[^a-z0-9])(576p|480p|360p|240p|sd|dvdrip)([^a-z0-9]|$)",
    ].map { try! NSRegularExpression(pattern: $0, options: .caseInsensitive) }
    private static let size = try! NSRegularExpression(pattern: "(\\d+(?:[.,]\\d+)?)\\s*(TB|GB|MB|TiB|GiB|MiB)(?![a-z])", options: .caseInsensitive)
    private static let cached = try! NSRegularExpression(pattern: "\\[(RD|AD|PM|DL|TB|OC|ED|EN|PKP|DLS|ST|TRB)\\+\\]|⚡|(^|[^a-z])(instant|cached)([^a-z]|$)", options: .caseInsensitive)
    private static let uncached = try! NSRegularExpression(pattern: "\\[(RD|AD|PM|DL|TB|OC|ED|EN|PKP|DLS|ST|TRB) ?download\\]|⏳|(^|[^a-z])uncached([^a-z]|$)", options: .caseInsensitive)

    private static func text(_ s: Source) -> String { (s.name ?? "") + " \n " + (s.description ?? "") }
    private static func matches(_ r: NSRegularExpression, _ t: String) -> Bool {
        r.firstMatch(in: t, range: NSRange(t.startIndex..., in: t)) != nil
    }

    static func quality(_ s: Source) -> String? {
        if let q = s.quality { return q }
        let t = text(s)
        for (i, p) in patterns.enumerated() where matches(p, t) { return qualities[i] }
        return nil
    }

    static func bytes(_ s: Source) -> Int64 {
        let t = text(s)
        guard let m = size.firstMatch(in: t, range: NSRange(t.startIndex..., in: t)),
              let r1 = Range(m.range(at: 1), in: t), let r2 = Range(m.range(at: 2), in: t),
              let v = Double(t[r1].replacingOccurrences(of: ",", with: ".")) else { return -1 }
        let u = t[r2].uppercased()
        let mult = u.hasPrefix("T") ? 1e12 : u.hasPrefix("G") ? 1e9 : 1e6
        return Int64(v * mult)
    }

    private static func score(_ s: Source) -> Int {
        let t = text(s)
        if matches(uncached, t) { return 2 }
        if s.cached || matches(cached, t) { return 0 }
        return 1
    }

    private static func better(_ a: Source, _ b: Source) -> Bool {
        let sa = score(a), sb = score(b)
        if sa != sb { return sa < sb }
        return a.rank >= 0 && (b.rank < 0 || a.rank < b.rank)
    }

    /// Best source per quality, best quality first.
    static func options(_ src: Sources) -> [Option] {
        var by: [String: Option] = [:]
        var unknown: Option?
        for s in src.list where s.url.hasPrefix("http") && !s.url.hasPrefix("http://127.0.0.1") {
            let q = quality(s)
            let cur = q.flatMap { by[$0] } ?? (q == nil ? unknown : nil)
            if let c = cur, !better(s, c.source) { continue }
            let o = Option(quality: q, bytes: bytes(s), source: s)
            if let q = q { by[q] = o } else { unknown = o }
        }
        let out = qualities.compactMap { by[$0] }
        if out.isEmpty, let u = unknown { return [u] }
        return out
    }

    /// For a whole season: the wanted quality, else the closest lower one, else the closest higher one.
    static func pick(_ src: Sources, wanted: String) -> Option? {
        let all = options(src)
        guard !all.isEmpty else { return nil }
        let w = qualities.firstIndex(of: wanted) ?? 2
        for i in w..<qualities.count { if let o = all.first(where: { $0.quality == qualities[i] }) { return o } }
        for i in stride(from: w - 1, through: 0, by: -1) { if let o = all.first(where: { $0.quality == qualities[i] }) { return o } }
        return all.first
    }

    static func fileExtension(_ s: Source) -> String {
        let known = ["mkv", "mp4", "m4v", "avi", "webm", "mov", "ts"]
        let u = (s.url.split(separator: "?").first.map(String.init) ?? s.url).lowercased()
        if let k = known.first(where: { u.hasSuffix("." + $0) }) { return k }
        let t = text(s).lowercased()
        return known.first(where: { t.contains("." + $0) }) ?? "mkv"
    }
}

/// Downloaded movies and episodes, kept in Documents/Codio (also visible in the Files app).
@MainActor
final class Downloads: NSObject, ObservableObject, URLSessionDownloadDelegate {
    static let shared = Downloads()

    enum State: String, Codable { case waiting, finding, downloading, done, failed }

    struct Entry: Codable, Identifiable {
        var type: String
        var metaId: String
        var videoId: String
        var name: String
        var poster: String?
        var background: String?
        var season: Int = 0
        var episode: Int = 0
        var episodeTitle: String?
        var quality: String?
        /// For a season download: what to aim for when this episode's source is looked up.
        var wanted: String?
        var url: String?
        var file: String?
        var bytes: Int64 = -1
        var received: Int64 = 0
        var state: State = .waiting
        var error: String?
        var position: Double = 0
        var duration: Double = 0
        var taskId: Int?
        var added = Date()

        var id: String { type + "/" + metaId + "/" + videoId }
        var isEpisode: Bool { season > 0 || episode > 0 }
        var progress: Double { bytes > 0 ? min(1, Double(received) / Double(bytes)) : 0 }
    }

    @Published private(set) var entries: [String: Entry] = [:]
    var backgroundCompletion: (() -> Void)?
    /// Looks sources up through the hidden engine (set by the engine).
    weak var engine: Engine?

    private lazy var session: URLSession = {
        let c = URLSessionConfiguration.background(withIdentifier: "com.codio.ios.downloads")
        c.sessionSendsLaunchEvents = true
        c.isDiscretionary = false
        c.timeoutIntervalForRequest = 60
        return URLSession(configuration: c, delegate: self, delegateQueue: .main)
    }()
    private var finding = false

    nonisolated static var wifiOnly: Bool {
        get { UserDefaults.standard.object(forKey: "dl_wifi_only") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "dl_wifi_only") }
    }

    nonisolated static let dir: URL = {
        let d = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Codio", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        var u = d
        var v = URLResourceValues()
        v.isExcludedFromBackup = true
        try? u.setResourceValues(v)
        return d
    }()
    nonisolated private static let listFile = dir.appendingPathComponent("downloads.json")

    private override init() {
        super.init()
        if let d = try? Data(contentsOf: Downloads.listFile), let list = try? JSONDecoder().decode([Entry].self, from: d) {
            for e in list { entries[e.id] = e }
        }
        _ = session // reconnects to downloads that ran while Codio was closed
        session.getAllTasks { tasks in
            Task { @MainActor in self.reattach(tasks) }
        }
    }

    private func reattach(_ tasks: [URLSessionTask]) {
        let running = Set(tasks.map { $0.taskIdentifier })
        for (k, e) in entries where e.state == .downloading {
            if let t = e.taskId, running.contains(t) { continue }
            entries[k]?.state = .failed
            entries[k]?.error = "Stopped. Tap to try again."
        }
        for (k, e) in entries where e.state == .finding { entries[k]?.state = .waiting }
        save()
        pump()
    }

    private func save() {
        if let d = try? JSONEncoder().encode(Array(entries.values)) { try? d.write(to: Downloads.listFile, options: .atomic) }
    }

    // MARK: - Queries

    func entry(_ type: String, _ metaId: String, _ videoId: String) -> Entry? { entries[type + "/" + metaId + "/" + videoId] }

    func fileURL(_ e: Entry) -> URL? {
        guard e.state == .done, let f = e.file else { return nil }
        let u = Downloads.dir.appendingPathComponent(f)
        return FileManager.default.fileExists(atPath: u.path) ? u : nil
    }

    func forMeta(_ metaId: String) -> [Entry] {
        entries.values.filter { $0.metaId == metaId }.sorted { ($0.season, $0.episode) < ($1.season, $1.episode) }
    }

    /// One row per title (a show's episodes grouped), newest first.
    var titles: [Entry] {
        var seen: [String: Entry] = [:]
        for e in entries.values.sorted(by: { $0.added > $1.added }) where seen[e.metaId] == nil { seen[e.metaId] = e }
        return seen.values.sorted { $0.added > $1.added }
    }

    var usedBytes: Int64 { entries.values.filter { $0.state == .done }.reduce(0) { $0 + max(0, $1.received) } }

    nonisolated static var freeBytes: Int64 {
        let v = try? URL(fileURLWithPath: NSHomeDirectory()).resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return v?.volumeAvailableCapacityForImportantUsage ?? -1
    }

    // MARK: - Starting downloads

    private func newEntry(_ m: Meta, _ vid: String) -> Entry {
        var e = Entry(type: m.type, metaId: m.id, videoId: vid, name: m.name, poster: m.poster, background: m.background)
        if let v = m.video(vid) {
            e.season = v.season
            e.episode = v.episode
            e.episodeTitle = v.title
        }
        return e
    }

    /// Download one movie/episode from the source the viewer picked.
    func start(_ m: Meta, _ vid: String, option: DownloadPick.Option) {
        var e = entries[m.type + "/" + m.id + "/" + vid] ?? newEntry(m, vid)
        guard e.state != .done && e.state != .downloading else { return }
        e.quality = option.quality
        e.bytes = option.bytes
        begin(&e, option.source)
    }

    /// Queue a whole season in one quality (closest lower one when an episode lacks it).
    func queueSeason(_ m: Meta, season: Int, quality: String) {
        for v in m.videos where v.season == season {
            if let old = entries[m.type + "/" + m.id + "/" + v.id], old.state == .done || old.state == .downloading { continue }
            var e = newEntry(m, v.id)
            e.wanted = quality
            e.state = .waiting
            entries[e.id] = e
        }
        save()
        pump()
    }

    private func begin(_ e: inout Entry, _ source: Source) {
        let name = e.id.replacingOccurrences(of: "[^A-Za-z0-9._-]", with: "_", options: .regularExpression) + "." + DownloadPick.fileExtension(source)
        guard let u = URL(string: source.url) else {
            e.state = .failed
            e.error = "Bad link"
            entries[e.id] = e
            save()
            return
        }
        var req = URLRequest(url: u)
        req.allowsCellularAccess = !Downloads.wifiOnly
        let task = session.downloadTask(with: req)
        task.taskDescription = e.id
        e.url = source.url
        e.file = name
        e.state = .downloading
        e.error = nil
        e.received = 0
        e.taskId = task.taskIdentifier
        e.added = Date()
        entries[e.id] = e
        save()
        task.resume()
    }

    func remove(_ e: Entry) {
        if let t = e.taskId {
            session.getAllTasks { tasks in tasks.first { $0.taskIdentifier == t }?.cancel() }
        }
        if let f = e.file { try? FileManager.default.removeItem(at: Downloads.dir.appendingPathComponent(f)) }
        entries[e.id] = nil
        save()
    }

    func removeAll(_ metaId: String) {
        for e in forMeta(metaId) { remove(e) }
    }

    func retry(_ e: Entry) {
        guard var x = entries[e.id] else { return }
        x.state = .waiting
        x.wanted = x.wanted ?? x.quality ?? "1080p"
        x.error = nil
        entries[x.id] = x
        save()
        pump()
    }

    func savePosition(_ id: String, position: Double, duration: Double) {
        guard entries[id] != nil else { return }
        entries[id]?.position = position >= duration - 30 ? 0 : position
        entries[id]?.duration = duration
        save()
    }

    // MARK: - Finding sources for queued episodes (one at a time, through the hidden engine)

    func pump() {
        guard !finding, let engine = engine, !engine.webBusy,
              let next = entries.values.filter({ $0.state == .waiting }).sorted(by: { ($0.season, $0.episode) < ($1.season, $1.episode) }).first
        else { return }
        finding = true
        entries[next.id]?.state = .finding
        let m = Meta(id: next.metaId, type: next.type, name: next.name)
        Task {
            await engine.store.loadMeta(m)
            let src = await engine.findSources(m, next.videoId)
            finding = false
            guard var e = entries[next.id], e.state == .finding else { pump(); return }
            if let s = src, let o = DownloadPick.pick(s, wanted: e.wanted ?? "1080p") {
                e.quality = o.quality
                e.bytes = o.bytes
                begin(&e, o.source)
            } else {
                e.state = .failed
                e.error = "No downloadable source (only debrid and direct links can be downloaded)"
                entries[e.id] = e
                save()
            }
            pump()
        }
    }

    // MARK: - URLSessionDownloadDelegate

    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                                totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        let id = downloadTask.taskDescription ?? ""
        Task { @MainActor in
            guard entries[id] != nil else { return }
            entries[id]?.received = totalBytesWritten
            if totalBytesExpectedToWrite > 0 { entries[id]?.bytes = totalBytesExpectedToWrite }
        }
    }

    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        let id = downloadTask.taskDescription ?? ""
        let status = (downloadTask.response as? HTTPURLResponse)?.statusCode ?? 200
        // The temporary file is gone once this returns: move it now.
        let tmp = Downloads.dir.appendingPathComponent(UUID().uuidString + ".part")
        try? FileManager.default.moveItem(at: location, to: tmp)
        Task { @MainActor in
            guard var e = entries[id], let f = e.file else {
                try? FileManager.default.removeItem(at: tmp)
                return
            }
            if status / 100 != 2 {
                try? FileManager.default.removeItem(at: tmp)
                e.state = .failed
                e.error = status == 403 || status == 404 || status == 410 ? "The link expired. Tap to try again." : "Download failed (\(status))"
            } else {
                let dest = Downloads.dir.appendingPathComponent(f)
                try? FileManager.default.removeItem(at: dest)
                try? FileManager.default.moveItem(at: tmp, to: dest)
                e.state = .done
                let size = (try? dest.resourceValues(forKeys: [.fileSizeKey]).fileSize).flatMap { $0 } ?? 0
                e.received = Int64(size)
                e.bytes = Int64(size)
            }
            e.taskId = nil
            entries[id] = e
            save()
        }
    }

    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error = error else { return }
        let id = task.taskDescription ?? ""
        let cancelled = (error as NSError).code == NSURLErrorCancelled
        Task { @MainActor in
            guard var e = entries[id], e.state == .downloading else { return }
            if cancelled { return }
            e.state = .failed
            e.error = "Download stopped: " + error.localizedDescription
            e.taskId = nil
            entries[id] = e
            save()
        }
    }

    nonisolated func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        Task { @MainActor in
            backgroundCompletion?()
            backgroundCompletion = nil
        }
    }
}

import CryptoKit
import Foundation

/// Calls to one server start at least `gap` seconds apart (MusicBrainz asks for no more than one a second).
actor MusicSpacer {
    static let shared = MusicSpacer()
    private var next: [String: Date] = [:]

    func wait(_ key: String, gap: TimeInterval) async {
        let now = Date()
        let at = max(now, next[key] ?? now)
        next[key] = at.addingTimeInterval(gap)
        let delay = at.timeIntervalSince(now)
        if delay > 0 { try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000)) }
    }
}

struct MusicReply {
    var status: Int
    var retryAfter: Int?
    var json: Any?
}

/// The network part of Music: metadata calls are spaced, cached on disk and sent with Codio's User-Agent.
enum MusicNet {
    static let agent = "Codio/1.0 ( https://github.com/cody103733/Codio )"
    static let day: TimeInterval = 24 * 3600

    static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 15
        c.requestCachePolicy = .useProtocolCachePolicy
        c.urlCache = URLCache(memoryCapacity: 30_000_000, diskCapacity: 300_000_000)
        return URLSession(configuration: c)
    }()

    static let dir: URL = {
        let d = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("music", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }()

    private static func file(_ key: String) -> URL {
        let digest = SHA256.hash(data: Data(key.utf8))
        return dir.appendingPathComponent(digest.map { String(format: "%02x", $0) }.joined() + ".json")
    }

    static func readCache(_ key: String, ttl: TimeInterval) -> Data? {
        let f = file(key)
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: f.path),
              let modified = attrs[.modificationDate] as? Date,
              Date().timeIntervalSince(modified) < ttl else { return nil }
        return try? Data(contentsOf: f)
    }

    static func writeCache(_ key: String, _ data: Data) {
        try? data.write(to: file(key), options: .atomic)
    }

    static func parse(_ data: Data) -> Any? {
        try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
    }

    /// A metadata GET: kept on disk for `ttl`, started at least `gap` seconds after the last one to the same server.
    static func metadata(_ url: String, ttl: TimeInterval, server: String, gap: TimeInterval = 1.0) async -> MusicReply {
        if let data = readCache(url, ttl: ttl), let json = parse(data) {
            return MusicReply(status: 200, retryAfter: nil, json: json)
        }
        guard let u = URL(string: url) else { return MusicReply(status: 0, retryAfter: nil, json: nil) }
        var last = MusicReply(status: 0, retryAfter: nil, json: nil)
        for attempt in 0..<3 {
            await MusicSpacer.shared.wait(server, gap: gap)
            var request = URLRequest(url: u)
            request.setValue(agent, forHTTPHeaderField: "User-Agent")
            request.setValue("application/json", forHTTPHeaderField: "Accept")
            do {
                let (data, response) = try await session.data(for: request)
                let status = (response as? HTTPURLResponse)?.statusCode ?? 0
                if status == 200 {
                    writeCache(url, data)
                    return MusicReply(status: 200, retryAfter: nil, json: parse(data))
                }
                last = MusicReply(status: status, retryAfter: nil, json: nil)
                if (status == 503 || status == 429) && attempt < 2 {
                    try? await Task.sleep(nanoseconds: 3_000_000_000)
                    continue
                }
                return last
            } catch {
                return MusicReply(status: 0, retryAfter: nil, json: nil)
            }
        }
        return last
    }

    /// A plain GET (music addons): never cached, never logged.
    static func call(_ url: String, timeout: TimeInterval = 15) async -> MusicReply {
        guard let u = URL(string: url) else { return MusicReply(status: 0, retryAfter: nil, json: nil) }
        var request = URLRequest(url: u)
        request.timeoutInterval = timeout
        request.setValue(agent, forHTTPHeaderField: "User-Agent")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            let http = response as? HTTPURLResponse
            let retry = http?.value(forHTTPHeaderField: "Retry-After").flatMap { Int($0) }
            return MusicReply(status: http?.statusCode ?? 0, retryAfter: retry, json: parse(data))
        } catch {
            return MusicReply(status: 0, retryAfter: nil, json: nil)
        }
    }
}

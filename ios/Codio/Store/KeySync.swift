import Foundation

/// Keeps the TMDB key with the Stremio account, so every device signed in to it gets the key.
/// Stremio has no place for app settings, so the key rides in a hidden library entry
/// ("codio:tmdb", type other, removed + temp), which Stremio apps never show.
/// The same entry is read by the Android TV and phone apps (ui/KeySync.java).
enum KeySync {
    static let itemId = "codio:tmdb"
    private static let api = "https://api.strem.io/api/"
    private static let defaultsKey = "tmdb_key"
    private static let pushedKey = "tmdb_key_pushed"   // "<auth key>|<tmdb key>" last written

    static func isKey(_ k: String) -> Bool { k.count == 32 && k.allSatisfy { $0.isHexDigit } }

    /// After sign-in (and at every start): this device's key goes up to the account,
    /// or, with no key here yet, the account's key comes down.
    @MainActor private static var tried = Set<String>()   // accounts already looked at since launch

    @MainActor static func sync(authKey: String?) {
        guard let auth = authKey, !auth.isEmpty else { return }
        let local = UserDefaults.standard.string(forKey: defaultsKey) ?? ""
        guard !tried.contains(auth + "|" + local) else { return }
        tried.insert(auth + "|" + local)
        syncNow(auth)
    }

    private static func syncNow(_ auth: String) {
        let local = UserDefaults.standard.string(forKey: defaultsKey) ?? ""
        Task {
            if isKey(local) {
                await push(local, authKey: auth)
            } else if let k = await pull(authKey: auth) {
                await MainActor.run {
                    if (UserDefaults.standard.string(forKey: defaultsKey) ?? "").isEmpty {
                        UserDefaults.standard.set(k, forKey: defaultsKey)
                    }
                }
            }
        }
    }

    /// Writes the key (empty = forget it) to the account. Skips a write it already made.
    static func push(_ key: String, authKey: String) async {
        let mark = authKey + "|" + key
        if UserDefaults.standard.string(forKey: pushedKey) == mark { return }
        let now = ISO8601DateFormatter.withFractions.string(from: Date())
        let item: [String: Any] = [
            "_id": itemId, "name": key, "type": "other", "poster": NSNull(), "posterShape": "poster",
            "removed": true, "temp": true, "_ctime": now, "_mtime": now,
            "state": ["lastWatched": NSNull(), "timeWatched": 0, "timeOffset": 0, "overallTimeWatched": 0,
                      "timesWatched": 0, "flaggedWatched": 0, "duration": 0, "video_id": NSNull(),
                      "watched": NSNull(), "noNotif": true] as [String: Any],
            "behaviorHints": ["defaultVideoId": NSNull(), "featuredVideoId": NSNull(), "hasScheduledVideos": false] as [String: Any],
        ]
        let body: [String: Any] = ["authKey": authKey, "collection": "libraryItem", "changes": [item]]
        if let r = await call("datastorePut", body), r["result"] != nil {
            UserDefaults.standard.set(mark, forKey: pushedKey)
        }
    }

    /// The key saved with the account, if any.
    static func pull(authKey: String) async -> String? {
        let body: [String: Any] = ["authKey": authKey, "collection": "libraryItem", "ids": [itemId], "all": false]
        guard let r = await call("datastoreGet", body), let items = r["result"] as? [[String: Any]] else { return nil }
        for it in items where (it["_id"] as? String) == itemId {
            if let k = (it["name"] as? String)?.trimmingCharacters(in: .whitespaces), isKey(k) { return k }
        }
        return nil
    }

    private static func call(_ method: String, _ body: [String: Any]) async -> [String: Any]? {
        guard let url = URL(string: api + method), let data = try? JSONSerialization.data(withJSONObject: body) else { return nil }
        var req = URLRequest(url: url, timeoutInterval: 15)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = data
        guard let (d, resp) = try? await URLSession.shared.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return (try? JSONSerialization.jsonObject(with: d)) as? [String: Any]
    }
}

private extension ISO8601DateFormatter {
    static let withFractions: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
}

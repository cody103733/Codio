import Foundation
import Network

/// Serves the Codio web UI (the bundled "web" folder) on http://127.0.0.1:11471/, the same address the
/// Windows app uses, plus /api/skip (Skip Intro times from IntroDB, asked natively so CORS doesn't matter).
/// A real http origin (not file://) keeps localStorage (sign-in, addons, settings) and lets addons be fetched.
final class LocalServer {
    static let shared = LocalServer()
    static let port: UInt16 = 11471
    static var baseURL: URL { URL(string: "http://127.0.0.1:\(port)/")! }

    private let queue = DispatchQueue(label: "codio.server")
    private var listener: NWListener?
    private let root: URL = Bundle.main.resourceURL!.appendingPathComponent("web", isDirectory: true).standardizedFileURL
    /// Script put at the top of index.html, before the page's own scripts (the native bridge).
    var shim: String = ""
    private var skipCache: [String: (at: Date, status: Int, body: Data)] = [:]

    private static let mime: [String: String] = [
        "html": "text/html; charset=utf-8", "js": "text/javascript; charset=utf-8",
        "css": "text/css; charset=utf-8", "json": "application/json; charset=utf-8",
        "wasm": "application/wasm", "png": "image/png", "jpg": "image/jpeg", "jpeg": "image/jpeg",
        "gif": "image/gif", "svg": "image/svg+xml", "ico": "image/x-icon", "webp": "image/webp",
        "woff": "font/woff", "woff2": "font/woff2", "ttf": "font/ttf", "txt": "text/plain; charset=utf-8",
        "webmanifest": "application/manifest+json",
    ]

    func start() {
        queue.async { self.startListener() }
    }

    func ensureRunning() {
        queue.async {
            if self.listener == nil { self.startListener() }
        }
    }

    private func startListener() {
        do {
            let params = NWParameters.tcp
            params.allowLocalEndpointReuse = true
            params.requiredLocalEndpoint = NWEndpoint.hostPort(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: LocalServer.port)!)
            let l = try NWListener(using: params)
            l.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
            l.stateUpdateHandler = { [weak self] state in
                guard let self = self else { return }
                switch state {
                case .failed(let error):
                    NSLog("Codio server failed: \(error)")
                    l.cancel()
                    if self.listener === l { self.listener = nil }
                    self.queue.asyncAfter(deadline: .now() + 1) { if self.listener == nil { self.startListener() } }
                case .cancelled:
                    if self.listener === l { self.listener = nil }
                default:
                    break
                }
            }
            listener = l
            l.start(queue: queue)
        } catch {
            NSLog("Codio server could not start: \(error)")
            queue.asyncAfter(deadline: .now() + 1) { self.startListener() }
        }
    }

    // MARK: - HTTP

    private func accept(_ connection: NWConnection) {
        connection.start(queue: queue)
        read(connection, buffer: Data())
    }

    private func read(_ connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, isComplete, error in
            guard let self = self else { return }
            var buffer = buffer
            if let data = data { buffer.append(data) }
            if let end = buffer.range(of: Data("\r\n\r\n".utf8)) {
                let head = String(decoding: buffer[buffer.startIndex..<end.lowerBound], as: UTF8.self)
                self.handle(head, on: connection)
            } else if error != nil || isComplete || buffer.count > 1_000_000 {
                connection.cancel()
            } else {
                self.read(connection, buffer: buffer)
            }
        }
    }

    private func handle(_ head: String, on connection: NWConnection) {
        let lines = head.components(separatedBy: "\r\n")
        let parts = (lines.first ?? "").split(separator: " ")
        guard parts.count >= 2 else { return respond(connection, status: 400) }
        let method = String(parts[0])
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            if let i = line.firstIndex(of: ":") {
                headers[line[..<i].lowercased()] = line[line.index(after: i)...].trimmingCharacters(in: .whitespaces)
            }
        }
        guard let url = URL(string: String(parts[1]), relativeTo: LocalServer.baseURL),
              let comps = URLComponents(url: url, resolvingAgainstBaseURL: true) else {
            return respond(connection, status: 400)
        }
        var path = comps.percentEncodedPath.removingPercentEncoding ?? comps.path
        if path == "/api/skip" { return skipTimes(comps, headers: headers, on: connection) }
        if path.hasPrefix("/api/") { return respond(connection, status: 404) } // e.g. downloads: not on iPhone yet
        if path.isEmpty || path == "/" { path = "/index.html" }

        let file = root.appendingPathComponent(String(path.dropFirst())).standardizedFileURL
        guard file.path.hasPrefix(root.path + "/") else { return respond(connection, status: 403) }
        var isDir: ObjCBool = false
        guard FileManager.default.fileExists(atPath: file.path, isDirectory: &isDir), !isDir.boolValue,
              let body = try? Data(contentsOf: file, options: .mappedIfSafe) else {
            return respond(connection, status: 404)
        }
        let ext = file.pathExtension.lowercased()
        var body = body
        if file.lastPathComponent == "index.html", !shim.isEmpty, var html = String(data: body, encoding: .utf8),
           let r = html.range(of: "<head>") {
            html.replaceSubrange(r, with: "<head><script>" + shim + "</script>")
            body = Data(html.utf8)
        }
        var extra: [String: String] = [:]
        if ext == "html" || file.lastPathComponent == "service-worker.js" || file.lastPathComponent == "manifest.json" {
            extra["Cache-Control"] = "no-cache"
        }
        respond(connection, status: 200, type: LocalServer.mime[ext] ?? "application/octet-stream",
                body: body, headOnly: method == "HEAD", extra: extra)
    }

    private func respond(_ connection: NWConnection, status: Int, type: String = "text/plain",
                         body: Data = Data(), headOnly: Bool = false, extra: [String: String] = [:]) {
        let reason: String
        switch status {
        case 200: reason = "OK"
        case 400: reason = "Bad Request"
        case 403: reason = "Forbidden"
        case 404: reason = "Not Found"
        default: reason = "Error"
        }
        var head = "HTTP/1.1 \(status) \(reason)\r\nContent-Type: \(type)\r\nContent-Length: \(body.count)\r\nConnection: close\r\n"
        for (k, v) in extra { head += "\(k): \(v)\r\n" }
        head += "\r\n"
        var out = Data(head.utf8)
        if !headOnly { out.append(body) }
        connection.send(content: out, completion: .contentProcessed { _ in connection.cancel() })
    }

    // MARK: - Skip Intro (IntroDB)

    private func skipTimes(_ comps: URLComponents, headers: [String: String], on connection: NWConnection) {
        let q = Dictionary((comps.queryItems ?? []).map { ($0.name, $0.value ?? "") }, uniquingKeysWith: { a, _ in a })
        let imdb = q["imdb_id"] ?? ""
        let season = Int(q["season"] ?? "") ?? 0
        let episode = Int(q["episode"] ?? "") ?? 0
        guard headers["x-codio"] == "1", imdb.range(of: "^tt[0-9]+$", options: .regularExpression) != nil,
              season > 0, episode > 0 else {
            return respond(connection, status: 400)
        }
        let key = "\(imdb):\(season):\(episode)"
        let json = "application/json; charset=utf-8"
        if let c = skipCache[key], Date().timeIntervalSince(c.at) < 6 * 3600 {
            return respond(connection, status: c.status, type: json, body: c.body, extra: ["Cache-Control": "no-store"])
        }
        var req = URLRequest(url: URL(string: "https://api.introdb.app/segments?imdb_id=\(imdb)&season=\(season)&episode=\(episode)")!)
        req.timeoutInterval = 12
        req.setValue("Codio-iOS/0.1 (+https://introdb.app)", forHTTPHeaderField: "User-Agent")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        URLSession.shared.dataTask(with: req) { data, response, error in
            self.queue.async {
                let code = (response as? HTTPURLResponse)?.statusCode ?? 0
                if error == nil, code == 200 || code == 404 {
                    let body = data ?? Data()
                    self.skipCache[key] = (Date(), code, body)
                    self.respond(connection, status: code, type: json, body: body, extra: ["Cache-Control": "no-store"])
                } else {
                    let msg = "{\"error\":\"IntroDB answered \(code)\"}"
                    self.respond(connection, status: 502, type: json, body: Data(msg.utf8))
                }
            }
        }.resume()
    }
}

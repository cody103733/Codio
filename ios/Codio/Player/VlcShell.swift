import Foundation
import MobileVLCKit
import UIKit

/// Plays video with VLC and speaks the same protocol as Stremio's Windows shell (mpv over WebView2 IPC),
/// so the Codio web player (Stremio's ShellVideo) drives it unchanged: auto-play, source fallback,
/// Skip Intro and progress saving all keep working. Ported from the Android TV app's VlcShell.
final class VlcShell: NSObject {
    /// Sends a signal (name, argument) to the web page.
    var signal: ((String, Any) -> Void)?
    /// Told when playback starts or stops (keeps the screen awake, shows the video layer).
    var onActiveChanged: ((Bool) -> Void)?
    /// A new video is loading (the native player resets its state).
    var onLoad: (() -> Void)?
    /// When the viewer last picked a subtitle themselves (automatic picks are ignored unless enabled).
    var userPickAt = Date.distantPast

    private let player = VLCMediaPlayer()
    private var observed = Set<String>()
    private var lastValues: [String: Any] = [:]

    private var active = false
    private var started = false
    private var wantPause = false
    private var volume: Int32 = 100
    private var muted = false
    private var lengthMs: Int32 = 0
    private var timeMs: Int32 = 0
    private var lastTimeSent = Date.distantPast
    private var lastProgressAt = Date()
    private var buffering = false
    private var stallTimer: Timer?

    init(view: UIView) {
        super.init()
        player.drawable = view
        // Notifications instead of the delegate: VLCKit posts these on the main thread.
        let nc = NotificationCenter.default
        nc.addObserver(forName: NSNotification.Name("VLCMediaPlayerStateChanged"), object: player, queue: .main) { [weak self] _ in
            self?.stateChanged()
        }
        nc.addObserver(forName: NSNotification.Name("VLCMediaPlayerTimeChanged"), object: player, queue: .main) { [weak self] _ in
            self?.timeChanged()
        }
    }

    private static func ms(_ t: VLCTime?) -> Int32 { t?.intValue ?? 0 }
    private var audio: VLCAudio? { player.audio }
    private var lengthNow: Int32 { VlcShell.ms(player.media?.length) }

    // MARK: - web UI -> VLC

    func handle(method: String, params: Any?) {
        switch method {
        case "mpv-observe-prop":
            if let name = params as? String { observe(name) }
        case "mpv-set-prop":
            if let a = params as? [Any], a.count >= 2, let name = a[0] as? String { setProp(name, a[1]) }
        case "mpv-command":
            if let a = params as? [Any] { command(a.map { "\($0)" }) }
        default:
            break
        }
    }

    private func observe(_ name: String) {
        observed.insert(name)
        switch name {
        // ShellVideo waits for mpv-version before it loads anything.
        case "mpv-version": forward(name, "mpv 0.40.0 (VLCKit)")
        case "ffmpeg-version": forward(name, "VLCKit")
        case "volume": forward(name, Double(volume))
        case "speed": forward(name, 1.0)
        case "sub-scale": forward(name, 1.0)
        case "sub-pos": forward(name, 100.0)
        case "sub-delay": forward(name, 0.0)
        default:
            if let v = lastValues[name] { forward(name, v) }
        }
    }

    private static func truthy(_ v: Any) -> Bool {
        if let b = v as? Bool { return b }
        if let n = v as? NSNumber { return n.doubleValue != 0 }
        let s = "\(v)"
        return s == "yes" || s == "true"
    }

    private static func num(_ v: Any, _ fallback: Double) -> Double {
        if let n = v as? NSNumber { return n.doubleValue }
        return Double("\(v)") ?? fallback
    }

    private static func trackId(_ v: Any) -> Int32 {
        if v is NSNull { return -1 }
        let s = "\(v)"
        if s == "no" || s == "false" { return -1 }
        return Int32(Double(s) ?? -1)
    }

    private func setProp(_ name: String, _ value: Any) {
        if value is NSNull { return }
        switch name {
        case "pause":
            wantPause = VlcShell.truthy(value)
            if wantPause, player.isPlaying { player.pause() }
            else if !wantPause, !player.isPlaying, started { player.play() }
            forward("pause", wantPause)
        case "time-pos":
            let s = VlcShell.num(value, -1)
            guard s >= 0 else { return }
            timeMs = Int32(s * 1000)
            player.time = VLCTime(int: timeMs)
            forward("seeking", false)
            forward("time-pos", s)
        case "speed":
            let r = VlcShell.num(value, 1)
            if r > 0 { player.rate = Float(r) }
            forward("speed", r)
        case "volume":
            volume = Int32(max(0, min(200, VlcShell.num(value, 100).rounded())))
            if !muted { audio?.volume = volume }
            forward("volume", Double(volume))
        case "mute":
            muted = VlcShell.truthy(value)
            audio?.volume = muted ? 0 : volume
        case "aid":
            let id = VlcShell.trackId(value)
            if id >= 0 { player.currentAudioTrackIndex = id }
            sendSelection()
        case "sid":
            let id = VlcShell.trackId(value)
            // Subtitles stay off unless the viewer turns them on: the web player would otherwise switch them on by itself.
            let auto = Date().timeIntervalSince(userPickAt) > 4
            if id >= 0, auto, !UserDefaults.standard.bool(forKey: "subs_auto") {
                sendSelection()
                return
            }
            player.currentVideoSubTitleIndex = id
            sendSelection()
        case "sub-delay":
            let s = VlcShell.num(value, 0)
            player.currentVideoSubTitleDelay = Int(s * 1_000_000)
            forward("sub-delay", s)
        default:
            break // hwdec, vo, subtitle colours, ...: VLC is set up once
        }
    }

    private func command(_ cmd: [String]) {
        guard let name = cmd.first else { return }
        switch name {
        case "loadfile":
            if cmd.count > 1 { load(cmd) }
        case "stop":
            stopPlayback()
        case "sub-add":
            if cmd.count > 1, let url = URL(string: cmd[1]) { player.addPlaybackSlave(url, type: .subtitle, enforce: true) }
        case "audio-add":
            if cmd.count > 1, let url = URL(string: cmd[1]) { player.addPlaybackSlave(url, type: .audio, enforce: false) }
        case "seek":
            if cmd.count > 1 {
                let s = VlcShell.num(cmd[1], 0)
                let absolute = cmd.count > 2 && cmd[2].hasPrefix("absolute")
                let current = VlcShell.ms(player.time)
                let target = absolute ? Int32(s * 1000) : current + Int32(s * 1000)
                player.time = VLCTime(int: max(0, target))
            }
        default:
            break
        }
    }

    private func load(_ cmd: [String]) {
        guard let url = URL(string: cmd[1]) else { return }
        var startAt = 0.0
        for a in cmd.dropFirst(2) {
            if let r = a.range(of: "start=") {
                startAt = Double(a[r.upperBound...].replacingOccurrences(of: "+", with: "")) ?? 0
            }
        }
        active = true
        started = false
        wantPause = false
        timeMs = 0
        lengthMs = 0
        lastValues.removeAll()
        onLoad?()
        let media = VLCMedia(url: url)
        // A shorter buffer than the TV app (8 s) so the picture starts sooner on a phone.
        media.addOption(":network-caching=3000")
        media.addOption(":file-caching=3000")
        if startAt > 0 { media.addOption(":start-time=\(startAt)") }
        player.media = media
        player.play()
        NSLog("Codio loadfile" + (startAt > 0 ? " from \(startAt)s" : ""))
        setBuffering(true)
        forward("pause", false)
        onActiveChanged?(true)
        startStallTimer()
    }

    private func stopPlayback() {
        let was = active
        active = false
        started = false
        stallTimer?.invalidate()
        stallTimer = nil
        if was {
            player.stop()
            onActiveChanged?(false)
        }
    }

    /// VLC 3 on iOS gives no buffer percentage, so "buffering" means: not paused and the clock isn't moving.
    private func startStallTimer() {
        stallTimer?.invalidate()
        lastProgressAt = Date()
        stallTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            guard let self = self, self.active else { return }
            if self.started, !self.wantPause, Date().timeIntervalSince(self.lastProgressAt) > 1.2 {
                self.setBuffering(true)
            }
        }
    }

    private func setBuffering(_ b: Bool) {
        if buffering == b, lastValues["paused-for-cache"] != nil { return }
        buffering = b
        forward("paused-for-cache", b)
    }

    // MARK: - VLC -> web UI

    private func stateChanged() {
        switch player.state {
        case .opening, .buffering:
            if !started { setBuffering(true) }
        case .playing:
            onPlaying()
        case .paused:
            forward("pause", true)
        case .ended:
            ended(error: false)
        case .error:
            ended(error: true)
        default:
            if player.state.rawValue == 7 { // ESAdded: a track appeared
                sendTracks()
                sendSelection()
            }
        }
    }

    private func timeChanged() {
        let ms = VlcShell.ms(player.time)
        if ms != timeMs {
            timeMs = ms
            lastProgressAt = Date()
            if !started { onPlaying() }
            if buffering { setBuffering(false) }
        }
        if lengthMs <= 0, lengthNow > 0 {
            let len = lengthNow
            lengthMs = len
            forward("duration", Double(len) / 1000)
        }
        if Date().timeIntervalSince(lastTimeSent) >= 0.25 {
            lastTimeSent = Date()
            forward("time-pos", Double(ms) / 1000)
        }
    }

    private func onPlaying() {
        let first = !started
        started = true
        forward("pause", false)
        if first {
            audio?.volume = muted ? 0 : volume
            sendTracks()
            sendSelection()
            let size = player.videoSize
            if size.width > 0 {
                forward("video-params", ["w": Int(size.width), "h": Int(size.height), "dw": Int(size.width), "dh": Int(size.height)])
            }
            if lengthMs <= 0, lengthNow > 0 {
            let len = lengthNow
                lengthMs = len
                forward("duration", Double(len) / 1000)
            }
            if wantPause { player.pause() }
        }
    }

    private func ended(error: Bool) {
        guard active else { return }
        active = false
        stallTimer?.invalidate()
        stallTimer = nil
        var reason = "eof"
        var err: [String: Any]?
        if error || !started {
            reason = "error"
            err = ["message": "This stream could not be opened.", "critical": true, "code": 83]
        } else if lengthMs > 0 && timeMs < lengthMs - 15000 {
            reason = "error"
            err = ["message": "Playback stopped unexpectedly.", "critical": false, "code": 81]
        }
        if error && started {
            err = ["message": "Playback stopped unexpectedly.", "critical": false, "code": 81]
        }
        NSLog("Codio end-file: \(reason) at \(timeMs)/\(lengthMs)")
        started = false
        onActiveChanged?(false)
        forward("eof-reached", reason == "eof")
        var o: [String: Any] = ["reason": reason]
        if let err = err { o["error"] = err }
        signal?("mpv-event-ended", o)
    }

    private func sendSelection() {
        let a = player.currentAudioTrackIndex
        let s = player.currentVideoSubTitleIndex
        let v = player.currentVideoTrackIndex
        forward("aid", a >= 0 ? Int(a) as Any : "no")
        forward("sid", s >= 0 ? Int(s) as Any : "no")
        forward("vid", v >= 0 ? Int(v) as Any : "no")
    }

    private func sendTracks() {
        var out: [[String: Any]] = []
        func add(_ type: String, ids: [Any]?, names: [Any]?, selected: Int32) {
            guard let ids = ids else { return }
            for (i, raw) in ids.enumerated() {
                guard let id = (raw as? NSNumber)?.int32Value, id >= 0 else { continue }
                var t: [String: Any] = ["id": Int(id), "type": type, "external": false, "selected": id == selected]
                if let names = names, i < names.count, let n = names[i] as? String, !n.isEmpty {
                    t["title"] = n
                    // VLC names look like "Track 1 - [English]"; pass the language on too.
                    if let open = n.lastIndex(of: "["), let close = n.lastIndex(of: "]"), open < close {
                        t["lang"] = String(n[n.index(after: open)..<close])
                    }
                }
                out.append(t)
            }
        }
        add("video", ids: player.videoTrackIndexes, names: player.videoTrackNames, selected: player.currentVideoTrackIndex)
        add("audio", ids: player.audioTrackIndexes, names: player.audioTrackNames, selected: player.currentAudioTrackIndex)
        add("sub", ids: player.videoSubTitlesIndexes, names: player.videoSubTitlesNames, selected: player.currentVideoSubTitleIndex)
        forward("track-list", out)
    }

    private func forward(_ name: String, _ data: Any) {
        lastValues[name] = data
        guard observed.contains(name) else { return }
        signal?("mpv-prop-change", ["name": name, "data": data])
    }
}

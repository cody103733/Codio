import SwiftUI

/// "Finding your stream": stays up while the engine picks a source and VLC starts.
struct LoadingView: View {
    @EnvironmentObject var engine: Engine

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let m = engine.playMeta {
                Color.clear.overlay(RemoteImage(url: m.background ?? m.poster).opacity(0.35).blur(radius: 2)).clipped().ignoresSafeArea()
            }
            VStack(spacing: 18) {
                if let m = engine.playMeta, !m.name.isEmpty {
                    Text(m.name).font(.title2.bold()).foregroundColor(.white).multilineTextAlignment(.center)
                } else {
                    BrandWordmark(height: 32)
                }
                ProgressView().tint(Theme.red).scaleEffect(1.3)
                Text(engine.loadingText).font(.subheadline).foregroundColor(Color(white: 0.85)).multilineTextAlignment(.center)
                Button("Cancel") { engine.cancelPlay() }
                    .font(.headline).foregroundColor(.white)
                    .padding(.horizontal, 26).padding(.vertical, 10)
                    .background(Capsule().fill(Color(white: 0.2)))
            }
            .padding(30)
        }
    }
}

/// Codio's controls over the VLC picture (the web player runs unseen underneath and saves progress).
struct PlayerView: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var st: PlayerState
    @State private var controls = true
    @State private var hideTask: Task<Void, Never>?
    @State private var dragging = false
    @State private var dragValue: Double = 0
    @State private var dragStart: Double = 0
    @State private var skip: SkipTimes.Times?
    @State private var skipFor: String?
    @State private var showCast = false
    /// Segments already auto-skipped in this video, so going back to watch one doesn't skip it again.
    @State private var autoSkipped: Set<String> = []

    private var meta: Meta? { engine.playMeta }
    private var title: String {
        guard let m = meta else { return "" }
        if let v = m.video(engine.playVideo) {
            return m.name + "  ·  S\(v.season) E\(v.episode)" + (v.title.isEmpty ? "" : " " + v.title)
        }
        return m.name
    }

    var body: some View {
        TimelineView(.periodic(from: .now, by: 0.5)) { _ in
            let pos = dragging ? dragValue : st.position
            ZStack {
                // UIKit gestures: SwiftUI's taps on a see-through layer didn't fire on device.
                ZStack {
                    Color.black.opacity(controls ? 0.45 : 0).allowsHitTesting(false)
                    TouchSurface(
                        onTap: { toggleControls() },
                        onDoubleTap: { left in seek(by: left ? -10 : 10) },
                        onPan: { dx, dy, width, ended in pan(dx, dy, width, ended) }
                    )
                }
                .ignoresSafeArea()
                if st.buffering && st.error == nil {
                    ProgressView().tint(Theme.red).scaleEffect(1.5).allowsHitTesting(false)
                }
                if let e = st.error {
                    Text(e + "\nTrying the next source…").multilineTextAlignment(.center).foregroundColor(.white)
                        .padding(16).background(RoundedRectangle(cornerRadius: 10).fill(Color.black.opacity(0.7)))
                }
                if controls { controlsLayer(pos) }
                skipButton(pos)
            }
        }
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .onAppear {
            scheduleHide()
            loadSkip()
        }
        .onChange(of: engine.playVideo) { _ in loadSkip() }
        .onChange(of: st.position) { p in autoSkip(p) }
        .sheet(isPresented: $showCast, onDismiss: { scheduleHide() }) {
            if let m = meta { CastSheet(meta: m) }
        }
    }

    @ViewBuilder private func controlsLayer(_ pos: Double) -> some View {
        VStack {
            HStack(spacing: 14) {
                Button { engine.stopPlayer() } label: {
                    Image(systemName: "xmark").font(.title2.weight(.semibold)).frame(width: 52, height: 52).contentShape(Rectangle())
                }
                Text(title).font(.headline).lineLimit(1)
                Spacer()
                Button { showCast = true; hideTask?.cancel() } label: {
                    Image(systemName: "person.2").font(.title3).frame(width: 44, height: 44).contentShape(Rectangle())
                }
                trackMenu
            }
            .padding(.horizontal, 20)
            Spacer()
            HStack(spacing: 56) {
                Button { seek(by: -10) } label: { Image(systemName: "gobackward.10").font(.system(size: 34)) }
                Button {
                    engine.player("mpv-set-prop", ["pause", !st.paused])
                    scheduleHide()
                } label: {
                    Image(systemName: st.paused ? "play.fill" : "pause.fill").font(.system(size: 46))
                }
                Button { seek(by: 10) } label: { Image(systemName: "goforward.10").font(.system(size: 34)) }
                if let next = nextVideo {
                    Button { playNext(next) } label: { Image(systemName: "forward.end.fill").font(.system(size: 28)) }
                }
            }
            Spacer()
            HStack(spacing: 12) {
                Text(formatTime(pos)).font(.caption.monospacedDigit())
                Slider(value: Binding(get: { pos }, set: { dragValue = $0 }),
                       in: 0...max(st.duration, max(pos, 1)),
                       onEditingChanged: { editing in
                           if editing {
                               dragValue = st.position
                               dragging = true
                               hideTask?.cancel()
                           } else {
                               engine.player("mpv-set-prop", ["time-pos", dragValue])
                               st.time = dragValue
                               dragging = false
                               scheduleHide()
                           }
                       })
                    .tint(Theme.red)
                Text(st.duration > 0 ? "-" + formatTime(max(0, st.duration - pos)) : "--:--").font(.caption.monospacedDigit())
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 14)
        }
        .foregroundColor(.white)
        .buttonStyle(.plain)
    }

    private var trackMenu: some View {
        Menu {
            if !st.audio.isEmpty {
                Section("Audio") {
                    ForEach(st.audio) { t in
                        Button { engine.player("mpv-set-prop", ["aid", t.id]) } label: {
                            Label(t.label, systemImage: t.id == st.aid ? "checkmark" : "")
                        }
                    }
                }
            }
            Section("Subtitles") {
                Button {
                    engine.userPicked()
                    engine.player("mpv-set-prop", ["sid", "no"])
                } label: { Label("Off", systemImage: st.sid < 0 ? "checkmark" : "") }
                ForEach(st.subs) { t in
                    Button {
                        engine.userPicked()
                        engine.player("mpv-set-prop", ["sid", t.id])
                    } label: { Label(t.label, systemImage: t.id == st.sid ? "checkmark" : "") }
                }
            }
        } label: {
            Image(systemName: "captions.bubble").font(.title2).frame(width: 44, height: 44)
        }
    }

    // MARK: - Skip Intro / Next Episode

    private enum SkipKind { case intro, recap, credits, next }

    private var nextVideo: Video? { meta?.next(after: engine.playVideo) }

    private func skipKind(_ t: Double) -> SkipKind? {
        guard let s = skip, st.started, st.error == nil, !dragging, Store.skipButtons else { return nil }
        if let i = s.intro, i.contains(t) { return .intro }
        if let r = s.recap, r.contains(t) { return .recap }
        if let o = s.outro, t >= o.start, st.duration <= 0 || t < st.duration - 1 {
            if nextVideo != nil { return .next }
            if o.contains(t), st.duration > 0, o.end < st.duration - 5 { return .credits }
        }
        return nil
    }

    @ViewBuilder private func skipButton(_ pos: Double) -> some View {
        if let kind = skipKind(pos) {
            VStack {
                Spacer()
                HStack {
                    Spacer()
                    Button {
                        switch kind {
                        case .intro: if let s = skip?.intro { seek(to: s.end) }
                        case .recap: if let s = skip?.recap { seek(to: s.end) }
                        case .credits: if let s = skip?.outro { seek(to: s.end) }
                        case .next: if let n = nextVideo { playNext(n) }
                        }
                    } label: {
                        Text(kind == .intro ? "Skip Intro" : kind == .recap ? "Skip Recap" : kind == .credits ? "Skip Credits" : "Next Episode ›")
                            .font(.headline).foregroundColor(.black)
                            .padding(.horizontal, 20).padding(.vertical, 12)
                            .background(RoundedRectangle(cornerRadius: 6).fill(Color.white))
                    }
                    .padding(.trailing, 28)
                    .padding(.bottom, controls ? 70 : 28)
                }
            }
        }
    }

    private func loadSkip() {
        let vid = engine.playVideo
        guard let vid = vid, vid != skipFor else { return }
        skipFor = vid
        skip = nil
        autoSkipped = []
        let m = meta
        Task {
            if let m = m, !m.full, m.type == "series" { await engine.store.loadMeta(m) }
            let t = await SkipTimes.load(meta: m, vid: vid)
            if engine.playVideo == vid { skip = t }
        }
    }

    /// Settings, Playback, Auto-skip: 1 = intros and recaps, 2 = also credits (straight to the next episode).
    private func autoSkip(_ t: Double) {
        let mode = UserDefaults.standard.integer(forKey: "auto_skip")
        guard mode > 0, let s = skip, st.started, st.error == nil, !st.paused, !dragging else { return }
        if let i = s.intro, i.contains(t), t < i.end - 1.5, !autoSkipped.contains("intro") {
            autoSkipped.insert("intro")
            seek(to: i.end)
            engine.show(toast: "Skipped intro")
        } else if let r = s.recap, r.contains(t), t < r.end - 1.5, !autoSkipped.contains("recap") {
            autoSkipped.insert("recap")
            seek(to: r.end)
            engine.show(toast: "Skipped recap")
        } else if mode >= 2, let o = s.outro, o.contains(t), let n = nextVideo, !autoSkipped.contains("credits") {
            autoSkipped.insert("credits")
            engine.show(toast: "Skipping the credits")
            playNext(n)
        }
    }

    // MARK: - helpers

    private func seek(by s: Double) {
        seek(to: max(0, st.position + s))
    }

    private func seek(to t: Double) {
        engine.player("mpv-set-prop", ["time-pos", t])
        st.time = t
        scheduleHide()
    }

    private func playNext(_ v: Video) {
        guard let m = meta else { return }
        engine.play(m, v.id)
    }

    /// Sideways drag scrubs (a full swipe across = 3 minutes); a downward swipe closes the player.
    private func pan(_ dx: CGFloat, _ dy: CGFloat, _ width: CGFloat, _ ended: Bool) {
        if !dragging, !ended, abs(dx) > 24, abs(dx) > abs(dy) {
            dragging = true
            dragStart = st.position
            withAnimation { controls = true }
            hideTask?.cancel()
        }
        if dragging {
            let span = 180.0 * Double(dx / max(width, 1))
            dragValue = min(max(0, dragStart + span), max(st.duration, dragStart + span))
            if ended {
                dragging = false
                seek(to: dragValue)
                scheduleHide()
            }
        } else if ended, dy > 120, dy > abs(dx) {
            engine.stopPlayer()
        }
    }

    private func toggleControls() {
        withAnimation(.easeOut(duration: 0.15)) { controls.toggle() }
        if controls { scheduleHide() }
    }

    private func scheduleHide() {
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            if !Task.isCancelled, !st.paused, !dragging {
                withAnimation(.easeOut(duration: 0.25)) { controls = false }
            }
        }
    }
}

/// The source list for one movie/episode (auto-play's picks first).
struct SourcesView: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    let meta: Meta
    let videoId: String

    var body: some View {
        NavigationStack {
            let s = store.sourcesFor(meta, videoId)
            List {
                if let s = s {
                    ForEach(s.list) { src in
                        Button {
                            dismiss()
                            engine.play(meta, videoId, source: src)
                        } label: { row(src) }
                        .listRowBackground(Color(white: 0.1))
                    }
                    if s.loading {
                        HStack { ProgressView().tint(Theme.red); Text("Still asking your addons…").foregroundColor(Theme.dim) }
                            .listRowBackground(Color(white: 0.1))
                    } else if s.list.isEmpty {
                        Text("No playable sources (Codio only plays debrid and direct links).").foregroundColor(Theme.dim)
                            .listRowBackground(Color(white: 0.1))
                    }
                } else {
                    HStack { ProgressView().tint(Theme.red); Text("Finding sources…").foregroundColor(Theme.dim) }
                        .listRowBackground(Color(white: 0.1))
                }
            }
            .scrollContentBackground(.hidden)
            .background(Theme.bg.ignoresSafeArea())
            .navigationTitle("Sources")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
            }
        }
        .preferredColorScheme(.dark)
        .onAppear { engine.prefetch(meta, videoId) }
    }

    private func row(_ s: Source) -> some View {
        HStack(alignment: .top, spacing: 10) {
            VStack(spacing: 4) {
                Text(s.qualityLabel ?? "?").font(.caption.bold()).foregroundColor(.white)
                    .padding(.horizontal, 6).padding(.vertical, 3)
                    .background(RoundedRectangle(cornerRadius: 4).fill(s.rank == 0 ? Theme.red : Color(white: 0.25)))
                if s.cached { Image(systemName: "bolt.fill").font(.caption).foregroundColor(.yellow) }
            }
            .frame(width: 48)
            VStack(alignment: .leading, spacing: 3) {
                Text((s.name ?? s.addon ?? "Source").replacingOccurrences(of: "\n", with: " "))
                    .font(.subheadline.weight(.semibold)).foregroundColor(.white).lineLimit(2)
                if let d = s.description {
                    Text(d).font(.caption).foregroundColor(Theme.dim).lineLimit(4)
                }
                if let a = s.addon { Text(a).font(.caption2).foregroundColor(Color(white: 0.45)) }
            }
        }
        .padding(.vertical, 4)
    }
}

/// A clear UIKit view that catches taps, double taps and drags over the video.
private struct TouchSurface: UIViewRepresentable {
    var onTap: () -> Void
    var onDoubleTap: (_ leftSide: Bool) -> Void
    var onPan: (_ dx: CGFloat, _ dy: CGFloat, _ width: CGFloat, _ ended: Bool) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> UIView {
        let v = UIView()
        v.backgroundColor = .clear
        v.isUserInteractionEnabled = true
        let c = context.coordinator
        let double = UITapGestureRecognizer(target: c, action: #selector(Coordinator.double(_:)))
        double.numberOfTapsRequired = 2
        let single = UITapGestureRecognizer(target: c, action: #selector(Coordinator.single(_:)))
        single.require(toFail: double)
        let pan = UIPanGestureRecognizer(target: c, action: #selector(Coordinator.pan(_:)))
        v.addGestureRecognizer(double)
        v.addGestureRecognizer(single)
        v.addGestureRecognizer(pan)
        return v
    }

    func updateUIView(_ uiView: UIView, context: Context) { context.coordinator.parent = self }

    final class Coordinator: NSObject {
        var parent: TouchSurface
        init(_ p: TouchSurface) { parent = p }

        @objc func single(_ g: UITapGestureRecognizer) { parent.onTap() }

        @objc func double(_ g: UITapGestureRecognizer) {
            guard let v = g.view else { return }
            parent.onDoubleTap(g.location(in: v).x < v.bounds.width / 2)
        }

        @objc func pan(_ g: UIPanGestureRecognizer) {
            guard let v = g.view else { return }
            let t = g.translation(in: v)
            let ended = g.state == .ended || g.state == .cancelled || g.state == .failed
            parent.onPan(t.x, t.y, v.bounds.width, ended)
        }
    }
}

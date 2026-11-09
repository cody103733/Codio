import SwiftUI

enum Theme {
    static let red = Color(red: 0.898, green: 0.035, blue: 0.078) // #E50914
    static let bg = Color.black
    static let card = Color(white: 0.13)
    static let dim = Color(white: 0.65)
    static let gradient = LinearGradient(colors: [Color(red: 1, green: 0.294, blue: 0.169), Color(red: 0.765, green: 0, blue: 0.114)],
                                         startPoint: .topLeading, endPoint: .bottomTrailing)
}

/// The Codio wordmark: the red C with the play button IS the C of "codio" (never a separate icon).
struct BrandWordmark: View {
    var height: CGFloat = 26

    var body: some View {
        Canvas { ctx, size in
            let s = size.height / 40
            func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x * s, y: y * s) }
            let stroke = StrokeStyle(lineWidth: 4.6 * s, lineCap: .round, lineJoin: .round)
            let grad = GraphicsContext.Shading.linearGradient(
                Gradient(colors: [Color(red: 1, green: 0.294, blue: 0.169), Color(red: 0.765, green: 0, blue: 0.114)]),
                startPoint: .zero, endPoint: CGPoint(x: 30 * s, y: 40 * s))
            // The C: an arc around (17, 19.25), r 14.25, open on the right.
            var c = Path()
            c.addArc(center: p(17, 19.25), radius: 14.25 * s, startAngle: .degrees(46), endAngle: .degrees(314), clockwise: false)
            ctx.stroke(c, with: grad, style: stroke)
            // The play button inside it.
            var tri = Path()
            tri.move(to: p(12.5, 12.8)); tri.addLine(to: p(24, 19.25)); tri.addLine(to: p(12.5, 25.7)); tri.closeSubpath()
            ctx.fill(tri, with: .color(.white))
            ctx.stroke(tri, with: .color(.white), style: StrokeStyle(lineWidth: 2 * s, lineJoin: .round))
            // "odio"
            var w = Path()
            for cx in [45.0, 70.0, 114.0] {
                w.addEllipse(in: CGRect(x: (cx - 9.5) * s, y: (24 - 9.5) * s, width: 19 * s, height: 19 * s))
            }
            w.move(to: p(79.5, 5)); w.addLine(to: p(79.5, 33.5))
            w.move(to: p(93, 16)); w.addLine(to: p(93, 33.5))
            ctx.stroke(w, with: .color(.white), style: stroke)
            ctx.fill(Path(ellipseIn: CGRect(x: 90 * s, y: 4.5 * s, width: 6 * s, height: 6 * s)), with: grad)
        }
        .frame(width: height * 129 / 40, height: height)
        .accessibilityLabel("Codio")
    }
}

struct RemoteImage: View {
    let url: String?
    var contentMode: ContentMode = .fill

    var body: some View {
        if let s = url, let u = URL(string: s) {
            AsyncImage(url: u, transaction: Transaction(animation: .easeOut(duration: 0.2))) { phase in
                switch phase {
                case .success(let img): img.resizable().aspectRatio(contentMode: contentMode)
                default: Color(white: 0.12)
                }
            }
        } else {
            Color(white: 0.12)
        }
    }
}

struct PosterCard: View {
    @ObservedObject var meta: Meta
    var width: CGFloat = 112
    var showProgress = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ZStack(alignment: .bottomLeading) {
                RemoteImage(url: meta.poster)
                    .frame(width: width, height: width * 1.5)
                    .clipped()
                if meta.poster == nil {
                    Text(meta.name)
                        .font(.caption.bold())
                        .foregroundColor(.white)
                        .padding(6)
                        .frame(width: width, height: width * 1.5, alignment: .bottomLeading)
                }
                if showProgress, meta.progress > 0 {
                    GeometryReader { g in
                        ZStack(alignment: .leading) {
                            Rectangle().fill(Color.white.opacity(0.3))
                            Rectangle().fill(Theme.red).frame(width: g.size.width * meta.progress)
                        }
                    }
                    .frame(height: 3)
                }
            }
            .frame(width: width, height: width * 1.5)
            .clipShape(RoundedRectangle(cornerRadius: 6))
        }
        .contentShape(Rectangle())
    }
}

/// A titled row of posters that scrolls sideways.
struct PosterRow: View {
    let title: String
    let items: [Meta]
    var showProgress = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.headline).foregroundColor(.white).padding(.horizontal, 16)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 8) {
                    ForEach(items) { m in
                        NavigationLink(value: m) { PosterCard(meta: m, showProgress: showProgress) }
                            .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 16)
            }
        }
    }
}

struct PosterGrid: View {
    let items: [Meta]
    var showProgress = false
    private let columns = [GridItem(.adaptive(minimum: 104, maximum: 140), spacing: 8)]

    var body: some View {
        LazyVGrid(columns: columns, spacing: 12) {
            ForEach(items) { m in
                NavigationLink(value: m) { PosterCard(meta: m, width: 108, showProgress: showProgress) }
                    .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 12)
    }
}

struct RedButtonStyle: ButtonStyle {
    var filled = true
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundColor(filled ? .black : .white)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity)
            .background(filled ? Color.white : Color(white: 0.2))
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

struct ToastView: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.subheadline.weight(.semibold))
            .foregroundColor(.white)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(Capsule().fill(Color(white: 0.18)))
            .shadow(radius: 8)
    }
}

func formatTime(_ t: Double) -> String {
    guard t.isFinite, t >= 0 else { return "0:00" }
    let s = Int(t)
    let h = s / 3600, m = (s % 3600) / 60, sec = s % 60
    return h > 0 ? String(format: "%d:%02d:%02d", h, m, sec) : String(format: "%d:%02d", m, sec)
}

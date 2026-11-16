import SwiftUI
import CoreText

extension Color {
    /// 0xRRGGBB
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}

/// The Codio theme. Same names and values as Theme.java (Android) and Codio.less (Windows); see DESIGN.md.
enum Theme {
    static let red = Color(hex: 0xE50914)
    static let redSoft = Color(hex: 0xFF5A5F)
    static let bg = Color(hex: 0x0B0B0B)
    static let card = Color(hex: 0x161616)
    static let raised = Color(hex: 0x262626)
    static let line = Color(hex: 0x333333)
    static let body = Color(hex: 0xD6D6D6)
    static let dim = Color(hex: 0xB3B3B3)
    static let faint = Color(hex: 0x8C8C8C)
    static let logoColors = [Color(hex: 0xFF4B2B), Color(hex: 0xC3001D)]
    static let gradient = LinearGradient(colors: logoColors, startPoint: .topLeading, endPoint: .bottomTrailing)
    static let profileColors = [Color(hex: 0xE60A14), Color(hex: 0x2178F2), Color(hex: 0xFABA1A), Color(hex: 0x29AD61), Color(hex: 0x8C4DD9), Color(hex: 0xF27326)]
    /// Translucent white behind a button that sits on artwork (Info on Home).
    static let glass = Color.white.opacity(0.2)

    // MARK: Plus Jakarta Sans, the font every Codio app uses.

    private static let fontReady: Bool = {
        let url = Bundle.main.bundleURL.appendingPathComponent("web/fonts/PlusJakartaSans.ttf")
        CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
        return UIFont(name: "PlusJakartaSans-Regular", size: 12) != nil
    }()

    private static func wght(_ w: Font.Weight) -> CGFloat {
        switch w {
        case .ultraLight, .thin: return 200
        case .light: return 300
        case .medium: return 500
        case .semibold: return 600
        case .bold: return 700
        case .heavy, .black: return 800
        default: return 400
        }
    }

    static func uiFont(_ size: CGFloat, _ weight: Font.Weight = .regular) -> UIFont {
        guard fontReady else { return UIFont.systemFont(ofSize: size, weight: wght(weight) >= 600 ? .bold : .regular) }
        // 2003265652 is the font's "wght" axis.
        let attrs: [UIFontDescriptor.AttributeName: Any] = [
            .name: "PlusJakartaSans-Regular",
            UIFontDescriptor.AttributeName(rawValue: kCTFontVariationAttribute as String): [2003265652: wght(weight)],
        ]
        let d = UIFontDescriptor(fontAttributes: attrs)
        return UIFont(descriptor: d, size: size)
    }

    static func font(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        Font(uiFont(size, weight) as CTFont)
    }

    /// The system text styles at their standard sizes, in the Codio font.
    static func font(_ style: Font.TextStyle, _ weight: Font.Weight? = nil) -> Font {
        let size: CGFloat
        switch style {
        case .largeTitle: size = 34
        case .title: size = 28
        case .title2: size = 22
        case .title3: size = 20
        case .headline, .body: size = 17
        case .callout: size = 16
        case .subheadline: size = 15
        case .footnote: size = 13
        case .caption: size = 12
        case .caption2: size = 11
        default: size = 17
        }
        return font(size, weight ?? (style == .headline ? .bold : .regular))
    }
}

/// A filter chip: red when chosen, grey otherwise. The same on every Codio app.
struct Chip: View {
    let text: String
    var selected = false
    var body: some View {
        Text(text)
            .font(Theme.font(14, .bold)).foregroundColor(.white).lineLimit(1)
            .padding(.horizontal, 14).padding(.vertical, 7)
            .background(Capsule().fill(selected ? Theme.red : Theme.raised))
    }
}

/// A row of chips that scrolls sideways.
struct ChipStrip<T: Hashable>: View {
    let options: [(String, T)]
    @Binding var selection: T
    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(options.indices, id: \.self) { i in
                    Button { selection = options[i].1 } label: { Chip(text: options[i].0, selected: options[i].1 == selection) }
                        .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
        }
    }
}

/// The title at the top of Library and Settings, with the round avatar on the right.
struct PageTitle: View {
    let text: String
    var body: some View {
        HStack {
            Text(text).font(Theme.font(24, .bold)).foregroundColor(.white)
            Spacer()
            ProfileButton(size: 34)
        }
        .padding(.leading, 16).padding(.trailing, 14).padding(.top, 14).padding(.bottom, 6)
    }
}

/// A heading inside a page: over a row of posters, a list, or a group of settings.
struct SectionTitle: View {
    let text: String
    var body: some View {
        Text(text).font(Theme.font(17, .bold)).foregroundColor(.white)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14).padding(.top, 18).padding(.bottom, 8)
    }
}

/// The grey line of text a page shows when it has nothing to list.
struct PageHint: View {
    let text: String
    var body: some View {
        Text(text).font(Theme.font(14)).foregroundColor(Theme.dim).multilineTextAlignment(.center)
            .frame(maxWidth: .infinity).padding(.horizontal, 24).padding(.top, 48)
    }
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
                Gradient(colors: Theme.logoColors),
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
                default: Theme.card
                }
            }
        } else {
            Theme.card
        }
    }
}

/// "New episode" in a red capsule, for posters of shows with a new episode out (new episode alerts).
struct NewEpisodeMark: View {
    var body: some View {
        Text("New episode")
            .font(Theme.font(10, .bold))
            .foregroundColor(.white)
            .lineLimit(1)
            .padding(.horizontal, 7).padding(.vertical, 3)
            .background(Capsule().fill(Theme.red))
    }
}

struct PosterCard: View {
    @ObservedObject var meta: Meta
    @ObservedObject private var newEpisodes = NewEpisodes.shared
    var width: CGFloat = 110
    var showProgress = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ZStack(alignment: .bottomLeading) {
                RemoteImage(url: meta.poster)
                    .frame(width: width, height: width * 1.5)
                    .clipped()
                if meta.poster == nil {
                    Text(meta.name)
                        .font(Theme.font(.caption, .bold))
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
            .overlay(alignment: .topLeading) {
                if newEpisodes.alert(meta.id) != nil { NewEpisodeMark().padding(5) }
            }
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
        VStack(alignment: .leading, spacing: 0) {
            SectionTitle(text: title)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 8) {
                    ForEach(items) { m in
                        NavigationLink(value: m) { PosterCard(meta: m, showProgress: showProgress) }
                            .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 14)
            }
            .frame(height: 165)
        }
    }
}

struct PosterGrid: View {
    let items: [Meta]
    var showProgress = false
    var onLast: (() -> Void)? = nil

    /// Three posters across on a phone, more on wider screens, 6 apart: the same grid as Codio for Android.
    static var cells: (count: Int, width: CGFloat) {
        let w = UIScreen.main.bounds.width
        let n = max(3, Int((w / 120).rounded()))
        return (n, ((w - 6 * CGFloat(n + 1)) / CGFloat(n)).rounded(.down))
    }

    var body: some View {
        let c = PosterGrid.cells
        LazyVGrid(columns: Array(repeating: GridItem(.fixed(c.width), spacing: 6), count: c.count), spacing: 6) {
            ForEach(items) { m in
                NavigationLink(value: m) { PosterCard(meta: m, width: c.width, showProgress: showProgress) }
                    .buttonStyle(.plain)
                    .onAppear { if m.id == items.last?.id { onLast?() } }
            }
        }
        .padding(.horizontal, 6)
    }
}

struct RedButtonStyle: ButtonStyle {
    var filled = true
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Theme.font(.headline))
            .foregroundColor(filled ? .black : .white)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity)
            .background(filled ? Color.white : Theme.raised)
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

struct ToastView: View {
    let text: String
    var body: some View {
        Text(text)
            .font(Theme.font(.subheadline, .semibold))
            .foregroundColor(.white)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(Capsule().fill(Theme.raised))
            .shadow(radius: 8)
    }
}

func formatTime(_ t: Double) -> String {
    guard t.isFinite, t >= 0 else { return "0:00" }
    let s = Int(t)
    let h = s / 3600, m = (s % 3600) / 60, sec = s % 60
    return h > 0 ? String(format: "%d:%02d:%02d", h, m, sec) : String(format: "%d:%02d", m, sec)
}

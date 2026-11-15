import SwiftUI

extension Color {
    init(hex: String, alpha: Double = 1) {
        let h = hex.trimmingCharacters(in: CharacterSet(charactersIn: "#"))
        let v = UInt64(h, radix: 16) ?? 0
        self.init(.sRGB, red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255,
                  blue: Double(v & 0xFF) / 255, opacity: alpha)
    }
    static let ink = Color(hex: "101218"), surf1 = Color(hex: "1A1D26"), surf2 = Color(hex: "242833")
    static let textHi = Color(hex: "F2F0EA"), textMid = Color(hex: "A6ADBB"), okBg = Color(hex: "13352B"), okFg = Color(hex: "6EE7B7")
}

/// Key → x position shared by the falling notes, keyboard and LED preview.
struct KeyGeom {
    let low: Int, high: Int
    private static let whiteBefore: [Int] = {
        var out = [Int](repeating: 0, count: 130), c = 0
        for p in 0..<130 { out[p] = c; if !isBlack(p) { c += 1 } }
        return out
    }()
    static func isBlack(_ p: Int) -> Bool { [1, 3, 6, 8, 10].contains(((p % 12) + 12) % 12) }
    var whiteCount: Int { Self.whiteBefore[high + 1] - Self.whiteBefore[low] }
    func x(_ p: Int, width w: CGFloat) -> (x: CGFloat, w: CGFloat) {
        let ww = w / CGFloat(max(1, whiteCount))
        let base = CGFloat(Self.whiteBefore[p] - Self.whiteBefore[low]) * ww
        return Self.isBlack(p) ? (base - 0.31 * ww, 0.62 * ww) : (base, ww)
    }
}

struct DrawState {
    var song: Song?
    var pos: Double
    var geom: KeyGeom
    var colors: [Hand: Color]
    var showLeft: Bool, showRight: Bool
    var guidance: Double
    var labelMode: String
    var fingering: [ObjectIdentifier: Int]
    var loop: ClosedRange<Double>?
    var measures: [Double]

    func practised(_ n: Note) -> Bool { !(n.hand == .left && !showLeft) && !(n.hand == .right && !showRight) }
    func color(_ h: Hand) -> Color { colors[h] ?? Color(hex: "9AA3AF") }
}

enum Draw {
    static let pxPerSec: CGFloat = 190

    static func roll(_ ctx: GraphicsContext, _ size: CGSize, _ st: DrawState) {
        ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Color(hex: "0B0D12")))
        let nowY = size.height - 8
        let tTop = st.pos + Double(nowY / pxPerSec), tBot = st.pos - Double((size.height - nowY) / pxPerSec)
        func y(_ t: Double) -> CGFloat { nowY - CGFloat(t - st.pos) * pxPerSec }
        for m in st.measures where m >= tBot && m <= tTop {
            ctx.fill(Path(CGRect(x: 0, y: y(m), width: size.width, height: 1)), with: .color(Color(hex: "2A313B")))
        }
        if let l = st.loop {
            ctx.fill(Path(CGRect(x: 0, y: y(l.upperBound), width: size.width, height: y(l.lowerBound) - y(l.upperBound))),
                     with: .color(Color.textHi.opacity(0.1)))
        }
        if let song = st.song {
            for n in song.notes {
                guard n.pitch >= st.geom.low, n.pitch <= st.geom.high, n.end >= tBot, n.start <= tTop else { continue }
                let k = st.geom.x(n.pitch, width: size.width)
                let r = CGRect(x: k.x + 1.5, y: y(n.end), width: k.w - 3, height: y(n.start) - y(n.end))
                let pr = st.practised(n)
                var a = (n.start <= st.pos && st.pos < n.end) ? 1.0 : 0.8
                if !pr { a = 0.25 } else if st.guidance < 1 { a *= 0.08 + 0.92 * st.guidance }
                ctx.fill(Path(roundedRect: r, cornerRadius: 5), with: .color(st.color(n.hand).opacity(a)))
                if pr, st.guidance > 0.3, r.height > 18, r.width > 10 {
                    var label: String?
                    if let f = st.fingering[ObjectIdentifier(n)] { label = "\(f)" } else if st.labelMode != "c" { label = NoteNames.name(n.pitch, st.labelMode) }
                    if let label {
                        ctx.draw(Text(label).font(.system(size: 11, weight: .bold)).foregroundColor(.ink),
                                 at: CGPoint(x: r.midX, y: r.maxY - 9))
                    }
                }
            }
        }
        ctx.fill(Path(CGRect(x: 0, y: nowY - 1, width: size.width, height: 2)), with: .color(Color.textHi.opacity(0.6)))
    }

    static func keyboard(_ ctx: GraphicsContext, _ size: CGSize, _ st: DrawState, active: [Int: Hand],
                         upcoming: [Int: Hand], pressed: [Int: Bool], fingers: [Int: Int]) {
        ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Color(hex: "0A0B0F")))
        let g = st.geom
        let ww = size.width / CGFloat(max(1, g.whiteCount))
        func highlight(_ p: Int, _ r: CGRect) {
            guard st.guidance > 0, let h = active[p] ?? upcoming[p] else { return }
            if (h == .left && !st.showLeft) || (h == .right && !st.showRight) { return }
            let act = active[p] != nil
            ctx.fill(Path(roundedRect: act ? r : r.insetBy(dx: 3, dy: 3), cornerRadius: 3),
                     with: .color(st.color(h).opacity((act ? 0.82 : 0.41) * st.guidance)))
        }
        func press(_ p: Int, _ r: CGRect) {
            guard let ok = pressed[p] else { return }
            let bh = min(r.height * 0.22, 18)
            ctx.fill(Path(roundedRect: CGRect(x: r.minX + 2, y: r.maxY - bh - 2, width: r.width - 4, height: bh), cornerRadius: 3),
                     with: .color(ok ? Color(hex: "22C55E", alpha: 0.9) : Color(hex: "EF4444", alpha: 0.9)))
        }
        func finger(_ p: Int, _ c: CGPoint, _ kw: CGFloat) {
            guard st.guidance > 0, let f = fingers[p] else { return }
            let rad = min(9, kw * 0.42)
            guard rad >= 5 else { return }
            ctx.fill(Path(ellipseIn: CGRect(x: c.x - rad, y: c.y - rad, width: rad * 2, height: rad * 2)),
                     with: .color(Color.ink.opacity(0.9 * st.guidance)))
            ctx.draw(Text("\(f)").font(.system(size: rad * 1.25, weight: .bold)).foregroundColor(.white), at: c)
        }
        for p in g.low...g.high where !KeyGeom.isBlack(p) {
            let k = g.x(p, width: size.width)
            let r = CGRect(x: k.x + 0.5, y: 0, width: k.w - 1, height: size.height)
            ctx.fill(Path(roundedRect: r, cornerRadius: 4), with: .color(Color(hex: "F4F2EE")))
            highlight(p, r)
            press(p, r)
            if ww >= 14, let l = NoteNames.keyLabel(p, st.labelMode) {
                ctx.draw(Text(l).font(.system(size: min(11, ww * 0.42), weight: .bold)).foregroundColor(Color(hex: "6B7280")),
                         at: CGPoint(x: r.midX, y: size.height - 12))
            }
            finger(p, CGPoint(x: r.midX, y: size.height * 0.68), k.w)
        }
        for p in g.low...g.high where KeyGeom.isBlack(p) {
            let k = g.x(p, width: size.width)
            let r = CGRect(x: k.x, y: 0, width: k.w, height: size.height * 0.62)
            ctx.fill(Path(roundedRect: r, cornerRadius: 4), with: .color(Color(hex: "17181C")))
            highlight(p, r)
            press(p, r)
            finger(p, CGPoint(x: r.midX, y: r.height * 0.62), k.w)
        }
    }

    static func staff(_ ctx: GraphicsContext, _ size: CGSize, _ st: DrawState) {
        ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Color(hex: "14171F")))
        let gap = size.height / 15, half = gap / 2
        func y(_ step: Int) -> CGFloat { size.height / 2 - CGFloat(step - 28) * half }
        let line = Color(hex: "6B7280")
        func hline(_ s: Int, _ x0: CGFloat, _ x1: CGFloat) {
            ctx.fill(Path(CGRect(x: x0, y: y(s) - 0.5, width: x1 - x0, height: 1)), with: .color(line))
        }
        for s in [30, 32, 34, 36, 38, 18, 20, 22, 24, 26] { hline(s, 0, size.width) }
        ctx.draw(Text("Sol").font(.system(size: 10, weight: .bold)).foregroundColor(.textMid), at: CGPoint(x: 14, y: y(34) - 7))
        ctx.draw(Text("Fa").font(.system(size: 10, weight: .bold)).foregroundColor(.textMid), at: CGPoint(x: 12, y: y(22) - 7))
        let pps: CGFloat = 110, nowX = size.width * 0.18
        let tl = st.pos - Double(nowX / pps), tr = st.pos + Double((size.width - nowX) / pps)
        for m in st.measures where m >= tl && m <= tr {
            let x = nowX + CGFloat(m - st.pos) * pps
            ctx.fill(Path(CGRect(x: x, y: y(38), width: 1, height: y(18) - y(38))), with: .color(Color(hex: "3E4452")))
        }
        let letter = [0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6]
        if let song = st.song {
            for n in song.notes where n.end >= tl && n.start <= tr {
                let a = st.practised(n) ? 0.15 + 0.85 * st.guidance : 0.27
                let step = (n.pitch / 12 - 1) * 7 + letter[n.pitch % 12]
                let yy = y(step)
                let x0 = nowX + CGFloat(n.start - st.pos) * pps, x1 = nowX + CGFloat(n.end - st.pos) * pps
                if step == 28 { hline(28, x0 - gap, x0 + gap) }
                var s = 40
                while s <= step { hline(s, x0 - gap, x0 + gap); s += 2 }
                s = 16
                while s >= step { hline(s, x0 - gap, x0 + gap); s -= 2 }
                let c = st.color(n.hand)
                ctx.fill(Path(CGRect(x: x0 + gap * 0.6, y: yy - 1.5, width: max(0, x1 - x0 - gap * 0.6), height: 3)), with: .color(c.opacity(a / 2)))
                ctx.fill(Path(ellipseIn: CGRect(x: x0 - gap * 0.65, y: yy - half * 0.95, width: gap * 1.3, height: half * 1.9)), with: .color(c.opacity(a)))
                if KeyGeom.isBlack(n.pitch) {
                    ctx.draw(Text("♯").font(.system(size: gap * 1.4)).foregroundColor(.textHi), at: CGPoint(x: x0 - gap * 1.4, y: yy))
                }
            }
        }
        ctx.fill(Path(CGRect(x: nowX - 1, y: 0, width: 2, height: size.height)), with: .color(Color.textHi.opacity(0.6)))
    }
}

/// Seven bars: practice minutes over the last 7 days (today on the right).
struct WeekBars: View {
    let seconds: [Int]
    let goalMinutes: Int
    var body: some View {
        HStack(alignment: .bottom, spacing: 5) {
            ForEach(0..<7, id: \.self) { i in
                let frac = min(1, Double(seconds[i]) / Double(max(60, goalMinutes * 60)))
                VStack(spacing: 2) {
                    ZStack(alignment: .bottom) {
                        RoundedRectangle(cornerRadius: 2.5).fill(Color.surf2)
                        RoundedRectangle(cornerRadius: 2.5).fill(frac >= 1 ? Color(hex: "34D399") : .textHi)
                            .frame(height: 30 * frac)
                    }
                    .frame(width: 10, height: 30)
                    Text(dayName(6 - i)).font(.system(size: 9)).foregroundColor(.textMid)
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(seconds.map { "\($0 / 60) phút" }.joined(separator: ", "))
    }

    private func dayName(_ back: Int) -> String {
        let d = Calendar.current.date(byAdding: .day, value: -back, to: Date())!
        let w = Calendar.current.component(.weekday, from: d) // 1 = Sunday
        return w == 1 ? "CN" : "T\(w)"
    }
}

/// A–B repeat range; both handles snap to measure lines.
struct LoopBar: View {
    let duration: Double
    let snaps: [Double]
    let range: ClosedRange<Double>
    /// Bars where the learner often makes mistakes ("chỗ khó"), drawn red.
    var hot: [ClosedRange<Double>] = []
    let onChange: (Double, Double) -> Void
    @State private var a: Double?
    @State private var b: Double?
    @State private var dragging = 0

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width - 24
            let lo = a ?? range.lowerBound, hi = b ?? range.upperBound
            let x = { (s: Double) in 12 + CGFloat(s / max(duration, 0.1)) * w }
            ZStack(alignment: .leading) {
                Capsule().fill(Color.surf2).frame(height: 6).padding(.horizontal, 12)
                ForEach(Array(hot.enumerated()), id: \.offset) { _, r in
                    Rectangle().fill(Color(hex: "E5484D").opacity(0.85))
                        .frame(width: max(2, x(r.upperBound) - x(r.lowerBound)), height: 6).offset(x: x(r.lowerBound))
                }
                Capsule().fill(Color.textHi.opacity(0.4)).frame(width: max(0, x(hi) - x(lo)), height: 10).offset(x: x(lo))
                Circle().fill(Color.textHi).frame(width: 16, height: 16).offset(x: x(lo) - 8)
                Circle().fill(Color.textHi).frame(width: 16, height: 16).offset(x: x(hi) - 8)
            }
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { v in
                    let s = Double((v.location.x - 12) / max(1, w)) * duration
                    let snap = (snaps + [duration]).min { abs($0 - s) < abs($1 - s) } ?? s
                    if dragging == 0 { dragging = abs(v.startLocation.x - x(lo)) <= abs(v.startLocation.x - x(hi)) ? 1 : 2 }
                    if dragging == 1, snap < hi { a = snap }
                    if dragging == 2, snap > lo { b = snap }
                }
                .onEnded { _ in
                    onChange(a ?? range.lowerBound, b ?? range.upperBound)
                    a = nil; b = nil; dragging = 0
                })
        }
        .frame(height: 30)
        .accessibilityLabel("Đoạn lặp lại, kéo hai đầu để chọn ô nhịp")
    }
}

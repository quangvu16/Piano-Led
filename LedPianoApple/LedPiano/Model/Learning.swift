import Foundation

/// Learning helpers for the practice screen (same rules as learning.py in the Python app and
/// Learning.kt on Android):
///  - HotSpots: in which bar mistakes happen ("Luyện chỗ khó"). Practising the exact hard spot slowly,
///    and repeating it until the mistake stops, is what expert teachers do (Duke, Simmons & Cash 2009).
///  - timingFlash: green flash for a key played on time, orange for early / late.
///  - dynScale / Dynamics: LED brightness from loudness, "play softer / louder" advice.
///  - Recorder / midiFile: record what the learner plays as a normal MIDI file.
///  - dailyPlan: today's 3 short tasks (warm-up, review an older song, one new lesson).
enum Learning {
    // MARK: hot spots

    /// The hard bar starts at 65 % speed (auto speed-up adds 10 % after 3 clean loops).
    static let hotSpotSpeed = 65

    /// Index of the bar that contains `sec` (`measures` = bar start times).
    static func barOf(_ measures: [Double], _ sec: Double) -> Int {
        var lo = 0, hi = measures.count
        while lo < hi {
            let mid = (lo + hi) / 2
            if measures[mid] <= sec + 1e-6 { lo = mid + 1 } else { hi = mid }
        }
        return max(0, lo - 1)
    }

    /// Loop range for one bar (two bars when one bar is shorter than 1.5 s).
    static func loopForBar(_ measures: [Double], duration: Double, bar: Int) -> (Double, Double) {
        let a = bar < measures.count ? measures[bar] : 0
        var b = bar + 1 < measures.count ? measures[bar + 1] : duration
        if b - a < 1.5 && bar + 2 < measures.count { b = measures[bar + 2] }
        return (a, min(b, duration))
    }

    /// Mistakes per bar per song, kept in UserDefaults.
    final class HotSpots {
        private let d = UserDefaults.standard
        private let storeKey = "hot_spots"

        private func all() -> [String: [String: Int]] { d.dictionary(forKey: storeKey) as? [String: [String: Int]] ?? [:] }

        private func update(_ key: String, _ change: (inout [String: Int]) -> Void) {
            var a = all()
            var bars = a[key] ?? [:]
            change(&bars)
            a[key] = bars.isEmpty ? nil : bars
            d.set(a, forKey: storeKey)
        }

        func addMiss(_ key: String, bar: Int, count: Int = 1) {
            update(key) { $0[String(bar), default: 0] += count }
        }

        /// A clean play of the bar lowers its count; at 0 it is no longer a hot spot.
        func addClean(_ key: String, bar: Int) {
            update(key) { bars in
                guard let v = bars[String(bar)] else { return }
                bars[String(bar)] = v <= 1 ? nil : v - 1
            }
        }

        func counts(_ key: String) -> [Int: Int] {
            var out: [Int: Int] = [:]
            for (k, v) in all()[key] ?? [:] { if let b = Int(k), v > 0 { out[b] = v } }
            return out
        }

        /// The bar with the most mistakes (the earlier one on a tie), or nil.
        func worst(_ key: String) -> Int? {
            counts(key).max { a, b in a.value != b.value ? a.value < b.value : a.key > b.key }?.key
        }
    }

    // MARK: feedback colours

    static let red: UInt32 = 0xFF0000, green: UInt32 = 0x00D040, orange: UInt32 = 0xFF8C00
    static let goodMs = 90.0

    /// Green when within ±90 ms of the beat, otherwise orange (early or late).
    static func timingFlash(_ offsetMs: Double) -> UInt32 { abs(offsetMs) <= goodMs ? green : orange }

    // MARK: dynamics

    /// LED brightness for a note's loudness: soft 30 % … loud 100 %.
    static func dynScale(_ velocity: Int) -> Double { 0.3 + 0.7 * Double(min(127, max(0, velocity))) / 127 }

    /// Compares how hard the learner plays (MIDI velocity) with the song.
    final class Dynamics {
        static let minNotes = 6, limit = 20.0
        private var diffs: [Int] = []
        func reset() { diffs.removeAll() }
        func add(expected: Int, played: Int) { if played > 0 && expected > 0 { diffs.append(played - expected) } }

        /// "softer" / "louder" when the learner is consistently off, else nil.
        func advice() -> String? {
            guard diffs.count >= Self.minNotes else { return nil }
            let mean = Double(diffs.reduce(0, +)) / Double(diffs.count)
            if mean > Self.limit { return "softer" }
            if mean < -Self.limit { return "louder" }
            return nil
        }
    }

    static let adviceVi = ["softer": "Chơi nhẹ tay hơn một chút", "louder": "Chơi mạnh tay hơn một chút"]

    // MARK: recording

    struct RecEvent { let sec: Double; let pitch: Int; let velocity: Int } // velocity 0 = released

    /// Records note on / off (seconds since start) and writes a standard MIDI file.
    final class Recorder {
        private(set) var events: [RecEvent] = []
        private(set) var active = false
        private var t0 = Date()

        func start() { events.removeAll(); active = true; t0 = Date() }

        func note(_ pitch: Int, _ velocity: Int) {
            if active { events.append(RecEvent(sec: max(0, Date().timeIntervalSince(t0)), pitch: pitch, velocity: velocity)) }
        }

        /// Stops; returns the number of notes played.
        func stop() -> Int { active = false; return events.filter { $0.velocity > 0 }.count }

        func midiBytes(bpm: Int = 120) -> Data { midiFile(events, bpm: bpm) }
    }

    /// Type-0 standard MIDI file (one track, piano) from recorded events.
    static func midiFile(_ events: [RecEvent], bpm: Int = 120, ppq: Int = 480) -> Data {
        let us = 60_000_000 / min(300, max(20, bpm))
        var trk: [UInt8] = []
        func vlq(_ value: Int) {
            var n = value
            var buf: [UInt8] = [UInt8(n & 0x7F)]
            n >>= 7
            while n != 0 { buf.insert(UInt8((n & 0x7F) | 0x80), at: 0); n >>= 7 }
            trk += buf
        }
        vlq(0); trk += [0xFF, 0x51, 3, UInt8((us >> 16) & 0xFF), UInt8((us >> 8) & 0xFF), UInt8(us & 0xFF)]
        vlq(0); trk += [0xFF, 0x58, 4, 4, 2, 24, 8]
        var last = 0
        var open = Set<Int>()
        let sorted = events.enumerated().sorted { a, b in
            if a.element.sec != b.element.sec { return a.element.sec < b.element.sec }
            let ao = a.element.velocity == 0, bo = b.element.velocity == 0
            if ao != bo { return !ao } // note-on before note-off at the same time (like Python's sort)
            return a.offset < b.offset
        }.map(\.element)
        for e in sorted {
            let tick = Int((e.sec * Double(ppq) * 1_000_000 / Double(us)).rounded())
            if e.velocity == 0 && !open.contains(e.pitch) { continue }
            vlq(max(0, tick - last))
            last = max(last, tick)
            if e.velocity > 0 {
                open.insert(e.pitch)
                trk += [0x90, UInt8(e.pitch & 0x7F), UInt8(min(127, e.velocity))]
            } else {
                open.remove(e.pitch)
                trk += [0x80, UInt8(e.pitch & 0x7F), 0]
            }
        }
        for p in open.sorted() { vlq(ppq / 2); trk += [0x80, UInt8(p & 0x7F), 0] } // close anything still held
        vlq(0); trk += [0xFF, 0x2F, 0]
        var out: [UInt8] = Array("MThd".utf8) + [0, 0, 0, 6, 0, 0, 0, 1, UInt8((ppq >> 8) & 0xFF), UInt8(ppq & 0xFF)]
        out += Array("MTrk".utf8)
        let n = trk.count
        out += [UInt8((n >> 24) & 0xFF), UInt8((n >> 16) & 0xFF), UInt8((n >> 8) & 0xFF), UInt8(n & 0xFF)]
        out += trk
        return Data(out)
    }

    // MARK: daily plan

    enum PlanKind: String { case warmup = "Khởi động", review = "Ôn lại", new = "Bài mới" }

    /// Today's plan from the lesson path: a warm-up (Hanon if there is one), the practised song not played
    /// for the longest time (spacing practice out helps it stick), and the first lesson not finished yet.
    static func dailyPlan(_ lessons: [Lesson], today: Int, lastDay: (String) -> Int?, done: (String) -> Bool) -> [(PlanKind, Lesson)] {
        var out: [(PlanKind, Lesson)] = []
        let warm = lessons.first { $0.title.lowercased().contains("hanon") } ?? lessons.first
        if let warm { out.append((.warmup, warm)) }
        let practised = lessons.filter { l in l.key != warm?.key && (lastDay(l.key).map { $0 < today } ?? false) }
        if let r = practised.min(by: { (lastDay($0.key) ?? 0) < (lastDay($1.key) ?? 0) }) { out.append((.review, r)) }
        if let n = lessons.first(where: { $0.key != warm?.key && !done($0.key) }), !out.contains(where: { $0.1.key == n.key }) {
            out.append((.new, n))
        }
        return out
    }
}

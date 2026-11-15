import Foundation

/// Which hand plays a note.
enum Hand: String, Codable { case left = "L", right = "R", unknown = "?" }

/// One note from a Standard MIDI File (reference type so it can be identified).
final class Note {
    let startTick: Int
    var endTick: Int
    let pitch: Int
    let velocity: Int
    let track: Int
    let channel: Int
    var start: Double = 0
    var end: Double = 0
    var hand: Hand = .unknown
    /// Finger number 1–5 from the score (MusicXML), 0 = none.
    var finger = 0

    init(startTick: Int, endTick: Int, pitch: Int, velocity: Int, track: Int, channel: Int) {
        self.startTick = startTick; self.endTick = endTick; self.pitch = pitch
        self.velocity = velocity; self.track = track; self.channel = channel
    }

    var isBlack: Bool { [1, 3, 6, 8, 10].contains(pitch % 12) }
}

enum Piano {
    static let lowest = 21, highest = 108, middleC = 60
}

struct MidiError: Error, LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// A parsed song with its tempo map, ready for playback and drawing.
final class Song {
    let title: String
    let ppq: Int
    let notes: [Note]
    let tempos: [(tick: Int, us: Int)]
    let timeSigs: [(tick: Int, num: Int, den: Int)]
    private(set) var duration: Double = 0
    private(set) var byTime: [Note] = []
    let multiInstrument: Bool
    /// MusicXML with two staves / parts: the score says which hand plays, so the split setting does not apply.
    var handsFromScore = false
    /// MusicXML with one staff: hands are split by pitch (not by track).
    var splitByPitchOnly = false

    init(title: String, ppq: Int, notes: [Note], tempos: [(Int, Int)], timeSigs: [(Int, Int, Int)]) {
        self.title = title; self.ppq = ppq; self.notes = notes
        self.tempos = tempos.map { (tick: $0.0, us: $0.1) }
        self.timeSigs = timeSigs.map { (tick: $0.0, num: $0.1, den: $0.2) }
        multiInstrument = Set(notes.map(\.track)).count > 2
        for n in notes {
            if n.endTick <= n.startTick { n.endTick = n.startTick + 1 }
            n.start = tickToSec(n.startTick)
            n.end = tickToSec(n.endTick)
        }
        duration = notes.map(\.end).max() ?? 0
        byTime = notes.sorted { $0.start < $1.start }
    }

    func tickToSec(_ tick: Int) -> Double {
        var sec = 0.0, last = 0, us = 500_000
        for t in tempos {
            if t.tick >= tick { break }
            if t.tick > last {
                sec += Double(t.tick - last) * Double(us) / Double(ppq) / 1e6
                last = t.tick
            }
            us = t.us
        }
        return sec + Double(tick - last) * Double(us) / Double(ppq) / 1e6
    }

    func measureTicks() -> [Int] {
        let maxTick = notes.map(\.endTick).max() ?? 0
        var out: [Int] = []
        var i = 0
        var tick = timeSigs[0].tick
        while tick <= maxTick && out.count < 100_000 {
            out.append(tick)
            let ts = timeSigs[i]
            let len = max(1, ts.num * 4 * ppq / max(1, ts.den))
            if i + 1 < timeSigs.count && timeSigs[i + 1].tick < tick + len {
                tick = timeSigs[i + 1].tick; i += 1
            } else {
                tick += len
            }
        }
        return out
    }

    lazy var measureSecs: [Double] = measureTicks().map { tickToSec($0) }.filter { $0 <= duration }

    /// Index of the first note in [byTime] starting at or after [sec].
    func lowerBound(_ sec: Double) -> Int {
        var lo = 0, hi = byTime.count
        while lo < hi {
            let mid = (lo + hi) / 2
            if byTime[mid].start < sec { lo = mid + 1 } else { hi = mid }
        }
        return lo
    }
}

/// Minimal SMF parser (formats 0 and 1, PPQ timing), same rules as the Android app.
enum MidiParser {
    static func parse(data: Data, title: String) throws -> Song {
        let b = [UInt8](data)
        var p = 0
        func u8() throws -> Int { guard p < b.count else { throw MidiError(message: "Tệp MIDI bị cắt") }; defer { p += 1 }; return Int(b[p]) }
        func u16() throws -> Int { try (u8() << 8) | u8() }
        func u32() throws -> Int { try (u16() << 16) | u16() }
        func ascii(_ n: Int) throws -> String {
            guard p + n <= b.count else { throw MidiError(message: "Tệp MIDI bị cắt") }
            defer { p += n }
            return String(bytes: b[p..<p + n], encoding: .ascii) ?? ""
        }
        func vlq() throws -> Int {
            var v = 0
            for _ in 0..<4 { let c = try u8(); v = (v << 7) | (c & 0x7F); if c & 0x80 == 0 { break } }
            return v
        }

        guard try ascii(4) == "MThd" else { throw MidiError(message: "Không phải tệp MIDI hợp lệ") }
        let hlen = try u32()
        _ = try u16()
        let ntrks = try u16()
        let division = try u16()
        guard division & 0x8000 == 0, division > 0 else { throw MidiError(message: "MIDI dùng định dạng SMPTE không được hỗ trợ") }
        p += max(0, hlen - 6)

        var notes: [Note] = []
        var tempos: [(Int, Int)] = []
        var sigs: [(Int, Int, Int)] = []
        for track in 0..<ntrks {
            guard p + 8 <= b.count else { break }
            let id = try ascii(4)
            let len = try u32()
            let end = min(b.count, p + len)
            if id != "MTrk" { p = end; continue }
            var tick = 0
            var running = -1
            var open: [Int: [Note]] = [:]
            while p < end {
                tick += try vlq()
                guard p < end else { break }
                var status = Int(b[p])
                if status < 0x80 {
                    guard running >= 0 else { throw MidiError(message: "Lỗi running status") }
                    status = running
                } else {
                    p += 1
                    if status < 0xF0 { running = status } else if status != 0xF7 && status != 0xFF { running = -1 }
                }
                if status == 0xFF {
                    let type = try u8()
                    let l = try vlq()
                    let payload = Array(b[p..<min(b.count, p + l)])
                    p += l
                    if type == 0x51, payload.count >= 3 {
                        tempos.append((tick, Int(payload[0]) << 16 | Int(payload[1]) << 8 | Int(payload[2])))
                    } else if type == 0x58, payload.count >= 2 {
                        sigs.append((tick, Int(payload[0]), 1 << Int(payload[1])))
                    } else if type == 0x2F { break }
                } else if status == 0xF0 || status == 0xF7 {
                    p += try vlq()
                } else {
                    let type = status & 0xF0, ch = status & 0x0F
                    let d1 = try u8()
                    var d2 = 0
                    if type != 0xC0 && type != 0xD0 { d2 = try u8() }
                    let key = ch << 8 | d1
                    if type == 0x90 && d2 > 0 {
                        let n = Note(startTick: tick, endTick: tick, pitch: d1, velocity: d2, track: track, channel: ch)
                        notes.append(n)
                        open[key, default: []].append(n)
                    } else if type == 0x80 || type == 0x90 {
                        if var stack = open[key], !stack.isEmpty {
                            stack.removeFirst().endTick = tick
                            open[key] = stack
                        }
                    }
                }
            }
            for stack in open.values { for n in stack { n.endTick = max(n.endTick, n.startTick + division / 2) } }
            p = end
        }
        guard !notes.isEmpty else { throw MidiError(message: "Tệp MIDI không có nốt nhạc nào") }
        tempos.sort { $0.0 < $1.0 }
        if tempos.first.map({ $0.0 > 0 }) ?? true { tempos.insert((0, 500_000), at: 0) }
        sigs.sort { $0.0 < $1.0 }
        if sigs.isEmpty { sigs = [(0, 4, 4)] }
        return Song(title: title, ppq: division, notes: notes, tempos: tempos, timeSigs: sigs)
    }
}

enum HandAssigner {
    static func usesPitchSplit(_ s: Song) -> Bool { !s.handsFromScore && (s.splitByPitchOnly || Set(s.notes.map(\.track)).count != 2) }

    /// Re-applies the hand split (Options) unless the score already says which hand plays.
    static func reassign(_ s: Song, split: Int) {
        if s.handsFromScore { return }
        if s.splitByPitchOnly { for n in s.notes { n.hand = n.pitch < split ? .left : .right } } else { assign(s, split: split) }
    }

    /// Two tracks: lower-average track = left hand. Otherwise split by pitch.
    static func assign(_ s: Song, split: Int = Piano.middleC) {
        let byTrack = Dictionary(grouping: s.notes, by: \.track)
        if byTrack.count == 2 {
            let avg = byTrack.mapValues { ns in Double(ns.map(\.pitch).reduce(0, +)) / Double(ns.count) }
            let low = avg.min { $0.value < $1.value }!.key
            for (t, ns) in byTrack { for n in ns { n.hand = t == low ? .left : .right } }
        } else {
            for n in s.notes { n.hand = n.pitch < split ? .left : .right }
        }
    }
}

/// Beat times for the metronome and count-in.
struct BeatGrid {
    var secs: [Double] = []
    var down: [Bool] = []
    var beatsPerMeasure = 4

    init(song: Song) {
        let measures = song.measureTicks()
        for (i, m) in measures.enumerated() {
            let ts = song.timeSigs.last { $0.tick <= m } ?? song.timeSigs[0]
            let beat = max(1, song.ppq * 4 / max(1, ts.den))
            let next = i + 1 < measures.count ? measures[i + 1] : m + beat * ts.num
            for k in 0..<max(1, ts.num) {
                let t = m + k * beat
                if t >= next { break }
                let s = song.tickToSec(t)
                if s > song.duration + 0.01 { break }
                secs.append(s); down.append(k == 0)
            }
        }
        beatsPerMeasure = min(8, max(2, song.timeSigs[0].num))
    }

    func lowerBound(_ sec: Double) -> Int {
        var lo = 0, hi = secs.count
        while lo < hi { let mid = (lo + hi) / 2; if secs[mid] < sec { lo = mid + 1 } else { hi = mid } }
        return lo
    }

    func beatLength(at sec: Double) -> Double {
        guard secs.count >= 2 else { return 0.5 }
        let i = min(max(lowerBound(sec), 1), secs.count - 1)
        let d = secs[i] - secs[i - 1]
        return d > 0.05 ? d : 0.5
    }
}

enum NoteNames {
    static let letters = ["C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B"]
    static let solfege = ["Đô", "Đô♯", "Rê", "Mi♭", "Mi", "Fa", "Fa♯", "Sol", "La♭", "La", "Si♭", "Si"]

    static func octave(_ p: Int) -> Int { p / 12 - 1 }

    /// mode: "letters", "solfege", "c" (C keys only) or "none".
    static func name(_ p: Int, _ mode: String) -> String? {
        let pc = ((p % 12) + 12) % 12
        switch mode {
        case "letters": return letters[pc]
        case "solfege": return solfege[pc]
        case "c": return pc == 0 ? "C\(octave(p))" : nil
        default: return nil
        }
    }

    static func keyLabel(_ p: Int, _ mode: String) -> String? {
        guard let base = name(p, mode) else { return nil }
        return p % 12 == 0 && mode != "c" ? "\(base)\(octave(p))" : base
    }
}

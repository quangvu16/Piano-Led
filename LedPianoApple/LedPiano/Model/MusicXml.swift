import Compression
import Foundation

/// MusicXML import (.musicxml / .xml, and compressed .mxl) – e.g. songs exported from MuseScore.
///
/// Unlike MIDI, MusicXML keeps which hand plays each note (staff 1 = right, staff 2 = left), the
/// finger numbers and the bars. Same rules as musicxml.py (Python) and MusicXml.kt (Android).
/// Handles: score-partwise, divisions changes, chords, backup/forward, ties, rests, grace notes
/// (skipped), sound tempo, time signatures, fingering, 1 part with 2 staves or 2 separate parts.
enum MusicXml {
    static let ppq = 480
    private static let step: [String: Int] = ["C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11]

    static func isMusicXml(_ data: Data) -> Bool {
        let b = [UInt8](data.prefix(400))
        if b.count >= 2 && b[0] == 0x50 && b[1] == 0x4B { return true } // "PK" = zip (.mxl)
        guard let head = String(bytes: b, encoding: .utf8) ?? String(bytes: b, encoding: .isoLatin1) else { return false }
        let t = head.trimmingCharacters(in: CharacterSet(charactersIn: "\u{FEFF} \n\r\t"))
        return t.hasPrefix("<") && (t.contains("score-partwise") || t.contains("score-timewise") || t.hasPrefix("<?xml"))
    }

    static func parse(_ raw: Data, title: String?) throws -> Song {
        var data = raw
        if data.count >= 2 && data[data.startIndex] == 0x50 && data[data.startIndex + 1] == 0x4B { data = try unzipScore(raw) }
        let tree = XTree()
        let parser = XMLParser(data: data)
        parser.shouldResolveExternalEntities = false // never fetch the DTD from the internet
        parser.delegate = tree
        guard parser.parse(), let root = tree.root else { throw MidiError(message: "Tệp MusicXML bị lỗi") }
        if root.name == "score-timewise" {
            throw MidiError(message: "MusicXML dạng 'timewise' chưa được hỗ trợ — hãy xuất lại dạng thường (partwise)")
        }
        let parts = root.all("part")
        guard !parts.isEmpty else { throw MidiError(message: "Tệp MusicXML không có phần nhạc nào") }
        let name = (title?.isEmpty == false ? title : nil) ?? root.text("work/work-title") ?? root.text("movement-title") ?? "MusicXML"
        var notes: [Note] = []
        var tempos: [(Int, Int)] = []
        var sigs: [(Int, Int, Int)] = []
        let twoParts = parts.count == 2

        for (pi, part) in parts.enumerated() {
            var divisions = 1, tick = 0, lastStart = 0, staves = 1
            var openTies: [String: Note] = [:] // "pitch/staff" → note being tied
            for measure in part.all("measure") {
                let mStart = tick
                for el in measure.children {
                    switch el.name {
                    case "attributes":
                        if let d = el.text("divisions").flatMap({ Int($0) }), d > 0 { divisions = d }
                        if let st = el.text("staves").flatMap({ Int($0) }) { staves = st }
                        if pi == 0, let t = el.first("time"),
                           let beats = t.text("beats").flatMap({ Int($0.split(separator: "+").first?.trimmingCharacters(in: .whitespaces) ?? "") }),
                           let type = t.text("beat-type").flatMap({ Int($0) }), beats > 0, type > 0 {
                            sigs.append((mStart, beats, type))
                        }
                    case "direction", "sound":
                        let snd = el.name == "sound" ? el : el.first("sound")
                        if pi == 0, let bpm = snd?.attrs["tempo"].flatMap({ Double($0) }), bpm > 0 {
                            tempos.append((tick, Int(60_000_000 / bpm)))
                        }
                    case "backup": tick -= (el.text("duration").flatMap { Int($0) } ?? 0) * ppq / divisions
                    case "forward": tick += (el.text("duration").flatMap { Int($0) } ?? 0) * ppq / divisions
                    case "note":
                        if el.first("grace") != nil { continue }
                        let dur = (el.text("duration").flatMap { Int($0) } ?? 0) * ppq / divisions
                        let chord = el.first("chord") != nil
                        let start = chord ? lastStart : tick
                        if !chord { lastStart = tick; tick += dur }
                        if el.first("rest") != nil || el.first("pitch") == nil { continue }
                        let octave = el.text("pitch/octave").flatMap { Int($0) } ?? 4
                        let st = step[el.text("pitch/step") ?? "C"] ?? 0
                        let alter = Int((el.text("pitch/alter").flatMap { Double($0) } ?? 0).rounded())
                        let pitch = (octave + 1) * 12 + st + alter
                        let staff = el.text("staff").flatMap { Int($0) } ?? 1
                        let hand: Hand = twoParts ? (pi == 0 ? .right : .left) : staves >= 2 ? (staff == 1 ? .right : .left) : .unknown
                        let ties = Set(el.all("tie").compactMap { $0.attrs["type"] })
                        let key = "\(pitch)/\(pi * 10 + staff)"
                        if ties.contains("stop"), let prev = openTies[key] {
                            prev.endTick = start + dur
                            if !ties.contains("start") { openTies[key] = nil }
                            continue
                        }
                        var vel = 80
                        if let dyn = el.attrs["dynamics"].flatMap({ Double($0) }) { vel = max(1, min(127, Int(dyn * 90 / 100))) }
                        let n = Note(startTick: start, endTick: start + max(dur, 1), pitch: pitch, velocity: vel, track: pi,
                                     channel: hand == .left ? 1 : 0)
                        n.hand = hand
                        if let f = el.text("notations/technical/fingering")?.first?.wholeNumberValue, (1...5).contains(f) { n.finger = f }
                        notes.append(n)
                        if ties.contains("start") { openTies[key] = n }
                    default: break
                    }
                }
            }
        }
        guard !notes.isEmpty else { throw MidiError(message: "Tệp MusicXML không có nốt nhạc nào") }
        tempos.sort { $0.0 < $1.0 }
        if tempos.first.map({ $0.0 > 0 }) ?? true { tempos.insert((0, 500_000), at: 0) }
        var seen = Set<Int>()
        sigs = sigs.sorted { $0.0 < $1.0 }.filter { seen.insert($0.0).inserted }
        if sigs.first.map({ $0.0 > 0 }) ?? true { sigs.insert((0, 4, 4), at: 0) }
        notes.sort { ($0.startTick, $0.pitch) < ($1.startTick, $1.pitch) }
        return Song(title: name, ppq: ppq, notes: notes, tempos: tempos, timeSigs: sigs)
    }

    // MARK: .mxl (zip)

    /// The score named by META-INF/container.xml (or the first .xml / .musicxml file).
    private static func unzipScore(_ data: Data) throws -> Data {
        let files = try Zip.entries([UInt8](data))
        var path: String?
        if let c = files["META-INF/container.xml"], let s = String(bytes: c, encoding: .utf8),
           let r = s.range(of: #"full-path\s*=\s*"([^"]+)""#, options: .regularExpression) {
            let m = String(s[r])
            if let q1 = m.firstIndex(of: "\""), let q2 = m[m.index(after: q1)...].firstIndex(of: "\"") {
                path = String(m[m.index(after: q1)..<q2])
            }
        }
        if path == nil || files[path!] == nil {
            path = files.keys.sorted().first { !$0.hasPrefix("META-INF") && ($0.lowercased().hasSuffix(".xml") || $0.lowercased().hasSuffix(".musicxml")) }
        }
        guard let p = path, let bytes = files[p] else { throw MidiError(message: "Tệp .mxl không có bản nhạc") }
        return Data(bytes)
    }
}

/// Minimal zip reader (stored and deflate entries) for .mxl files.
enum Zip {
    static func entries(_ b: [UInt8]) throws -> [String: [UInt8]] {
        func u16(_ i: Int) -> Int { i + 1 < b.count ? Int(b[i]) | Int(b[i + 1]) << 8 : 0 }
        func u32(_ i: Int) -> Int { u16(i) | u16(i + 2) << 16 }
        // End of central directory: scan back from the end.
        var eocd = -1
        var i = b.count - 22
        while i >= max(0, b.count - 65_557) {
            if u32(i) == 0x0605_4B50 { eocd = i; break }
            i -= 1
        }
        guard eocd >= 0 else { throw MidiError(message: "Tệp .mxl bị lỗi") }
        let count = u16(eocd + 10)
        var p = u32(eocd + 16)
        var out: [String: [UInt8]] = [:]
        for _ in 0..<count {
            guard p + 46 <= b.count, u32(p) == 0x0201_4B50 else { break }
            let method = u16(p + 10), csize = u32(p + 20), usize = u32(p + 24)
            let nlen = u16(p + 28), elen = u16(p + 30), clen = u16(p + 32), local = u32(p + 42)
            let name = String(bytes: b[(p + 46)..<min(b.count, p + 46 + nlen)], encoding: .utf8) ?? ""
            p += 46 + nlen + elen + clen
            guard local + 30 <= b.count, u32(local) == 0x0403_4B50 else { continue }
            let start = local + 30 + u16(local + 26) + u16(local + 28)
            guard start + csize <= b.count, !name.hasSuffix("/") else { continue }
            let raw = Array(b[start..<(start + csize)])
            if method == 0 {
                out[name] = raw
            } else if method == 8, let inflated = inflate(raw, size: usize) {
                out[name] = inflated
            }
        }
        return out
    }

    /// Raw DEFLATE (Apple's COMPRESSION_ZLIB is raw deflate, as used inside zip files).
    private static func inflate(_ src: [UInt8], size: Int) -> [UInt8]? {
        guard size > 0, !src.isEmpty else { return size == 0 ? [] : nil }
        var dst = [UInt8](repeating: 0, count: size)
        let n = src.withUnsafeBufferPointer { s in
            dst.withUnsafeMutableBufferPointer { d in
                compression_decode_buffer(d.baseAddress!, size, s.baseAddress!, src.count, nil, COMPRESSION_ZLIB)
            }
        }
        return n == size ? dst : nil
    }
}

/// A tiny element tree built with Foundation's XMLParser.
final class XNode {
    let name: String
    let attrs: [String: String]
    var children: [XNode] = []
    var text = ""
    init(_ name: String, _ attrs: [String: String]) { self.name = name; self.attrs = attrs }

    func all(_ tag: String) -> [XNode] { children.filter { $0.name == tag } }
    func first(_ tag: String) -> XNode? { children.first { $0.name == tag } }

    /// Text of a child path like "pitch/step", or nil.
    func text(_ path: String) -> String? {
        var cur: XNode = self
        for part in path.split(separator: "/") {
            guard let c = cur.first(String(part)) else { return nil }
            cur = c
        }
        let t = cur.text.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }
}

final class XTree: NSObject, XMLParserDelegate {
    private(set) var root: XNode?
    private var stack: [XNode] = []

    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?,
                attributes: [String: String] = [:]) {
        let local = name.split(separator: ":").last.map(String.init) ?? name // drop a namespace prefix, if any
        let n = XNode(local, attributes)
        if let parent = stack.last { parent.children.append(n) } else { root = n }
        stack.append(n)
    }

    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
        _ = stack.popLast()
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        stack.last?.text += string
    }
}

/// Opens a song file of any supported kind (MIDI, MusicXML) and assigns hands.
enum SongFiles {
    static func parse(_ data: Data, title: String, split: Int) throws -> Song {
        if MusicXml.isMusicXml(data) {
            let song = try MusicXml.parse(data, title: title)
            if song.notes.allSatisfy({ $0.hand != .unknown }) {
                song.handsFromScore = true
            } else {
                // One staff: split the hands by pitch like a one-track MIDI file.
                song.splitByPitchOnly = true
                for n in song.notes { n.hand = n.pitch < split ? .left : .right }
            }
            return song
        }
        let song = try MidiParser.parse(data: data, title: title)
        HandAssigner.assign(song, split: split)
        return song
    }
}

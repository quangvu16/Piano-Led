import Foundation

/// WS2812B strip driven by the LED Piano Strip firmware (ESP32 + FastLED, folder LedPianoStrip).
/// The app decides which LED is above each key and its colour, then sends the pixels — like
/// `leds[i] = CRGB(r, g, b); FastLED.show()` in the HowToMechatronics FastLED example.
/// Message: `A5 op len payload… checksum` (checksum = op ^ len ^ every payload byte).
/// Firmware 2 adds 0x04 CONFIG, 0x05 EXPECT, 0x06–0x08 store a song, and the ESP32 sends back
/// 0x10 NOTE (pitch, velocity from a Bluetooth MIDI piano) and 0x11 STATUS on characteristic 8f1b0003.
/// Same logic as KeyMap.h (firmware), Ws2812.kt (Android) and ws2812.py (Python).
enum Ws2812 {
    static let opPixels: UInt8 = 0x01, opBrightness: UInt8 = 0x02, opFill: UInt8 = 0x03
    static let opConfig: UInt8 = 0x04, opExpect: UInt8 = 0x05
    static let opSongBegin: UInt8 = 0x06, opSongData: UInt8 = 0x07, opSongEnd: UInt8 = 0x08
    /// Song name (UTF-8, ≤ 60 bytes) just before BEGIN: firmware 2.1 keeps a song library.
    static let opSongTitle: UInt8 = 0x09
    static let opNote: UInt8 = 0x10, opStatus: UInt8 = 0x11
    static let maxPixels = 50
    static let songPerMessage = 49, maxSongNotes = 3000

    struct Pixel { let index: Int; let rgb: UInt32 } // 0xRRGGBB

    struct Config {
        var numLeds = 177
        var ledsPerMetre = 144.0
        var lowestPitch = 21
        var numKeys = 88
        var whiteKeyMm = 23.5
        var firstLed = 0
        var reversed = false
    }

    static func isBlack(_ p: Int) -> Bool { [1, 3, 6, 8, 10].contains(((p % 12) + 12) % 12) }

    static func keyCentreMm(_ pitch: Int, lowest: Int, whiteMm: Double) -> Double {
        var whites = 0
        for p in lowest..<max(lowest, pitch) where !isBlack(p) { whites += 1 }
        return isBlack(pitch) ? Double(whites) * whiteMm : (Double(whites) + 0.5) * whiteMm
    }

    /// The LED above `pitch`, or -1 when the key is not on this piano / off the strip.
    static func led(forPitch pitch: Int, _ c: Config) -> Int {
        guard pitch >= c.lowestPitch, pitch < c.lowestPitch + c.numKeys else { return -1 }
        let mmPerLed = 1000.0 / c.ledsPerMetre
        // Nearest LED to the key's centre (LED i sits i × spacing from the left edge of the lowest key).
        var idx = c.firstLed + Int((keyCentreMm(pitch, lowest: c.lowestPitch, whiteMm: c.whiteKeyMm) / mmPerLed + 0.5).rounded(.down))
        if c.reversed { idx = c.numLeds - 1 - idx }
        return (0..<c.numLeds).contains(idx) ? idx : -1
    }

    static func ledsNeeded(keys: Int, lowest: Int, ledsPerMetre: Double, whiteMm: Double = 23.5) -> Int {
        let whites = (lowest..<(lowest + keys)).filter { !isBlack($0) }.count
        return Int(Double(whites) * whiteMm * ledsPerMetre / 1000) + 1
    }

    static func pixels(_ list: [Pixel]) -> Data {
        var payload: [UInt8] = []
        for p in list.prefix(maxPixels) {
            payload += [UInt8(p.index & 0xFF), UInt8((p.index >> 8) & 0xFF),
                        UInt8((p.rgb >> 16) & 0xFF), UInt8((p.rgb >> 8) & 0xFF), UInt8(p.rgb & 0xFF)]
        }
        return message(opPixels, payload)
    }

    static func brightness(_ v: Int) -> Data { message(opBrightness, [UInt8(max(0, min(255, v)))]) }
    static func fill(_ rgb: UInt32) -> Data { message(opFill, [UInt8((rgb >> 16) & 0xFF), UInt8((rgb >> 8) & 0xFF), UInt8(rgb & 0xFF)]) }
    static var off: Data { pixels([]) }

    /// Strip geometry + hand colours, so the ESP32 can light keys itself (practice without the phone).
    static func config(_ c: Config, right: UInt32, left: UInt32) -> Data {
        let n = UInt16(truncatingIfNeeded: c.numLeds)
        let first = UInt16(truncatingIfNeeded: c.firstLed)
        let bytes: [UInt8] = [
            UInt8(n & 0xFF), UInt8(n >> 8), UInt8(truncatingIfNeeded: Int(c.ledsPerMetre)),
            UInt8(c.lowestPitch & 0x7F), UInt8(c.numKeys & 0x7F), UInt8(first & 0xFF), UInt8(first >> 8),
            c.reversed ? 1 : 0,
            UInt8((right >> 16) & 0xFF), UInt8((right >> 8) & 0xFF), UInt8(right & 0xFF),
            UInt8((left >> 16) & 0xFF), UInt8((left >> 8) & 0xFF), UInt8(left & 0xFF),
        ]
        return message(opConfig, bytes)
    }

    /// The keys the learner should press now (the ESP32 flashes green / red at once).
    static func expect(_ pitches: Set<Int>) -> Data {
        let list = Set(pitches.map { $0 & 0x7F }).sorted().prefix(32).map { UInt8($0) }
        return message(opExpect, Array(list))
    }

    struct SongNote { let start: Double; let pitch: Int; let left: Bool }

    /// Stores a song on the ESP32 (TITLE, BEGIN, DATA…, END). `maxNotes`: what the board holds — its STATUS says
    /// (3000 on an ESP32-WROOM-32, 20000 on an ESP32-S3-DevKitC N16R8 with PSRAM).
    static func songMessages(_ notes: [SongNote], title: String? = nil, maxNotes: Int = maxSongNotes) -> [Data] {
        let ev = Array(notes.prefix(max(1, min(65535, maxNotes))))
        let n = ev.count
        var out: [Data] = []
        if let title, !title.trimmingCharacters(in: .whitespaces).isEmpty {
            var name = Array(title.utf8)
            if name.count > 60 {
                var cut = 60
                while cut > 0 && name[cut] & 0xC0 == 0x80 { cut -= 1 } // whole UTF-8 characters only
                name = Array(name[0..<cut])
            }
            out.append(message(opSongTitle, name))
        }
        out.append(message(opSongBegin, [UInt8(n & 0xFF), UInt8(n >> 8)]))
        var i = 0
        while i < n {
            var body: [UInt8] = [UInt8(i & 0xFF), UInt8(i >> 8)]
            for e in ev[i..<min(n, i + songPerMessage)] {
                let ms = Int((e.start * 1000).rounded()) & 0xFFFFFF
                body += [UInt8(ms & 0xFF), UInt8((ms >> 8) & 0xFF), UInt8(ms >> 16), UInt8(e.pitch & 0x7F), e.left ? 1 : 0]
            }
            out.append(message(opSongData, body))
            i += songPerMessage
        }
        out.append(message(opSongEnd, [UInt8(n & 0xFF), UInt8(n >> 8)]))
        return out
    }

    /// A colour at a fraction of its brightness (look-ahead glow, loudness).
    static func scale(_ rgb: UInt32, _ f: Double) -> UInt32 {
        let k = max(0, min(1, f))
        let r = UInt32(Double((rgb >> 16) & 0xFF) * k), g = UInt32(Double((rgb >> 8) & 0xFF) * k), b = UInt32(Double(rgb & 0xFF) * k)
        return (r << 16) | (g << 8) | b
    }

    /// Reassembles "A5 op len payload chk" messages coming back from the ESP32.
    struct Parser {
        private(set) var op: UInt8 = 0
        private(set) var data: [UInt8] = []
        private var st = 0, len = 0, chk: UInt8 = 0
        private var buf: [UInt8] = []

        /// Feed one byte; true when a whole valid message has arrived (`op`, `data`).
        mutating func feed(_ b: UInt8) -> Bool {
            switch st {
            case 0: if b == 0xA5 { st = 1 }
            case 1: op = b; chk = b; st = 2
            case 2: len = Int(b); chk ^= b; buf = []; st = len == 0 ? 4 : 3
            case 3:
                buf.append(b); chk ^= b
                if buf.count == len { st = 4 }
            default:
                st = 0
                if b == chk { data = buf; return true }
            }
            return false
        }
    }

    private static func message(_ op: UInt8, _ payload: [UInt8]) -> Data {
        var chk = op ^ UInt8(payload.count)
        for b in payload { chk ^= b }
        return Data([0xA5, op, UInt8(payload.count)] + payload + [chk])
    }

    /// The HowToMechatronics FastLED example: these LEDs and colours, lit one by one 500 ms apart.
    static let tutorial: [Pixel] = [
        Pixel(index: 0, rgb: 0xFF0000), Pixel(index: 1, rgb: 0x00FF00), Pixel(index: 2, rgb: 0x0000FF),
        Pixel(index: 5, rgb: 0x9600FF), Pixel(index: 9, rgb: 0xFFC814), Pixel(index: 14, rgb: 0x553CB4),
        Pixel(index: 19, rgb: 0x32FF14),
    ]

    /// Black keys get a dimmer version of the hand colour so they stand out.
    static func blackKeyColor(_ rgb: UInt32) -> UInt32 {
        let r = ((rgb >> 16) & 0xFF) * 6 / 10, g = ((rgb >> 8) & 0xFF) * 6 / 10, b = (rgb & 0xFF) * 6 / 10
        return (r << 16) | (g << 8) | b
    }

    /// "#RRGGBB" → 0xRRGGBB.
    static func rgb(hex: String) -> UInt32 {
        UInt32(hex.trimmingCharacters(in: CharacterSet(charactersIn: "#")), radix: 16) ?? 0xFFFFFF
    }
}

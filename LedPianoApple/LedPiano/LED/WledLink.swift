import Foundation
import Network

/// Pixel output with standard protocols, chosen by the address prefix:
/// "wled:IP" — WLED controllers (below); "artnet:IP" or "artnet:IP/universe" — Art-Net nodes such as
/// the BanlanX SP801E (Art-Net mode): ArtDmx on UDP 6454, 170 RGB LEDs per universe;
/// "e131:IP[/universe]" — E1.31 / sACN on UDP 5568 (universe 1 = first 170 LEDs); "ddp:IP" — DDP on
/// UDP 4048 (480 LEDs per packet). Also Adalight bytes for an Arduino on a serial port (Mac).
///
/// The phone drives a WS2812B strip over Wi-Fi through a ready-made WLED controller (e.g. Gledopto
/// "WLED" boxes; nothing to program). WLED realtime UDP (kno.wled.ge/interfaces/udp-realtime):
/// port 21324, DNRGB packets `04, timeout, startHigh, startLow, r, g, b, …` (≤ 489 LEDs per packet).
/// Accepts the same `Ws2812` messages as the ESP32 strip, keeps the whole strip picture here and sends
/// all of it, re-sent every second; if the app goes away WLED returns to its own mode after 3 s.
/// Same as WledTransport.kt (Android) and wled.py (Python).
final class WledLink {
    enum Kind { case wled, artnet, e131, ddp, adalight }
    static let prefix = "wled:"
    static let defaultPort: UInt16 = 21324
    static let artNetPrefix = "artnet:"
    static let artNetPort: UInt16 = 6454
    static let e131Prefix = "e131:", ddpPrefix = "ddp:", adaPrefix = "ada:"
    static let e131Port: UInt16 = 5568, ddpPort: UInt16 = 4048
    let kind: Kind
    private var artNet: Bool { kind == .artnet }
    private var adalightWrite: ((Data) -> Void)?
    private var universe = 0
    private var sequence: UInt8 = 0
    private static let perPacket = 489, timeoutS: UInt8 = 3

    private let conn: NWConnection?
    private let q = DispatchQueue(label: "wled")
    private let numLeds: () -> Int
    private var frame: [UInt8] = []
    private var brightness = 255
    private var timer: DispatchSourceTimer?
    // A5 message parser state (same checks as the ESP32 firmware)
    private var st = 0, op: UInt8 = 0, len = 0, got = 0, chk: UInt8 = 0
    private var data = [UInt8](repeating: 0, count: 255)

    /// `onState(true)` when ready, `onState(false)` when it failed or closed. Called on the main queue.
    init?(address: String, numLeds: @escaping () -> Int, onState: @escaping (Bool, String?) -> Void) {
        kind = address.hasPrefix(Self.artNetPrefix) ? .artnet : address.hasPrefix(Self.e131Prefix) ? .e131
            : address.hasPrefix(Self.ddpPrefix) ? .ddp : .wled
        var hostPort = address
        for p in [Self.prefix, Self.artNetPrefix, Self.e131Prefix, Self.ddpPrefix] where hostPort.hasPrefix(p) {
            hostPort = String(hostPort.dropFirst(p.count))
        }
        hostPort = hostPort.trimmingCharacters(in: .whitespaces)
        universe = kind == .e131 ? 1 : 0
        if kind == .artnet || kind == .e131, let slash = hostPort.firstIndex(of: "/") {
            let low = universe
            universe = max(low, min(63999, Int(hostPort[hostPort.index(after: slash)...]) ?? low))
            hostPort = String(hostPort[..<slash])
        }
        var port: UInt16 = kind == .artnet ? Self.artNetPort : kind == .e131 ? Self.e131Port : kind == .ddp ? Self.ddpPort : Self.defaultPort
        let parts = hostPort.split(separator: ":")
        if parts.count == 2, let p = UInt16(parts[1]) { hostPort = String(parts[0]); port = p }
        guard !hostPort.isEmpty, let nwPort = NWEndpoint.Port(rawValue: port) else { return nil }
        self.numLeds = numLeds
        let c = NWConnection(host: NWEndpoint.Host(hostPort), port: nwPort, using: .udp)
        conn = c
        c.stateUpdateHandler = { [weak self] s in
            switch s {
            case .ready:
                DispatchQueue.main.async { onState(true, nil) }
                self?.startKeepAlive()
            case .failed(let e):
                DispatchQueue.main.async { onState(false, "WLED: \(e.localizedDescription)") }
            case .cancelled:
                DispatchQueue.main.async { onState(false, nil) }
            default: break
            }
        }
        c.start(queue: q)
    }

    /// Adalight for an Arduino Uno/Nano (LedPianoAdalight.ino) on a serial port: `write` sends the bytes.
    init(adalight write: @escaping (Data) -> Void, numLeds: @escaping () -> Int) {
        kind = .adalight
        adalightWrite = write
        conn = nil
        self.numLeds = numLeds
        q.async { self.startKeepAlive() }
    }

    func close() {
        q.async {
            self.timer?.cancel()
            // Dark, with a 1 s timeout so WLED goes back to its own mode right away.
            for i in self.frame.indices { self.frame[i] = 0 }
            self.flush(timeout: 1)
            self.q.asyncAfter(deadline: .now() + 0.2) { self.conn?.cancel() }
        }
    }

    func send(_ bytes: Data) {
        q.async {
            var changed = false
            for b in bytes where self.feed(b) { self.apply(); changed = true }
            if changed { self.flush(timeout: Self.timeoutS) }
        }
    }

    private func startKeepAlive() {
        let t = DispatchSource.makeTimerSource(queue: q)
        t.schedule(deadline: .now() + 1, repeating: 1)
        t.setEventHandler { [weak self] in self?.flush(timeout: Self.timeoutS) }
        t.resume()
        timer = t
    }

    private func feed(_ b: UInt8) -> Bool {
        switch st {
        case 0: if b == 0xA5 { st = 1 }
        case 1: op = b; chk = b; st = 2
        case 2: len = Int(b); chk ^= b; got = 0; st = len == 0 ? 4 : 3
        case 3: data[got] = b; got += 1; chk ^= b; if got == len { st = 4 }
        default: st = 0; return b == chk
        }
        return false
    }

    private func apply() {
        let n = max(1, min(1500, numLeds()))
        if frame.count != n * 3 { frame = [UInt8](repeating: 0, count: n * 3) }
        switch op {
        case Ws2812.opPixels:
            for i in frame.indices { frame[i] = 0 }
            var i = 0
            while i + 4 < len {
                let idx = Int(data[i]) | Int(data[i + 1]) << 8
                if idx < n { frame[idx * 3] = data[i + 2]; frame[idx * 3 + 1] = data[i + 3]; frame[idx * 3 + 2] = data[i + 4] }
                i += 5
            }
        case Ws2812.opFill:
            for k in 0..<n { frame[k * 3] = data[0]; frame[k * 3 + 1] = data[1]; frame[k * 3 + 2] = data[2] }
        case Ws2812.opBrightness:
            brightness = Int(data[0])
        default: break
        }
    }

    /// Whole picture (on `q`).
    private func flush(timeout: UInt8) {
        if frame.isEmpty { frame = [UInt8](repeating: 0, count: max(1, min(1500, numLeds())) * 3) }
        let leds = frame.count / 3
        guard leds > 0 else { return }
        sequence = sequence >= 255 ? 1 : sequence + 1
        if kind == .adalight {
            adalightWrite?(Self.adalightPacket(frame, brightness: brightness))
            return
        }
        guard let conn else { return }
        if kind != .wled {
            let pkts: [Data]
            switch kind {
            case .artnet: pkts = Self.artDmxPackets(frame, brightness: brightness, startUniverse: universe, sequence: sequence)
            case .e131: pkts = Self.e131Packets(frame, brightness: brightness, startUniverse: universe, sequence: sequence)
            default: pkts = Self.ddpPackets(frame, brightness: brightness, sequence: sequence)
            }
            for pkt in pkts { conn.send(content: pkt, completion: .contentProcessed { _ in }) }
            return
        }
        var start = 0
        repeat {
            let count = min(Self.perPacket, leds - start)
            var pkt: [UInt8] = [4, timeout, UInt8((start >> 8) & 0xFF), UInt8(start & 0xFF)]
            pkt.reserveCapacity(4 + count * 3)
            for k in 0..<(count * 3) { pkt.append(UInt8(Int(frame[start * 3 + k]) * brightness / 255)) }
            conn.send(content: Data(pkt), completion: .contentProcessed { _ in })
            start += count
        } while start < leds
    }

    /// Fixed 16-byte E1.31 sender id (same as the Python and Android apps).
    private static let e131Cid: [UInt8] = [0x4c, 0x45, 0x44, 0x50, 0x69, 0x61, 0xe0, 0x0a, 0x8c, 0x5e, 0x5a, 0x1e, 0x0d, 0x2c, 0x77, 0x01]

    /// E1.31 (sACN) data packets: root, framing and DMP layers; 170 RGB LEDs (510 slots) per universe.
    static func e131Packets(_ frame: [UInt8], brightness: Int, startUniverse: Int, sequence: UInt8) -> [Data] {
        let leds = frame.count / 3
        var out: [Data] = []
        var start = 0, u = max(1, startUniverse)
        repeat {
            let count = max(0, min(170, leds - start))
            let slots = count * 3
            let n = 126 + slots
            var p = [UInt8](repeating: 0, count: n)
            func put16(_ i: Int, _ v: Int) { p[i] = UInt8((v >> 8) & 0xFF); p[i + 1] = UInt8(v & 0xFF) }
            put16(0, 0x0010)
            for (k, c) in Array("ASC-E1.17".utf8).enumerated() { p[4 + k] = c }
            put16(16, 0x7000 | (n - 16)); put16(20, 4)
            for k in 0..<16 { p[22 + k] = e131Cid[k] }
            put16(38, 0x7000 | (n - 38)); put16(42, 2)
            for (k, c) in Array("LED Piano".utf8).enumerated() { p[44 + k] = c }
            p[108] = 100; p[111] = sequence; put16(113, u)
            put16(115, 0x7000 | (n - 115)); p[117] = 0x02; p[118] = 0xA1; put16(121, 1)
            put16(123, 1 + slots)
            for k in 0..<slots { p[126 + k] = UInt8(Int(frame[start * 3 + k]) * brightness / 255) }
            out.append(Data(p))
            start += max(count, 1)
            u += 1
        } while start < leds
        return out
    }

    /// DDP packets: flags (V1, PUSH on the last), sequence 1…15, type RGB24, id 1, byte offset, length.
    static func ddpPackets(_ frame: [UInt8], brightness: Int, sequence: UInt8) -> [Data] {
        let chunk = 480 * 3
        var out: [Data] = []
        var off = 0
        repeat {
            let len = min(chunk, frame.count - off)
            let last = off + chunk >= frame.count
            let seq = sequence & 0x0F == 0 ? 1 : sequence & 0x0F
            var p: [UInt8] = [last ? 0x41 : 0x40, seq, 0x0B, 0x01,
                              UInt8((off >> 24) & 0xFF), UInt8((off >> 16) & 0xFF), UInt8((off >> 8) & 0xFF), UInt8(off & 0xFF),
                              UInt8((len >> 8) & 0xFF), UInt8(len & 0xFF)]
            for k in 0..<max(0, len) { p.append(UInt8(Int(frame[off + k]) * brightness / 255)) }
            out.append(Data(p))
            off += chunk
        } while off < frame.count
        return out
    }

    /// Adalight (Arduino Uno/Nano sketches, Hyperion, Prismatik): "Ada", count−1, checksum, r g b …
    static func adalightPacket(_ frame: [UInt8], brightness: Int) -> Data {
        let leds = max(1, frame.count / 3)
        let n = leds - 1
        let hi = UInt8((n >> 8) & 0xFF), lo = UInt8(n & 0xFF)
        var p: [UInt8] = [0x41, 0x64, 0x61, hi, lo, hi ^ lo ^ 0x55]
        for k in 0..<(leds * 3) { p.append(k < frame.count ? UInt8(Int(frame[k]) * brightness / 255) : 0) }
        return Data(p)
    }

    /// ArtDmx packets for the whole strip: "Art-Net\0", OpCode 0x5000 (little-endian), protocol 14,
    /// sequence, physical 0, universe (low byte, then net), length (big-endian, even), data.
    static func artDmxPackets(_ frame: [UInt8], brightness: Int, startUniverse: Int, sequence: UInt8) -> [Data] {
        let leds = frame.count / 3
        var out: [Data] = []
        var start = 0, u = startUniverse
        while start < leds {
            let count = min(170, leds - start)
            var len = count * 3
            if len % 2 == 1 { len += 1 }
            var pkt: [UInt8] = Array("Art-Net".utf8) + [0, 0x00, 0x50, 0, 14, sequence, 0,
                                                        UInt8(u & 0xFF), UInt8((u >> 8) & 0x7F),
                                                        UInt8((len >> 8) & 0xFF), UInt8(len & 0xFF)]
            for k in 0..<(count * 3) { pkt.append(UInt8(Int(frame[start * 3 + k]) * brightness / 255)) }
            if len > count * 3 { pkt.append(0) }
            out.append(Data(pkt))
            start += count
            u += 1
        }
        return out
    }
}

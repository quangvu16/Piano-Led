import AVFoundation
import CoreMIDI
import Foundation

/// Where the learner's notes come from.
enum InputSource { case none, midi, mic }

protocol NoteInputListener: AnyObject {
    func inputNoteOn(_ pitch: Int, velocity: Int)
    func inputNoteOff(_ pitch: Int)
    func inputStateChanged()
}

/// Collects the learner's notes (USB / Bluetooth MIDI or microphone); calls listeners on the main thread.
final class InputHub: ObservableObject {
    @Published private(set) var source: InputSource = .none
    @Published private(set) var deviceName: String?
    weak var listener: NoteInputListener?
    private(set) var down: [Int: Int] = [:]

    var connected: Bool { source != .none }

    func noteOn(_ p: Int, _ v: Int) {
        DispatchQueue.main.async { self.down[p] = v; self.listener?.inputNoteOn(p, velocity: v) }
    }

    func noteOff(_ p: Int) {
        DispatchQueue.main.async {
            guard self.down.removeValue(forKey: p) != nil else { return }
            self.listener?.inputNoteOff(p)
        }
    }

    func setState(_ s: InputSource, _ name: String?) {
        DispatchQueue.main.async {
            self.source = s
            self.deviceName = name
            if s == .none { for p in self.down.keys { self.noteOff(p) } }
            self.listener?.inputStateChanged()
        }
    }
}

/// Digital pianos over USB (iPhone/iPad: USB-C or Lightning adapter; Mac: USB) and
/// Bluetooth LE MIDI (pair in the app's Bluetooth MIDI screen on iOS, or Audio MIDI Setup on Mac).
/// Every MIDI source is connected automatically, also when plugged in later.
final class MidiInput {
    private let hub: InputHub
    private var client = MIDIClientRef()
    private var port = MIDIPortRef()
    private var connectedSources: [MIDIEndpointRef] = []

    init(hub: InputHub) {
        self.hub = hub
        MIDIClientCreateWithBlock("LedPiano" as CFString, &client) { [weak self] _ in
            DispatchQueue.main.async { self?.reconnect() }
        }
        MIDIInputPortCreateWithProtocol(client, "Input" as CFString, ._1_0, &port) { [weak self] list, _ in
            self?.handle(list)
        }
        reconnect()
    }

    func reconnect() {
        for s in connectedSources { MIDIPortDisconnectSource(port, s) }
        connectedSources.removeAll()
        var names: [String] = []
        for i in 0..<MIDIGetNumberOfSources() {
            let src = MIDIGetSource(i)
            if MIDIPortConnectSource(port, src, nil) == noErr {
                connectedSources.append(src)
                var name: Unmanaged<CFString>?
                if MIDIObjectGetStringProperty(src, kMIDIPropertyDisplayName, &name) == noErr, let n = name?.takeRetainedValue() {
                    names.append(n as String)
                }
            }
        }
        if !connectedSources.isEmpty, hub.source != .mic {
            hub.setState(.midi, names.first ?? "MIDI")
        } else if connectedSources.isEmpty, hub.source == .midi, hub.deviceName != AppModel.esp32PianoName { // keep the ESP32 piano
            hub.setState(.none, nil)
        }
    }

    private func handle(_ list: UnsafePointer<MIDIEventList>) {
        for packet in list.unsafeSequence() {
            let count = min(Int(packet.pointee.wordCount), 64)
            var words: [UInt32] = []
            withUnsafeBytes(of: packet.pointee.words) { raw in
                let ws = raw.bindMemory(to: UInt32.self)
                for i in 0..<min(count, ws.count) { words.append(ws[i]) }
            }
            for w in words {
                let type = (w >> 28) & 0xF
                guard type == 0x2 else { continue } // MIDI 1.0 channel voice
                let status = (w >> 16) & 0xF0
                let note = Int((w >> 8) & 0x7F)
                let vel = Int(w & 0x7F)
                if status == 0x90 && vel > 0 { hub.noteOn(note, vel) }
                else if status == 0x80 || status == 0x90 { hub.noteOff(note) }
            }
        }
    }
}

/// Experimental microphone input for acoustic pianos: YIN pitch detection, one note at a time.
final class MicInput {
    private let hub: InputHub
    private let engine = AVAudioEngine()
    private var buf = [Float](repeating: 0, count: 2048)
    private var current = -1, candidate = -1, count = 0, quiet = 0
    private(set) var running = false

    init(hub: InputHub) { self.hub = hub }

    func start(_ done: @escaping (Bool) -> Void) {
        let begin = { [weak self] (granted: Bool) in
            DispatchQueue.main.async {
                guard let self, granted else { done(false); return }
                done(self.startEngine())
            }
        }
        #if os(iOS)
        AVAudioApplication.requestRecordPermission(completionHandler: begin)
        #else
        AVCaptureDevice.requestAccess(for: .audio, completionHandler: begin)
        #endif
    }

    private func startEngine() -> Bool {
        guard !running else { return true }
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setCategory(.playAndRecord, mode: .default,
                                                         options: [.defaultToSpeaker, .allowBluetoothA2DP, .mixWithOthers])
        try? AVAudioSession.sharedInstance().setActive(true)
        #endif
        let input = engine.inputNode
        let fmt = input.outputFormat(forBus: 0)
        guard fmt.sampleRate > 0 else { return false }
        let rate = Float(fmt.sampleRate)
        input.installTap(onBus: 0, bufferSize: 1024, format: fmt) { [weak self] b, _ in
            guard let self, let ch = b.floatChannelData?[0] else { return }
            let n = Int(b.frameLength)
            // Downsample by 2 when at 44.1/48 kHz to keep YIN cheap.
            let step = rate > 30000 ? 2 : 1
            var fresh: [Float] = []
            fresh.reserveCapacity(n / step)
            var i = 0
            while i < n { fresh.append(ch[i]); i += step }
            self.process(fresh, rate: rate / Float(step))
        }
        do { try engine.start() } catch { input.removeTap(onBus: 0); return false }
        running = true
        hub.setState(.mic, "Micro")
        return true
    }

    func stop() {
        guard running else { return }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        running = false
        if current >= 0 { hub.noteOff(current) }
        current = -1
        if hub.source == .mic { hub.setState(.none, nil) }
    }

    private func process(_ x: [Float], rate: Float) {
        buf = Array(buf.dropFirst(min(x.count, buf.count))) + x.suffix(buf.count)
        if buf.count > 2048 { buf = Array(buf.suffix(2048)) }
        var e: Float = 0
        for v in buf { e += v * v }
        let rms = sqrt(e / Float(buf.count))
        let p = rms > 0.012 ? Yin.midi(buf, rate: rate) : -1
        if p < 0 {
            quiet += 1
            if quiet >= 3 && current >= 0 { hub.noteOff(current); current = -1 }
            candidate = -1; count = 0
            return
        }
        quiet = 0
        if p == candidate { count += 1 } else { candidate = p; count = 1 }
        if count >= 2 && p != current {
            if current >= 0 { hub.noteOff(current) }
            current = p
            hub.noteOn(p, min(127, max(20, Int(rms * 900))))
        }
    }
}

/// YIN pitch detection (de Cheveigné & Kawahara 2002).
enum Yin {
    static func midi(_ x: [Float], rate: Float, threshold: Float = 0.15) -> Int {
        let half = x.count / 2
        let minTau = Int(rate / 2100), maxTau = min(half - 1, Int(rate / 27))
        guard maxTau > minTau else { return -1 }
        var d = [Float](repeating: 0, count: maxTau + 1)
        for tau in 1...maxTau {
            var s: Float = 0
            for i in 0..<half { let k = x[i] - x[i + tau]; s += k * k }
            d[tau] = s
        }
        d[0] = 1
        var run: Float = 0
        for tau in 1...maxTau { run += d[tau]; d[tau] = run > 0 ? d[tau] * Float(tau) / run : 1 }
        var tau = minTau
        while tau <= maxTau {
            if d[tau] < threshold {
                while tau + 1 <= maxTau && d[tau + 1] < d[tau] { tau += 1 }
                break
            }
            tau += 1
        }
        guard tau <= maxTau else { return -1 }
        var better = Float(tau)
        if tau >= 2 && tau < maxTau {
            let den = 2 * (2 * d[tau] - d[tau + 1] - d[tau - 1])
            if den != 0 { better += (d[tau + 1] - d[tau - 1]) / den }
        }
        let m = Int((69 + 12 * log2(Double(rate / better) / 440)).rounded())
        return (21...108).contains(m) ? m : -1
    }
}

import AVFoundation
import Foundation

/// Built-in piano sound: two detuned Karplus-Strong strings per note with a
/// velocity-dependent hammer, rendered in an AVAudioSourceNode. Also metronome clicks.
final class Synth: @unchecked Sendable {
    private let engine = AVAudioEngine()
    private var source: AVAudioSourceNode!
    private let lock = NSLock()
    private var voices: [Int: Voice] = [:]
    private var sampled: [SampledVoice] = []
    private let bank = SoundBank.shared
    /// Instrument (General MIDI program) for new notes; see SoundBank.
    var program = 0 { didSet { let p = program; DispatchQueue.global().async { [bank = self.bank] in bank?.preload(p) } } }
    private var clicks: [Click] = []
    private var board: Float = 0
    private(set) var running = false
    var volume: Float = 1
    let rate: Double

    init() {
        rate = engine.outputNode.outputFormat(forBus: 0).sampleRate > 0 ? engine.outputNode.outputFormat(forBus: 0).sampleRate : 44100
        let format = AVAudioFormat(standardFormatWithSampleRate: rate, channels: 1)!
        source = AVAudioSourceNode(format: format) { [unowned self] _, _, frameCount, abl -> OSStatus in
            let buffers = UnsafeMutableAudioBufferListPointer(abl)
            guard let ptr = buffers[0].mData?.assumingMemoryBound(to: Float.self) else { return noErr }
            self.render(ptr, Int(frameCount))
            for i in 1..<buffers.count {
                if let p = buffers[i].mData { memcpy(p, ptr, Int(frameCount) * 4) }
            }
            return noErr
        }
        engine.attach(source)
        engine.connect(source, to: engine.mainMixerNode, format: format)
    }

    /// Audio setup runs on its own queue: AVAudioSession calls can block, so they must
    /// not run on the main thread (Xcode "AVAudioSession Hang Risk").
    private let audioQueue = DispatchQueue(label: "LedPiano.audio", qos: .userInitiated)

    func start() {
        guard !running else { return }
        running = true
        audioQueue.async { [self] in
            #if os(iOS)
            // .playback = loudspeaker, plays even with the silent switch on. The mic (optional)
            // switches the session to .playAndRecord itself (see MicInput).
            let session = AVAudioSession.sharedInstance()
            if session.category != .playAndRecord {
                try? session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            }
            try? session.setPreferredIOBufferDuration(0.01)
            try? session.setActive(true)
            #endif
            engine.prepare()
            do { try engine.start() } catch {
                running = false
                print("LedPiano audio: engine failed to start: \(error)")
            }
        }
        #if os(iOS)
        // Restart after a phone call, Siri or a route change stops the engine.
        if observers.isEmpty {
            let nc = NotificationCenter.default
            observers.append(nc.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: nil) { [weak self] _ in
                self?.restartIfNeeded()
            })
            observers.append(nc.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: nil) { [weak self] _ in
                self?.restartIfNeeded()
            })
        }
        #endif
    }

    private var observers: [NSObjectProtocol] = []

    private func restartIfNeeded() {
        audioQueue.async { [self] in
            guard running, !engine.isRunning else { return }
            #if os(iOS)
            try? AVAudioSession.sharedInstance().setActive(true)
            #endif
            try? engine.start()
        }
    }

    func stop() {
        running = false
        audioQueue.async { [self] in engine.stop() }
        lock.lock(); voices.removeAll(); sampled.removeAll(); clicks.removeAll(); lock.unlock()
    }

    func noteOn(_ pitch: Int, velocity: Int) {
        if let bank {
            let v = SampledVoice(bank: bank, program: program, pitch: pitch, velocity: velocity, outRate: rate)
            lock.lock()
            for old in sampled where old.pitch == pitch { old.release() }
            sampled.append(v)
            if sampled.count > 48 { sampled.removeFirst() }
            lock.unlock()
            return
        }
        let v = Voice(pitch: pitch, velocity: velocity, rate: rate)
        lock.lock(); voices[pitch] = v; lock.unlock()
    }

    func noteOff(_ pitch: Int) {
        lock.lock(); voices[pitch]?.held = false; for v in sampled where v.pitch == pitch { v.release() }; lock.unlock()
    }

    func allNotesOff() {
        lock.lock(); for v in voices.values { v.held = false }; for v in sampled { v.release() }; lock.unlock()
    }

    func click(accent: Bool) {
        lock.lock(); clicks.append(Click(freq: accent ? 1760 : 1175, amp: accent ? 1 : 0.7, rate: rate)); lock.unlock()
    }

    private func render(_ out: UnsafeMutablePointer<Float>, _ n: Int) {
        lock.lock()
        defer { lock.unlock() }
        voices = voices.filter { !$0.value.finished }
        let count = voices.count
        let poly: Float = count > 1 ? 1 / (1 + 0.22 * Float(count - 1)) : 1
        let vs = Array(voices.values)
        sampled.removeAll { $0.finished }
        let rec = sampled
        for i in 0..<n {
            var s: Float = 0
            for v in vs { s += v.next() }
            s *= volume * poly
            board += (s - board) * 0.55
            s = board
            if !rec.isEmpty {
                var r: Float = 0
                for v in rec { r += v.next() }
                s += r * 0.7 * volume
            }
            for c in clicks { s += c.next() * 0.6 }
            out[i] = tanh(s * 1.2) * 0.82
        }
        clicks.removeAll { $0.finished }
    }

    private final class Click {
        let step: Float, amp: Float
        var phase: Float = 0, env: Float = 1, finished = false
        init(freq: Float, amp: Float, rate: Double) { step = Float(2 * Double.pi * Double(freq) / rate); self.amp = amp }
        func next() -> Float {
            if finished { return 0 }
            let v = sin(phase) * env * amp * 0.5
            phase += step; env *= 0.9985
            if env < 0.01 { finished = true }
            return v
        }
    }

    private final class Voice {
        var buf1: [Float], buf2: [Float]
        var i1 = 0, i2 = 0
        var p1: Float = 0, p2: Float = 0
        let damping: Float
        var gain: Float = 0
        let target: Float
        var held = true
        var finished = false
        var silent = 0

        init(pitch: Int, velocity: Int, rate: Double) {
            let f = 440 * pow(2, Double(pitch - 69) / 12)
            let v = Float(min(max(velocity, 1), 127)) / 127
            target = v * v * 0.9 + 0.1 * v
            let hammer = 0.55 - 0.4 * v
            func excite(_ len: Int) -> [Float] {
                var out = [Float](repeating: 0, count: len)
                var last: Float = 0
                for i in 0..<len {
                    let w = Float.random(in: -1...1)
                    last = last * hammer + w * (1 - hammer)
                    out[i] = last
                }
                return out
            }
            buf1 = excite(max(2, Int(rate / f)))
            buf2 = excite(max(2, Int(rate / (f * 1.0015))))
            let ring = 1.2 + 6.5 * (1 - min(max(Double(pitch - 21) / 87, 0), 1))
            damping = Float(exp(-1 / (f * ring)))
        }

        func next() -> Float {
            if finished { return 0 }
            let goal: Float = held ? target : 0
            gain += (goal - gain) * (held ? 0.25 : 0.0028)
            if !held && gain < 0.0005 { finished = true; return 0 }
            let c1 = buf1[i1]
            buf1[i1] = (c1 + p1) * 0.5 * damping
            p1 = c1; i1 += 1; if i1 >= buf1.count { i1 = 0 }
            let c2 = buf2[i2]
            buf2[i2] = (c2 + p2) * 0.5 * damping
            p2 = c2; i2 += 1; if i2 >= buf2.count { i2 = 0 }
            let out = (c1 * 0.6 + c2 * 0.4) * gain
            if abs(out) < 1e-4 { silent += 1; if silent > 4096 { finished = true } } else { silent = 0 }
            return out
        }
    }
}

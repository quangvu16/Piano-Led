import Foundation

/// Recorded instruments — 8 pianos, 8 organs, 8 bells/mallets — from the bundled
/// sound pack (soundbank.json + soundbank.bin, made from the free GeneralUser GS
/// SoundFont by tools/make_soundbank.py). Same format and rules as Android/Python.
final class SoundBank: @unchecked Sendable {
    struct Zone {
        let lo: Int, hi: Int, sample: Int, root: Int, tune: Int
        let attenDb: Float, attack: Float, hold: Float, decay: Float, keyDecay: Int, sustainDb: Float, release: Float
        let loop: Bool
    }
    struct Instrument: Identifiable { let program: Int; let group: String; let name: String; let zones: [Zone]; var id: Int { program } }
    struct Sample { let offset: Int, length: Int, bytes: Int, rate: Int, loopStart: Int, loopEnd: Int }

    static let groups = [("piano", "Đàn piano"), ("organ", "Đàn organ"), ("bells", "Chuông & gõ")]
    static let shared: SoundBank? = SoundBank()

    let instruments: [Instrument]
    let samples: [Sample]
    let credit: String
    private let blob: [UInt8]
    private var cache: [Int: [Float]] = [:]
    private let lock = NSLock()

    private init?() {
        guard let ju = Lessons.resource("soundbank.json"), let bu = Lessons.resource("soundbank.bin"),
              let jd = try? Data(contentsOf: ju), let bd = try? Data(contentsOf: bu),
              let doc = try? JSONSerialization.jsonObject(with: jd) as? [String: Any],
              let sa = doc["samples"] as? [[String: Any]], let ia = doc["instruments"] as? [[String: Any]] else { return nil }
        blob = [UInt8](bd)
        credit = doc["source"] as? String ?? ""
        samples = sa.map { s in
            let loop = s["loop"] as? [Int]
            return Sample(offset: s["offset"] as? Int ?? 0, length: s["length"] as? Int ?? 0, bytes: s["bytes"] as? Int ?? 0,
                          rate: s["rate"] as? Int ?? 44100, loopStart: loop?.first ?? -1, loopEnd: loop?.last ?? -1)
        }
        func f(_ z: [String: Any], _ k: String) -> Float { Float((z[k] as? NSNumber)?.doubleValue ?? 0) }
        instruments = ia.map { o in
            let zones = (o["zones"] as? [[String: Any]] ?? []).map { z in
                Zone(lo: z["lo"] as? Int ?? 0, hi: z["hi"] as? Int ?? 127, sample: z["sample"] as? Int ?? 0,
                     root: z["root"] as? Int ?? 60, tune: z["tune"] as? Int ?? 0, attenDb: f(z, "atten"),
                     attack: f(z, "attack"), hold: f(z, "hold"), decay: f(z, "decay"), keyDecay: z["key_decay"] as? Int ?? 0,
                     sustainDb: f(z, "sustain"), release: f(z, "release"), loop: z["loop"] as? Bool ?? false)
            }
            return Instrument(program: o["program"] as? Int ?? 0, group: o["group"] as? String ?? "",
                              name: o["name"] as? String ?? "", zones: zones)
        }
        guard !instruments.isEmpty else { return nil }
    }

    func instrument(_ program: Int) -> Instrument { instruments.first { $0.program == program } ?? instruments[0] }

    func name(_ program: Int) -> String { instrument(program).name }

    func zone(_ program: Int, key: Int) -> Zone {
        let zs = instrument(program).zones
        return zs.first { $0.lo <= key && key <= $0.hi }
            ?? zs.min { min(abs(key - $0.lo), abs(key - $0.hi)) < min(abs(key - $1.lo), abs(key - $1.hi)) }!
    }

    func pcm(_ i: Int) -> [Float] {
        lock.lock(); defer { lock.unlock() }
        if let c = cache[i] { return c }
        let s = samples[i]
        let out = SoundBank.imaDecode(blob, offset: s.offset, count: s.length)
        cache[i] = out
        return out
    }

    func preload(_ program: Int) { for z in instrument(program).zones { _ = pcm(z.sample) } }

    private static let steps: [Int] = [7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73,
        80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494,
        544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499,
        2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487,
        12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767]
    private static let indexTable = [-1, -1, -1, -1, 2, 4, 6, 8]

    /// IMA ADPCM, low nibble first, predictor and index start at 0.
    static func imaDecode(_ data: [UInt8], offset: Int, count: Int) -> [Float] {
        var out = [Float](repeating: 0, count: count)
        var pred = 0, index = 0
        for i in 0..<count {
            let b = Int(data[offset + (i >> 1)])
            let c = i & 1 == 0 ? b & 0x0F : b >> 4
            let step = steps[index]
            var delta = step >> 3
            if c & 4 != 0 { delta += step }
            if c & 2 != 0 { delta += step >> 1 }
            if c & 1 != 0 { delta += step >> 2 }
            pred = c & 8 != 0 ? pred - delta : pred + delta
            pred = min(32767, max(-32768, pred))
            index = min(88, max(0, index + indexTable[c & 7]))
            out[i] = Float(pred) / 32768
        }
        return out
    }
}

/// One playing note of a recorded instrument (owned by the render thread).
final class SampledVoice {
    let pitch: Int
    private let zone: SoundBank.Zone
    private let pcm: [Float]
    private let step: Double
    private let loopStart: Double, loopEnd: Double
    private let looped: Bool
    private let gain: Float
    private let decay: Float
    private let dt: Float
    private var pos = 0.0, t: Float = 0, relT: Float = 0, relDb: Float = 0, env: Float = 0
    private var released = false, counter = 0
    private(set) var finished = false

    init(bank: SoundBank, program: Int, pitch: Int, velocity: Int, outRate: Double) {
        self.pitch = pitch
        zone = bank.zone(program, key: pitch)
        let s = bank.samples[zone.sample]
        pcm = bank.pcm(zone.sample)
        step = pow(2, Double((pitch - zone.root) * 100 + zone.tune) / 1200) * Double(s.rate) / outRate
        looped = zone.loop && s.loopEnd > s.loopStart
        loopStart = Double(s.loopStart); loopEnd = Double(s.loopEnd)
        let v = Float(min(max(velocity, 1), 127)) / 127
        gain = pow(10, -zone.attenDb / 20) * v * v * 1.6
        decay = max(1e-3, zone.decay * pow(2, Float(zone.keyDecay * (60 - pitch)) / 1200))
        dt = Float(1 / outRate)
    }

    func release() {
        guard !released else { return }
        released = true
        relDb = envDb(t)
        relT = 0
    }

    private func envDb(_ time: Float) -> Float {
        let a = zone.attack, h = zone.hold
        if time < a { return 20 * log10(max(time / max(a, 1e-4), 1e-5)) }
        if time < a + h { return 0 }
        return -min(1, (time - a - h) / decay) * zone.sustainDb
    }

    func next() -> Float {
        if finished { return 0 }
        if counter == 0 {
            var db = envDb(t)
            if released {
                db = min(db, relDb - 100 * relT / max(0.02, zone.release))
                if db < -90 { finished = true }
            } else if db < -90 && t > 0.05 { finished = true }
            env = pow(10, db / 20)
        }
        counter = (counter + 1) & 31
        t += dt
        if released { relT += dt }
        if looped && pos >= loopEnd { pos -= loopEnd - loopStart }
        let i0 = Int(pos)
        if i0 >= pcm.count - 1 { finished = true; return 0 }
        let f = Float(pos - Double(i0))
        let y = pcm[i0] * (1 - f) + pcm[i0 + 1] * f
        pos += step
        return y * gain * env
    }
}

import Foundation

/// All user options, saved in UserDefaults.
final class Settings: ObservableObject {
    private let d = UserDefaults.standard

    @Published var labelMode: String { didSet { d.set(labelMode, forKey: "label_mode") } }
    @Published var practiceMode: String { didSet { d.set(practiceMode, forKey: "practice_mode") } }
    @Published var lightsMode: String { didSet { d.set(lightsMode, forKey: "lights_mode") } }
    @Published var countIn: Bool { didSet { d.set(countIn, forKey: "count_in") } }
    @Published var metronome: Bool { didSet { d.set(metronome, forKey: "metronome") } }
    @Published var autoSpeed: Bool { didSet { d.set(autoSpeed, forKey: "auto_speed") } }
    @Published var showStaff: Bool { didSet { d.set(showStaff, forKey: "show_staff") } }
    @Published var showFingers: Bool { didSet { d.set(showFingers, forKey: "show_fingers") } }
    @Published var handSplit: Int { didSet { d.set(handSplit, forKey: "hand_split") } }
    @Published var micInput: Bool { didSet { d.set(micInput, forKey: "mic_input") } }
    @Published var dailyGoal: Int { didSet { d.set(dailyGoal, forKey: "daily_goal") } }
    @Published var speed: Int { didSet { d.set(speed, forKey: "speed") } }
    @Published var volume: Int { didSet { d.set(volume, forKey: "volume") } }
    /// Instrument sound (General MIDI program): 0 = grand piano. See SoundBank.
    @Published var instrument: Int { didSet { d.set(instrument, forKey: "instrument") } }
    @Published var colorLeft: String { didSet { d.set(colorLeft, forKey: "color_left") } }
    @Published var colorRight: String { didSet { d.set(colorRight, forKey: "color_right") } }
    @Published var ledTransport: String { didSet { d.set(ledTransport, forKey: "led_transport") } }
    @Published var ledEffect: String { didSet { d.set(ledEffect, forKey: "led_effect") } }
    @Published var ledBrightness: Int { didSet { d.set(ledBrightness, forKey: "led_brightness") } }
    @Published var ledLength: Int { didSet { d.set(ledLength, forKey: "led_length") } }
    @Published var organOffset: Int { didSet { d.set(organOffset, forKey: "organ_offset") } }
    // WS2812B strip (ESP32 + FastLED, "LED Piano Strip" firmware)
    @Published var wsDensity: Int { didSet { d.set(wsDensity, forKey: "ws_density") } }      // LEDs per metre
    @Published var wsKeys: Int { didSet { d.set(wsKeys, forKey: "ws_keys") } }              // 88, 76, 61, 49
    @Published var wsNumLedsSet: Int { didSet { d.set(wsNumLedsSet, forKey: "ws_num_leds") } } // 0 = automatic
    @Published var wsFirstLed: Int { didSet { d.set(wsFirstLed, forKey: "ws_first_led") } }
    @Published var wsReversed: Bool { didSet { d.set(wsReversed, forKey: "ws_reversed") } }
    @Published var autoReconnect: Bool { didSet { d.set(autoReconnect, forKey: "auto_reconnect") } }
    // LED learning aids (Practice options)
    /// The next key of each hand glows dimly before it is due (off by default: one LED per key).
    @Published var ledPreview: Bool { didSet { d.set(ledPreview, forKey: "led_preview") } }
    /// Short flash on the key: green = on time, orange = early/late, red = wrong key.
    @Published var ledFeedback: Bool { didSet { d.set(ledFeedback, forKey: "led_feedback") } }
    /// Brighter LED for louder notes of the song.
    @Published var ledDynamics: Bool { didSet { d.set(ledDynamics, forKey: "led_dynamics") } }
    @Published var ledAddress: String? { didSet { d.set(ledAddress, forKey: "led_address") } }
    @Published var ledName: String? { didSet { d.set(ledName, forKey: "led_name") } }
    /// Recently opened songs: [key, title].
    @Published var recent: [[String]] { didSet { d.set(recent, forKey: "recent") } }

    init() {
        // A local UserDefaults, so the helpers below do not touch `self` before every setting has a value.
        let ud = UserDefaults.standard
        func s(_ k: String, _ v: String) -> String { ud.string(forKey: k) ?? v }
        func b(_ k: String, _ v: Bool) -> Bool { ud.object(forKey: k) as? Bool ?? v }
        func i(_ k: String, _ v: Int) -> Int { ud.object(forKey: k) as? Int ?? v }
        labelMode = s("label_mode", "c"); practiceMode = s("practice_mode", "normal"); lightsMode = s("lights_mode", "full")
        countIn = b("count_in", true); metronome = b("metronome", false); autoSpeed = b("auto_speed", false)
        showStaff = b("show_staff", false); showFingers = b("show_fingers", true); handSplit = i("hand_split", 60)
        micInput = b("mic_input", false); dailyGoal = i("daily_goal", 10); speed = i("speed", 100); volume = i("volume", 100)
        instrument = i("instrument", 0)
        colorLeft = s("color_left", "#3B82F6"); colorRight = s("color_right", "#F97316")
        ledTransport = s("led_transport", "legacy_nus"); ledEffect = s("led_effect", "spectrum")
        ledBrightness = i("led_brightness", 180); ledLength = i("led_length", 88); organOffset = i("organ_offset", 21)
        autoReconnect = b("auto_reconnect", true)
        ledPreview = b("led_preview", false); ledFeedback = b("led_feedback", true); ledDynamics = b("led_dynamics", false)
        wsDensity = i("ws_density", 144); wsKeys = i("ws_keys", 88); wsNumLedsSet = i("ws_num_leds", 0)
        wsFirstLed = i("ws_first_led", 0); wsReversed = b("ws_reversed", false)
        ledAddress = ud.string(forKey: "led_address"); ledName = ud.string(forKey: "led_name")
        recent = ud.array(forKey: "recent") as? [[String]] ?? []
    }

    /// Leftmost key of the learner's piano (88 = A0, 76 = E1, 61/49 = C2).
    var wsLowestPitch: Int { wsKeys == 76 ? 28 : (wsKeys == 61 || wsKeys == 49) ? 36 : 21 }

    var wsNumLeds: Int {
        get { wsNumLedsSet > 0 ? wsNumLedsSet : Ws2812.ledsNeeded(keys: wsKeys, lowest: wsLowestPitch, ledsPerMetre: Double(wsDensity)) }
        set { wsNumLedsSet = max(1, min(600, newValue)) }
    }

    var wsConfig: Ws2812.Config {
        Ws2812.Config(numLeds: wsNumLeds, ledsPerMetre: Double(wsDensity), lowestPitch: wsLowestPitch,
                      numKeys: wsKeys, firstLed: wsFirstLed, reversed: wsReversed)
    }

    func addRecent(_ key: String, _ title: String) {
        recent = [[key, title]] + recent.filter { $0.first != key }.prefix(49)
    }
}

struct PassResult {
    let total: Int, hits: Int, wrong: Int, meanOffsetMs: Int
    var accuracy: Int { total == 0 ? 0 : hits * 100 / total }
    var clean: Bool { total > 0 && accuracy >= 95 && wrong == 0 }
}

/// Scores the learner's keys against the notes to play (0.3 s real-time tolerance).
final class ScoreKeeper {
    enum Verdict { case hit, wrong, ignored }
    private var expected: [Note] = []
    private var hit: [Bool] = []
    private var wrong = 0
    private var offSum = 0.0, offN = 0
    /// Timing of the last correct key in real ms (+ = late), and the expected note it matched.
    private(set) var lastOffsetMs = 0.0
    private(set) var lastNote: Note?

    func startPass(_ notes: [Note]) {
        expected = notes.sorted { $0.start < $1.start }
        hit = Array(repeating: false, count: expected.count)
        wrong = 0; offSum = 0; offN = 0
    }

    func onKey(_ pitch: Int, at sec: Double, speed: Int, tolerance: Double = 0.3) -> Verdict {
        guard !expected.isEmpty else { return .ignored }
        let t = tolerance * Double(speed) / 100
        var best = -1, bd = Double.greatestFiniteMagnitude
        for (i, n) in expected.enumerated() where n.start >= sec - t && n.start <= sec + t && !hit[i] && n.pitch == pitch {
            if abs(n.start - sec) < bd { bd = abs(n.start - sec); best = i }
        }
        if best >= 0 {
            hit[best] = true
            let off = (sec - expected[best].start) * 100 / Double(speed)
            offSum += off
            offN += 1
            lastOffsetMs = off * 1000
            lastNote = expected[best]
            return .hit
        }
        wrong += 1
        return .wrong
    }

    func countWrong() { wrong += 1 }

    func markChordDone(_ chord: [Note]) {
        for n in chord { if let i = expected.firstIndex(where: { $0 === n }) { hit[i] = true } }
    }

    /// Expected notes up to `upTo` that were not played (for the hot spots).
    func missed(upTo: Double) -> [Note] {
        expected.indices.filter { expected[$0].start <= upTo && !hit[$0] }.map { expected[$0] }
    }

    func finishPass(upTo: Double) -> PassResult {
        var total = 0, hits = 0
        for (i, n) in expected.enumerated() {
            if n.start > upTo { break }
            total += 1
            if hit[i] { hits += 1 }
        }
        return PassResult(total: total, hits: hits, wrong: wrong, meanOffsetMs: offN == 0 ? 0 : Int(offSum / Double(offN) * 1000))
    }
}

/// Fading guidance (Hsieh et al., CHI 2026) and accuracy-gated tempo (Duke, Simmons & Cash 2009).
final class Coach {
    var lights = "full" { didSet { guidance = lights == "off" ? 0 : 1; ok = 0; bad = 0 } }
    private(set) var guidance: Double = 1
    private(set) var cleanStreak = 0
    private var ok = 0, bad = 0

    func onCorrect() -> Bool {
        bad = 0
        guard lights == "fade" else { return false }
        ok += 1
        if ok >= 4 && guidance > 0 { ok = 0; guidance = max(0, guidance - 0.25); return true }
        return false
    }

    func onError() -> Bool {
        ok = 0
        guard lights == "fade" else { return false }
        bad += 1
        if bad >= 2 && guidance < 1 { bad = 0; guidance = 1; return true }
        return false
    }

    func onPass(_ r: PassResult, speed: Int, auto: Bool) -> Int? {
        guard r.total > 0 else { return nil }
        cleanStreak = r.clean ? cleanStreak + 1 : 0
        guard auto else { return nil }
        if cleanStreak >= 3 && speed < 100 { cleanStreak = 0; return min(100, speed + 10) }
        if r.accuracy < 70 && speed > 25 { cleanStreak = 0; return max(25, speed - 10) }
        return nil
    }

    func reset() { cleanStreak = 0; ok = 0; bad = 0 }
}

/// Practice minutes per day (forgiving streak: one missed day per 7) and per-song records.
final class Stats: ObservableObject {
    struct Record: Codable { var sec = 0; var best = -1; var speed = 0; var clean = 0; var done = false; var last: Int? = nil }
    private struct Store: Codable { var days: [String: Int] = [:]; var songs: [String: Record] = [:] }

    @Published private var store: Store
    private let url: URL

    init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("LedPiano", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        url = dir.appendingPathComponent("stats.json")
        store = (try? JSONDecoder().decode(Store.self, from: Data(contentsOf: url))) ?? Store()
    }

    private func save() { try? JSONEncoder().encode(store).write(to: url, options: .atomic) }

    static func day(_ offset: Int = 0) -> Int {
        let cal = Calendar.current
        let d = cal.date(byAdding: .day, value: offset, to: cal.startOfDay(for: Date()))!
        return Int(d.timeIntervalSince1970 / 86400 + 0.5)
    }

    func addPractice(_ key: String, seconds: Int, speed: Int) {
        guard seconds > 0 else { return }
        let t = String(Stats.day())
        store.days[t, default: 0] += seconds
        let cutoff = Stats.day(-90)
        store.days = store.days.filter { (Int($0.key) ?? 0) >= cutoff }
        store.songs[key, default: Record()].sec += seconds
        store.songs[key]!.speed = max(store.songs[key]!.speed, speed)
        store.songs[key]!.last = Stats.day()
        save()
    }

    var todaySeconds: Int { store.days[String(Stats.day())] ?? 0 }

    var lastSeven: [Int] { (0..<7).map { store.days[String(Stats.day($0 - 6))] ?? 0 } }

    var streak: Int {
        func ok(_ d: Int) -> Bool { (store.days[String(d)] ?? 0) >= 60 }
        var day = Stats.day()
        if !ok(day) { day -= 1 }
        var n = 0, forgiven = Int.max
        while n < 3650 {
            if ok(day) { n += 1 } else if n > 0 && forgiven - day >= 7 && ok(day - 1) { forgiven = day } else { break }
            day -= 1
        }
        return n
    }

    func record(_ key: String) -> Record { store.songs[key] ?? Record() }

    /// Day the song was last practised (for the daily plan's review), nil if never.
    func lastDay(_ key: String) -> Int? { store.songs[key]?.last }

    func recordResult(_ key: String, _ r: PassResult, finished: Bool, speed: Int) {
        var rec = record(key)
        if r.total > 0 { rec.best = max(rec.best, r.accuracy) }
        if r.clean { rec.clean += 1 }
        if finished { rec.done = true; rec.speed = max(rec.speed, speed) }
        store.songs[key] = rec
        save()
    }

    func markCompleted(_ key: String, speed: Int) {
        var rec = record(key)
        rec.done = true
        rec.speed = max(rec.speed, speed)
        store.songs[key] = rec
        save()
    }
}

/// The lesson path bundled as lessons.json (+ MIDI files, possibly flattened into the bundle root).
struct Lesson: Identifiable {
    let key: String, file: String, title: String, subtitle: String, level: Int, hands: String
    let fingersR: [Int]?, fingersL: [Int]?
    var id: String { key }
}

enum Lessons {
    struct Level: Identifiable { let number: Int; let title: String; let lessons: [Lesson]; var id: Int { number } }

    static let levels: [Level] = {
        guard let url = resource("lessons.json"), let data = try? Data(contentsOf: url),
              let doc = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let lvs = doc["levels"] as? [[String: Any]] else { return [] }
        return lvs.map { lv in
            let n = lv["level"] as? Int ?? 0
            let songs = (lv["songs"] as? [[String: Any]] ?? []).map { s -> Lesson in
                let f = s["fingers"] as? [String: [Int]]
                let file = s["file"] as? String ?? ""
                return Lesson(key: "asset://" + file, file: file, title: s["title"] as? String ?? "",
                              subtitle: s["subtitle"] as? String ?? "", level: n, hands: s["hands"] as? String ?? "LR",
                              fingersR: f?["R"], fingersL: f?["L"])
            }
            return Level(number: n, title: lv["title"] as? String ?? "", lessons: songs)
        }
    }()

    static var all: [Lesson] { levels.flatMap(\.lessons) }
    static func find(_ key: String) -> Lesson? { all.first { $0.key == key } }
    static func next(_ key: String) -> Lesson? {
        let a = all
        guard let i = a.firstIndex(where: { $0.key == key }), i + 1 < a.count else { return nil }
        return a[i + 1]
    }

    /// Finds a bundled file by path ("lessons/x.mid") whether Xcode kept folders or flattened them.
    static func resource(_ path: String) -> URL? {
        let name = (path as NSString).lastPathComponent
        let base = (name as NSString).deletingPathExtension, ext = (name as NSString).pathExtension
        let dir = (path as NSString).deletingLastPathComponent
        return Bundle.main.url(forResource: base, withExtension: ext, subdirectory: dir.isEmpty ? nil : dir)
            ?? Bundle.main.url(forResource: base, withExtension: ext, subdirectory: "Resources/" + dir)
            ?? Bundle.main.url(forResource: base, withExtension: ext)
    }

    struct Sample: Identifiable { let key: String; let title: String; var id: String { key } }

    /// Other bundled sample songs not on the path.
    static var otherSamples: [Sample] {
        let used = Set(all.map(\.key))
        let urls = (Bundle.main.urls(forResourcesWithExtension: "mid", subdirectory: nil) ?? [])
            + (Bundle.main.urls(forResourcesWithExtension: "mid", subdirectory: "samples") ?? [])
        var seen = Set<String>()
        return urls.compactMap { u in
            let name = u.lastPathComponent
            guard !name.hasPrefix("l1_"), !name.hasPrefix("l2_"), !name.hasPrefix("l3_"), !name.hasPrefix("l4_"),
                  !name.hasPrefix("l5_"), seen.insert(name).inserted else { return nil }
            let key = "asset://samples/" + name
            guard !used.contains(key) else { return nil }
            let title = (name as NSString).deletingPathExtension.replacingOccurrences(of: "-", with: " ")
                .replacingOccurrences(of: "_", with: " ").trimmingCharacters(in: .whitespaces)
            return Sample(key: key, title: title)
        }.sorted { $0.title < $1.title }
    }

    /// Finger number per note (ObjectIdentifier of the note).
    static func fingering(_ l: Lesson, _ song: Song) -> [ObjectIdentifier: Int] {
        var out: [ObjectIdentifier: Int] = [:]
        for (hand, fingers) in [(Hand.right, l.fingersR), (Hand.left, l.fingersL)] {
            guard let fingers else { continue }
            let ns = song.notes.filter { $0.hand == hand }.sorted { ($0.startTick, $0.pitch) < ($1.startTick, $1.pitch) }
            for (n, f) in zip(ns, fingers) where (1...5).contains(f) { out[ObjectIdentifier(n)] = f }
        }
        return out
    }
}

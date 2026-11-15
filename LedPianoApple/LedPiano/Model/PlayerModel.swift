import Foundation
import SwiftUI

/// Shared app services (Android's Application object).
final class AppModel: ObservableObject {
    let settings = Settings()
    let stats = Stats()
    let hub = InputHub()
    lazy var led = LedController(settings: settings)
    lazy var midi = MidiInput(hub: hub)
    lazy var mic = MicInput(hub: hub)
    lazy var player = PlayerModel(app: self)
    let hot = Learning.HotSpots()
    static let esp32PianoName = "Đàn (qua ESP32)"

    init() {
        _ = midi
        _ = led
        // A Bluetooth MIDI piano paired with the ESP32 strip (firmware 2): its notes come in over the LED
        // link, so the phone needs only one Bluetooth connection.
        led.onPianoNote = { [weak self] p, v in
            guard let self else { return }
            if v > 0 { self.hub.noteOn(p, v) } else { self.hub.noteOff(p) }
        }
        led.onEsp32Status = { [weak self] in
            guard let self else { return }
            let on = self.led.esp32PianoConnected
            if on && self.hub.source == .none { self.hub.setState(.midi, Self.esp32PianoName) }
            else if !on && self.hub.deviceName == Self.esp32PianoName { self.hub.setState(.none, nil) }
        }
    }

    func url(for key: String) -> URL? {
        key.hasPrefix("asset://") ? Lessons.resource(String(key.dropFirst("asset://".count))) : URL(fileURLWithPath: key)
    }
}

/// Practice-screen logic: same rules as Android's PlayerActivity.
final class PlayerModel: ObservableObject, EngineListener, NoteInputListener {
    unowned let app: AppModel
    var s: Settings { app.settings }

    @Published private(set) var song: Song?
    @Published private(set) var title = ""
    @Published private(set) var playing = false
    @Published var practice = "both"   // left / both / right
    @Published private(set) var speed = 100
    @Published private(set) var guidance = 1.0
    @Published private(set) var looping = false
    @Published private(set) var loopRange: ClosedRange<Double>?
    @Published var overlay: String?
    @Published var overlayBig = false
    @Published var feedback: (text: String, good: Bool)?
    @Published var pressed: [Int: Bool] = [:]
    @Published var result: (message: String, next: Lesson?)?
    @Published var alert: String?
    @Published private(set) var range = (low: 60, high: 83)
    @Published var needsWaitChoice = false
    @Published private(set) var recording = false
    /// Bars with many mistakes, as loop ranges (drawn red on the repeat bar).
    @Published private(set) var hotSpans: [ClosedRange<Double>] = []
    /// After a recording was saved: (title, key) so the view can offer "listen".
    @Published var savedRecording: (title: String, key: String, notes: Int)?

    private(set) var engine: Engine?
    let synth = Synth()
    private(set) var key = ""
    private(set) var lesson: Lesson?
    private(set) var fingering: [ObjectIdentifier: Int] = [:]
    private(set) var measures: [Double] = []
    private(set) var active: [Int: Hand] = [:]
    private(set) var activeFingers: [Int: Int] = [:]
    let coach = Coach()
    private let score = ScoreKeeper()
    private var passActive = false
    private var waitRemaining = Set<Int>()
    private var unsaved = 0.0
    private let dynamics = Learning.Dynamics()
    private let recorder = Learning.Recorder()
    /// The bar being practised with "Chỗ khó" (a clean loop lowers its mistake count).
    private var hotBar: Int?
    private var hot: Learning.HotSpots { app.hot }
    private var lastTick = Date()
    var landscape = false { didSet { if oldValue != landscape { applyRange() } } }

    init(app: AppModel) { self.app = app }

    var position: Double { engine?.position ?? 0 }

    // MARK: open / close

    func open(key: String, title: String) -> Bool {
        close()
        guard let url = app.url(for: key), let data = try? Data(contentsOf: url) else {
            alert = "Không thể mở tệp MIDI này"; return false
        }
        let song: Song
        do { song = try SongFiles.parse(data, title: title, split: s.handSplit) } catch { // MIDI or MusicXML
            alert = "Không thể mở tệp này\n\(error.localizedDescription)"; return false
        }
        self.key = key
        lesson = Lessons.find(key)
        self.song = song
        self.title = lesson?.title ?? title
        measures = song.measureSecs
        fingering = fingeringFor(song)
        hotBar = nil
        dynamics.reset()
        applyHotMarks()
        if let h = lesson?.hands { practice = h == "R" ? "right" : h == "L" ? "left" : practice }
        coach.lights = s.lightsMode
        guidance = coach.guidance
        synth.volume = Float(s.volume) / 100
        synth.program = s.instrument
        synth.start()
        let e = Engine(song: song, synth: synth)
        e.listener = self
        e.speed = s.speed
        engine = e
        speed = e.speed
        passActive = false
        app.hub.listener = self
        applyRange()
        applyEngine()
        applyGuidance()
        if s.micInput { app.mic.start { _ in } }
        lastTick = Date()
        if song.multiInstrument { alert = "Bản nhạc này có thể gồm nhiều nhạc cụ hoặc 2 cây đàn." }
        return true
    }

    func close() {
        engine?.stop()
        flush(force: true)
        if recorder.active { _ = recorder.stop(); recording = false }
        engine = nil
        song = nil
        playing = false
        looping = false
        loopRange = nil
        overlay = nil
        active.removeAll()
        activeFingers.removeAll()
        app.led.guidance = 1
        app.led.allOff()
        app.mic.stop()
    }

    // MARK: rules

    func practised(_ n: Note) -> Bool {
        switch practice {
        case "left": return n.hand != .right
        case "right": return n.hand != .left
        default: return true
        }
    }

    var effectiveMode: String { s.practiceMode == "wait" && !app.hub.connected ? "normal" : s.practiceMode }

    func applyEngine() {
        guard let e = engine else { return }
        e.gateFilter = { [unowned self] in self.practised($0) }
        e.gateMode = effectiveMode == "wait" || effectiveMode == "step"
        e.metronome = s.metronome
        switch app.hub.source {
        case .midi: e.muteFilter = { [unowned self] in self.practised($0) }
        case .mic: e.muteFilter = { _ in true }
        case .none: e.muteFilter = { _ in false }
        }
        if !e.gateMode { overlay = nil }
    }

    func applyRange() {
        let minOct = landscape ? 3 : 2
        let ps = song?.notes.map(\.pitch) ?? [60, 71]
        var lo = (ps.min() ?? 60) / 12 * 12, hi = (ps.max() ?? 71) / 12 * 12 + 11
        var up = true
        while (hi - lo + 1) / 12 < minOct {
            let cu = hi + 12 <= Piano.highest, cd = lo - 12 >= Piano.lowest
            if !cu && !cd { break }
            if (up && cu) || !cd { hi += 12 } else { lo -= 12 }
            up.toggle()
        }
        range = (max(lo, Piano.lowest), min(hi, Piano.highest))
    }

    func applyGuidance() {
        guidance = coach.guidance
        app.led.guidance = guidance
    }

    func setPractice(_ p: String) {
        practice = p
        passActive = false
        coach.reset()
        applyEngine()
    }

    func setSpeed(_ v: Int) {
        let c = min(Engine.maxSpeed, max(Engine.minSpeed, v))
        engine?.speed = c
        speed = c
        s.speed = c
    }

    func optionChanged(_ what: String) {
        switch what {
        case "mode":
            passActive = false
            applyEngine()
            if s.practiceMode == "wait" && !app.hub.connected { needsWaitChoice = true }
        case "lights":
            coach.lights = s.lightsMode
            applyGuidance()
        case "metronome": engine?.metronome = s.metronome
        case "instrument":
            synth.program = s.instrument
            preview()
        case "split":
            guard let song else { return }
            HandAssigner.reassign(song, split: s.handSplit)
            fingering = fingeringFor(song)
            passActive = false
            objectWillChange.send()
        case "led":
            if !s.ledPreview { app.led.setPreview([]) }
            objectWillChange.send()
        default: objectWillChange.send()
        }
    }

    /// Plays C–E–G–C with the chosen instrument (when not playing a song).
    func preview() {
        guard engine?.playing != true else { return }
        synth.start()
        synth.program = s.instrument
        for (i, k) in [60, 64, 67, 72].enumerated() {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.22 * Double(i)) { self.synth.noteOn(k, velocity: 96) }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.22 * Double(i) + 0.6) { self.synth.noteOff(k) }
        }
    }

    // MARK: transport

    func togglePlay() {
        guard let e = engine else { return }
        if e.playing { e.pause(); return }
        if s.practiceMode == "wait" && !app.hub.connected { needsWaitChoice = true; return }
        applyEngine()
        if !passActive { startPass(from: e.position) }
        lastTick = Date()
        if s.countIn { e.playWithCountIn() } else { e.play() }
    }

    func useStepInstead() {
        s.practiceMode = "step"
        togglePlay()
    }

    func restart() {
        guard let e = engine else { return }
        e.stop()
        clearRepeat()
        app.led.allOff()
        active.removeAll()
        activeFingers.removeAll()
        overlay = nil
        passActive = false
    }

    func seek(_ t: Double) {
        engine?.seek(t)
        passActive = false
    }

    func stepForward() {
        guard let e = engine, let chord = e.waitingChord else { return }
        score.markChordDone(chord)
        e.releaseGate()
        overlay = nil
    }

    func tapOrPedal() {
        if engine?.waitingChord != nil && s.practiceMode == "step" { stepForward() }
    }

    // MARK: repeat

    func toggleRepeat() {
        guard let e = engine, let song else { return }
        if e.looping { clearRepeat(); return }
        let pos = e.position
        let idx = measures.lastIndex { $0 <= pos + 0.01 } ?? -1
        var a = max(0, pos), b = min(pos + 8, song.duration)
        if idx >= 0 && idx + 2 < measures.count { a = measures[idx]; b = measures[idx + 2] }
        guard b - a >= 0.5 else { return }
        e.loopStart = a; e.loopEnd = b; e.looping = true
        if !e.playing { e.seek(a) }
        coach.reset()
        startPass(from: a)
        looping = true
        loopRange = a...b
    }

    func setLoop(_ a: Double, _ b: Double) {
        guard let e = engine, b > a else { return }
        e.loopStart = a; e.loopEnd = b
        loopRange = a...b
        if e.position < a || e.position >= b { e.seek(a) }
        coach.reset()
        startPass(from: a)
    }

    func clearRepeat() {
        hotBar = nil
        engine?.looping = false
        engine?.loopStart = nil
        engine?.loopEnd = nil
        looping = false
        loopRange = nil
    }

    // MARK: passes / stats

    private func startPass(from: Double) {
        guard let song, let e = engine else { return }
        let end = e.looping ? (e.loopEnd ?? song.duration) : song.duration
        score.startPass(song.notes.filter { practised($0) && $0.start >= from - 1e-3 && $0.start < end })
        passActive = true
    }

    private func onPassFinished() {
        guard let e = engine else { return }
        if app.hub.connected && passActive {
            let upTo = e.loopEnd ?? e.position
            recordMisses(upTo: upTo)
            let r = score.finishPass(upTo: upTo)
            app.stats.recordResult(key, r, finished: false, speed: speed)
            if let b = hotBar, r.clean { hot.addClean(key, bar: b); applyHotMarks() }
            let adv = dynamicsAdvice()
            if let ns = coach.onPass(r, speed: speed, auto: s.autoSpeed) {
                let msg = ns > speed ? "Rất tốt! 3 lần đúng liên tiếp — tăng lên \(ns)%" : "Chậm lại một chút: \(ns)%"
                setSpeed(ns)
                showFeedback(msg + (adv.map { " · " + $0 } ?? ""), good: true)
            } else if r.total > 0 {
                showFeedback("Lượt vừa rồi: đúng \(r.accuracy)%, sai \(r.wrong) phím" + (adv.map { " · " + $0 } ?? ""), good: r.clean)
            } else if let adv {
                showFeedback(adv, good: true)
            }
        }
        startPass(from: e.loopStart ?? 0)
    }

    private func onFinished() {
        guard let song else { return }
        flush(force: true)
        var msg: String
        if app.hub.connected && passActive {
            recordMisses(upTo: song.duration)
            let r = score.finishPass(upTo: song.duration)
            app.stats.recordResult(key, r, finished: true, speed: speed)
            msg = "Chính xác \(r.accuracy)% (\(r.hits)/\(r.total) nốt) · \(r.wrong) phím sai"
            if r.hits > 0 {
                msg += r.meanOffsetMs > 60 ? "\nTrung bình bạn chơi trễ \(r.meanOffsetMs) ms."
                    : r.meanOffsetMs < -60 ? "\nTrung bình bạn chơi sớm \(-r.meanOffsetMs) ms." : "\nNhịp rất đều!"
            }
            if let adv = dynamicsAdvice() { msg += "\n" + adv + "." }
            if let w = hot.worst(key) { msg += "\nChỗ khó nhất: ô nhịp \(w + 1) — bấm 🎯 để luyện riêng." }
        } else {
            app.stats.markCompleted(key, speed: speed)
            msg = "Bạn đã chơi hết bài. Nối đàn để được chấm điểm."
        }
        passActive = false
        result = (msg, Lessons.next(key))
    }

    func lightsOffRun() {
        s.lightsMode = "off"
        coach.lights = "off"
        applyGuidance()
        restart()
        togglePlay()
    }

    func flush(force: Bool) {
        let now = Date()
        if engine?.playing == true { unsaved += min(2, max(0, now.timeIntervalSince(lastTick))) }
        lastTick = now
        if !key.isEmpty && (force || unsaved >= 15) && unsaved >= 1 {
            app.stats.addPractice(key, seconds: Int(unsaved), speed: speed)
            unsaved -= Double(Int(unsaved))
        }
    }

    // MARK: overlays

    private func showWait(_ chord: [Note]) {
        for n in chord { app.led.keyOn(n.pitch, hand: n.hand, velocity: n.velocity) }
        if let first = chord.first { updatePreview(after: first.start) }
        overlayBig = false
        if s.practiceMode == "wait" {
            waitRemaining = app.hub.source == .mic ? [chord.map(\.pitch).max() ?? 0] : Set(chord.map(\.pitch))
            let mode = s.labelMode == "solfege" ? "solfege" : "letters"
            let names = Set(chord.map(\.pitch)).sorted().map { (NoteNames.name($0, mode) ?? "") + "\(NoteNames.octave($0))" }
            overlay = "Bấm: " + names.joined(separator: "  ")
        } else {
            overlay = "Chạm để đi tiếp"
        }
    }

    func showFeedback(_ text: String, good: Bool) {
        feedback = (text, good)
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) { [weak self] in
            if self?.feedback?.text == text { self?.feedback = nil }
        }
    }

    // MARK: EngineListener (engine thread)

    func noteStart(_ n: Note) {
        app.led.keyOn(n.pitch, hand: n.hand, velocity: n.velocity)
        updatePreview(after: n.start)
        DispatchQueue.main.async {
            self.active[n.pitch] = n.hand
            if let f = self.fingering[ObjectIdentifier(n)] { self.activeFingers[n.pitch] = f }
        }
    }

    func noteEnd(_ n: Note) {
        app.led.keyOff(n.pitch)
        DispatchQueue.main.async {
            self.active.removeValue(forKey: n.pitch)
            self.activeFingers.removeValue(forKey: n.pitch)
        }
    }

    func stateChanged(_ playing: Bool) {
        app.led.playing = playing
        DispatchQueue.main.async {
            self.playing = playing
            if !playing { self.flush(force: true) }
            self.lastTick = Date()
        }
    }

    func songEnded() {
        DispatchQueue.main.async {
            self.active.removeAll()
            self.app.led.allOff()
            self.onFinished()
        }
    }

    func waiting(_ chord: [Note]) { DispatchQueue.main.async { self.showWait(chord) } }

    func countIn(_ left: Int) {
        DispatchQueue.main.async {
            if left > 0 { self.overlayBig = true; self.overlay = "\(left)" } else { self.overlay = nil }
        }
    }

    func loopWrapped() { DispatchQueue.main.async { self.onPassFinished() } }

    // MARK: NoteInputListener (main thread)

    func inputNoteOn(_ pitch: Int, velocity: Int) {
        var ok = true
        recorder.note(pitch, velocity)
        let fromMidi = app.hub.source == .midi
        if let e = engine, let chord = e.waitingChord, s.practiceMode == "wait" {
            if let n = chord.first(where: { $0.pitch == pitch }) {
                app.led.flash(pitch, Learning.green, ms: 150)
                if fromMidi { dynamics.add(expected: n.velocity, played: velocity) }
                waitRemaining.remove(pitch)
                if waitRemaining.isEmpty {
                    score.markChordDone(chord)
                    if coach.onCorrect() { applyGuidance() }
                    e.releaseGate()
                    overlay = nil
                }
            } else {
                ok = false
                score.countWrong()
                app.led.flash(pitch, Learning.red, ms: 300)
                missAt(e.position)
                if coach.onError() { applyGuidance() }
                showFeedback("Sai phím", good: false)
            }
        } else if let e = engine, e.playing, !e.countingIn, passActive {
            let v = score.onKey(pitch, at: e.position, speed: speed)
            ok = v != .wrong
            if v == .hit {
                app.led.flash(pitch, Learning.timingFlash(score.lastOffsetMs), ms: 180)
                if fromMidi, let n = score.lastNote { dynamics.add(expected: n.velocity, played: velocity) }
                if coach.onCorrect() { applyGuidance() }
            }
            if v == .wrong {
                app.led.flash(pitch, Learning.red, ms: 300)
                missAt(e.position)
                if coach.onError() { applyGuidance() }
                showFeedback("Sai phím", good: false)
            }
        } else if engine?.playing != true {
            app.led.keyOn(pitch, hand: pitch < s.handSplit ? .left : .right, velocity: velocity)
        }
        pressed[pitch] = ok
    }

    func inputNoteOff(_ pitch: Int) {
        recorder.note(pitch, 0)
        pressed.removeValue(forKey: pitch)
        if engine?.playing != true { app.led.keyOff(pitch) }
    }

    func inputStateChanged() {
        applyEngine()
        objectWillChange.send()
    }

    // MARK: learning features

    /// Lesson fingering, or the finger numbers written in an imported score (MusicXML).
    private func fingeringFor(_ song: Song) -> [ObjectIdentifier: Int] {
        if let l = lesson { return Lessons.fingering(l, song) }
        var out: [ObjectIdentifier: Int] = [:]
        for n in song.notes where (1...5).contains(n.finger) { out[ObjectIdentifier(n)] = n.finger }
        return out
    }

    /// Next note of each practised hand after `after` glows dimly on the strip (option, off by default).
    /// Called on the engine thread, so it only reads the song (fixed while playing).
    private func updatePreview(after: Double) {
        guard s.ledPreview, let song else { return }
        var i = song.lowerBound(after + 0.02)
        while i < song.byTime.count && song.byTime[i].start <= after + 0.02 { i += 1 }
        var next: [(pitch: Int, hand: Hand)] = []
        var seenLeft = false, seenRight = false
        var k = 0
        while i < song.byTime.count && k < 64 && next.count < 2 {
            let n = song.byTime[i]
            i += 1; k += 1
            guard practised(n) else { continue }
            let left = n.hand == .left
            if left && !seenLeft { seenLeft = true; next.append((n.pitch, .left)) }
            if !left && !seenRight { seenRight = true; next.append((n.pitch, .right)) }
        }
        app.led.setPreview(next)
    }

    private func missAt(_ sec: Double) {
        guard !measures.isEmpty, !key.isEmpty else { return }
        hot.addMiss(key, bar: Learning.barOf(measures, sec))
        applyHotMarks()
    }

    /// Notes not played in this pass count as mistakes of their bar.
    private func recordMisses(upTo: Double) {
        guard !measures.isEmpty, !key.isEmpty else { return }
        var bars: [Int: Int] = [:]
        for n in score.missed(upTo: upTo) { bars[Learning.barOf(measures, n.start), default: 0] += 1 }
        for (b, c) in bars { hot.addMiss(key, bar: b, count: c) }
        if !bars.isEmpty { applyHotMarks() }
    }

    private func applyHotMarks() {
        guard let song else { hotSpans = []; return }
        hotSpans = hot.counts(key).keys.sorted().filter { $0 < measures.count }.map { b in
            let r = Learning.loopForBar(measures, duration: song.duration, bar: b)
            return r.0...max(r.0, r.1)
        }
    }

    private func dynamicsAdvice() -> String? {
        let a = dynamics.advice()
        dynamics.reset()
        return a.flatMap { Learning.adviceVi[$0] }
    }

    var hasHotSpot: Bool { hot.worst(key) != nil }

    /// Loop the bar with the most mistakes at 65 %, auto speed-up after 3 clean loops (Duke et al.).
    func practiseHotSpot() {
        guard let e = engine, let song else { return }
        guard let bar = hot.worst(key) else {
            showFeedback("Chưa có chỗ khó — nối đàn và chơi thử, ứng dụng sẽ tự tìm", good: true); return
        }
        let (a, b) = Learning.loopForBar(measures, duration: song.duration, bar: bar)
        guard b - a >= 0.3 else { return }
        e.loopStart = a; e.loopEnd = b; e.looping = true
        hotBar = bar
        s.autoSpeed = true
        setSpeed(min(speed, Learning.hotSpotSpeed))
        e.seek(a)
        coach.reset()
        startPass(from: a)
        looping = true
        loopRange = a...b
        showFeedback("Luyện ô nhịp \(bar + 1) ở \(speed)% · tự tăng tốc sau 3 lần đúng", good: true)
    }

    /// Records what the learner plays; on stop it is saved as a MIDI file in "Bài hát của bạn".
    func toggleRecord() {
        if !recorder.active {
            recorder.start()
            recording = true
            showFeedback("Đang ghi âm phần bạn chơi…", good: true)
            return
        }
        let n = recorder.stop()
        recording = false
        guard n > 0 else { showFeedback("Chưa ghi được nốt nào — hãy nối đàn", good: false); return }
        let bpm = song?.tempos.first.map { 60_000_000 / max(1, $0.us) } ?? 120
        let f = DateFormatter()
        f.dateFormat = "dd-MM HH.mm"
        let base = (title.isEmpty ? "Bản ghi" : title) + " – " + f.string(from: Date())
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Recordings", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let safe = String(base.filter { !"\\/:*?\"<>|".contains($0) })
        let url = dir.appendingPathComponent(safe + ".mid")
        do { try recorder.midiBytes(bpm: bpm).write(to: url, options: .atomic) } catch {
            alert = "Không lưu được bản ghi"; return
        }
        let t = "Bản ghi: " + base
        s.addRecent(url.path, t)
        savedRecording = (t, url.path, n)
    }

    /// Stores the song on the ESP32 strip, to practise without the phone (BOOT button).
    func sendSongToEsp32() {
        guard let song else { return }
        guard app.led.isEsp32 else { alert = "Hãy kết nối mạch ESP32 (LED Piano Strip) trước"; return }
        let n = app.led.sendSong(song)
        alert = "Đã gửi \(n) nốt vào thư viện bài của mạch ESP32. Trên mạch: nhấn BOOT để luyện bài này (đèn chờ bạn bấm đúng phím trên đàn), nhấn 2 lần nhanh để đổi bài, nhấn giữ để dừng. Danh sách bài: trang http://ledpiano.local"
    }

    // MARK: per-frame data for drawing

    func upcomingAndFingers(at pos: Double) -> ([Int: Hand], [Int: Int]) {
        guard let song else { return ([:], [:]) }
        var up: [Int: Hand] = [:]
        var fingers = activeFingers
        let showF = s.showFingers && !fingering.isEmpty
        var i = song.lowerBound(pos)
        while i < song.byTime.count {
            let n = song.byTime[i]
            if n.start > pos + 2.5 { break }
            if active[n.pitch] == nil {
                up[n.pitch] = n.hand
                if showF, fingers[n.pitch] == nil, let f = fingering[ObjectIdentifier(n)] { fingers[n.pitch] = f }
            }
            i += 1
        }
        return (up, showF ? fingers : [:])
    }
}

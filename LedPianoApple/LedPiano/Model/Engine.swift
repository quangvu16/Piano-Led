import Foundation

protocol EngineListener: AnyObject {
    func noteStart(_ n: Note)
    func noteEnd(_ n: Note)
    func stateChanged(_ playing: Bool)
    func songEnded()
    func waiting(_ chord: [Note])
    func countIn(_ beatsLeft: Int)
    func loopWrapped()
}

/// Playback engine (same behaviour as Android): speed 25–100 %, A–B loop,
/// count-in, metronome and the Wait/Step gate that holds time before each
/// chord the learner must play. Callbacks run on the engine thread.
final class Engine: @unchecked Sendable {
    static let minSpeed = 25, maxSpeed = 100
    static let chordWindow = 0.06
    private static let eps = 1e-4

    let song: Song
    let beats: BeatGrid
    private let synth: Synth
    weak var listener: EngineListener?

    private let lock = NSRecursiveLock()
    private var thread: Thread?
    private var stopFlag = false

    var speed = 100 { didSet { speed = min(Engine.maxSpeed, max(Engine.minSpeed, speed)) } }
    var loopStart: Double?
    var loopEnd: Double?
    var looping = false
    var metronome = false
    var gateFilter: (Note) -> Bool = { _ in true }
    var muteFilter: (Note) -> Bool = { _ in false }
    var gateMode = false {
        didSet {
            if gateMode { floor = position - Engine.eps } else { waitingChord = nil; released = false }
        }
    }

    private(set) var playing = false
    private(set) var position = 0.0
    private(set) var waitingChord: [Note]?
    var countingIn: Bool { countLeft > 0 }

    private var released = false
    private var floor = -1.0
    private var cursor = 0
    private var beatCursor = 0
    private var active: [Note] = []
    private var countLeft = 0
    private var countTotal = 0
    private var countTimer = 0.0

    init(song: Song, synth: Synth) {
        self.song = song
        self.synth = synth
        beats = BeatGrid(song: song)
    }

    func play() {
        guard !playing else { return }
        if position >= song.duration { seek(0) }
        playing = true
        stopFlag = false
        listener?.stateChanged(true)
        let t = Thread { [weak self] in self?.run() }
        t.qualityOfService = .userInteractive
        thread = t
        t.start()
    }

    func playWithCountIn(_ count: Int? = nil) {
        guard !playing else { return }
        countLeft = min(8, max(1, count ?? beats.beatsPerMeasure))
        countTotal = countLeft
        countTimer = 0
        play()
    }

    private func joinThread() {
        let deadline = Date().addingTimeInterval(0.3)
        while let t = thread, t.isExecuting, Date() < deadline, Thread.current != t { usleep(2000) }
        thread = nil
    }

    func pause() {
        guard playing else { return }
        playing = false
        stopFlag = true
        joinThread()
        countLeft = 0
        releaseActive()
        listener?.stateChanged(false)
    }

    func stop() {
        playing = false
        stopFlag = true
        joinThread()
        countLeft = 0
        releaseActive()
        lock.lock(); position = 0; cursor = 0; beatCursor = 0; lock.unlock()
        floor = -1; waitingChord = nil; released = false
        synth.allNotesOff()
        listener?.stateChanged(false)
    }

    func seek(_ sec: Double) {
        lock.lock()
        releaseActive()
        position = min(max(0, sec), song.duration)
        cursor = song.lowerBound(position)
        beatCursor = beats.lowerBound(position)
        lock.unlock()
        floor = position - Engine.eps
        waitingChord = nil
        released = false
        synth.allNotesOff()
    }

    func releaseGate() { if waitingChord != nil { released = true } }

    private func releaseActive() {
        lock.lock()
        for n in active { synth.noteOff(n.pitch); listener?.noteEnd(n) }
        active.removeAll()
        lock.unlock()
    }

    private func nextGate(after f: Double) -> Double? {
        var i = song.lowerBound(f + 1e-9)
        let notes = song.byTime
        while i < notes.count {
            if notes[i].start > f && gateFilter(notes[i]) { return notes[i].start }
            i += 1
        }
        return nil
    }

    private func chord(at t: Double) -> [Note] {
        var i = song.lowerBound(t - 1e-9)
        var out: [Note] = []
        let notes = song.byTime
        while i < notes.count && notes[i].start <= t + Engine.chordWindow {
            if gateFilter(notes[i]) { out.append(notes[i]) }
            i += 1
        }
        return out
    }

    private func run() {
        var last = CFAbsoluteTimeGetCurrent()
        while !stopFlag {
            let now = CFAbsoluteTimeGetCurrent()
            let dt = now - last
            last = now

            if countLeft > 0 {
                countTimer -= dt
                if countTimer <= 0 {
                    let left = countLeft
                    synth.click(accent: left == countTotal)
                    listener?.countIn(left)
                    countLeft = left - 1
                    countTimer += beats.beatLength(at: position) * 100 / Double(speed)
                    if countLeft == 0 {
                        let end = CFAbsoluteTimeGetCurrent() + countTimer
                        while CFAbsoluteTimeGetCurrent() < end && !stopFlag { usleep(10_000) }
                        last = CFAbsoluteTimeGetCurrent()
                        listener?.countIn(0)
                    }
                }
                usleep(8000)
                continue
            }

            if let held = waitingChord {
                if released {
                    waitingChord = nil
                    released = false
                    if let first = held.first { floor = first.start + Engine.chordWindow }
                } else {
                    usleep(8000)
                    continue
                }
            }

            var newPos = position + dt * Double(speed) / 100
            if gateMode, let g = nextGate(after: floor), newPos >= g {
                newPos = g - Engine.eps
                let c = chord(at: g)
                if c.isEmpty { floor = g } else { waitingChord = c; listener?.waiting(c) }
            }
            position = newPos

            if looping, let le = loopEnd, position >= le {
                let ls = loopStart ?? 0
                position = ls
                releaseActive()
                lock.lock(); cursor = song.lowerBound(ls); beatCursor = beats.lowerBound(ls); lock.unlock()
                floor = ls - Engine.eps
                waitingChord = nil
                listener?.loopWrapped()
            }

            lock.lock()
            while beatCursor < beats.secs.count && beats.secs[beatCursor] <= position {
                if metronome && position - beats.secs[beatCursor] < 0.25 { synth.click(accent: beats.down[beatCursor]) }
                beatCursor += 1
            }
            let notes = song.byTime
            while cursor < notes.count && notes[cursor].start <= position {
                let n = notes[cursor]
                if position - n.start < 1.0 {
                    if !muteFilter(n) { synth.noteOn(n.pitch, velocity: n.velocity) }
                    active.append(n)
                    listener?.noteStart(n)
                }
                cursor += 1
            }
            var keep: [Note] = []
            for n in active {
                if n.end <= position { synth.noteOff(n.pitch); listener?.noteEnd(n) } else { keep.append(n) }
            }
            active = keep
            let done = position >= song.duration && active.isEmpty
            lock.unlock()

            if done {
                stopFlag = true
                playing = false
                listener?.songEnded()
                listener?.stateChanged(false)
                break
            }
            usleep(8000)
        }
    }
}

package com.ledpiano.app.playback

import com.ledpiano.app.midi.MidiNote
import com.ledpiano.app.midi.MidiSong

/**
 * Drives playback of a [MidiSong]: dispatches note events to the synthesizer
 * and to listeners (UI, LED mapper) with sample-accurate-enough timing.
 *
 * Learner features:
 *  - speed 25–100 %, A–B loop, seeking;
 *  - count-in clicks before playback starts ([playWithCountIn]);
 *  - metronome clicks on every beat ([metronome]);
 *  - "gate" mode for Wait and Step practice: time stops just before each chord
 *    the learner must play ([gateFilter]) until [releaseGate] is called.
 */
class PlaybackEngine(
    private val song: MidiSong,
    private val synth: SynthEngine,
    private val listener: Listener,
) {

    interface Listener {
        fun onNoteStart(note: MidiNote)
        fun onNoteEnd(note: MidiNote)
        fun onTimeUpdate(positionSec: Double)
        fun onStateChange(playing: Boolean)
        fun onSongEnd()

        /** Gate mode: playback is holding until [chord] is played. */
        fun onWaiting(chord: List<MidiNote>) {}

        /** Count-in click; [beatsLeft] = number shown (n…1), 0 when playback starts. */
        fun onCountIn(beatsLeft: Int) {}

        /** The A–B loop jumped back to its start (one pass completed). */
        fun onLoopWrapped() {}
    }

    private val notes: List<MidiNote> = song.notesByTime
    val beats: BeatGrid = BeatGrid.from(song)

    @Volatile var speedPercent: Int = 100
        set(value) {
            field = value.coerceIn(MIN_SPEED, MAX_SPEED)
        }

    @Volatile var loopStartSec: Double? = null
    @Volatile var loopEndSec: Double? = null
    @Volatile var looping: Boolean = false

    /** Click on every beat while playing. */
    @Volatile var metronome: Boolean = false

    /** Wait / Step practice: hold before every chord that matches [gateFilter]. */
    @Volatile var gateMode: Boolean = false
        set(value) {
            field = value
            if (value) {
                gateFloor = positionSec - GATE_EPS
            } else {
                waitingChord = null
                gateReleased = false
            }
        }

    /** Notes the learner has to play (e.g. only the practised hand). */
    @Volatile var gateFilter: (MidiNote) -> Boolean = { true }

    /** Notes the built-in synth should stay silent for (the learner's own piano plays them). */
    @Volatile var muteFilter: (MidiNote) -> Boolean = { false }

    @Volatile
    var isPlaying: Boolean = false
        private set

    @Volatile
    var positionSec: Double = 0.0
        private set

    /** The chord playback is currently holding for, or null. */
    @Volatile
    var waitingChord: List<MidiNote>? = null
        private set

    val isCountingIn: Boolean get() = countInLeft > 0

    private var cursor = 0
    private var beatCursor = 0
    private val active = ArrayList<MidiNote>()
    @Volatile private var gateFloor = -1.0
    @Volatile private var gateReleased = false
    @Volatile private var countInLeft = 0
    private var countInTotal = 0
    private var countInTimer = 0.0

    /** Guards [cursor], [beatCursor] and [active]: the playback thread and seek() (UI) share them. */
    private val eventLock = Any()

    private var thread: Thread? = null
    @Volatile private var stopRequested = false

    @Synchronized
    fun play() {
        if (isPlaying) return
        if (positionSec >= song.durationSec) seek(0.0)
        isPlaying = true
        stopRequested = false
        listener.onStateChange(true)
        thread = Thread({ runLoop() }, "MidiPlayback").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    /** Plays [count] metronome clicks at the current tempo and speed, then starts. */
    @Synchronized
    fun playWithCountIn(count: Int = beats.beatsPerMeasure) {
        if (isPlaying) return
        countInLeft = count.coerceIn(1, 8)
        countInTotal = countInLeft
        countInTimer = 0.0
        play()
    }

    @Synchronized
    fun pause() {
        if (!isPlaying) return
        isPlaying = false
        stopRequested = true
        thread?.join(300)
        thread = null
        countInLeft = 0
        releaseActiveNotes()
        listener.onStateChange(false)
    }

    @Synchronized
    fun stop() {
        isPlaying = false
        stopRequested = true
        thread?.join(300)
        thread = null
        countInLeft = 0
        releaseActiveNotes()
        positionSec = 0.0
        synchronized(eventLock) {
            cursor = 0
            beatCursor = 0
        }
        gateFloor = -1.0
        waitingChord = null
        gateReleased = false
        synth.allNotesOff()
        listener.onTimeUpdate(0.0)
        listener.onStateChange(false)
    }

    @Synchronized
    fun seek(sec: Double) {
        synchronized(eventLock) {
            releaseActiveNotes()
            positionSec = sec.coerceIn(0.0, song.durationSec)
            cursor = lowerBound(positionSec)
            beatCursor = beats.lowerBound(positionSec)
        }
        gateFloor = positionSec - GATE_EPS
        waitingChord = null
        gateReleased = false
        synth.allNotesOff()
        listener.onTimeUpdate(positionSec)
    }

    /** Lets playback continue past the chord it is holding for. */
    fun releaseGate() {
        if (waitingChord != null) gateReleased = true
    }

    /** Release the currently sounding notes and tell listeners about it. */
    private fun releaseActiveNotes() {
        synchronized(eventLock) {
            for (n in active) {
                synth.noteOff(n.pitch)
                listener.onNoteEnd(n)
            }
            active.clear()
        }
    }

    private fun lowerBound(sec: Double): Int {
        var lo = 0
        var hi = notes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (notes[mid].startSec < sec) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Start time of the next chord the learner must play, strictly after [floor]. */
    private fun nextGateTime(floor: Double): Double? {
        val filter = gateFilter
        var i = lowerBound(floor + 1e-9)
        while (i < notes.size) {
            val n = notes[i]
            if (n.startSec > floor && filter(n)) return n.startSec
            i++
        }
        return null
    }

    private fun chordAt(time: Double): List<MidiNote> {
        val filter = gateFilter
        val out = ArrayList<MidiNote>()
        var i = lowerBound(time - 1e-9)
        while (i < notes.size && notes[i].startSec <= time + CHORD_WINDOW_SEC) {
            if (filter(notes[i])) out.add(notes[i])
            i++
        }
        return out
    }

    private fun runLoop() {
        var lastNanos = System.nanoTime()
        var lastUiUpdate = 0L
        while (!stopRequested) {
            val now = System.nanoTime()
            val dt = (now - lastNanos) / 1_000_000_000.0
            lastNanos = now

            // ---- count-in: clicks only, song time does not move ----
            if (countInLeft > 0) {
                countInTimer -= dt
                if (countInTimer <= 0.0) {
                    val left = countInLeft
                    synth.click(accent = left == countInTotal)
                    listener.onCountIn(left)
                    countInLeft = left - 1
                    countInTimer += beats.beatLengthAt(positionSec) * 100.0 / speedPercent
                    if (countInLeft == 0) {
                        // Wait out the last beat before the first note.
                        sleepBeat(countInTimer)
                        lastNanos = System.nanoTime()
                        listener.onCountIn(0)
                    }
                }
                sleepTick()
                continue
            }

            // ---- gate (Wait / Step): hold time while a chord is pending ----
            if (waitingChord != null) {
                if (gateReleased) {
                    val held = waitingChord
                    waitingChord = null
                    gateReleased = false
                    if (held != null && held.isNotEmpty()) gateFloor = held.first().startSec + CHORD_WINDOW_SEC
                } else {
                    if (now - lastUiUpdate > 33_000_000L) {
                        lastUiUpdate = now
                        listener.onTimeUpdate(positionSec)
                    }
                    sleepTick()
                    continue
                }
            }

            var newPos = positionSec + dt * speedPercent / 100.0
            if (gateMode) {
                val g = nextGateTime(gateFloor)
                if (g != null && newPos >= g) {
                    // Stop a hair before the chord so it only sounds once released.
                    newPos = g - GATE_EPS
                    val chord = chordAt(g)
                    if (chord.isNotEmpty()) {
                        waitingChord = chord
                        listener.onWaiting(chord)
                    } else {
                        gateFloor = g
                    }
                }
            }
            positionSec = newPos

            // Loop handling.
            val loopEnd = loopEndSec
            if (looping && loopEnd != null && positionSec >= loopEnd) {
                val loopStart = loopStartSec ?: 0.0
                positionSec = loopStart
                releaseActiveNotes()
                synchronized(eventLock) {
                    cursor = lowerBound(loopStart)
                    beatCursor = beats.lowerBound(loopStart)
                }
                gateFloor = loopStart - GATE_EPS
                waitingChord = null
                listener.onLoopWrapped()
            }

            synchronized(eventLock) {
                // Metronome.
                while (beatCursor < beats.size && beats.beatSecs[beatCursor] <= positionSec) {
                    if (metronome && positionSec - beats.beatSecs[beatCursor] < 0.25) {
                        synth.click(accent = beats.downbeat[beatCursor])
                    }
                    beatCursor++
                }

                // Start and end due notes (shared state with seek()).
                while (cursor < notes.size && notes[cursor].startSec <= positionSec) {
                    val note = notes[cursor]
                    if (positionSec - note.startSec < 1.0) { // don't replay stale notes after a seek
                        if (!muteFilter(note)) synth.noteOn(note.pitch, note.velocity)
                        active.add(note)
                        listener.onNoteStart(note)
                    }
                    cursor++
                }

                val it = active.iterator()
                while (it.hasNext()) {
                    val note = it.next()
                    if (note.endSec <= positionSec) {
                        synth.noteOff(note.pitch)
                        listener.onNoteEnd(note)
                        it.remove()
                    }
                }
            }

            // UI/LED progress updates at ~30 Hz.
            if (now - lastUiUpdate > 33_000_000L) {
                lastUiUpdate = now
                listener.onTimeUpdate(positionSec)
            }

            if (positionSec >= song.durationSec && active.isEmpty()) {
                stopRequested = true
                isPlaying = false
                listener.onSongEnd()
                listener.onStateChange(false)
                break
            }

            sleepTick()
        }
    }

    private fun sleepTick() {
        try {
            Thread.sleep(8)
        } catch (_: InterruptedException) {
        }
    }

    private fun sleepBeat(sec: Double) {
        var remaining = (sec * 1000).toLong()
        while (remaining > 0 && !stopRequested) {
            val step = minOf(remaining, 20L)
            try {
                Thread.sleep(step)
            } catch (_: InterruptedException) {
                return
            }
            remaining -= step
        }
    }

    companion object {
        const val MIN_SPEED = 25
        const val MAX_SPEED = 100

        /** Notes starting within this window form one chord. */
        const val CHORD_WINDOW_SEC = 0.06
        private const val GATE_EPS = 1e-4
    }
}

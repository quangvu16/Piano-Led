package com.ledpiano.app.practice

import com.ledpiano.app.midi.MidiNote
import kotlin.math.abs

/**
 * Scores what the learner plays (from a MIDI keyboard or the microphone)
 * against the notes they should play, one "pass" at a time (a full song or
 * one loop of the A–B section).
 *
 * A note counts as hit when the same key is pressed within [toleranceSec]
 * (real seconds) of its start. Feedback describes the result, not the body
 * ("late", "wrong key"), which supports learning better than movement cues.
 */
class ScoreKeeper {

    data class PassResult(
        val total: Int,
        val hits: Int,
        val wrong: Int,
        /** Average timing of hit notes in ms: positive = late, negative = early. */
        val meanOffsetMs: Int,
    ) {
        val accuracy: Int get() = if (total == 0) 0 else (hits * 100 / total)
        /** Clean pass: (almost) everything hit and no wrong keys. */
        val isClean: Boolean get() = total > 0 && accuracy >= 95 && wrong == 0
    }

    enum class Verdict { HIT, WRONG, IGNORED }

    private var expected: List<MidiNote> = emptyList()
    private var hit = BooleanArray(0)
    private var wrong = 0
    private var offsetSum = 0.0
    private var offsetCount = 0

    /** Timing of the last correct key in real ms (+ = late), and the expected note it matched. */
    var lastOffsetMs = 0.0
        private set
    var lastNote: MidiNote? = null
        private set

    /** Starts a new pass over [notes] (already filtered to the practised hand and range). */
    fun startPass(notes: List<MidiNote>) {
        expected = notes.sortedBy { it.startSec }
        hit = BooleanArray(expected.size)
        wrong = 0
        offsetSum = 0.0
        offsetCount = 0
    }

    /**
     * A key was pressed at song position [songSec]. [speedPercent] converts the
     * real-time tolerance into song time.
     */
    fun onKey(pitch: Int, songSec: Double, speedPercent: Int, toleranceSec: Double = 0.3): Verdict {
        if (expected.isEmpty()) return Verdict.IGNORED
        val tol = toleranceSec * speedPercent / 100.0
        var best = -1
        var bestDelta = Double.MAX_VALUE
        // Expected notes are sorted; scan the small window around songSec.
        var i = lowerBound(songSec - tol)
        while (i < expected.size && expected[i].startSec <= songSec + tol) {
            if (!hit[i] && expected[i].pitch == pitch) {
                val d = abs(expected[i].startSec - songSec)
                if (d < bestDelta) {
                    bestDelta = d
                    best = i
                }
            }
            i++
        }
        if (best >= 0) {
            hit[best] = true
            val off = (songSec - expected[best].startSec) * 100.0 / speedPercent
            offsetSum += off
            offsetCount++
            lastOffsetMs = off * 1000
            lastNote = expected[best]
            return Verdict.HIT
        }
        wrong++
        return Verdict.WRONG
    }

    /** Wait mode: the learner pressed a key that is not in the current chord. */
    fun countWrong() {
        wrong++
    }

    /** Wait mode: every note of [chord] was played. */
    fun markChordDone(chord: List<MidiNote>) {
        for (n in chord) {
            val i = expected.indexOf(n)
            if (i >= 0) hit[i] = true
        }
    }

    /** Expected notes up to [upToSec] that were not played (for the hot spots). */
    fun missed(upToSec: Double): List<MidiNote> =
        expected.indices.filter { expected[it].startSec <= upToSec && !hit[it] }.map { expected[it] }

    /** Ends the pass at song position [upToSec]; notes after it are not counted. */
    fun finishPass(upToSec: Double): PassResult {
        var total = 0
        var hits = 0
        for ((i, n) in expected.withIndex()) {
            if (n.startSec > upToSec) break
            total++
            if (hit[i]) hits++
        }
        val mean = if (offsetCount == 0) 0 else (offsetSum / offsetCount * 1000).toInt()
        return PassResult(total, hits, wrong, mean)
    }

    private fun lowerBound(sec: Double): Int {
        var lo = 0
        var hi = expected.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (expected[mid].startSec < sec) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

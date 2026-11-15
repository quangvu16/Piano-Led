package com.ledpiano.app.midi

/** Which hand plays a note (or the fallback classification). */
enum class Hand { LEFT, RIGHT, UNKNOWN }

/**
 * One note extracted from a Standard MIDI File.
 * Tick times are converted to seconds by [MidiSong.tickToSec].
 */
data class MidiNote(
    val startTick: Long,
    val endTick: Long,
    val pitch: Int,
    val velocity: Int,
    val track: Int,
    val channel: Int,
) {
    /** Seconds from song start; filled in by [MidiSong.finalizeTiming]. */
    var startSec: Double = 0.0
    var endSec: Double = 0.0

    /** Filled in by [HandAssigner]. */
    var hand: Hand = Hand.UNKNOWN

    /** Finger number 1–5 from the score (MusicXML), 0 = none. */
    var finger: Int = 0

    val isBlackKey: Boolean
        get() = when (pitch % 12) {
            1, 3, 6, 8, 10 -> true
            else -> false
        }
}

data class TempoChange(val tick: Long, val usPerQuarter: Long)

data class TimeSignature(val tick: Long, val numerator: Int, val denominator: Int) {
    /** Length of one measure expressed in ticks (given the PPQ of the file). */
    fun measureTicks(ppq: Int): Long =
        numerator * 4L * ppq / denominator
}

/**
 * Fully parsed MIDI song, ready for playback and rendering.
 */
class MidiSong(
    val ppq: Int,
    val notes: List<MidiNote>,
    val tempoChanges: List<TempoChange>,
    val timeSignatures: List<TimeSignature>,
    val trackNames: Map<Int, String>,
    val trackCount: Int,
    val title: String,
) {
    val durationSec: Double
    val isMultiInstrument: Boolean

    init {
        finalizeTiming()
        durationSec = notes.maxOfOrNull { it.endSec } ?: 0.0
        // More than two sounding tracks usually means multiple instruments/pianos.
        isMultiInstrument = notes.map { it.track }.distinct().size > 2
    }

    /** Convert an absolute tick position to seconds using the tempo map. */
    fun tickToSec(tick: Long): Double {
        if (tempoChanges.isEmpty()) {
            return tick * 500_000.0 / ppq / 1_000_000.0
        }
        var sec = 0.0
        var lastTick = 0L
        var usPerQuarter = 500_000L
        for (tc in tempoChanges) {
            if (tc.tick >= tick) break
            if (tc.tick > lastTick) {
                sec += (tc.tick - lastTick) * usPerQuarter / ppq / 1_000_000.0
                lastTick = tc.tick
            }
            usPerQuarter = tc.usPerQuarter
        }
        sec += (tick - lastTick) * usPerQuarter / ppq / 1_000_000.0
        return sec
    }

    /** Seconds from song start of one measure at [measureIndex] (0-based). */
    fun measureStartSec(measureIndex: Int, defaultTs: TimeSignature): Double {
        val ts = timeSignatures.lastOrNull { it.tick <= measureTickStart(measureIndex, defaultTs) } ?: defaultTs
        return tickToSec(measureTickStart(measureIndex, defaultTs)).let { ts.let { _ -> it } }
    }

    private fun measureTickStart(measureIndex: Int, defaultTs: TimeSignature): Long {
        // Simplified: assumes a constant time signature for measure grid math.
        return measureIndex.toLong() * defaultTs.measureTicks(ppq)
    }

    /** The measure grid in ticks, computed with the time-signature changes in effect. */
    fun measureGridTicks(): List<Long> {
        val result = ArrayList<Long>()
        if (timeSignatures.isEmpty()) {
            val step = 4L * ppq
            var t = 0L
            while (t < (durationSec * 1_000_000)) { // generous upper bound; trimmed by seconds below
                result.add(t)
                t += step
            }
            return result
        }
        val maxTick = notes.maxOfOrNull { it.endTick } ?: 0L
        var tsIndex = 0
        var tick = timeSignatures[0].tick
        var guard = 0
        while (tick <= maxTick && guard++ < 100_000) {
            result.add(tick)
            var ts = timeSignatures[tsIndex]
            // Advance to the next time signature if it starts before the end of this measure.
            val nextTs = timeSignatures.getOrNull(tsIndex + 1)
            val measureLen = ts.measureTicks(ppq)
            if (nextTs != null && nextTs.tick < tick + measureLen) {
                tick = nextTs.tick
                tsIndex++
            } else {
                tick += measureLen
            }
        }
        return result
    }

    private fun finalizeTiming() {
        for (n in notes) {
            n.startSec = tickToSec(n.startTick)
            n.endSec = tickToSec(n.endTick)
        }
    }

    /** Notes sorted by start time — the order the player consumes them. */
    val notesByTime: List<MidiNote> by lazy { notes.sortedBy { it.startSec } }

    companion object {
        const val PIANO_LOWEST = 21   // A0
        const val PIANO_HIGHEST = 108 // C8
        const val MIDDLE_C = 60
    }
}

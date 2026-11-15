package com.ledpiano.app.playback

import com.ledpiano.app.midi.MidiSong

/**
 * Beat positions (in song seconds) for the metronome and the count-in,
 * built from the song's measure grid and time signatures.
 */
class BeatGrid private constructor(
    val beatSecs: DoubleArray,
    val downbeat: BooleanArray,
    /** Beats per measure at the start of the song (count-in length). */
    val beatsPerMeasure: Int,
) {
    val size: Int get() = beatSecs.size

    /** Index of the first beat at or after [sec]. */
    fun lowerBound(sec: Double): Int {
        var lo = 0
        var hi = beatSecs.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (beatSecs[mid] < sec) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Length of one beat (song seconds) around [sec]; 0.5 s if unknown. */
    fun beatLengthAt(sec: Double): Double {
        if (beatSecs.size < 2) return 0.5
        val i = lowerBound(sec).coerceIn(1, beatSecs.size - 1)
        return (beatSecs[i] - beatSecs[i - 1]).takeIf { it > 0.05 } ?: 0.5
    }

    companion object {
        fun from(song: MidiSong): BeatGrid {
            val measures = song.measureGridTicks()
            val secs = ArrayList<Double>()
            val down = ArrayList<Boolean>()
            val tsList = song.timeSignatures
            for ((i, m) in measures.withIndex()) {
                val ts = tsList.lastOrNull { it.tick <= m } ?: tsList.firstOrNull()
                val num = ts?.numerator?.coerceIn(1, 16) ?: 4
                val den = ts?.denominator?.coerceIn(1, 32) ?: 4
                val beatTicks = (song.ppq * 4L / den).coerceAtLeast(1)
                val next = measures.getOrNull(i + 1) ?: (m + beatTicks * num)
                for (k in 0 until num) {
                    val t = m + k * beatTicks
                    if (t >= next) break
                    val s = song.tickToSec(t)
                    if (s > song.durationSec + 0.01) break
                    secs.add(s)
                    down.add(k == 0)
                }
            }
            val first = tsList.firstOrNull()?.numerator?.coerceIn(2, 8) ?: 4
            return BeatGrid(secs.toDoubleArray(), down.toBooleanArray(), first)
        }
    }
}

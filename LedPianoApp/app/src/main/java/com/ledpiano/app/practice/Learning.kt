package com.ledpiano.app.practice

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Learning helpers for the practice screen (same rules as learning.py in the Python app and
 * Learning.swift on iPhone/Mac):
 *
 *  - [HotSpots]: in which bar mistakes happen ("Luyện chỗ khó"). Practising the exact hard spot
 *    slowly and repeating it until the mistake stops is what expert teachers do (Duke, Simmons &
 *    Cash 2009).
 *  - [timingFlash]: green flash for a key played on time, orange for early/late.
 *  - [dynScale] / [Dynamics]: LED brightness from loudness, and "play softer/louder" advice.
 *  - [Recorder] / [midiFile]: record what the learner plays as a normal MIDI file.
 *  - [dailyPlan]: today's 3 short tasks (warm-up, review an older song, one new lesson).
 */
object Learning {

    // ---- hot spots ----

    /** The hard bar starts at 65 % speed (auto speed-up adds 10 % after 3 clean loops). */
    const val HOTSPOT_SPEED = 65

    /** Index of the bar that contains [sec] ([measures] = bar start times). */
    fun barOf(measures: List<Double>, sec: Double): Int {
        var lo = 0
        var hi = measures.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (measures[mid] <= sec + 1e-6) lo = mid + 1 else hi = mid
        }
        return maxOf(0, lo - 1)
    }

    /** Loop range for one bar (two bars when one bar is shorter than 1.5 s). */
    fun loopForBar(measures: List<Double>, duration: Double, bar: Int): Pair<Double, Double> {
        val a = measures.getOrNull(bar) ?: 0.0
        var b = measures.getOrNull(bar + 1) ?: duration
        if (b - a < 1.5 && bar + 2 < measures.size) b = measures[bar + 2]
        return a to minOf(b, duration)
    }

    /** Mistakes per bar per song, kept on the phone. */
    class HotSpots(context: Context) {
        private val prefs = context.applicationContext.getSharedPreferences("hot_spots", Context.MODE_PRIVATE)

        private fun bars(key: String): JSONObject = try {
            JSONObject(prefs.getString(key, "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }

        fun addMiss(key: String, bar: Int, n: Int = 1) {
            val b = bars(key)
            b.put(bar.toString(), b.optInt(bar.toString(), 0) + n)
            prefs.edit().putString(key, b.toString()).apply()
        }

        /** A clean play of the bar lowers its count; at 0 it is no longer a hot spot. */
        fun addClean(key: String, bar: Int) {
            val b = bars(key)
            val k = bar.toString()
            if (!b.has(k)) return
            val v = b.optInt(k, 0) - 1
            if (v <= 0) b.remove(k) else b.put(k, v)
            prefs.edit().putString(key, b.toString()).apply()
        }

        fun counts(key: String): Map<Int, Int> {
            val b = bars(key)
            val out = HashMap<Int, Int>()
            for (k in b.keys()) {
                val bar = k.toIntOrNull() ?: continue
                val v = b.optInt(k, 0)
                if (v > 0) out[bar] = v
            }
            return out
        }

        /** The bar with the most mistakes (the earlier one on a tie), or null. */
        fun worst(key: String): Int? =
            counts(key).entries.maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key })?.key
    }

    // ---- feedback colours ----

    const val RED = 0xFF0000
    const val GREEN = 0x00D040
    const val ORANGE = 0xFF8C00
    const val GOOD_MS = 90

    /** Green when within ±90 ms of the beat, otherwise orange (early or late). */
    fun timingFlash(offsetMs: Double): Int = if (abs(offsetMs) <= GOOD_MS) GREEN else ORANGE

    // ---- dynamics ----

    /** LED brightness for a note's loudness: soft 30 % … loud 100 %. */
    fun dynScale(velocity: Int): Float = 0.3f + 0.7f * velocity.coerceIn(0, 127) / 127f

    /** Compares how hard the learner plays (MIDI velocity) with the song. */
    class Dynamics {
        private val diffs = ArrayList<Int>()
        fun reset() = diffs.clear()
        fun add(expected: Int, played: Int) {
            if (played > 0 && expected > 0) diffs.add(played - expected)
        }

        /** "softer" / "louder" when the learner is consistently off, else null. */
        fun advice(): String? {
            if (diffs.size < MIN_NOTES) return null
            val mean = diffs.sum().toDouble() / diffs.size
            return when {
                mean > LIMIT -> "softer"
                mean < -LIMIT -> "louder"
                else -> null
            }
        }

        companion object {
            const val MIN_NOTES = 6
            const val LIMIT = 20
        }
    }

    val ADVICE_VI = mapOf("softer" to "Chơi nhẹ tay hơn một chút", "louder" to "Chơi mạnh tay hơn một chút")

    // ---- recording ----

    /** Records note on/off (seconds since start) and writes a standard MIDI file. */
    class Recorder {
        data class Event(val sec: Double, val pitch: Int, val velocity: Int) // velocity 0 = released

        val events = ArrayList<Event>()
        var active = false
            private set
        private var t0 = 0L

        fun start() {
            events.clear()
            active = true
            t0 = System.nanoTime()
        }

        fun note(pitch: Int, velocity: Int) {
            if (active) events.add(Event(maxOf(0.0, (System.nanoTime() - t0) / 1e9), pitch, velocity))
        }

        /** Stops; returns the number of notes played. */
        fun stop(): Int {
            active = false
            return events.count { it.velocity > 0 }
        }

        fun midiBytes(bpm: Int = 120): ByteArray = midiFile(events, bpm)
    }

    /** Type-0 standard MIDI file (one track, piano) from recorded events. */
    fun midiFile(events: List<Recorder.Event>, bpm: Int = 120, ppq: Int = 480): ByteArray {
        val us = 60_000_000 / bpm.coerceIn(20, 300)
        val trk = ByteArrayOutputStream()
        fun vlq(value: Int) {
            var n = value
            val buf = ArrayList<Int>()
            buf.add(n and 0x7F)
            n = n shr 7
            while (n != 0) {
                buf.add(0, (n and 0x7F) or 0x80)
                n = n shr 7
            }
            for (b in buf) trk.write(b)
        }
        fun bytes(vararg b: Int) = b.forEach { trk.write(it) }
        vlq(0); bytes(0xFF, 0x51, 3, us shr 16 and 0xFF, us shr 8 and 0xFF, us and 0xFF)
        vlq(0); bytes(0xFF, 0x58, 4, 4, 2, 24, 8)
        var last = 0
        val open = HashSet<Int>()
        for (e in events.sortedWith(compareBy<Recorder.Event>({ it.sec }, { it.velocity == 0 }))) {
            val tick = (e.sec * ppq * 1_000_000 / us).roundToInt()
            if (e.velocity == 0 && e.pitch !in open) continue
            vlq(maxOf(0, tick - last))
            last = maxOf(last, tick)
            if (e.velocity > 0) {
                open.add(e.pitch)
                bytes(0x90, e.pitch and 0x7F, minOf(127, e.velocity))
            } else {
                open.remove(e.pitch)
                bytes(0x80, e.pitch and 0x7F, 0)
            }
        }
        for (p in open.sorted()) { // close anything still held
            vlq(ppq / 2)
            bytes(0x80, p and 0x7F, 0)
        }
        vlq(0); bytes(0xFF, 0x2F, 0)
        val t = trk.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray())
        out.write(byteArrayOf(0, 0, 0, 6, 0, 0, 0, 1, (ppq shr 8).toByte(), ppq.toByte()))
        out.write("MTrk".toByteArray())
        out.write(byteArrayOf((t.size shr 24).toByte(), (t.size shr 16).toByte(), (t.size shr 8).toByte(), t.size.toByte()))
        out.write(t)
        return out.toByteArray()
    }

    // ---- daily plan ----

    enum class PlanKind(val vi: String) { WARMUP("Khởi động"), REVIEW("Ôn lại"), NEW("Bài mới") }

    /**
     * Today's plan from the lesson path: a warm-up (Hanon if there is one), the practised song not
     * played for the longest time (spacing practice out helps it stick), and the first lesson not
     * finished yet. [lastDay] / [done] read the practice history.
     */
    fun dailyPlan(
        lessons: List<Lessons.Lesson>,
        today: Long,
        lastDay: (String) -> Long?,
        done: (String) -> Boolean,
    ): List<Pair<PlanKind, Lessons.Lesson>> {
        val out = ArrayList<Pair<PlanKind, Lessons.Lesson>>()
        val warm = lessons.firstOrNull { it.title.lowercase().contains("hanon") } ?: lessons.firstOrNull()
        if (warm != null) out.add(PlanKind.WARMUP to warm)
        val practised = lessons.filter { it !== warm && (lastDay(it.uri) ?: Long.MAX_VALUE) < today }
        practised.minByOrNull { lastDay(it.uri) ?: Long.MAX_VALUE }?.let { out.add(PlanKind.REVIEW to it) }
        val new = lessons.firstOrNull { it !== warm && !done(it.uri) }
        if (new != null && out.none { it.second === new }) out.add(PlanKind.NEW to new)
        return out
    }
}

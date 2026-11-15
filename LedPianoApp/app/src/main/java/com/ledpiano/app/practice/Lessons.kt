package com.ledpiano.app.practice

import android.content.Context
import com.ledpiano.app.midi.Hand
import com.ledpiano.app.midi.MidiSong
import com.ledpiano.app.util.MidiIo
import org.json.JSONObject

/**
 * The guided lesson path (levels 1–5) bundled in `assets/lessons/lessons.json`.
 *
 * Lesson songs may carry finger numbers: one list per hand, in the order that
 * hand's notes start (ties broken by pitch).
 */
object Lessons {

    data class Lesson(
        val uri: String,
        val title: String,
        val subtitle: String,
        val level: Int,
        val hands: String, // "R", "L" or "LR"
        val fingersRight: IntArray?,
        val fingersLeft: IntArray?,
    )

    data class Level(val number: Int, val title: String, val lessons: List<Lesson>)

    @Volatile private var cache: List<Level>? = null

    fun levels(context: Context): List<Level> {
        cache?.let { return it }
        val result = try {
            val text = context.assets.open("lessons/lessons.json").bufferedReader().use { it.readText() }
            parse(JSONObject(text))
        } catch (_: Exception) {
            emptyList()
        }
        cache = result
        return result
    }

    fun find(context: Context, uri: String): Lesson? =
        levels(context).asSequence().flatMap { it.lessons.asSequence() }.firstOrNull { it.uri == uri }

    /** The lesson after [uri] in the path, if any. */
    fun next(context: Context, uri: String): Lesson? {
        val all = levels(context).flatMap { it.lessons }
        val i = all.indexOfFirst { it.uri == uri }
        return if (i >= 0) all.getOrNull(i + 1) else null
    }

    /**
     * Finger number for every note of [song] (keyed by note index in
     * [MidiSong.notes]), or empty when the lesson has no fingering.
     */
    fun fingering(lesson: Lesson, song: MidiSong): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        fun apply(hand: Hand, fingers: IntArray?) {
            if (fingers == null) return
            val idx = song.notes.indices
                .filter { song.notes[it].hand == hand }
                .sortedWith(compareBy({ song.notes[it].startTick }, { song.notes[it].pitch }))
            for ((k, noteIndex) in idx.withIndex()) {
                if (k >= fingers.size) break
                val f = fingers[k]
                if (f in 1..5) out[noteIndex] = f
            }
        }
        apply(Hand.RIGHT, lesson.fingersRight)
        apply(Hand.LEFT, lesson.fingersLeft)
        return out
    }

    private fun parse(root: JSONObject): List<Level> {
        val levels = ArrayList<Level>()
        val arr = root.getJSONArray("levels")
        for (i in 0 until arr.length()) {
            val lv = arr.getJSONObject(i)
            val number = lv.getInt("level")
            val songs = lv.getJSONArray("songs")
            val lessons = ArrayList<Lesson>()
            for (j in 0 until songs.length()) {
                val s = songs.getJSONObject(j)
                val fingers = s.optJSONObject("fingers")
                lessons.add(
                    Lesson(
                        uri = MidiIo.ASSET_SCHEME + s.getString("file"),
                        title = s.getString("title"),
                        subtitle = s.optString("subtitle", ""),
                        level = number,
                        hands = s.optString("hands", "LR"),
                        fingersRight = fingers?.optJSONArray("R")?.let { a -> IntArray(a.length()) { a.getInt(it) } },
                        fingersLeft = fingers?.optJSONArray("L")?.let { a -> IntArray(a.length()) { a.getInt(it) } },
                    ),
                )
            }
            levels.add(Level(number, lv.getString("title"), lessons))
        }
        return levels
    }
}

package com.ledpiano.app.practice

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.time.LocalDate

/**
 * Practice history kept on the phone: minutes per day (for the streak and the
 * weekly summary) and per-song records (time, best accuracy, top speed).
 *
 * Streaks are forgiving: one missed day per week does not break them, because
 * a broken streak shown front and centre makes people give up
 * (Silverman & Barasch 2022).
 */
class PracticeStats(context: Context) {

    data class SongRecord(
        val seconds: Int,
        val bestAccuracy: Int,
        val maxSpeed: Int,
        val cleanLoops: Int,
        val completed: Boolean,
    )

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)


    // ---- days ----

    private fun days(): JSONObject = try {
        JSONObject(prefs.getString(KEY_DAYS, "{}") ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    fun secondsOn(epochDay: Long): Int = days().optInt(epochDay.toString(), 0)

    fun todaySeconds(): Int = secondsOn(today())

    /** Seconds practised on each of the last 7 days, oldest first (today last). */
    fun lastSevenDays(): IntArray {
        val d = days()
        val t = today()
        return IntArray(7) { i -> d.optInt((t - 6 + i).toString(), 0) }
    }

    /** Adds practice time to today and to the song. */
    fun addPractice(songKey: String, seconds: Int, speedPercent: Int) {
        if (seconds <= 0) return
        val d = days()
        val key = today().toString()
        d.put(key, d.optInt(key, 0) + seconds)
        // Keep ~90 days.
        val cutoff = today() - 90
        val old = ArrayList<String>()
        for (k in d.keys()) if ((k.toLongOrNull() ?: 0) < cutoff) old.add(k)
        for (k in old) d.remove(k)

        val s = song(songKey)
        s.put("sec", s.optInt("sec", 0) + seconds)
        s.put("speed", maxOf(s.optInt("speed", 0), speedPercent))
        s.put("last", today())
        prefs.edit()
            .putString(KEY_DAYS, d.toString())
            .putString(KEY_SONG_PREFIX + songKey, s.toString())
            .apply()
    }

    /**
     * Current streak in days. Today counts if practised; if not, the streak is
     * still alive from yesterday. One missed day per 7 is forgiven.
     */
    fun streakDays(): Int {
        val d = days()
        fun practised(day: Long) = d.optInt(day.toString(), 0) >= MIN_SECONDS_FOR_DAY
        var day = today()
        if (!practised(day)) day -= 1
        var streak = 0
        var lastForgiven = Long.MAX_VALUE
        while (true) {
            if (practised(day)) {
                streak++
            } else {
                val canForgive = streak > 0 && lastForgiven - day >= 7 && practised(day - 1)
                if (!canForgive) break
                lastForgiven = day
            }
            day -= 1
            if (streak > 3650) break
        }
        return streak
    }

    // ---- songs ----

    private fun song(key: String): JSONObject = try {
        JSONObject(prefs.getString(KEY_SONG_PREFIX + key, "{}") ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    fun record(songKey: String): SongRecord {
        val s = song(songKey)
        return SongRecord(
            seconds = s.optInt("sec", 0),
            bestAccuracy = s.optInt("best", -1),
            maxSpeed = s.optInt("speed", 0),
            cleanLoops = s.optInt("clean", 0),
            completed = s.optBoolean("done", false),
        )
    }

    /** Day (epoch day) the song was last practised, or null if never (for the daily plan's review). */
    fun lastDay(songKey: String): Long? = song(songKey).let { if (it.has("last")) it.optLong("last") else null }

    fun today(): Long = LocalDate.now().toEpochDay()

    fun recordResult(songKey: String, result: ScoreKeeper.PassResult, finishedSong: Boolean, speedPercent: Int) {
        val s = song(songKey)
        if (result.total > 0) s.put("best", maxOf(s.optInt("best", -1), result.accuracy))
        if (result.isClean) s.put("clean", s.optInt("clean", 0) + 1)
        if (finishedSong) {
            s.put("done", true)
            s.put("speed", maxOf(s.optInt("speed", 0), speedPercent))
        }
        prefs.edit().putString(KEY_SONG_PREFIX + songKey, s.toString()).apply()
    }

    /** Marks a song as played to the end (no keyboard connected, so no score). */
    fun markCompleted(songKey: String, speedPercent: Int) {
        val s = song(songKey)
        s.put("done", true)
        s.put("speed", maxOf(s.optInt("speed", 0), speedPercent))
        prefs.edit().putString(KEY_SONG_PREFIX + songKey, s.toString()).apply()
    }

    companion object {
        private const val PREFS = "practice_stats"
        private const val KEY_DAYS = "days"
        private const val KEY_SONG_PREFIX = "song:"
        /** One minute counts as a practice day. */
        const val MIN_SECONDS_FOR_DAY = 60
    }
}

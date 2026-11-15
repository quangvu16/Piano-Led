package com.ledpiano.app.voice

import java.text.Normalizer

/**
 * Finds the song the learner asked for by voice ("mở bài ngôi sao lấp lánh",
 * "play twinkle twinkle", "chúc mừng sinh nhật").
 *
 * Xiaozhi usually passes an exact title it got from `list_songs`, but speech
 * and the assistant can both garble a name, so matching is forgiving:
 * accents and case are ignored, filler words ("mở bài", "play") are dropped,
 * and each song is scored on its title and its subtitle (the English name).
 */
object SongMatcher {

    data class Song(val uri: String, val title: String, val subtitle: String = "")

    data class Match(val song: Song, val score: Double)

    /** Scores below this are "not found" (the tool then lists suggestions). */
    const val MIN_SCORE = 0.55

    private val FILLER = setOf(
        "mo", "bai", "hat", "choi", "phat", "cho", "toi", "minh", "em", "nhe", "di", "voi", "nhac", "ban", "nghe",
        "hay", "giup", "bat", "dau", "tap", "luyen", "song", "play", "open", "the", "a", "please", "start",
        "practice", "piece", "lesson",
    )

    /** Lower-case, no accents (đ → d), only letters/digits separated by single spaces. */
    fun normalize(text: String): String {
        val noMarks = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('đ', 'd').replace('Đ', 'D')
            .lowercase()
        return noMarks.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }

    private fun tokens(text: String, dropFiller: Boolean): List<String> {
        val t = normalize(text).split(' ').filter { it.isNotEmpty() }
        if (!dropFiller) return t
        val kept = t.filter { it !in FILLER }
        return kept.ifEmpty { t }
    }

    /** Best matches first; songs scoring 0 are left out. */
    fun rank(query: String, songs: List<Song>): List<Match> {
        val q = tokens(query, dropFiller = true)
        if (q.isEmpty()) return emptyList()
        return songs.map { s ->
            val title = tokens(s.title, false)
            val sub = tokens(s.subtitle, false)
            Match(s, maxOf(score(q, title), score(q, sub) * 0.95, score(q, title + sub) * 0.97))
        }.filter { it.score > 0 }.sortedByDescending { it.score }
    }

    /** The song meant by [query], or null when nothing is close enough. */
    fun best(query: String, songs: List<Song>): Song? =
        rank(query, songs).firstOrNull()?.takeIf { it.score >= MIN_SCORE }?.song

    /** True when the top two songs score almost the same: better to ask which one. */
    fun isAmbiguous(ranked: List<Match>): Boolean =
        ranked.size >= 2 && ranked[0].score - ranked[1].score < 0.02 && ranked[0].score < 0.999

    private fun score(q: List<String>, c: List<String>): Double {
        if (c.isEmpty()) return 0.0
        val qs = q.joinToString(" ")
        val cs = c.joinToString(" ")
        if (qs == cs) return 1.0
        // Word overlap, each query word may match a title word with a small typo.
        var hits = 0.0
        val used = BooleanArray(c.size)
        for (w in q) {
            var bestJ = -1
            var bestSim = 0.0
            for ((j, cw) in c.withIndex()) {
                if (used[j]) continue
                val sim = wordSim(w, cw)
                if (sim > bestSim) { bestSim = sim; bestJ = j }
            }
            if (bestJ >= 0 && bestSim >= 0.75) { used[bestJ] = true; hits += bestSim }
        }
        val precision = hits / q.size
        val recall = hits / c.size
        val f1 = if (hits == 0.0) 0.0 else 2 * precision * recall / (precision + recall)
        // Every spoken word is in the title ("canon", "minuet"): a short name is still a match.
        val covered = if (precision >= 0.999) 0.7 + 0.3 * recall else 0.0
        // Whole title said inside a longer sentence ("cho mình nghe ngôi sao lấp lánh nhé").
        val contained = if (cs.length >= 4 && (" $qs ").contains(" $cs ")) 0.97 else 0.0
        val chars = 1.0 - levenshtein(qs, cs).toDouble() / maxOf(qs.length, cs.length)
        return maxOf(f1, covered, contained, chars * 0.9)
    }

    private fun wordSim(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.length < 3 || b.length < 3) return 0.0
        return 1.0 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length)
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}

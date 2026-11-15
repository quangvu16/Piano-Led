package com.ledpiano.app.midi

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt

/**
 * MusicXML import (.musicxml / .xml, and compressed .mxl) – e.g. songs exported from MuseScore.
 *
 * Unlike MIDI, MusicXML keeps which hand plays each note (staff 1 = right, staff 2 = left), the
 * finger numbers and the bars. Same rules as musicxml.py (Python) and MusicXml.swift (iPhone/Mac).
 *
 * Handles: score-partwise, divisions changes, chords, backup/forward, ties, rests, grace notes
 * (skipped), sound tempo, time signatures, fingering, 1 part with 2 staves or 2 separate parts.
 */
object MusicXml {

    const val PPQ = 480
    private val STEP = mapOf("C" to 0, "D" to 2, "E" to 4, "F" to 5, "G" to 7, "A" to 9, "B" to 11)

    fun isMusicXml(data: ByteArray): Boolean {
        if (data.size >= 2 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte()) return true
        val head = String(data, 0, minOf(data.size, 400), Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return head.startsWith("<") && (head.contains("score-partwise") || head.contains("score-timewise") || head.startsWith("<?xml"))
    }

    fun parse(input: InputStream, title: String?): MidiSong = parse(input.readBytes(), title)

    fun parse(data: ByteArray, title: String?): MidiSong {
        val root = readRoot(data)
        if (root.tagName == "score-timewise") {
            throw IllegalArgumentException("MusicXML dạng 'timewise' chưa được hỗ trợ — hãy xuất lại dạng thường (partwise)")
        }
        val parts = children(root, "part")
        if (parts.isEmpty()) throw IllegalArgumentException("Tệp MusicXML không có phần nhạc nào")
        val name = title?.takeIf { it.isNotBlank() }
            ?: text(root, "work/work-title") ?: text(root, "movement-title") ?: "MusicXML"
        val notes = ArrayList<MidiNote>()
        val tempos = ArrayList<TempoChange>()
        val sigs = ArrayList<TimeSignature>()
        val twoParts = parts.size == 2

        for ((pi, part) in parts.withIndex()) {
            var divisions = 1
            var tick = 0L
            var lastStart = 0L
            var staves = 1
            val openTies = HashMap<Pair<Int, Int>, Int>() // (pitch, staff) → index in notes
            val ends = HashMap<Int, Long>()                // tie-extended end ticks
            for (measure in children(part, "measure")) {
                val mStart = tick
                for (el in children(measure, null)) {
                    when (el.tagName) {
                        "attributes" -> {
                            divisions = text(el, "divisions")?.toIntOrNull()?.takeIf { it > 0 } ?: divisions
                            staves = text(el, "staves")?.toIntOrNull() ?: staves
                            val t = child(el, "time")
                            if (t != null && pi == 0) {
                                val beats = text(t, "beats")?.split("+")?.firstOrNull()?.trim()?.toIntOrNull()
                                val type = text(t, "beat-type")?.toIntOrNull()
                                if (beats != null && type != null && beats > 0 && type > 0) sigs.add(TimeSignature(mStart, beats, type))
                            }
                        }
                        "direction", "sound" -> {
                            val snd = if (el.tagName == "sound") el else child(el, "sound")
                            val bpm = snd?.getAttribute("tempo")?.toDoubleOrNull()
                            if (bpm != null && bpm > 0 && pi == 0) tempos.add(TempoChange(tick, (60_000_000 / bpm).toLong()))
                        }
                        "backup" -> tick -= (text(el, "duration")?.toLongOrNull() ?: 0L) * PPQ / divisions
                        "forward" -> tick += (text(el, "duration")?.toLongOrNull() ?: 0L) * PPQ / divisions
                        "note" -> {
                            if (child(el, "grace") != null) continue
                            val dur = (text(el, "duration")?.toLongOrNull() ?: 0L) * PPQ / divisions
                            val chord = child(el, "chord") != null
                            val start = if (chord) lastStart else tick
                            if (!chord) {
                                lastStart = tick
                                tick += dur
                            }
                            if (child(el, "rest") != null || child(el, "pitch") == null) continue
                            val octave = text(el, "pitch/octave")?.toIntOrNull() ?: 4
                            val step = STEP[text(el, "pitch/step") ?: "C"] ?: 0
                            val alter = text(el, "pitch/alter")?.toDoubleOrNull()?.roundToInt() ?: 0
                            val pitch = (octave + 1) * 12 + step + alter
                            val staff = text(el, "staff")?.toIntOrNull() ?: 1
                            val hand = when {
                                twoParts -> if (pi == 0) Hand.RIGHT else Hand.LEFT
                                staves >= 2 -> if (staff == 1) Hand.RIGHT else Hand.LEFT
                                else -> Hand.UNKNOWN
                            }
                            val ties = children(el, "tie").map { it.getAttribute("type") }.toSet()
                            val key = pitch to pi * 10 + staff
                            val prev = openTies[key]
                            if ("stop" in ties && prev != null) {
                                ends[prev] = start + dur
                                if ("start" !in ties) openTies.remove(key)
                                continue
                            }
                            var vel = 80
                            el.getAttribute("dynamics").toDoubleOrNull()?.let { vel = (it * 90 / 100).toInt().coerceIn(1, 127) }
                            val n = MidiNote(start, start + maxOf(dur, 1L), pitch, vel, pi, if (hand == Hand.LEFT) 1 else 0)
                            n.hand = hand
                            text(el, "notations/technical/fingering")?.firstOrNull()?.digitToIntOrNull()?.let {
                                if (it in 1..5) n.finger = it
                            }
                            if ("start" in ties) openTies[key] = notes.size
                            notes.add(n)
                        }
                    }
                }
            }
            // Ties: one longer note instead of several (MidiNote is immutable, so copy).
            for ((i, end) in ends) {
                val o = notes[i]
                notes[i] = o.copy(endTick = end).also { it.hand = o.hand; it.finger = o.finger }
            }
        }
        if (notes.isEmpty()) throw IllegalArgumentException("Tệp MusicXML không có nốt nhạc nào")
        tempos.sortBy { it.tick }
        if (tempos.isEmpty() || tempos[0].tick > 0) tempos.add(0, TempoChange(0, 500_000))
        val ts = sigs.distinctBy { it.tick }.sortedBy { it.tick }.toMutableList()
        if (ts.isEmpty() || ts[0].tick > 0) ts.add(0, TimeSignature(0, 4, 4))
        notes.sortWith(compareBy({ it.startTick }, { it.pitch }))
        val tracks = notes.map { it.track }.distinct().size
        return MidiSong(PPQ, notes, tempos, ts, emptyMap(), tracks, name)
    }

    // ---- XML helpers ----

    private fun readRoot(raw: ByteArray): Element {
        var data = raw
        if (data.size >= 2 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte()) data = unzip(data)
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        f.isValidating = false
        // Never fetch the DTD from the internet (MusicXML files name one).
        try {
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        } catch (_: Exception) {
        }
        val b = f.newDocumentBuilder()
        b.setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
        return b.parse(ByteArrayInputStream(data)).documentElement
    }

    /** .mxl: the score named by META-INF/container.xml (or the first .xml / .musicxml file). */
    private fun unzip(data: ByteArray): ByteArray {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(data)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) files[e.name] = z.readBytes()
            }
        }
        val container = files["META-INF/container.xml"]
        val path = container?.let {
            Regex("full-path\\s*=\\s*\"([^\"]+)\"").find(String(it, Charsets.UTF_8))?.groupValues?.get(1)
        } ?: files.keys.firstOrNull { !it.startsWith("META-INF") && (it.endsWith(".xml", true) || it.endsWith(".musicxml", true)) }
        return files[path] ?: throw IllegalArgumentException("Tệp .mxl không có bản nhạc")
    }

    private fun children(el: Element, tag: String?): List<Element> {
        val out = ArrayList<Element>()
        val list = el.childNodes
        for (i in 0 until list.length) {
            val n = list.item(i)
            if (n is Element && (tag == null || n.tagName == tag)) out.add(n)
        }
        return out
    }

    private fun child(el: Element, tag: String): Element? = children(el, tag).firstOrNull()

    /** Text of a child path like "pitch/step", or null. */
    private fun text(el: Element, path: String): String? {
        var cur: Element = el
        for (part in path.split('/')) cur = child(cur, part) ?: return null
        return cur.textContent?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/** Opens a song file of any supported kind (MIDI, MusicXML) and assigns hands. */
object SongFiles {
    private val fromScore = java.util.Collections.newSetFromMap(java.util.WeakHashMap<MidiSong, Boolean>())
    private val xmlByPitch = java.util.Collections.newSetFromMap(java.util.WeakHashMap<MidiSong, Boolean>())

    fun parse(input: InputStream, name: String, splitPitch: Int): MidiSong {
        val data = input.readBytes()
        if (MusicXml.isMusicXml(data)) {
            val song = MusicXml.parse(data, name.substringBeforeLast('.').takeIf { it.isNotBlank() })
            if (song.notes.all { it.hand != Hand.UNKNOWN }) fromScore.add(song) else { xmlByPitch.add(song); splitUnknown(song, splitPitch) }
            return song
        }
        return MidiParser.parse(data, name).also { HandAssigner.assign(it, splitPitch) }
    }

    /** One-staff score: split the hands by pitch like a one-track MIDI file. */
    private fun splitUnknown(song: MidiSong, splitPitch: Int) {
        for (n in song.notes) if (n.hand == Hand.UNKNOWN) n.hand = if (n.pitch < splitPitch) Hand.LEFT else Hand.RIGHT
    }

    /** True when the hands come from the score (MusicXML staves): the split setting does not apply. */
    fun handsFromScore(song: MidiSong): Boolean = song in fromScore

    /** Re-applies the hand split (Options) unless the score already says which hand plays. */
    fun reassign(song: MidiSong, splitPitch: Int) {
        if (handsFromScore(song)) return
        if (song in xmlByPitch) {
            for (n in song.notes) n.hand = if (n.pitch < splitPitch) Hand.LEFT else Hand.RIGHT
        } else {
            HandAssigner.assign(song, splitPitch)
        }
    }
}

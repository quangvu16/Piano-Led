package com.ledpiano.app.midi

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Parser for Standard MIDI Files (format 0 and 1).
 *
 * Produces a [MidiSong] with all note on/off events paired into [MidiNote]s,
 * the tempo map, time signatures and track names.
 */
object MidiParser {

    class MidiParseException(message: String) : IOException(message)

    fun parse(input: InputStream, title: String): MidiSong {
        val bytes = input.readBytes()
        return parse(bytes, title)
    }

    fun parse(data: ByteArray, title: String): MidiSong {
        val r = Reader(data)

        // ---- Header chunk ----
        val magic = r.readAscii(4)
        if (magic != "MThd") throw MidiParseException("Không phải tệp MIDI hợp lệ (thiếu MThd)")
        val headerLen = r.readU32()
        if (headerLen < 6) throw MidiParseException("Header MIDI không hợp lệ")
        val format = r.readU16()
        val ntrks = r.readU16()
        val division = r.readU16()
        if (division and 0x8000 != 0) {
            throw MidiParseException("MIDI dùng định dạng SMPTE không được hỗ trợ")
        }
        val ppq = division
        if (ppq <= 0) throw MidiParseException("PPQ không hợp lệ")
        // Skip any extra header bytes.
        if (headerLen > 6) r.skip(headerLen - 6)

        val tempoChanges = ArrayList<TempoChange>()
        val timeSignatures = ArrayList<TimeSignature>()
        val trackNames = HashMap<Int, String>()
        val notes = ArrayList<MidiNote>()

        for (trackIndex in 0 until ntrks) {
            if (r.remaining < 8) break
            val chunkId = r.readAscii(4)
            val chunkLen = r.readU32()
            if (chunkId != "MTrk") {
                // Unknown chunk – skip it.
                r.skip(chunkLen)
                continue
            }
            parseTrack(r, chunkLen, trackIndex, ppq, notes, tempoChanges, timeSignatures, trackNames)
        }

        if (notes.isEmpty()) throw MidiParseException("Tệp MIDI không có nốt nhạc nào")

        // Tempo map must be ordered and start at tick 0 for correct conversion.
        tempoChanges.sortBy { it.tick }
        if (tempoChanges.isEmpty() || tempoChanges.first().tick > 0) {
            tempoChanges.add(0, TempoChange(0, 500_000))
        }
        timeSignatures.sortBy { it.tick }
        if (timeSignatures.isEmpty()) {
            timeSignatures.add(TimeSignature(0, 4, 4))
        }

        return MidiSong(
            ppq = ppq,
            notes = notes,
            tempoChanges = tempoChanges,
            timeSignatures = timeSignatures,
            trackNames = trackNames,
            trackCount = ntrks,
            title = title,
        )
    }

    private fun parseTrack(
        r: Reader,
        chunkLen: Long,
        trackIndex: Int,
        ppq: Int,
        outNotes: MutableList<MidiNote>,
        tempoChanges: MutableList<TempoChange>,
        timeSignatures: MutableList<TimeSignature>,
        trackNames: MutableMap<Int, String>,
    ) {
        val end = r.position + chunkLen
        var tick = 0L
        var runningStatus: Int = -1
        // (key/channel) -> stack of open notes for this track.
        val open = HashMap<Pair<Int, Int>, ArrayDeque<MidiNote>>()

        while (r.position < end) {
            val delta = r.readVlq()
            tick += delta
            if (r.position >= end) break

            var status = r.peek().toInt() and 0xFF
            if (status < 0x80) {
                if (runningStatus < 0) throw MidiParseException("Lỗi running status")
                status = runningStatus
            } else {
                r.skip(1)
                if (status < 0xF0) {
                    runningStatus = status
                } else if (status != 0xF7 && status != 0xFF) {
                    // Meta events (0xFF) keep running status, per the SMF spec.
                    runningStatus = -1
                }
            }

            when {
                status == 0xFF -> { // Meta
                    val type = r.readU8()
                    val len = r.readVlq()
                    val payload = r.readBytes(len.toInt())
                    when (type) {
                        0x03 -> trackNames[trackIndex] = payload.toString(Charsets.UTF_8).trim()
                        0x51 -> {
                            if (payload.size >= 3) {
                                val us = ((payload[0].toInt() and 0xFF) shl 16) or
                                    ((payload[1].toInt() and 0xFF) shl 8) or
                                    (payload[2].toInt() and 0xFF)
                                tempoChanges.add(TempoChange(tick, us.toLong()))
                            }
                        }
                        0x58 -> {
                            if (payload.size >= 2) {
                                val denom = 1 shl (payload[1].toInt() and 0xFF)
                                timeSignatures.add(TimeSignature(tick, payload[0].toInt() and 0xFF, denom))
                            }
                        }
                        0x2F -> return // End of track
                    }
                }
                status == 0xF0 || status == 0xF7 -> { // SysEx
                    val len = r.readVlq()
                    r.skip(len)
                }
                else -> {
                    val kind = status and 0xF0
                    val channel = status and 0x0F
                    when (kind) {
                        0x80 -> { // Note off
                            val key = r.readU8()
                            r.readU8() // velocity
                            closeNote(open, outNotes, channel, key, tick)
                        }
                        0x90 -> { // Note on
                            val key = r.readU8()
                            val vel = r.readU8()
                            if (vel > 0) {
                                if (channel != 9) { // skip drum channel
                                    val note = MidiNote(tick, tick, key, vel, trackIndex, channel)
                                    open.getOrPut(Pair(channel, key)) { ArrayDeque() }.addLast(note)
                                }
                            } else {
                                closeNote(open, outNotes, channel, key, tick)
                            }
                        }
                        0xA0, 0xB0, 0xE0 -> r.skip(2) // poly pressure, CC, pitch bend
                        0xC0, 0xD0 -> r.skip(1) // program change, channel pressure
                        else -> throw MidiParseException("Unknown status byte 0x${status.toString(16)}")
                    }
                }
            }
        }
        // Close any dangling notes at the end of the track.
        for (queue in open.values) {
            for (note in queue) {
                outNotes.add(note.copy(endTick = tick.coerceAtLeast(note.startTick + 1)))
            }
        }
        open.clear()
    }

    private fun closeNote(
        open: MutableMap<Pair<Int, Int>, ArrayDeque<MidiNote>>,
        outNotes: MutableList<MidiNote>,
        channel: Int,
        key: Int,
        tick: Long,
    ) {
        val queue = open[Pair(channel, key)] ?: return
        val note = queue.removeFirstOrNull() ?: return
        outNotes.add(note.copy(endTick = tick.coerceAtLeast(note.startTick + 1)))
        if (queue.isEmpty()) open.remove(Pair(channel, key))
    }

    // ------- Binary reader -------
    private class Reader(val data: ByteArray) {
        var position = 0
        val remaining: Int get() = data.size - position

        fun peek(): Byte = data[position]

        fun skip(n: Long) {
            position += n.toInt()
            if (position > data.size) throw MidiParseException("Tệp MIDI bị cắt cụt")
        }

        fun readU8(): Int {
            if (position >= data.size) throw MidiParseException("Tệp MIDI bị cắt cụt")
            return data[position++].toInt() and 0xFF
        }

        fun readU16(): Int = (readU8() shl 8) or readU8()

        fun readU32(): Long =
            ((readU8().toLong() shl 24) or (readU8().toLong() shl 16) or
                (readU8().toLong() shl 8) or readU8().toLong()) and 0xFFFFFFFFL

        fun readAscii(n: Int): String {
            if (position + n > data.size) throw MidiParseException("Tệp MIDI bị cắt cụt")
            val s = String(data, position, n, Charsets.US_ASCII)
            position += n
            return s
        }

        fun readBytes(n: Int): ByteArray {
            if (position + n > data.size) throw MidiParseException("Tệp MIDI bị cắt cụt")
            val out = data.copyOfRange(position, position + n)
            position += n
            return out
        }

        /** Variable-length quantity used by delta times and meta/sysex lengths. */
        fun readVlq(): Long {
            var value = 0L
            repeat(4) {
                val b = readU8()
                value = (value shl 7) or (b and 0x7F).toLong()
                if (b and 0x80 == 0) return value
            }
            throw MidiParseException("VLQ không hợp lệ")
        }
    }
}

package com.ledpiano.app.led

import kotlin.math.floor

/**
 * WS2812B strip driven by the LED Piano Strip firmware (ESP32 + FastLED, folder LedPianoStrip).
 *
 * The app decides which LED sits above each key and what colour it gets, then sends the
 * pixels, like `leds[i] = CRGB(r, g, b); FastLED.show()` in the HowToMechatronics FastLED
 * example. Message: `A5 op len payload… checksum` (checksum = op ^ len ^ every payload byte):
 *  - 0x01 PIXELS: n × (indexLow, indexHigh, r, g, b) — all LEDs off, these on, show.
 *  - 0x02 BRIGHTNESS: 1 byte.
 *  - 0x03 FILL: r, g, b.
 *  - 0x04 CONFIG, 0x05 EXPECT, 0x06–0x08 store a song (firmware 2, see the ESP32 README).
 *  ESP32 → app (notify 8f1b0003): 0x10 NOTE (pitch, velocity) from a Bluetooth MIDI piano, 0x11 STATUS.
 * Same logic as KeyMap.h in the firmware and the iPhone/Mac and Python apps.
 */
object Ws2812 {

    const val OP_PIXELS = 0x01
    const val OP_BRIGHTNESS = 0x02
    const val OP_FILL = 0x03
    const val OP_CONFIG = 0x04
    const val OP_EXPECT = 0x05
    const val OP_SONG_BEGIN = 0x06
    const val OP_SONG_DATA = 0x07
    const val OP_SONG_END = 0x08
    /** Song name (UTF-8, ≤ 60 bytes) just before BEGIN: firmware 2.1 keeps a song library. */
    const val OP_SONG_TITLE = 0x09
    const val OP_NOTE = 0x10
    const val OP_STATUS = 0x11
    const val MAX_PIXELS = 50
    const val SONG_PER_MSG = 49
    const val MAX_SONG_NOTES = 3000

    data class Pixel(val index: Int, val color: Int) // color = 0xRRGGBB (alpha ignored)

    /** Strip + piano geometry, from Settings. */
    data class Config(
        val numLeds: Int = 177,
        val ledsPerMetre: Float = 144f,
        val lowestPitch: Int = 21,
        val numKeys: Int = 88,
        val whiteKeyMm: Float = 23.5f,
        val firstLed: Int = 0,
        val reversed: Boolean = false,
        /**
         * True: one LED per key, in order — the lowest key is LED [firstLed], the next key (white
         * or black) the next LED, and so on; no LED is skipped. False: the LED physically above
         * the key, from [ledsPerMetre].
         */
        val sequential: Boolean = false,
    )

    fun isBlack(pitch: Int): Boolean = Math.floorMod(pitch, 12) in intArrayOf(1, 3, 6, 8, 10)

    /** Centre of a key in mm from the left edge of the lowest key (white 23.5 mm, black on the gap). */
    fun keyCentreMm(pitch: Int, lowestPitch: Int, whiteMm: Float): Float {
        var whites = 0
        for (p in lowestPitch until pitch) if (!isBlack(p)) whites++
        return if (!isBlack(pitch)) (whites + 0.5f) * whiteMm else whites * whiteMm
    }

    /** The LED above [pitch], or -1 when the key is off the strip / not on this piano. */
    fun ledForPitch(pitch: Int, c: Config): Int {
        if (pitch < c.lowestPitch || pitch >= c.lowestPitch + c.numKeys) return -1
        if (c.sequential) {
            var seq = c.firstLed + (pitch - c.lowestPitch)
            if (c.reversed) seq = c.numLeds - 1 - seq
            return if (seq in 0 until c.numLeds) seq else -1
        }
        val mmPerLed = 1000f / c.ledsPerMetre
        // Nearest LED to the key's centre (LED i sits i × spacing from the left edge of the lowest key).
        var idx = c.firstLed + floor(keyCentreMm(pitch, c.lowestPitch, c.whiteKeyMm) / mmPerLed + 0.5f).toInt()
        if (c.reversed) idx = c.numLeds - 1 - idx
        return if (idx in 0 until c.numLeds) idx else -1
    }

    /** LED position of a key's centre before the strip direction / length are applied (may be off the strip). */
    private fun rawLed(pitch: Int, c: Config): Int =
        c.firstLed + floor(keyCentreMm(pitch, c.lowestPitch, c.whiteKeyMm) * c.ledsPerMetre / 1000f + 0.5f).toInt()

    /**
     * ALL the LEDs that belong to [pitch]: every LED goes to the key whose centre is nearest, so
     * neighbouring keys light neighbouring LEDs with no dark LED in between (on a 144 LED/m strip a
     * key gets 1–3 LEDs). On a sparse strip it is just the LED of [ledForPitch]. Empty when the key
     * is off the strip.
     */
    fun ledsForPitch(pitch: Int, c: Config): List<Int> {
        if (pitch < c.lowestPitch || pitch >= c.lowestPitch + c.numKeys) return emptyList()
        if (c.sequential) return ledForPitch(pitch, c).let { if (it >= 0) listOf(it) else emptyList() }
        val centre = rawLed(pitch, c)
        val hasPrev = pitch > c.lowestPitch
        val hasNext = pitch < c.lowestPitch + c.numKeys - 1
        // First LED of this key = just past the middle between the previous key's centre and ours.
        var lo = if (hasPrev) Math.floorDiv(rawLed(pitch - 1, c) + centre, 2) + 1 else Int.MIN_VALUE
        var hi = if (hasNext) Math.floorDiv(centre + rawLed(pitch + 1, c), 2) else Int.MAX_VALUE
        if (!hasPrev) lo = if (hasNext) centre - (hi - centre) else centre
        if (!hasNext) hi = if (hasPrev) centre + (centre - lo) else centre
        if (lo > centre) lo = centre
        if (hi < centre) hi = centre
        val out = ArrayList<Int>(hi - lo + 1)
        for (raw in lo..hi) {
            val idx = if (c.reversed) c.numLeds - 1 - raw else raw
            if (idx in 0 until c.numLeds) out.add(idx)
        }
        return out
    }

    /** LEDs needed to cover the piano at this density (a sensible default for "number of LEDs"). */
    fun ledsNeeded(numKeys: Int, lowestPitch: Int, ledsPerMetre: Float, whiteMm: Float = 23.5f): Int {
        var whites = 0
        for (p in lowestPitch until lowestPitch + numKeys) if (!isBlack(p)) whites++
        return (whites * whiteMm * ledsPerMetre / 1000f).toInt() + 1
    }

    fun pixels(list: List<Pixel>): ByteArray {
        val payload = ByteArray(minOf(list.size, MAX_PIXELS) * 5)
        list.take(MAX_PIXELS).forEachIndexed { i, p ->
            payload[i * 5] = (p.index and 0xFF).toByte()
            payload[i * 5 + 1] = (p.index shr 8 and 0xFF).toByte()
            payload[i * 5 + 2] = (p.color shr 16 and 0xFF).toByte()
            payload[i * 5 + 3] = (p.color shr 8 and 0xFF).toByte()
            payload[i * 5 + 4] = (p.color and 0xFF).toByte()
        }
        return message(OP_PIXELS, payload)
    }

    fun brightness(level: Int): ByteArray = message(OP_BRIGHTNESS, byteArrayOf(level.coerceIn(0, 255).toByte()))

    fun fill(color: Int): ByteArray =
        message(OP_FILL, byteArrayOf((color shr 16).toByte(), (color shr 8).toByte(), color.toByte()))

    val OFF: ByteArray get() = pixels(emptyList())

    /** Strip geometry + hand colours, so the ESP32 can light keys itself (practice without the phone). */
    fun configMessage(c: Config, right: Int, left: Int): ByteArray {
        val first = c.firstLed and 0xFFFF
        return message(OP_CONFIG, byteArrayOf(
            (c.numLeds and 0xFF).toByte(), (c.numLeds shr 8 and 0xFF).toByte(), (c.ledsPerMetre.toInt() and 0xFF).toByte(),
            (c.lowestPitch and 0x7F).toByte(), (c.numKeys and 0x7F).toByte(), (first and 0xFF).toByte(), (first shr 8).toByte(),
            (if (c.reversed) 1 else 0).toByte(),
            (right shr 16).toByte(), (right shr 8).toByte(), right.toByte(),
            (left shr 16).toByte(), (left shr 8).toByte(), left.toByte(),
        ))
    }

    /** The keys the learner should press now: the ESP32 flashes green / red at once when the piano is paired with it. */
    fun expectMessage(pitches: Collection<Int>): ByteArray =
        message(OP_EXPECT, pitches.map { it and 0x7F }.toSortedSet().take(32).map { it.toByte() }.toByteArray())

    data class SongNote(val startSec: Double, val pitch: Int, val left: Boolean)

    /**
     * Stores a song on the ESP32 (TITLE, BEGIN, DATA…, END). [maxNotes]: what the board holds — its STATUS
     * says (3000 on an ESP32-WROOM-32, 20000 on an ESP32-S3-DevKitC N16R8 with PSRAM).
     */
    fun songMessages(notes: List<SongNote>, title: String? = null, maxNotes: Int = MAX_SONG_NOTES): List<ByteArray> {
        val ev = notes.take(maxNotes.coerceIn(1, 65535))
        val n = ev.size
        val out = ArrayList<ByteArray>()
        if (!title.isNullOrBlank()) {
            var name = title.toByteArray(Charsets.UTF_8)
            if (name.size > 60) {
                var cut = 60
                while (cut > 0 && (name[cut].toInt() and 0xC0) == 0x80) cut-- // whole UTF-8 characters only
                name = name.copyOf(cut)
            }
            out.add(message(OP_SONG_TITLE, name))
        }
        out.add(message(OP_SONG_BEGIN, byteArrayOf((n and 0xFF).toByte(), (n shr 8).toByte())))
        for (i in 0 until n step SONG_PER_MSG) {
            val chunk = ev.subList(i, minOf(n, i + SONG_PER_MSG))
            val body = ByteArray(2 + chunk.size * 5)
            body[0] = (i and 0xFF).toByte()
            body[1] = (i shr 8).toByte()
            chunk.forEachIndexed { k, e ->
                val ms = Math.round(e.startSec * 1000).toInt() and 0xFFFFFF
                body[2 + k * 5] = (ms and 0xFF).toByte()
                body[3 + k * 5] = (ms shr 8 and 0xFF).toByte()
                body[4 + k * 5] = (ms shr 16).toByte()
                body[5 + k * 5] = (e.pitch and 0x7F).toByte()
                body[6 + k * 5] = (if (e.left) 1 else 0).toByte()
            }
            out.add(message(OP_SONG_DATA, body))
        }
        out.add(message(OP_SONG_END, byteArrayOf((n and 0xFF).toByte(), (n shr 8).toByte())))
        return out
    }

    /** A colour at a fraction of its brightness (look-ahead glow, loudness). */
    fun scale(color: Int, f: Float): Int {
        val k = f.coerceIn(0f, 1f)
        return ((((color shr 16) and 0xFF) * k).toInt() shl 16) or ((((color shr 8) and 0xFF) * k).toInt() shl 8) or
            ((color and 0xFF) * k).toInt()
    }

    /** Reassembles "A5 op len payload chk" messages coming back from the ESP32. */
    class MessageParser {
        var op = 0
            private set
        var data = ByteArray(0)
            private set
        private var st = 0
        private var len = 0
        private var chk = 0
        private var got = 0
        private val buf = ByteArray(255)

        /** Feed one byte; true when a whole valid message has arrived ([op], [data]). */
        fun feed(byte: Byte): Boolean {
            val b = byte.toInt() and 0xFF
            when (st) {
                0 -> if (b == 0xA5) st = 1
                1 -> { op = b; chk = b; st = 2 }
                2 -> { len = b; chk = chk xor b; got = 0; st = if (len == 0) 4 else 3 }
                3 -> { buf[got++] = b.toByte(); chk = chk xor b; if (got == len) st = 4 }
                else -> {
                    st = 0
                    if (b == chk) { data = buf.copyOf(len); return true }
                }
            }
            return false
        }
    }

    private fun message(op: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(payload.size + 4)
        out[0] = 0xA5.toByte()
        out[1] = op.toByte()
        out[2] = payload.size.toByte()
        var chk = op xor payload.size
        payload.forEachIndexed { i, b -> out[3 + i] = b; chk = chk xor (b.toInt() and 0xFF) }
        out[out.size - 1] = chk.toByte()
        return out
    }

    /** The exact LEDs and colours of the HowToMechatronics FastLED example, lit one by one 500 ms apart. */
    val TUTORIAL: List<Pixel> = listOf(
        Pixel(0, 0xFF0000), Pixel(1, 0x00FF00), Pixel(2, 0x0000FF), Pixel(5, 0x9600FF),
        Pixel(9, 0xFFC814), Pixel(14, 0x553CB4), Pixel(19, 0x32FF14),
    )

    /** Lighter/darker version of a hand colour for black keys, so they stand out. */
    fun blackKeyColor(color: Int): Int {
        val r = (color shr 16 and 0xFF) * 6 / 10
        val g = (color shr 8 and 0xFF) * 6 / 10
        val b = (color and 0xFF) * 6 / 10
        return (r shl 16) or (g shl 8) or b
    }
}

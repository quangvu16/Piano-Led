package com.ledpiano.app.playback

import android.content.Context
import org.json.JSONObject
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Recorded instruments — 8 pianos, 8 organs, 8 bells/mallets — from the
 * bundled sound pack (assets/soundbank.json + soundbank.bin), made from the
 * free GeneralUser GS SoundFont by tools/make_soundbank.py.
 *
 * Each instrument has key zones; a zone plays one recorded sample (IMA ADPCM),
 * re-pitched to the key, with an optional sustain loop and an
 * attack/hold/decay/sustain/release envelope.
 */
class SoundBank private constructor(json: JSONObject, private val blob: ByteArray) {

    class Zone(
        val lo: Int, val hi: Int, val sample: Int, val root: Int, val tune: Int,
        val attenDb: Float, val attack: Float, val hold: Float, val decay: Float, val keyDecay: Int,
        val sustainDb: Float, val release: Float, val loop: Boolean,
    )

    class Instrument(val program: Int, val group: String, val name: String, val zones: List<Zone>)

    class Sample(val offset: Int, val length: Int, val bytes: Int, val rate: Int, val loopStart: Int, val loopEnd: Int)

    val instruments: List<Instrument>
    val samples: List<Sample>
    val credit: String = json.optString("source")
    private val pcm = arrayOfNulls<FloatArray>(json.getJSONArray("samples").length())

    init {
        val sa = json.getJSONArray("samples")
        samples = (0 until sa.length()).map { i ->
            val s = sa.getJSONObject(i)
            val loop = s.optJSONArray("loop")
            Sample(
                s.getInt("offset"), s.getInt("length"), s.getInt("bytes"), s.getInt("rate"),
                loop?.getInt(0) ?: -1, loop?.getInt(1) ?: -1,
            )
        }
        val ia = json.getJSONArray("instruments")
        instruments = (0 until ia.length()).map { i ->
            val o = ia.getJSONObject(i)
            val za = o.getJSONArray("zones")
            Instrument(
                o.getInt("program"), o.getString("group"), o.getString("name"),
                (0 until za.length()).map { j ->
                    val z = za.getJSONObject(j)
                    Zone(
                        z.getInt("lo"), z.getInt("hi"), z.getInt("sample"), z.getInt("root"), z.getInt("tune"),
                        z.getDouble("atten").toFloat(), z.getDouble("attack").toFloat(), z.getDouble("hold").toFloat(),
                        z.getDouble("decay").toFloat(), z.optInt("key_decay", 0), z.getDouble("sustain").toFloat(),
                        z.getDouble("release").toFloat(), z.getBoolean("loop"),
                    )
                },
            )
        }
    }

    fun instrument(program: Int): Instrument = instruments.firstOrNull { it.program == program } ?: instruments[0]

    fun zone(program: Int, key: Int): Zone {
        val zs = instrument(program).zones
        return zs.firstOrNull { key in it.lo..it.hi }
            ?: zs.minBy { min(kotlin.math.abs(key - it.lo), kotlin.math.abs(key - it.hi)) }
    }

    /** Decoded sample (cached). */
    @Synchronized
    fun pcm(index: Int): FloatArray {
        pcm[index]?.let { return it }
        val s = samples[index]
        val out = imaDecode(blob, s.offset, s.length)
        pcm[index] = out
        return out
    }

    /** Decode every sample of [program] ahead of time (call off the audio thread). */
    fun preload(program: Int) {
        for (z in instrument(program).zones) pcm(z.sample)
    }

    companion object {
        /** Loads the pack from assets, or returns null if it is missing. */
        fun load(context: Context): SoundBank? = try {
            val json = context.assets.open("soundbank.json").bufferedReader().use { it.readText() }
            val bin = context.assets.open("soundbank.bin").use { it.readBytes() }
            SoundBank(JSONObject(json), bin)
        } catch (_: Exception) {
            null
        }

        private val STEPS = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73,
            80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494,
            544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499,
            2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487,
            12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
        )
        private val INDEX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)

        /** IMA ADPCM, low nibble first, predictor and index start at 0. */
        fun imaDecode(data: ByteArray, offset: Int, n: Int): FloatArray {
            val out = FloatArray(n)
            var pred = 0
            var index = 0
            for (i in 0 until n) {
                val b = data[offset + (i shr 1)].toInt() and 0xFF
                val c = if (i and 1 == 0) b and 0x0F else b shr 4
                val step = STEPS[index]
                var delta = step shr 3
                if (c and 4 != 0) delta += step
                if (c and 2 != 0) delta += step shr 1
                if (c and 1 != 0) delta += step shr 2
                pred = if (c and 8 != 0) pred - delta else pred + delta
                pred = pred.coerceIn(-32768, 32767)
                index = (index + INDEX[c and 7]).coerceIn(0, 88)
                out[i] = pred / 32768f
            }
            return out
        }
    }
}

/** One playing note of a recorded instrument. Not thread-safe; owned by the render thread. */
class SampledVoice(bank: SoundBank, program: Int, val pitch: Int, velocity: Int, private val outRate: Int) {
    private val zone = bank.zone(program, pitch)
    private val sample = bank.samples[zone.sample]
    private val pcm = bank.pcm(zone.sample)
    private val step = 2.0.pow(((pitch - zone.root) * 100 + zone.tune) / 1200.0) * sample.rate / outRate
    private val looped = zone.loop && sample.loopEnd > sample.loopStart
    private val gain: Float
    private val decay = max(1e-3f, zone.decay * 2f.pow(zone.keyDecay * (60 - pitch) / 1200f))
    private var pos = 0.0
    private var t = 0f
    private var released = false
    private var relDb = 0f
    private var relT = 0f
    private var env = 0f
    private var counter = 0
    var finished = false
        private set

    init {
        val v = velocity.coerceIn(1, 127) / 127f
        gain = 10f.pow(-zone.attenDb / 20f) * v * v * 1.6f
    }

    fun release() {
        if (!released) {
            released = true
            relDb = envDb(t)
            relT = 0f
        }
    }

    private fun envDb(time: Float): Float {
        val a = zone.attack
        val h = zone.hold
        return when {
            time < a -> 20f * log10(max(time / max(a, 1e-4f), 1e-5f))
            time < a + h -> 0f
            else -> -min(1f, (time - a - h) / decay) * zone.sustainDb
        }
    }

    fun next(): Float {
        if (finished) return 0f
        // Envelope is updated every 32 samples (cheap, inaudible).
        if (counter == 0) {
            var db = envDb(t)
            if (released) {
                db = min(db, relDb - 100f * relT / max(0.02f, zone.release))
                if (db < -90f) finished = true
            } else if (db < -90f && t > 0.05f) {
                finished = true
            }
            env = 10f.pow(db / 20f)
        }
        counter = (counter + 1) and 31
        val dt = 1f / outRate
        t += dt
        if (released) relT += dt

        if (looped && pos >= sample.loopEnd) pos -= (sample.loopEnd - sample.loopStart)
        val i0 = pos.toInt()
        if (i0 >= pcm.size - 1) {
            finished = true
            return 0f
        }
        val f = (pos - i0).toFloat()
        val y = pcm[i0] * (1 - f) + pcm[i0 + 1] * f
        pos += step
        return y * gain * env
    }
}

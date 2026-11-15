package com.ledpiano.app.led

import com.ledpiano.app.midi.MidiSong
import kotlin.math.exp

/**
 * Per-key LED model.
 *
 * Keeps one energy level per zone (zones tile the 88 piano keys) with a fast
 * attack and exponential decay, so the strip reads as an animated level meter.
 * The SP611E can only ingest a 16-band spectrum frame, so the zone levels are
 * resampled to 16 bands before transmission; a future per-pixel controller can
 * consume the per-key state directly.
 */
class ZoneMapper(zones: Int = DEFAULT_ZONES) {

    @Volatile
    var zones: Int = zones.coerceIn(4, 32)
        set(value) {
            val v = value.coerceIn(4, 32)
            if (v != field) {
                field = v
                synchronized(lock) {
                    levels = FloatArray(v)
                }
            }
        }

    private val lock = Any()
    private var levels = FloatArray(this.zones)

    fun reset() {
        synchronized(lock) {
            for (i in levels.indices) levels[i] = 0f
        }
    }

    /** Registers a note hit; spills a little energy into neighbouring zones. */
    fun noteOn(pitch: Int, velocity: Int) {
        val z = zoneFor(pitch)
        val target = 0.35f + 0.65f * (velocity.coerceIn(1, 127) / 127f)
        synchronized(lock) {
            if (z in levels.indices) {
                levels[z] = maxOf(levels[z], target)
                if (z - 1 >= 0) levels[z - 1] = maxOf(levels[z - 1], target * 0.5f)
                if (z + 1 < levels.size) levels[z + 1] = maxOf(levels[z + 1], target * 0.5f)
            }
        }
    }

    /** Advances the decay by [dt] seconds. */
    fun tick(dt: Float) {
        val factor = exp(-dt / DECAY_TIME)
        synchronized(lock) {
            for (i in levels.indices) levels[i] *= factor
        }
    }

    fun hasEnergy(): Boolean {
        synchronized(lock) {
            for (l in levels) if (l > 0.01f) return true
        }
        return false
    }

    /** Resamples the zone levels to 16 band bytes in 0..255. */
    fun bands16(): ByteArray {
        val out = ByteArray(BAND_COUNT)
        synchronized(lock) {
            val n = levels.size
            for (b in 0 until BAND_COUNT) {
                val z0 = b * n / BAND_COUNT
                val z1 = maxOf(z0 + 1, (b + 1) * n / BAND_COUNT)
                var peak = 0f
                for (z in z0 until minOf(z1, n)) peak = maxOf(peak, levels[z])
                out[b] = (peak * 255f).toInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    fun zoneFor(pitch: Int): Int {
        val span = MidiSong.PIANO_HIGHEST - MidiSong.PIANO_LOWEST + 1
        val idx = (pitch - MidiSong.PIANO_LOWEST) * zones / span
        return idx.coerceIn(0, zones - 1)
    }

    companion object {
        const val DEFAULT_ZONES = 16
        const val BAND_COUNT = 16
        /** Seconds for a full-level zone to decay to e^-1. */
        const val DECAY_TIME = 0.18f
    }
}

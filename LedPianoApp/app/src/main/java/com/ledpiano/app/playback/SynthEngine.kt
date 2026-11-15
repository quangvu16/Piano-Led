package com.ledpiano.app.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.tanh

/**
 * Real-time piano-ish synthesizer.
 *
 * Uses Karplus-Strong string synthesis per voice, mixed into a single
 * [AudioTrack] stream by a dedicated render thread. Each note uses two
 * slightly detuned strings (like the 2–3 strings of a real piano note) and a
 * velocity-dependent hammer, followed by a gentle "soundboard" low-pass.
 * Also renders metronome clicks. No soundfont files needed.
 */
class SynthEngine(
    /** Recorded instruments; when null the Karplus-Strong string sound is used. */
    private val bank: SoundBank? = null,
) {
    /** Instrument (General MIDI program) used for new notes. */
    @Volatile var program: Int = 0

    private val sampled = ArrayList<SampledVoice>()
    private val sampledLock = Any()

    private var audioTrack: AudioTrack? = null
    private var renderThread: Thread? = null
    @Volatile private var running = false

    private val voices = ConcurrentHashMap<Int, Voice>()
    private val clicks = ConcurrentLinkedQueue<Click>()
    @Volatile var volume: Float = 1.0f
    @Volatile var clickVolume: Float = 0.6f

    val sampleRate: Int = 44100

    @Synchronized
    fun start() {
        if (running) return
        running = true
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track
        track.play()
        renderThread = Thread({ renderLoop(track) }, "SynthRender").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        renderThread?.join(500)
        renderThread = null
        voices.clear()
        audioTrack?.run {
            try {
                stop()
            } catch (_: IllegalStateException) {
            }
            release()
        }
        audioTrack = null
    }

    fun noteOn(pitch: Int, velocity: Int) {
        if (!running) return
        val b = bank
        if (b != null) {
            val v = SampledVoice(b, program, pitch, velocity, sampleRate)
            synchronized(sampledLock) {
                for (old in sampled) if (old.pitch == pitch) old.release()
                sampled.add(v)
                if (sampled.size > 48) sampled.removeAt(0)
            }
            return
        }
        val v = voices[pitch]
        if (v != null && v.released) {
            v.restart(velocity)
        } else if (v == null) {
            voices[pitch] = Voice(pitch, velocity)
        } else {
            v.held = true
            v.targetGain = gainFor(velocity)
        }
    }

    fun noteOff(pitch: Int) {
        voices[pitch]?.held = false
        synchronized(sampledLock) { for (v in sampled) if (v.pitch == pitch) v.release() }
    }

    fun allNotesOff() {
        for (v in voices.values) v.held = false
        synchronized(sampledLock) { for (v in sampled) v.release() }
    }

    fun panic() {
        voices.clear()
        clicks.clear()
    }

    /** Short metronome tick; [accent] = first beat of the measure. */
    fun click(accent: Boolean) {
        if (!running) return
        clicks.add(Click(if (accent) 1760f else 1175f, if (accent) 1f else 0.7f))
    }

    private fun gainFor(velocity: Int): Float = (velocity.coerceIn(1, 127) / 127f).let { it * it * 0.9f + 0.1f * it }

    private fun renderLoop(track: AudioTrack) {
        val block = 512
        val out = ShortArray(block)
        var board = 0f
        while (running) {
            // Drop finished voices.
            val iter = voices.entries.iterator()
            while (iter.hasNext()) {
                if (iter.next().value.finished) iter.remove()
            }
            val rec = synchronized(sampledLock) {
                sampled.removeAll { it.finished }
                sampled.toTypedArray()
            }
            val active = voices.size
            val polyScale = if (active > 1) 1f / (1f + 0.22f * (active - 1)) else 1f
            for (i in 0 until block) {
                var sample = 0f
                for (v in voices.values) {
                    sample += v.next()
                }
                sample *= volume * polyScale
                // Soundboard: one-pole low-pass takes the edge off the strings.
                board += (sample - board) * 0.55f
                sample = board
                if (rec.isNotEmpty()) {
                    var r = 0f
                    for (v in rec) r += v.next()
                    sample += r * 0.7f * volume
                }
                for (c in clicks) sample += c.next() * clickVolume
                // Soft clip to keep chords from crackling.
                sample = tanh(sample * 1.2f) * 0.82f
                out[i] = (sample * 32767f).toInt().coerceIn(-32767, 32767).toShort()
            }
            clicks.removeAll { it.finished }
            track.write(out, 0, block)
        }
    }

    /**
     * One Karplus-Strong voice with an amplitude envelope.
     *
     * The string decay factor is computed per voice from the target ring time:
     * `damping = exp(-1 / (freq * tau))` applied once per period, so low notes
     * ring much longer than high notes like a real piano.
     */
    private inner class Voice(pitch: Int, velocity: Int) {
        private val freq: Float = (440.0 * Math.pow(2.0, (pitch - 69) / 12.0)).toFloat()
        private val delayLen: Int = maxOf(2, floor(sampleRate / freq).toInt())
        private val buf = FloatArray(delayLen)
        private var idx = 0
        private var prev = 0f

        // Second string, detuned ~2.5 cents, for a fuller, beating tone.
        private val delayLen2: Int = maxOf(2, floor(sampleRate / (freq * 1.0015f)).toInt())
        private val buf2 = FloatArray(delayLen2)
        private var idx2 = 0
        private var prev2 = 0f
        private var hammer = 0.35f

        /** Target ring time (seconds to decay e^-1), longer for bass notes. */
        private val ringTime: Float = 1.2f + 6.5f * (1f - ((pitch - 21) / 87f).coerceIn(0f, 1f))
        private val damping: Float = exp(-1.0 / (freq * ringTime)).toFloat()

        @Volatile var held = true
        @Volatile var targetGain: Float = gainFor(velocity)
        var gain: Float = 0f
            private set
        @Volatile var released = false
        @Volatile var finished = false
        private var silentSamples = 0

        init {
            hammer = hammerFor(velocity)
            excite()
        }

        fun restart(velocity: Int) {
            held = true
            released = false
            targetGain = gainFor(velocity)
            silentSamples = 0
            hammer = hammerFor(velocity)
            excite()
        }

        private fun excite() {
            // Noise burst excitation, slightly low-passed for a softer hammer.
            // Soft notes = darker hammer (more smoothing), loud notes = brighter.
            var last = 0f
            for (i in buf.indices) {
                val white = (Math.random().toFloat() * 2f - 1f)
                last = last * hammer + white * (1f - hammer)
                buf[i] = last
            }
            last = 0f
            for (i in buf2.indices) {
                val white = (Math.random().toFloat() * 2f - 1f)
                last = last * hammer + white * (1f - hammer)
                buf2[i] = last
            }
            idx = 0
            prev = 0f
            idx2 = 0
            prev2 = 0f
        }

        fun next(): Float {
            if (finished) return 0f

            if (!held && !released) released = true

            // Envelope: fast attack, hold while key is down, ~150 ms release.
            val target = if (released) 0f else targetGain
            val rate = if (released) RELEASE_STEP else ATTACK_STEP
            gain += (target - gain) * rate

            if (released && gain < 0.0005f) {
                finished = true
                return 0f
            }

            // Karplus-Strong step: half-sample averaging filter + tuned decay.
            val cur = buf[idx]
            val filtered = (cur + prev) * 0.5f
            buf[idx] = filtered * damping
            prev = cur
            idx++
            if (idx >= delayLen) idx = 0

            val cur2 = buf2[idx2]
            buf2[idx2] = (cur2 + prev2) * 0.5f * damping
            prev2 = cur2
            idx2++
            if (idx2 >= delayLen2) idx2 = 0

            val out = (cur * 0.6f + cur2 * 0.4f) * gain
            // Natural decay detection for held notes.
            if (abs(out) < 1e-4f) {
                if (++silentSamples > 4096) finished = true
            } else {
                silentSamples = 0
            }
            return out
        }
    }

    private fun hammerFor(velocity: Int): Float =
        0.55f - 0.4f * (velocity.coerceIn(1, 127) / 127f)

    /** Decaying sine burst used for metronome ticks (~35 ms). */
    private inner class Click(freq: Float, private val amp: Float) {
        private val step = (2.0 * Math.PI * freq / sampleRate).toFloat()
        private var phase = 0f
        private var env = 1f
        @Volatile var finished = false
        fun next(): Float {
            if (finished) return 0f
            val v = kotlin.math.sin(phase) * env * amp * 0.5f
            phase += step
            env *= 0.9985f
            if (env < 0.01f) finished = true
            return v
        }
    }

    companion object {
        private const val ATTACK_STEP = 0.25f
        private const val RELEASE_STEP = 0.0028f
    }
}

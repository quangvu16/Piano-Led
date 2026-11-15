package com.ledpiano.app.input

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Experimental microphone input for acoustic pianos.
 *
 * Detects ONE note at a time with the YIN pitch algorithm (de Cheveigné &
 * Kawahara 2002), implemented here so no GPL library is needed. Chords are not
 * recognised — good enough for single-note Wait practice, not for scoring
 * chords. Needs the RECORD_AUDIO permission.
 */
class MicPitchInput(private val hub: NoteInputHub) {

    @Volatile private var running = false
    private var thread: Thread? = null

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME * 4),
            )
        } catch (_: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        running = true
        hub.setState(InputSource.MIC, "Micro")
        thread = Thread({ loop(rec) }, "MicPitch").also { it.start() }
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        thread?.join(300)
        thread = null
        if (hub.source == InputSource.MIC) hub.setState(InputSource.NONE, null)
    }

    private fun loop(rec: AudioRecord) {
        val pcm = ShortArray(HOP)
        val window = FloatArray(FRAME)
        val diff = FloatArray(FRAME / 2)
        var filled = 0
        var current = -1
        var candidate = -1
        var candidateCount = 0
        var quietFrames = 0
        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(pcm, 0, HOP)
                if (n <= 0) continue
                // Slide the analysis window by n samples.
                System.arraycopy(window, n, window, 0, FRAME - n)
                for (i in 0 until n) window[FRAME - n + i] = pcm[i] / 32768f
                filled = minOf(FRAME, filled + n)
                if (filled < FRAME) continue

                var energy = 0f
                for (v in window) energy += v * v
                val rms = sqrt(energy / FRAME)
                val pitch = if (rms > RMS_GATE) detectMidi(window, diff) else -1

                if (pitch < 0) {
                    if (++quietFrames >= 3 && current >= 0) {
                        hub.emitNoteOff(current)
                        current = -1
                    }
                    candidate = -1
                    candidateCount = 0
                    continue
                }
                quietFrames = 0
                if (pitch == candidate) candidateCount++ else {
                    candidate = pitch
                    candidateCount = 1
                }
                // Require 2 consistent frames (~45 ms) before reporting a new note.
                if (candidateCount >= 2 && pitch != current) {
                    if (current >= 0) hub.emitNoteOff(current)
                    current = pitch
                    hub.emitNoteOn(pitch, (rms * 900).toInt().coerceIn(20, 127))
                }
            }
        } catch (_: Exception) {
        } finally {
            if (current >= 0) hub.emitNoteOff(current)
            try {
                rec.stop()
            } catch (_: Exception) {
            }
            rec.release()
        }
    }

    /** YIN: returns the MIDI note number, or -1 if no clear pitch. */
    private fun detectMidi(x: FloatArray, d: FloatArray): Int {
        val half = d.size
        val minTau = RATE / MAX_HZ
        val maxTau = minOf(half - 1, RATE / MIN_HZ)
        // Difference function.
        for (tau in 1..maxTau) {
            var sum = 0f
            for (i in 0 until half) {
                val delta = x[i] - x[i + tau]
                sum += delta * delta
            }
            d[tau] = sum
        }
        // Cumulative mean normalised difference.
        d[0] = 1f
        var running = 0f
        for (tau in 1..maxTau) {
            running += d[tau]
            d[tau] = if (running > 0f) d[tau] * tau / running else 1f
        }
        // First dip under the threshold.
        var tau = minTau
        while (tau <= maxTau) {
            if (d[tau] < YIN_THRESHOLD) {
                while (tau + 1 <= maxTau && d[tau + 1] < d[tau]) tau++
                break
            }
            tau++
        }
        if (tau > maxTau) return -1
        // Parabolic interpolation for sub-sample accuracy.
        val better = if (tau in 2 until maxTau) {
            val s0 = d[tau - 1]
            val s1 = d[tau]
            val s2 = d[tau + 1]
            val denom = 2f * (2f * s1 - s2 - s0)
            if (denom != 0f) tau + (s2 - s0) / denom else tau.toFloat()
        } else {
            tau.toFloat()
        }
        val hz = RATE / better
        val midi = (69 + 12 * ln(hz / 440.0) / ln(2.0)).roundToInt()
        return if (midi in 21..108) midi else -1
    }

    companion object {
        private const val RATE = 22050
        private const val FRAME = 2048
        private const val HOP = 512
        private const val MIN_HZ = 27 // A0 ≈ 27.5 Hz
        private const val MAX_HZ = 2100
        private const val YIN_THRESHOLD = 0.15f
        private const val RMS_GATE = 0.012f
    }
}

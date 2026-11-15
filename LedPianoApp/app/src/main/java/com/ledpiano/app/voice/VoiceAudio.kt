package com.ledpiano.app.voice

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import org.concentus.OpusApplication
import org.concentus.OpusDecoder
import org.concentus.OpusEncoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Opus for the Xiaozhi voice channel, using the vendored pure-Java Concentus
 * port of libopus (same as FriendBot). The microphone goes up as 16 kHz mono,
 * 60 ms frames (what our `hello` announces); the server's speech comes down at
 * the rate given in its `hello`.
 */
class OpusCodec(val playbackRate: Int) {
    private val encoder = OpusEncoder(MIC_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP)
    private val decoder = OpusDecoder(playbackRate, 1)

    /** One [FRAME_SAMPLES]-sample frame → one raw opus packet (null when it failed). */
    fun encode(pcm: ShortArray): ByteArray? = try {
        val out = ByteArray(MAX_PACKET)
        val n = encoder.encode(pcm, 0, FRAME_SAMPLES, out, 0, out.size)
        if (n > 0) out.copyOf(n) else null
    } catch (_: Exception) {
        null
    }

    /** One opus packet → PCM in [out]; returns the number of samples (0 = skip it). */
    fun decode(opus: ByteArray, out: ShortArray): Int = try {
        decoder.decode(opus, 0, opus.size, out, 0, out.size, false)
    } catch (_: Exception) {
        0
    }

    companion object {
        const val MIC_RATE = 16000
        const val FRAME_MS = 60
        const val FRAME_SAMPLES = MIC_RATE * FRAME_MS / 1000 // 960
        private const val MAX_PACKET = 1276
    }
}

/** Streams the microphone as opus frames; [onFrame] runs on the capture thread. */
class MicStreamer(private val codec: OpusCodec, private val onFrame: (opus: ByteArray, level: Float) -> Unit) {
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    val isRunning: Boolean get() = running

    /** Caller must hold RECORD_AUDIO. Returns false when the microphone could not open. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): Boolean {
        if (running) return true
        val min = AudioRecord.getMinBufferSize(OpusCodec.MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, OpusCodec.MIC_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, OpusCodec.FRAME_SAMPLES * 2 * 4),
            )
        } catch (_: Exception) {
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
        record = r
        running = true
        thread = Thread({ loop(r) }, "voice-mic").apply { start() }
        return true
    }

    @Synchronized
    fun stop() {
        running = false
        val r = record
        record = null
        try { r?.stop() } catch (_: Exception) {}
        thread?.let { it.interrupt(); try { it.join(500) } catch (_: InterruptedException) {} }
        thread = null
        r?.release()
    }

    private fun loop(r: AudioRecord) {
        try { r.startRecording() } catch (_: Exception) { running = false; return }
        val pcm = ShortArray(OpusCodec.FRAME_SAMPLES)
        while (running) {
            var filled = 0
            while (filled < pcm.size && running) {
                val n = r.read(pcm, filled, pcm.size - filled)
                if (n <= 0) { running = false; return }
                filled += n
            }
            if (!running) return
            val opus = codec.encode(pcm) ?: continue
            onFrame(opus, rms(pcm, pcm.size))
        }
    }
}

/**
 * Plays the assistant's opus speech. [onDrained] fires on the player thread
 * once the queue has been empty for a moment after speech (so listening can
 * resume without hearing the tail of the reply).
 */
class OpusPlayer(private val codec: OpusCodec, private val onLevel: (Float) -> Unit, private val onDrained: () -> Unit) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var released = false
    @Volatile var busy = false
        private set
    private val rate = codec.playbackRate
    private val track: AudioTrack? = try {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min, rate / 1000 * 240 * 2))
            .build().also { it.play() }
    } catch (_: Exception) {
        null
    }
    private val thread = Thread({ loop() }, "voice-speaker").apply { start() }

    fun play(opus: ByteArray) {
        if (released || opus.isEmpty()) return
        busy = true
        queue.offer(opus)
    }

    /** Barge-in: drop everything still queued. */
    fun flush() {
        queue.clear()
        busy = false
        try { track?.pause(); track?.flush(); track?.play() } catch (_: Exception) {}
    }

    fun release() {
        released = true
        queue.clear()
        busy = false
        thread.interrupt()
        try { thread.join(500) } catch (_: InterruptedException) {}
        try { track?.stop() } catch (_: Exception) {}
        track?.release()
    }

    private fun loop() {
        val pcm = ShortArray(rate * 120 / 1000 + 16)
        var lastMs = SystemClock.uptimeMillis()
        while (!released) {
            val opus = try { queue.poll(120, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { return }
            if (opus == null) {
                if (busy && SystemClock.uptimeMillis() - lastMs > 350) { busy = false; onDrained() }
                continue
            }
            val n = codec.decode(opus, pcm)
            if (n <= 0) continue
            onLevel(rms(pcm, n))
            try { track?.write(pcm, 0, n) } catch (_: Exception) {}
            lastMs = SystemClock.uptimeMillis()
        }
    }
}

private fun rms(pcm: ShortArray, n: Int): Float {
    var sum = 0.0
    for (i in 0 until n) sum += pcm[i].toDouble() * pcm[i]
    return (sqrt(sum / maxOf(1, n)) / 32768.0).toFloat()
}

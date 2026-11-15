package com.ledpiano.app.input

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Where the learner's played notes come from. Every source (USB MIDI,
 * Bluetooth MIDI, microphone) feeds the same [NoteInputHub], so practice
 * code does not care which one is connected.
 */
enum class InputSource { NONE, MIDI, MIC }

interface NoteInputListener {
    /** Called on the main thread. */
    fun onNoteOn(pitch: Int, velocity: Int)
    fun onNoteOff(pitch: Int)
    fun onInputStateChanged(source: InputSource, deviceName: String?) {}
}

/** Collects note events from the active source and fans them out on the main thread. */
class NoteInputHub {

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<NoteInputListener>()
    private val down = ConcurrentHashMap<Int, Int>()

    @Volatile var source: InputSource = InputSource.NONE
        private set
    @Volatile var deviceName: String? = null
        private set

    val isConnected: Boolean get() = source != InputSource.NONE

    /** Keys currently held on the learner's piano. */
    fun pressedKeys(): Set<Int> = HashSet(down.keys)

    fun addListener(l: NoteInputListener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: NoteInputListener) {
        listeners.remove(l)
    }

    /** Called by sources from any thread. */
    fun emitNoteOn(pitch: Int, velocity: Int) {
        if (pitch !in 0..127) return
        down[pitch] = velocity
        main.post { for (l in listeners) l.onNoteOn(pitch, velocity) }
    }

    fun emitNoteOff(pitch: Int) {
        if (down.remove(pitch) == null) return
        main.post { for (l in listeners) l.onNoteOff(pitch) }
    }

    fun setState(source: InputSource, name: String?) {
        this.source = source
        this.deviceName = name
        if (source == InputSource.NONE) {
            for (p in down.keys.toList()) emitNoteOff(p)
        }
        main.post { for (l in listeners) l.onInputStateChanged(source, name) }
    }
}

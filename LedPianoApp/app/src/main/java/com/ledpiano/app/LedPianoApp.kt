package com.ledpiano.app

import android.app.Application
import com.ledpiano.app.input.InputSource
import com.ledpiano.app.input.MicPitchInput
import com.ledpiano.app.input.MidiInput
import com.ledpiano.app.input.NoteInputHub
import com.ledpiano.app.led.LedController
import com.ledpiano.app.model.AppSettings
import com.ledpiano.app.practice.PracticeStats

/** Application-wide singletons: settings, LED controller, piano input and practice stats. */
class LedPianoApp : Application() {

    lateinit var settings: AppSettings
        private set

    lateinit var ledController: LedController
        private set

    /** Notes the learner plays, from USB/Bluetooth MIDI or the microphone. */
    lateinit var inputHub: NoteInputHub
        private set
    lateinit var midiInput: MidiInput
        private set
    lateinit var micInput: MicPitchInput
        private set
    lateinit var stats: PracticeStats
        private set

    /** Recorded pianos / organs / bells; null until loaded (or if missing). */
    @Volatile var soundBank: com.ledpiano.app.playback.SoundBank? = null
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = AppSettings(this)
        ledController = LedController(settings)
        inputHub = NoteInputHub()
        midiInput = MidiInput(this, inputHub)
        micInput = MicPitchInput(inputHub)
        stats = PracticeStats(this)
        // Watch for USB / Bluetooth MIDI keyboards for the whole app session.
        midiInput.start()
        // A Bluetooth MIDI piano paired with the ESP32 strip (firmware 2): its notes come in over the
        // LED link, so the phone needs only one Bluetooth connection.
        ledController.pianoListeners.add { p, v -> if (v > 0) inputHub.emitNoteOn(p, v) else inputHub.emitNoteOff(p) }
        ledController.esp32Listeners.add { updateEsp32Piano() }
        Thread {
            val b = com.ledpiano.app.playback.SoundBank.load(this)
            b?.preload(settings.instrument)
            soundBank = b
        }.start()
    }

    private fun updateEsp32Piano() {
        val name = getString(R.string.esp32_piano)
        val on = ledController.esp32PianoConnected
        if (on && inputHub.source == InputSource.NONE) inputHub.setState(InputSource.MIDI, name)
        else if (!on && inputHub.deviceName == name) inputHub.setState(InputSource.NONE, null)
    }

    companion object {
        lateinit var instance: LedPianoApp
            private set
    }
}

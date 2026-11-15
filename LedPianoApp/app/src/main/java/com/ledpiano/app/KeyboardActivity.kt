package com.ledpiano.app

import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.ledpiano.app.databinding.ActivityKeyboardBinding
import com.ledpiano.app.input.NoteInputListener
import com.ledpiano.app.led.BleScanner
import com.ledpiano.app.led.LedController
import com.ledpiano.app.midi.Hand
import com.ledpiano.app.playback.SynthEngine
import com.ledpiano.app.ui.BleConnectDialog
import com.ledpiano.app.util.NoteNames
import com.ledpiano.app.util.padForSystemBars

/**
 * A piano keyboard on the screen. The learner taps a key: it sounds, and the LED above that key
 * lights on the strip for as long as the finger stays down (several fingers = several LEDs).
 * ◀ ▶ move the keyboard one octave lower / higher. Keys played on a connected piano light too.
 */
class KeyboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKeyboardBinding
    private val app get() = application as LedPianoApp
    private val settings get() = app.settings
    private val led get() = app.ledController
    private lateinit var scanner: BleScanner
    private var synth: SynthEngine? = null

    /** Lowest key shown (always a C). */
    private var low = 48
    private var octaves = 2

    /** Keys down right now (fingers and connected piano) → hand colour. */
    private val down = HashMap<Int, Hand>()
    /** Keys held by a finger on the screen (they make the sound). */
    private val touched = HashSet<Int>()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) BleConnectDialog(this, scanner, led).show()
            else Toast.makeText(this, R.string.lb_permission_needed, Toast.LENGTH_LONG).show()
        }

    private val ledListener = object : LedController.Listener {
        override fun onLedStateChanged(state: LedController.State, deviceName: String?, message: String?) {
            runOnUiThread { updateLed() }
        }
    }

    /** A real piano (MIDI / microphone): show its keys here and on the strip as well. */
    private val pianoListener = object : NoteInputListener {
        override fun onNoteOn(pitch: Int, velocity: Int) = keyDown(pitch, velocity, sound = false)
        override fun onNoteOff(pitch: Int) {
            if (pitch !in touched) keyUp(pitch)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityKeyboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()
        scanner = BleScanner(this)

        // Wide screen (phone held sideways): three octaves fit; upright: two.
        octaves = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 3 else 2
        low = (savedInstanceState?.getInt(KEY_LOW) ?: 48).coerceIn(LOWEST_C, highestLow())

        binding.btnBack.setOnClickListener { finish() }
        binding.btnLower.setOnClickListener { shift(-12) }
        binding.btnHigher.setOnClickListener { shift(+12) }
        binding.btnTestKb.setOnClickListener {
            if (led.state == LedController.State.CONNECTED) led.testLights()
            else Toast.makeText(this, R.string.lb_led_needs_connect, Toast.LENGTH_SHORT).show()
        }
        binding.btnLed.setOnClickListener {
            if (led.state != LedController.State.DISCONNECTED) return@setOnClickListener
            if (!scanner.hasPermissions()) permissionLauncher.launch(scanner.requiredPermissions())
            else BleConnectDialog(this, scanner, led).show()
        }

        binding.keyboard.setHandColors(settings.colorLeft, settings.colorRight)
        binding.keyboard.setLabelMode(if (settings.labelMode == "none") "c" else settings.labelMode)
        binding.keyboard.onKeyTouch = { pitch, isDown ->
            if (isDown) {
                touched.add(pitch)
                keyDown(pitch, TOUCH_VELOCITY, sound = true)
            } else {
                touched.remove(pitch)
                synth?.noteOff(pitch)
                keyUp(pitch)
            }
        }
        applyRange()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_LOW, low)
    }

    override fun onStart() {
        super.onStart()
        val s = SynthEngine(app.soundBank)
        s.program = settings.instrument
        s.volume = settings.volume / 100f
        s.start()
        synth = s
        led.listener = ledListener
        app.inputHub.addListener(pianoListener)
        updateLed()
    }

    override fun onStop() {
        app.inputHub.removeListener(pianoListener)
        if (led.listener === ledListener) led.listener = null
        releaseAll()
        synth?.stop()
        synth = null
        super.onStop()
    }

    private fun keyDown(pitch: Int, velocity: Int, sound: Boolean) {
        val hand = if (pitch < settings.handSplitPitch) Hand.LEFT else Hand.RIGHT
        if (sound) synth?.noteOn(pitch, velocity)
        led.pressKey(pitch, hand, velocity)
        down[pitch] = hand
        binding.keyboard.setActive(HashMap(down))
    }

    private fun keyUp(pitch: Int) {
        led.releaseKey(pitch)
        down.remove(pitch)
        binding.keyboard.setActive(HashMap(down))
    }

    private fun releaseAll() {
        synth?.allNotesOff()
        touched.clear()
        down.clear()
        led.releaseAllKeys()
        binding.keyboard.setActive(emptyMap())
    }

    private fun highestLow(): Int = HIGHEST_C - 12 * octaves

    private fun shift(delta: Int) {
        val next = (low + delta).coerceIn(LOWEST_C, highestLow())
        if (next == low) return
        low = next
        applyRange()
    }

    private fun applyRange() {
        val high = low + 12 * octaves // from a C up to the C [octaves] higher
        binding.keyboard.setRange(low, high)
        binding.txtRange.text = getString(R.string.kb_range, keyName(low), keyName(high))
        binding.btnLower.isEnabled = low > LOWEST_C
        binding.btnHigher.isEnabled = low < highestLow()
    }

    private fun keyName(pitch: Int): String = NoteNames.name(pitch, "letters") + NoteNames.octave(pitch)

    private fun updateLed() {
        binding.txtStripInfo.text = getString(R.string.kb_strip_info, settings.wsNumLeds)
        binding.btnLed.text = when (led.state) {
            LedController.State.CONNECTED -> getString(R.string.lb_led_on_short)
            LedController.State.CONNECTING -> getString(R.string.lb_connecting)
            LedController.State.DISCONNECTED -> getString(R.string.kb_led_connect)
        }
    }

    companion object {
        private const val KEY_LOW = "low"
        private const val LOWEST_C = 24 // C1
        private const val HIGHEST_C = 108 // C8, the top key of an 88-key piano
        private const val TOUCH_VELOCITY = 96
    }
}

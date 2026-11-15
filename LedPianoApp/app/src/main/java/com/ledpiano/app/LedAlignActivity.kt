package com.ledpiano.app

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.ledpiano.app.databinding.ActivityLedAlignBinding
import com.ledpiano.app.led.BleScanner
import com.ledpiano.app.led.LedController
import com.ledpiano.app.midi.MidiSong
import com.ledpiano.app.ui.BleConnectDialog
import com.ledpiano.app.util.NoteNames
import com.ledpiano.app.util.padForSystemBars

/**
 * LED setup wizard, in plain steps instead of technical settings:
 *  1. count the LEDs (last LED lights at the end of the strip),
 *  2. line the strip up with the keys (light middle C, move it with ◀ ▶),
 *  3. brightness.
 * Per-key alignment only applies to the legacy per-key controller; the SP611E
 * lights 16 zones, so step 2 just explains that.
 */
class LedAlignActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLedAlignBinding
    private val app get() = application as LedPianoApp
    private val settings get() = app.settings
    private val led get() = app.ledController
    private lateinit var scanner: BleScanner

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) BleConnectDialog(this, scanner, led).show()
            else Toast.makeText(this, R.string.lb_permission_needed, Toast.LENGTH_LONG).show()
        }

    private val ledListener = object : LedController.Listener {
        override fun onLedStateChanged(state: LedController.State, deviceName: String?, message: String?) {
            updateStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityLedAlignBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()
        scanner = BleScanner(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAlignDone.setOnClickListener { finish() }
        binding.btnAlignConnect.setOnClickListener {
            if (led.state == LedController.State.CONNECTED) return@setOnClickListener
            if (!scanner.hasPermissions()) permissionLauncher.launch(scanner.requiredPermissions())
            else BleConnectDialog(this, scanner, led).show()
        }
        binding.btnAlignTest.setOnClickListener {
            if (!requireConnected()) return@setOnClickListener
            led.testLights()
        }

        // 1. LED count
        binding.btnCountDown.setOnClickListener { changeCount(-1) }
        binding.btnCountUp.setOnClickListener { changeCount(+1) }

        // 2. Key alignment
        binding.btnShiftLeft.setOnClickListener { shift(-1) }
        binding.btnShiftRight.setOnClickListener { shift(+1) }
        binding.btnShowMiddleC.setOnClickListener {
            if (requireConnected()) led.showKeys(listOf(MidiSong.MIDDLE_C))
        }
        binding.btnShowEnds.setOnClickListener {
            if (requireConnected()) led.showKeys(listOf(MidiSong.PIANO_LOWEST, MidiSong.PIANO_HIGHEST))
        }

        // 3. Brightness
        binding.seekBrightness.max = 255
        binding.seekBrightness.progress = settings.ledBrightness
        binding.seekBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) led.setBrightness(progress.coerceAtLeast(10))
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val perKey = led.supportsSingleKey()
        binding.txtKeyHint.text = getString(
            when {
                led.isWs2812() -> R.string.align_key_hint_ws
                perKey -> R.string.align_key_hint_nus
                else -> R.string.align_key_hint_sp611e
            },
        )
        val keyVis = if (perKey) View.VISIBLE else View.GONE
        binding.txtKeyValue.visibility = keyVis
        binding.btnShiftLeft.visibility = keyVis
        binding.btnShiftRight.visibility = keyVis
        binding.btnShowMiddleC.visibility = keyVis
        binding.btnShowEnds.visibility = keyVis
        updateValues()
    }

    override fun onStart() {
        super.onStart()
        led.listener = ledListener
        updateStatus()
    }

    override fun onStop() {
        if (led.listener === ledListener) led.listener = null
        led.endManual()
        super.onStop()
    }

    private fun requireConnected(): Boolean {
        if (led.state == LedController.State.CONNECTED) return true
        Toast.makeText(this, R.string.lb_led_needs_connect, Toast.LENGTH_SHORT).show()
        return false
    }

    private fun changeCount(delta: Int) {
        if (led.isWs2812()) {
            // WS2812B: the number of LEDs on the strip; show both ends so the last key can be checked.
            settings.wsNumLeds = settings.wsNumLeds + delta
            updateValues()
            if (led.state == LedController.State.CONNECTED) {
                val c = settings.wsConfig
                led.showKeys(listOf(c.lowestPitch, c.lowestPitch + c.numKeys - 1))
            }
            return
        }
        led.setLength((settings.ledLength + delta).coerceIn(1, 150))
        updateValues()
        if (led.state == LedController.State.CONNECTED && !led.supportsSingleKey()) led.testLights()
    }

    private fun shift(delta: Int) {
        if (led.isWs2812()) {
            // Slide every light by one LED (◀ = towards the bass end).
            settings.wsFirstLed = settings.wsFirstLed + if (settings.wsReversed) -delta else delta
            updateValues()
            if (led.state == LedController.State.CONNECTED) led.showKeys(listOf(MidiSong.MIDDLE_C))
            return
        }
        settings.organOffset = (settings.organOffset + delta).coerceIn(MidiSong.PIANO_LOWEST - 12, MidiSong.PIANO_HIGHEST)
        updateValues()
        if (led.state == LedController.State.CONNECTED) led.showKeys(listOf(MidiSong.MIDDLE_C))
    }

    private fun updateValues() {
        if (led.isWs2812()) {
            binding.txtCount.text = settings.wsNumLeds.toString()
            binding.txtKeyValue.text = getString(R.string.align_ws_value, settings.wsFirstLed)
            return
        }
        binding.txtCount.text = settings.ledLength.toString()
        val first = settings.organOffset
        val name = NoteNames.name(first, "letters") + NoteNames.octave(first)
        binding.txtKeyValue.text = getString(R.string.align_key_value, name)
    }

    private fun updateStatus() {
        val connected = led.state == LedController.State.CONNECTED
        binding.txtAlignStatus.text = if (connected) {
            getString(R.string.align_connected, led.deviceName ?: "")
        } else {
            getString(R.string.align_not_connected)
        }
        binding.txtAlignStatusHint.text =
            getString(if (connected) R.string.align_connected_hint else R.string.align_not_connected_hint)
        binding.btnAlignConnect.visibility = if (connected) View.GONE else View.VISIBLE
        if (connected && led.supportsSingleKey()) led.showKeys(listOf(MidiSong.MIDDLE_C))
        if (::binding.isInitialized) updateValues()
    }
}

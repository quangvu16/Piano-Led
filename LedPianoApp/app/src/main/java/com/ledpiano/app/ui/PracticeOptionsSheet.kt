package com.ledpiano.app.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.ledpiano.app.R
import com.ledpiano.app.databinding.SheetPracticeOptionsBinding
import com.ledpiano.app.input.InputSource
import com.ledpiano.app.model.AppSettings
import com.ledpiano.app.util.NoteNames

/**
 * The "Tùy chọn" sheet on the Practice screen. Everything that is not needed
 * every minute lives here, so the main screen stays simple. Each change is
 * saved to [AppSettings] and reported through [onChanged].
 */
class PracticeOptionsSheet(
    private val context: Context,
    private val settings: AppSettings,
    private val showSplit: Boolean,
    private val onChanged: (key: String) -> Unit,
    private val onBluetoothMidi: () -> Unit,
    private val onMicToggled: (Boolean) -> Unit,
    /** "Gửi bài này vào ESP32": store the song on the ESP32 strip for practice without the phone. */
    private val onSendEsp32: () -> Unit = {},
) {
    private var dialog: BottomSheetDialog? = null
    private var binding: SheetPracticeOptionsBinding? = null

    fun show(source: InputSource, deviceName: String?) {
        val b = SheetPracticeOptionsBinding.inflate(LayoutInflater.from(context))
        binding = b
        val d = BottomSheetDialog(context)
        d.setContentView(b.root)
        d.setOnDismissListener { if (dialog === d) dialog = null }
        dialog = d

        // Mode
        b.optModeNormal.setOnClickListener { set("mode") { settings.practiceMode = "normal" } }
        b.optModeWait.setOnClickListener { set("mode") { settings.practiceMode = "wait" } }
        b.optModeStep.setOnClickListener { set("mode") { settings.practiceMode = "step" } }
        // Lights
        b.optLightsFull.setOnClickListener { set("lights") { settings.lightsMode = "full" } }
        b.optLightsFade.setOnClickListener { set("lights") { settings.lightsMode = "fade" } }
        b.optLightsOff.setOnClickListener { set("lights") { settings.lightsMode = "off" } }
        // Labels
        b.optLabelNone.setOnClickListener { set("labels") { settings.labelMode = "none" } }
        b.optLabelC.setOnClickListener { set("labels") { settings.labelMode = "c" } }
        b.optLabelLetters.setOnClickListener { set("labels") { settings.labelMode = "letters" } }
        b.optLabelSolfege.setOnClickListener { set("labels") { settings.labelMode = "solfege" } }
        // Switches
        b.swCountIn.isChecked = settings.countIn
        b.swMetronome.isChecked = settings.metronome
        b.swAutoSpeed.isChecked = settings.autoSpeed
        b.swStaff.isChecked = settings.showStaff
        b.swFingers.isChecked = settings.showFingers
        b.swMic.isChecked = settings.micInput
        b.swCountIn.setOnCheckedChangeListener { _, v -> set("countIn") { settings.countIn = v } }
        b.swMetronome.setOnCheckedChangeListener { _, v -> set("metronome") { settings.metronome = v } }
        b.swAutoSpeed.setOnCheckedChangeListener { _, v -> set("autoSpeed") { settings.autoSpeed = v } }
        b.swStaff.setOnCheckedChangeListener { _, v -> set("staff") { settings.showStaff = v } }
        b.swFingers.setOnCheckedChangeListener { _, v -> set("fingers") { settings.showFingers = v } }
        b.swMic.setOnCheckedChangeListener { _, v -> onMicToggled(v) }
        // LED learning aids
        b.swLedFeedback.isChecked = settings.ledFeedback
        b.swLedPreview.isChecked = settings.ledPreview
        b.swLedDynamics.isChecked = settings.ledDynamics
        b.swLedFeedback.setOnCheckedChangeListener { _, v -> set("led") { settings.ledFeedback = v } }
        b.swLedPreview.setOnCheckedChangeListener { _, v -> set("led") { settings.ledPreview = v } }
        b.swLedDynamics.setOnCheckedChangeListener { _, v -> set("led") { settings.ledDynamics = v } }
        b.btnSendEsp32.setOnClickListener { onSendEsp32() }
        // Hand split (only for songs whose hands come from a pitch split)
        b.splitRow.visibility = if (showSplit) View.VISIBLE else View.GONE
        b.splitDown.setOnClickListener { set("split") { settings.handSplitPitch = settings.handSplitPitch - 1 } }
        b.splitUp.setOnClickListener { set("split") { settings.handSplitPitch = settings.handSplitPitch + 1 } }

        b.btnBleMidi.setOnClickListener { onBluetoothMidi() }
        b.btnInstrument.setOnClickListener { pickInstrument() }

        refresh()
        updateInput(source, deviceName)
        d.show()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    /** Called by the activity when the piano connection changes. */
    fun updateInput(source: InputSource, deviceName: String?) {
        val b = binding ?: return
        b.txtInputStatus.text = when (source) {
            InputSource.MIDI -> context.getString(R.string.opt_input_midi, deviceName ?: "MIDI")
            InputSource.MIC -> context.getString(R.string.opt_input_mic)
            InputSource.NONE -> context.getString(R.string.opt_input_none)
        }
    }

    fun setMicChecked(on: Boolean) {
        binding?.swMic?.isChecked = on
    }

    /** Grouped list: pianos, organs, bells. Picking one plays a short preview. */
    private fun pickInstrument() {
        val names = context.resources.getStringArray(R.array.instrument_entries)
        val values = context.resources.getStringArray(R.array.instrument_values).map { it.toInt() }
        val checked = values.indexOf(settings.instrument).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.opt_instrument)
            .setSingleChoiceItems(names, checked) { d, which ->
                set("instrument") { settings.instrument = values[which] }
                d.dismiss()
            }
            .setNegativeButton(R.string.lb_cancel, null)
            .show()
    }

    private fun set(key: String, change: () -> Unit) {
        change()
        refresh()
        onChanged(key)
    }

    private fun refresh() {
        val b = binding ?: return
        val values = context.resources.getStringArray(R.array.instrument_values).map { it.toInt() }
        val names = context.resources.getStringArray(R.array.instrument_entries)
        b.btnInstrument.text = names.getOrNull(values.indexOf(settings.instrument)) ?: names[0]
        val mode = settings.practiceMode
        b.optModeNormal.isSelected = mode == "normal"
        b.optModeWait.isSelected = mode == "wait"
        b.optModeStep.isSelected = mode == "step"
        b.txtModeHint.text = context.getString(
            when (mode) {
                "wait" -> R.string.opt_mode_wait_hint
                "step" -> R.string.opt_mode_step_hint
                else -> R.string.opt_mode_normal_hint
            },
        )
        val lights = settings.lightsMode
        b.optLightsFull.isSelected = lights == "full"
        b.optLightsFade.isSelected = lights == "fade"
        b.optLightsOff.isSelected = lights == "off"
        b.txtLightsHint.text = context.getString(
            when (lights) {
                "fade" -> R.string.opt_lights_fade_hint
                "off" -> R.string.opt_lights_off_hint
                else -> R.string.opt_lights_full_hint
            },
        )
        val labels = settings.labelMode
        b.optLabelNone.isSelected = labels == "none"
        b.optLabelC.isSelected = labels == "c"
        b.optLabelLetters.isSelected = labels == "letters"
        b.optLabelSolfege.isSelected = labels == "solfege"
        val split = settings.handSplitPitch
        b.splitValue.text = (NoteNames.name(split, "letters") ?: "") + NoteNames.octave(split)
    }
}

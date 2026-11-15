package com.ledpiano.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.ledpiano.app.databinding.ActivitySettingsBinding
import com.ledpiano.app.model.AppSettings
import com.ledpiano.app.midi.MidiSong
import com.ledpiano.app.util.padForSystemBars

/** App settings: LED protocol, effects, hand colours, volume, organ offset. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()
        binding.btnBack.setOnClickListener { finish() }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = AppSettings.PREFS_NAME
            setPreferencesFromResource(R.xml.preferences, rootKey)

            val settings = AppSettings(requireContext())
            val organ = findPreference<Preference>("organ_offset")
            organ?.summary = noteSummary(settings.organOffset)
            organ?.setOnPreferenceClickListener { pref ->
                showNotePicker(settings, pref)
                true
            }
            findPreference<Preference>("led_align")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), LedAlignActivity::class.java))
                true
            }
        }

        private fun noteSummary(pitch: Int): String =
            noteName(pitch) + " — " + getString(R.string.set_organ_offset_summary)

        private fun showNotePicker(settings: AppSettings, pref: Preference) {
            val pitches = (MidiSong.PIANO_LOWEST..MidiSong.PIANO_HIGHEST).toList()
            val names = pitches.map { noteName(it) }.toTypedArray()
            val checked = (settings.organOffset - MidiSong.PIANO_LOWEST)
                .coerceIn(0, pitches.size - 1)
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.set_organ_offset)
                .setSingleChoiceItems(names, checked) { dialog, which ->
                    val pitch = pitches[which]
                    settings.organOffset = pitch
                    pref.summary = noteSummary(pitch)
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.lb_cancel, null)
                .show()
        }

        private fun noteName(pitch: Int): String {
            val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
            return names[((pitch % 12) + 12) % 12] + (pitch / 12 - 1)
        }
    }
}

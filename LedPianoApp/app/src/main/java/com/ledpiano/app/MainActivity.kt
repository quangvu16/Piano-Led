package com.ledpiano.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.ledpiano.app.databinding.ActivityMainBinding
import com.ledpiano.app.led.BleScanner
import com.ledpiano.app.led.LedController
import com.ledpiano.app.midi.SongFiles
import com.ledpiano.app.practice.Learning
import com.ledpiano.app.practice.Lessons
import com.ledpiano.app.ui.BleConnectDialog
import com.ledpiano.app.ui.SongAdapter
import com.ledpiano.app.util.MidiIo
import com.ledpiano.app.util.padForSystemBars

/** Home screen: open MIDI files, browse recent songs and connect the LED strip. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val settings get() = (application as LedPianoApp).settings
    private val ledController get() = (application as LedPianoApp).ledController

    private lateinit var scanner: BleScanner
    private lateinit var songAdapter: SongAdapter
    private var connectDialog: BleConnectDialog? = null

    private val openMidiLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onMidiPicked(uri)
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                showBleDialog()
            } else {
                Toast.makeText(this, R.string.lb_permission_needed, Toast.LENGTH_LONG).show()
            }
        }

    private val ledListener = object : LedController.Listener {
        override fun onLedStateChanged(state: LedController.State, deviceName: String?, message: String?) {
            updateLedStatus(state, deviceName, message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()
        scanner = BleScanner(this)

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnUploadMidi.setOnClickListener {
            openMidiLauncher.launch(arrayOf("*/*"))
        }
        binding.btnExercises.setOnClickListener {
            startActivity(Intent(this, LessonsActivity::class.java))
        }
        binding.btnVoice.setOnClickListener {
            startActivity(Intent(this, com.ledpiano.app.voice.VoiceActivity::class.java))
        }
        binding.btnTestLed.setOnClickListener {
            if (ledController.state == LedController.State.CONNECTED) {
                ledController.testLights()
            } else {
                Toast.makeText(this, R.string.lb_led_needs_connect, Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnKeyboard.setOnClickListener {
            startActivity(Intent(this, KeyboardActivity::class.java))
        }
        binding.btnAlignLed.setOnClickListener {
            startActivity(Intent(this, LedAlignActivity::class.java))
        }
        binding.cardContinue.setOnClickListener {
            settings.recentSongs.firstOrNull()?.let { openSongEntry(it) }
        }
        binding.btnConnectLed.setOnClickListener {
            if (ledController.state == LedController.State.CONNECTED) {
                ledController.disconnect()
            } else {
                requestBleAndShowDialog()
            }
        }

        songAdapter = SongAdapter(
            items = settings.recentSongs.toMutableList(),
            onClick = { entry -> openSongEntry(entry) },
            onLongPress = { entry -> confirmRemoveSong(entry) },
        )
        // One column in portrait, two in landscape (values-land/dimens.xml).
        binding.recyclerSongs.layoutManager =
            GridLayoutManager(this, resources.getInteger(R.integer.song_columns))
        binding.recyclerSongs.adapter = songAdapter
    }

    override fun onStart() {
        super.onStart()
        ledController.listener = ledListener
    }

    override fun onResume() {
        super.onResume()
        refreshSongs()
        refreshProgress()
        updateLedStatus(ledController.state, ledController.deviceName, null)
        ledController.sendConfig() // ESP32: settings may have changed (keys, LEDs, colours)
        // Reconnect to the last strip automatically (once per app start).
        if (scanner.hasPermissions()) ledController.autoConnect(this)
    }

    override fun onStop() {
        if (ledController.listener === ledListener) ledController.listener = null
        super.onStop()
    }

    // ---- songs ----

    private fun refreshSongs() {
        val songs = settings.recentSongs
        songAdapter.replace(songs)
        binding.txtEmpty.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Streak, today's minutes vs the daily goal, the 7-day bars and the "continue" card. */
    private fun refreshProgress() {
        val stats = (application as LedPianoApp).stats
        val streak = stats.streakDays()
        binding.txtStreak.text = if (streak > 0) {
            getString(R.string.home_streak, streak)
        } else {
            getString(R.string.home_streak_zero)
        }
        val goal = settings.dailyGoalMinutes
        binding.txtToday.text = getString(R.string.home_today, stats.todaySeconds() / 60, goal)
        binding.weekBars.setData(stats.lastSevenDays(), goal)

        val last = settings.recentSongs.firstOrNull()
        binding.cardContinue.visibility = if (last == null) View.GONE else View.VISIBLE
        binding.txtContinue.text = last?.substringAfter('|', last).orEmpty()
        refreshPlan()
    }

    /** "Kế hoạch hôm nay": warm-up, review the song not played for longest, one new lesson (5–10 min). */
    private fun refreshPlan() {
        val stats = (application as LedPianoApp).stats
        val lessons = Lessons.levels(this).flatMap { it.lessons }
        val plan = Learning.dailyPlan(lessons, stats.today(), { stats.lastDay(it) }, { stats.record(it).completed })
        val list = binding.planList
        list.removeAllViews()
        val dp = resources.displayMetrics.density
        for ((kind, lesson) in plan) {
            val row = android.widget.TextView(this)
            row.text = getString(R.string.plan_item, kind.vi, lesson.title)
            row.setTextColor(ContextCompat.getColor(this, R.color.text_hi))
            row.textSize = 15f
            row.minHeight = (48 * dp).toInt()
            row.gravity = android.view.Gravity.CENTER_VERTICAL
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener { openSong(lesson.uri, lesson.title) }
            list.addView(row)
        }
        binding.planCard.visibility = if (plan.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun confirmRemoveSong(entry: String) {
        val name = entry.substringAfter('|', entry)
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.lb_delete_song_question, name))
            .setPositiveButton(R.string.lb_delete) { _, _ ->
                settings.removeRecentSong(entry)
                refreshSongs()
            }
            .setNegativeButton(R.string.lb_cancel, null)
            .show()
    }

    private fun onMidiPicked(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // Not every provider grants persistable permissions – ignore.
        }
        val name = MidiIo.queryDisplayName(this, uri)
        Thread {
            val ok = try {
                MidiIo.openStream(this, uri.toString()).use { stream ->
                    SongFiles.parse(stream, name, settings.handSplitPitch) // MIDI or MusicXML (.musicxml / .mxl)
                }
                true
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                if (ok) {
                    openSong(uri.toString(), name)
                } else {
                    Toast.makeText(this, R.string.lb_error_open_file, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun openSongEntry(entry: String) {
        val sep = entry.indexOf('|')
        if (sep < 0) return
        openSong(entry.take(sep), entry.substring(sep + 1))
    }

    private fun openSong(uri: String, name: String) {
        settings.addRecentSong(uri, name)
        refreshSongs()
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_URI, uri)
                .putExtra(PlayerActivity.EXTRA_NAME, name),
        )
    }

    // ---- LED connection ----

    private fun requestBleAndShowDialog() {
        if (!scanner.hasPermissions()) {
            permissionLauncher.launch(scanner.requiredPermissions())
        } else {
            showBleDialog()
        }
    }

    private fun showBleDialog() {
        val dialog = BleConnectDialog(this, scanner, ledController)
        connectDialog = dialog
        dialog.show()
    }

    private fun updateLedStatus(state: LedController.State, deviceName: String?, message: String?) {
        val connected = state == LedController.State.CONNECTED
        binding.imgLedIcon.setBackgroundResource(
            if (connected) R.drawable.bg_tile_ok else R.drawable.bg_tile_neutral,
        )
        binding.imgLedIcon.imageTintList = ContextCompat.getColorStateList(
            this,
            if (connected) R.color.ok_fg else R.color.text_mid,
        )
        when (state) {
            LedController.State.CONNECTED -> {
                binding.txtLedStatus.text = getString(R.string.home_led_on_title)
                binding.txtLedHint.text = if (deviceName.isNullOrBlank()) {
                    getString(R.string.home_led_on_hint)
                } else {
                    deviceName + " · " + getString(R.string.home_led_on_hint)
                }
                binding.btnConnectLed.text = getString(R.string.bt_disconnect_short)
                binding.btnConnectLed.isEnabled = true
            }
            LedController.State.CONNECTING -> {
                binding.txtLedStatus.text = getString(R.string.lb_connecting)
                binding.txtLedHint.text = getString(R.string.home_led_wait_hint)
                binding.btnConnectLed.isEnabled = false
            }
            LedController.State.DISCONNECTED -> {
                binding.txtLedStatus.text = getString(R.string.home_led_off_title)
                binding.txtLedHint.text = message ?: getString(R.string.home_led_off_hint)
                binding.btnConnectLed.text = getString(R.string.bt_connect)
                binding.btnConnectLed.isEnabled = true
            }
        }
    }
}

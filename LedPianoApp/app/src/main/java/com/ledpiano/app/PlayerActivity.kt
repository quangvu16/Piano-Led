package com.ledpiano.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ledpiano.app.databinding.ActivityPlayerBinding
import com.ledpiano.app.input.InputSource
import com.ledpiano.app.input.NoteInputListener
import com.ledpiano.app.led.BleScanner
import com.ledpiano.app.led.LedController
import com.ledpiano.app.midi.Hand
import com.ledpiano.app.midi.HandAssigner
import com.ledpiano.app.midi.MidiNote
import com.ledpiano.app.midi.SongFiles
import com.ledpiano.app.midi.MidiSong
import com.ledpiano.app.playback.PlaybackEngine
import com.ledpiano.app.playback.SynthEngine
import com.ledpiano.app.practice.Coach
import com.ledpiano.app.practice.Learning
import com.ledpiano.app.practice.Lessons
import com.ledpiano.app.practice.ScoreKeeper
import com.ledpiano.app.ui.BleConnectDialog
import com.ledpiano.app.ui.PianoRollView
import com.ledpiano.app.ui.PracticeOptionsSheet
import com.ledpiano.app.util.MidiIo
import com.ledpiano.app.util.NoteNames
import com.ledpiano.app.util.padForSystemBars
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Practice screen for learners.
 *
 * Always visible: falling notes, a zoomed keyboard, Hands (Left / Both /
 * Right), Speed (25–100 %) and Restart / Play / Repeat. Everything else is in
 * the options sheet: Wait / Step modes, fading lights, note names, count-in,
 * metronome, auto speed-up, staff, finger numbers, hand split and the piano
 * connection (USB / Bluetooth MIDI or microphone).
 *
 * Works in portrait and landscape. The manifest keeps this activity alive on
 * rotation (configChanges), so the song keeps playing; [onConfigurationChanged]
 * swaps in the layout for the new orientation via [bindUi].
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_NAME = "extra_name"
        /** Start playing as soon as the song is loaded (voice "play song X"). */
        const val EXTRA_AUTOPLAY = "extra_autoplay"

        /** How far ahead the keyboard highlights keys to press. */
        private const val LOOKAHEAD_SEC = 2.5

        /** Minimum keyboard width shown, in octaves. */
        private const val MIN_OCTAVES_PORTRAIT = 2
        private const val MIN_OCTAVES_LANDSCAPE = 3

        /** "Repeat" loops this many measures starting at the current one (then drag to change). */
        private const val REPEAT_MEASURES = 2
        private const val REPEAT_FALLBACK_SEC = 8.0

        private const val SPEED_STEP = 5
        private const val STATS_FLUSH_SEC = 15.0

        fun newIntent(context: Context, uri: String, name: String): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URI, uri)
                .putExtra(EXTRA_NAME, name)
    }

    /** Which hand the learner is practising; the other hand is shown faded. */
    private enum class Practice { LEFT, BOTH, RIGHT }

    private lateinit var binding: ActivityPlayerBinding
    private val app get() = application as LedPianoApp
    private val settings get() = app.settings
    private val ledController get() = app.ledController
    private val hub get() = app.inputHub

    private val mainHandler = Handler(Looper.getMainLooper())

    private var songUri: String = ""
    private var song: MidiSong? = null
    private var synth: SynthEngine? = null
    private var engine: PlaybackEngine? = null
    private var measureSecs: List<Double> = emptyList()
    private var lesson: Lessons.Lesson? = null
    /** Finger number per note index in [MidiSong.notes] (lesson songs only). */
    private var fingering: Map<Int, Int> = emptyMap()
    private val noteIndex = IdentityHashMap<MidiNote, Int>()

    private var practice = Practice.BOTH
    private val showLeft get() = practice != Practice.RIGHT
    private val showRight get() = practice != Practice.LEFT
    private var speedPercent = 100

    private val coach = Coach()
    private val score = ScoreKeeper()
    private val hot by lazy { Learning.HotSpots(this) }
    /** The bar being practised with "Chỗ khó" (a clean loop lowers its mistake count). */
    private var hotBar: Int? = null
    private val dynamics = Learning.Dynamics()
    private val recorder = Learning.Recorder()
    /** Start times of [MidiSong.notesByTime], for the look-ahead glow. */
    private var startTimes = DoubleArray(0)
    private var passActive = false
    private var passStart = 0.0
    private var waitRemaining: MutableSet<Int> = HashSet()
    /** Keys held on the learner's piano: true = expected, false = wrong. */
    private val pressed = HashMap<Int, Boolean>()

    private val activeNotes = ConcurrentHashMap<Int, Hand>()
    private val activeFingers = ConcurrentHashMap<Int, Int>()
    /** pitch → the song note that currently lights that key (see onNoteEnd). */
    private val activeOwner = ConcurrentHashMap<Int, MidiNote>()

    @Volatile private var userSeeking = false

    private var practiceNanos = 0L
    private var unsavedSec = 0.0

    private lateinit var scanner: BleScanner
    private var loadingDialog: AlertDialog? = null
    private var optionsSheet: PracticeOptionsSheet? = null

    private val hideFeedback = Runnable { binding.txtFeedback.visibility = View.GONE }

    /** Landscape: the right-side controls slide away while practising. */
    private var panelShown = true
    private val autoHidePanel = Runnable {
        if (isLandscape() && engine?.isPlaying == true) setPanelShown(false)
    }

    private fun setPanelShown(shown: Boolean) {
        panelShown = shown
        val land = isLandscape()
        android.transition.TransitionManager.beginDelayedTransition(binding.root as android.view.ViewGroup)
        binding.controlsPanel.visibility = if (shown || !land) View.VISIBLE else View.GONE
        binding.btnShowControls.visibility = if (!shown && land) View.VISIBLE else View.GONE
    }

    /** Hide the side panel [delayMs] after playback starts (landscape only). */
    private fun scheduleAutoHide(delayMs: Long) {
        mainHandler.removeCallbacks(autoHidePanel)
        if (isLandscape() && engine?.isPlaying == true) mainHandler.postDelayed(autoHidePanel, delayMs)
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                BleConnectDialog(this, scanner, ledController).show()
            } else {
                Toast.makeText(this, R.string.lb_permission_needed, Toast.LENGTH_LONG).show()
            }
        }

    private val blePermissionForMidi =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) pickBluetoothMidi()
            else Toast.makeText(this, R.string.lb_permission_needed, Toast.LENGTH_LONG).show()
        }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                settings.micInput = true
                startMic()
            } else {
                settings.micInput = false
                optionsSheet?.setMicChecked(false)
                Toast.makeText(this, R.string.lb_mic_permission, Toast.LENGTH_LONG).show()
            }
        }

    private val ledListener = object : LedController.Listener {
        override fun onLedStateChanged(state: LedController.State, deviceName: String?, message: String?) {
            updateLedChip(state, deviceName)
        }
    }

    // ---- lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        scanner = BleScanner(this)

        val uri = intent.getStringExtra(EXTRA_URI)
        val name = intent.getStringExtra(EXTRA_NAME)
        if (uri.isNullOrBlank()) {
            finish()
            return
        }
        songUri = uri
        speedPercent = settings.lastSpeedPercent.coerceIn(PlaybackEngine.MIN_SPEED, PlaybackEngine.MAX_SPEED)
        coach.lights = lightsFromSettings()
        lesson = Lessons.find(this, uri)

        bindUi()
        loadSong(uri, name ?: "song.mid")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Portrait <-> landscape: re-inflate (layout vs layout-land) and restore
        // the current state. Playback and the LED feed are not interrupted.
        bindUi()
    }

    override fun onStart() {
        super.onStart()
        ledController.listener = ledListener
        hub.addListener(inputListener)
        if (settings.micInput && hasMicPermission()) startMic()
        applyEngineOptions()
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return // finished early in onCreate (no song)
        synth?.volume = settings.volume / 100f
        applyColors()
        updateLedChip(ledController.state, ledController.deviceName)
        practiceNanos = System.nanoTime()
        ledController.sendConfig() // ESP32: settings may have changed (keys, LEDs, colours)
    }

    override fun onPause() {
        flushStats(force = true)
        super.onPause()
    }

    override fun onStop() {
        if (ledController.listener === ledListener) ledController.listener = null
        hub.removeListener(inputListener)
        ledController.releaseAllKeys()
        app.micInput.stop()
        super.onStop()
    }

    override fun onDestroy() {
        engine?.stop()
        synth?.stop()
        ledController.setPlaying(false)
        ledController.guidance = 1f
        if (ledController.listener === ledListener) ledController.listener = null
        optionsSheet?.dismiss()
        super.onDestroy()
    }

    /** Bluetooth page-turner pedals and keyboards: next note in Step mode, Space = play/pause. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val e = engine
        if (e != null && e.waitingChord != null && settings.practiceMode == "step") {
            when (keyCode) {
                KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_DPAD_CENTER -> {
                    stepForward()
                    return true
                }
            }
        }
        if (keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            togglePlay()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---- UI binding (runs on create and on every rotation) ----

    private fun bindUi() {
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()

        binding.txtSongTitle.text = songTitle()
        setupControls()
        applyColors()
        applySongToViews()
        applyHandUi()
        applySpeedUi()
        applyDisplayOptions()
        applyGuidance()
        applyPlayingUi(engine?.isPlaying == true)
        applyRepeatUi()
        updateModeChip()
        updateLedChip(ledController.state, ledController.deviceName)
        updateTimeUi(engine?.positionSec ?: 0.0)
        binding.keyboard.setActive(HashMap(activeNotes))
        binding.keyboard.setPressed(HashMap(pressed))
        engine?.waitingChord?.let { showWaitPrompt(it) }
        // After rotation: keep the panel hidden if we were practising in landscape.
        panelShown = !(isLandscape() && engine?.isPlaying == true)
        binding.controlsPanel.visibility = if (panelShown || !isLandscape()) View.VISIBLE else View.GONE
        binding.btnShowControls.visibility = if (!panelShown && isLandscape()) View.VISIBLE else View.GONE
    }

    private fun songTitle(): String =
        lesson?.title ?: song?.title?.takeIf { it.isNotBlank() } ?: intent.getStringExtra(EXTRA_NAME).orEmpty()

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun applyColors() {
        binding.pianoRoll.setHandColors(settings.colorLeft, settings.colorRight)
        binding.keyboard.setHandColors(settings.colorLeft, settings.colorRight)
        binding.staff.setHandColors(settings.colorLeft, settings.colorRight)
    }

    private fun applySongToViews() {
        val (low, high) = keyRangeFor(song)
        binding.pianoRoll.setRange(low, high)
        binding.keyboard.setRange(low, high)

        val s = song ?: return
        binding.pianoRoll.setSong(s)
        binding.staff.setSong(s)
        binding.pianoRoll.listener = object : PianoRollView.Listener {
            override fun onScrubRequested(positionSec: Double) {
                engine?.seek(positionSec)
                passActive = false
            }

            override fun onTap() {
                if (engine?.waitingChord != null && settings.practiceMode == "step") stepForward()
            }
        }
        binding.pianoRoll.setFingers(if (settings.showFingers) fingering else emptyMap())
        binding.txtTimeTotal.text = PianoRollView.formatTime(s.durationSec)
        binding.loopBar.setSong(s.durationSec, measureSecs)
        applyHotMarks()
    }

    /** Note names, staff and finger numbers, from the options. */
    private fun applyDisplayOptions() {
        val labels = settings.labelMode
        binding.keyboard.setLabelMode(labels)
        binding.pianoRoll.setLabelMode(labels)
        binding.staff.visibility = if (settings.showStaff) View.VISIBLE else View.GONE
        binding.pianoRoll.setFingers(if (settings.showFingers) fingering else emptyMap())
        if (!settings.showFingers) binding.keyboard.setFingers(emptyMap())
    }

    /**
     * Keys to show: the song's own range rounded out to whole octaves (C…B),
     * widened to at least 2 octaves in portrait / 3 in landscape. A narrower
     * range means bigger keys, which is easier for learners than all 88.
     */
    private fun keyRangeFor(s: MidiSong?): Pair<Int, Int> {
        val minOctaves = if (isLandscape()) MIN_OCTAVES_LANDSCAPE else MIN_OCTAVES_PORTRAIT
        val pitches = s?.notes?.map { it.pitch }.orEmpty()
        var low = ((pitches.minOrNull() ?: 60) / 12) * 12
        var high = ((pitches.maxOrNull() ?: 71) / 12) * 12 + 11
        var growUp = true
        while ((high - low + 1) / 12 < minOctaves) {
            val canUp = high + 12 <= MidiSong.PIANO_HIGHEST
            val canDown = low - 12 >= MidiSong.PIANO_LOWEST
            if (!canUp && !canDown) break
            if ((growUp && canUp) || !canDown) high += 12 else low -= 12
            growUp = !growUp
        }
        return low.coerceAtLeast(MidiSong.PIANO_LOWEST) to high.coerceAtMost(MidiSong.PIANO_HIGHEST)
    }

    // ---- song loading ----

    private fun loadSong(uri: String, name: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage(R.string.lb_loading_song)
            .setCancelable(false)
            .create()
        loadingDialog = dialog
        dialog.show()

        val split = settings.handSplitPitch
        Thread {
            val result: MidiSong? = try {
                MidiIo.openStream(this, uri).use { stream ->
                    SongFiles.parse(stream, name, split) // MIDI or MusicXML
                }
            } catch (_: Exception) {
                null
            }
            mainHandler.post {
                loadingDialog?.dismiss()
                loadingDialog = null
                if (result == null) {
                    Toast.makeText(this, R.string.lb_error_open_file, Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    onSongLoaded(result)
                }
            }
        }.start()
    }

    private fun onSongLoaded(loaded: MidiSong) {
        song = loaded
        measureSecs = loaded.measureGridTicks()
            .map { loaded.tickToSec(it) }
            .filter { it <= loaded.durationSec }
        noteIndex.clear()
        for ((i, n) in loaded.notes.withIndex()) noteIndex[n] = i
        fingering = fingeringFor(loaded)
        startTimes = loaded.notesByTime.map { it.startSec }.toDoubleArray()
        // A right-hand-only lesson starts with the right hand selected.
        practice = when (lesson?.hands) {
            "R" -> Practice.RIGHT
            "L" -> Practice.LEFT
            else -> practice
        }

        binding.txtSongTitle.text = songTitle()
        applySongToViews()
        applyHandUi()

        val synthEngine = SynthEngine(app.soundBank)
        synthEngine.program = settings.instrument
        synthEngine.volume = settings.volume / 100f
        synthEngine.start()
        synth = synthEngine

        val playbackEngine = PlaybackEngine(loaded, synthEngine, playbackListener)
        playbackEngine.speedPercent = speedPercent
        engine = playbackEngine
        applyEngineOptions()
        updateTimeUi(0.0)

        if (loaded.isMultiInstrument) {
            AlertDialog.Builder(this)
                .setTitle(R.string.lb_warning)
                .setMessage(R.string.lb_music_sheet_not_composed_piano)
                .setPositiveButton(R.string.lb_ok, null)
                .setNegativeButton(R.string.lb_cancel) { _, _ -> finish() }
                .show()
        }

        // Opened by voice with "play it": start once (not again after rotation).
        if (intent.getBooleanExtra(EXTRA_AUTOPLAY, false)) {
            intent.removeExtra(EXTRA_AUTOPLAY)
            mainHandler.postDelayed({ if (engine?.isPlaying == false) togglePlay() }, 400)
        }
    }

    // ---- practice rules ----

    private fun isPractised(n: MidiNote): Boolean = when (practice) {
        Practice.LEFT -> n.hand != Hand.RIGHT
        Practice.RIGHT -> n.hand != Hand.LEFT
        Practice.BOTH -> true
    }

    private fun lightsFromSettings(): Coach.Lights = when (settings.lightsMode) {
        "fade" -> Coach.Lights.FADE
        "off" -> Coach.Lights.OFF
        else -> Coach.Lights.FULL
    }

    /** Wait mode needs a connected piano (or the mic); without one it behaves like normal play. */
    private fun effectiveMode(): String {
        val m = settings.practiceMode
        return if (m == "wait" && !hub.isConnected) "normal" else m
    }

    private fun applyEngineOptions() {
        val e = engine ?: return
        val mode = effectiveMode()
        e.gateFilter = { n -> isPractised(n) }
        e.gateMode = mode == "wait" || mode == "step"
        e.metronome = settings.metronome
        e.muteFilter = when (hub.source) {
            // The learner's own piano sounds their notes; the mic must not hear the app.
            InputSource.MIDI -> { n -> isPractised(n) }
            InputSource.MIC -> { _ -> true }
            InputSource.NONE -> { _ -> false }
        }
        if (!e.gateMode) hideOverlay()
    }

    private fun startPass(from: Double) {
        val s = song ?: return
        val e = engine
        val end = if (e?.looping == true) e.loopEndSec ?: s.durationSec else s.durationSec
        passStart = from
        score.startPass(s.notes.filter { isPractised(it) && it.startSec >= from - 1e-3 && it.startSec < end })
        passActive = true
    }

    private fun onCoachCorrect() {
        if (coach.onCorrect()) applyGuidance()
    }

    private fun onCoachError() {
        if (coach.onError()) applyGuidance()
    }

    private fun applyGuidance() {
        val g = coach.guidance
        binding.pianoRoll.setGuidance(g)
        binding.keyboard.setGuidance(g)
        binding.staff.setGuidance(g)
        ledController.guidance = g
    }

    // ---- piano input ----

    private val inputListener = object : NoteInputListener {
        override fun onNoteOn(pitch: Int, velocity: Int) {
            val e = engine
            val chord = e?.waitingChord
            val ok: Boolean
            recorder.note(pitch, velocity)
            val fromMidi = hub.source == InputSource.MIDI
            // The LED above the key the learner presses lights at once and stays on while it is held.
            ledController.pressKey(pitch, if (pitch < settings.handSplitPitch) Hand.LEFT else Hand.RIGHT, velocity)
            if (e != null && chord != null && settings.practiceMode == "wait") {
                if (chord.any { it.pitch == pitch }) {
                    ok = true
                    ledController.flash(pitch, Learning.GREEN, 150)
                    if (fromMidi) dynamics.add(chord.first { it.pitch == pitch }.velocity, velocity)
                    waitRemaining.remove(pitch)
                    if (waitRemaining.isEmpty()) {
                        score.markChordDone(chord)
                        onCoachCorrect()
                        e.releaseGate()
                        hideOverlay()
                    }
                } else {
                    ok = false
                    score.countWrong()
                    ledController.flash(pitch, Learning.RED, 300)
                    missAt(e.positionSec)
                    onCoachError()
                    showFeedback(getString(R.string.fb_wrong), good = false)
                }
            } else if (e != null && e.isPlaying && !e.isCountingIn && passActive) {
                val v = score.onKey(pitch, e.positionSec, speedPercent)
                ok = v != ScoreKeeper.Verdict.WRONG
                if (v == ScoreKeeper.Verdict.HIT) {
                    ledController.flash(pitch, Learning.timingFlash(score.lastOffsetMs), 180)
                    score.lastNote?.let { if (fromMidi) dynamics.add(it.velocity, velocity) }
                    onCoachCorrect()
                }
                if (v == ScoreKeeper.Verdict.WRONG) {
                    ledController.flash(pitch, Learning.RED, 300)
                    missAt(e.positionSec)
                    onCoachError()
                    showFeedback(getString(R.string.fb_wrong), good = false)
                }
            } else {
                // Free play: the pressed key is already lit (pressKey above).
                ok = true
            }
            pressed[pitch] = ok
            binding.keyboard.setPressed(HashMap(pressed))
        }

        override fun onNoteOff(pitch: Int) {
            recorder.note(pitch, 0)
            pressed.remove(pitch)
            binding.keyboard.setPressed(HashMap(pressed))
            ledController.releaseKey(pitch)
        }

        override fun onInputStateChanged(source: InputSource, deviceName: String?) {
            applyEngineOptions()
            updateModeChip()
            optionsSheet?.updateInput(source, deviceName)
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startMic() {
        if (!app.micInput.start()) {
            settings.micInput = false
            optionsSheet?.setMicChecked(false)
            Toast.makeText(this, R.string.lb_mic_permission, Toast.LENGTH_LONG).show()
        }
    }

    private fun pickBluetoothMidi() {
        if (!app.midiInput.isSupported) {
            Toast.makeText(this, R.string.opt_midi_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        BleConnectDialog(this, scanner, ledController) { device ->
            app.midiInput.openBluetooth(device.address) { ok ->
                if (!ok) Toast.makeText(this, R.string.opt_midi_failed, Toast.LENGTH_LONG).show()
            }
        }.show()
    }

    // ---- playback listener (called on the playback thread) ----

    private val playbackListener = object : PlaybackEngine.Listener {

        override fun onNoteStart(note: MidiNote) {
            activeNotes[note.pitch] = note.hand
            activeOwner[note.pitch] = note
            noteIndex[note]?.let { idx -> fingering[idx]?.let { activeFingers[note.pitch] = it } }
            ledController.onNoteStart(note)
            updatePreview(note.startSec)
            mainHandler.post { binding.keyboard.setActive(HashMap(activeNotes)) }
        }

        override fun onNoteEnd(note: MidiNote) {
            ledController.onNoteEnd(note)
            // Same key struck again while this note was ending: the new note keeps the key lit.
            val owner = activeOwner[note.pitch]
            if (owner != null && owner !== note) return
            activeOwner.remove(note.pitch)
            activeNotes.remove(note.pitch)
            activeFingers.remove(note.pitch)
            mainHandler.post { binding.keyboard.setActive(HashMap(activeNotes)) }
        }

        override fun onTimeUpdate(positionSec: Double) {
            mainHandler.post { updateTimeUi(positionSec) }
        }

        override fun onStateChange(playing: Boolean) {
            ledController.setPlaying(playing)
            mainHandler.post {
                applyPlayingUi(playing)
                if (!playing) flushStats(force = true)
                practiceNanos = System.nanoTime()
            }
        }

        override fun onSongEnd() {
            mainHandler.post {
                activeNotes.clear()
                activeOwner.clear()
                activeFingers.clear()
                binding.keyboard.setActive(emptyMap())
                binding.keyboard.setUpcoming(emptyMap())
                ledController.allNotesOff()
                onSongFinished()
            }
        }

        override fun onWaiting(chord: List<MidiNote>) {
            mainHandler.post { showWaitPrompt(chord) }
        }

        override fun onCountIn(beatsLeft: Int) {
            mainHandler.post {
                if (beatsLeft > 0) {
                    binding.txtOverlay.text = beatsLeft.toString()
                    binding.txtOverlay.textSize = 56f
                    binding.txtOverlay.visibility = View.VISIBLE
                } else {
                    hideOverlay()
                }
            }
        }

        override fun onLoopWrapped() {
            mainHandler.post { onPassFinished() }
        }
    }

    private fun showWaitPrompt(chord: List<MidiNote>) {
        val mode = settings.practiceMode
        // Light the chord on the strip while we wait for it.
        for (n in chord) ledController.keyOn(n.pitch, n.hand, n.velocity)
        chord.firstOrNull()?.let { updatePreview(it.startSec) }
        if (mode == "wait") {
            // The microphone hears one note at a time: wait for the top note of the chord.
            waitRemaining = if (hub.source == InputSource.MIC) {
                hashSetOf(chord.maxOf { it.pitch })
            } else {
                chord.map { it.pitch }.toHashSet()
            }
            val labelMode = if (settings.labelMode == "solfege") "solfege" else "letters"
            val names = chord.map { it.pitch }.distinct().sorted()
                .joinToString("  ") { NoteNames.name(it, labelMode) + NoteNames.octave(it) }
            showOverlay(getString(R.string.wait_press, names))
        } else {
            showOverlay(getString(R.string.step_tap))
        }
    }

    private fun stepForward() {
        val e = engine ?: return
        val chord = e.waitingChord ?: return
        score.markChordDone(chord)
        e.releaseGate()
        hideOverlay()
    }

    private fun showOverlay(text: String) {
        binding.txtOverlay.textSize = 22f
        binding.txtOverlay.text = text
        binding.txtOverlay.visibility = View.VISIBLE
    }

    private fun hideOverlay() {
        binding.txtOverlay.visibility = View.GONE
    }

    private fun showFeedback(text: String, good: Boolean) {
        binding.txtFeedback.text = text
        binding.txtFeedback.setBackgroundResource(if (good) R.drawable.bg_chip_ok else R.drawable.bg_chip_off)
        binding.txtFeedback.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideFeedback)
        mainHandler.postDelayed(hideFeedback, 1200)
    }

    // ---- passes, results and stats ----

    /** One loop pass done: score it, maybe change speed, start the next pass. */
    private fun onPassFinished() {
        val e = engine ?: return
        if (hub.isConnected && passActive) {
            val upTo = e.loopEndSec ?: e.positionSec
            recordMisses(upTo)
            val result = score.finishPass(upTo)
            app.stats.recordResult(songUri, result, finishedSong = false, speedPercent = speedPercent)
            hotBar?.let { if (result.isClean) { hot.addClean(songUri, it); applyHotMarks() } }
            dynamicsAdvice()?.let { showFeedback(it, good = true) }
            val newSpeed = coach.onPass(result, speedPercent, settings.autoSpeed)
            when {
                newSpeed != null && newSpeed > speedPercent -> {
                    setSpeed(newSpeed)
                    Toast.makeText(this, getString(R.string.speed_up_toast, newSpeed), Toast.LENGTH_SHORT).show()
                }
                newSpeed != null -> {
                    setSpeed(newSpeed)
                    Toast.makeText(this, getString(R.string.speed_down_toast, newSpeed), Toast.LENGTH_SHORT).show()
                }
                result.total > 0 -> Toast.makeText(
                    this, getString(R.string.pass_toast, result.accuracy, result.wrong), Toast.LENGTH_SHORT,
                ).show()
            }
        }
        startPass(e.loopStartSec ?: 0.0)
    }

    private fun onSongFinished() {
        val s = song ?: return
        flushStats(force = true)
        val message: String
        if (hub.isConnected && passActive) {
            val r = score.finishPass(s.durationSec)
            app.stats.recordResult(songUri, r, finishedSong = true, speedPercent = speedPercent)
            val timing = when {
                r.hits == 0 -> ""
                r.meanOffsetMs > 60 -> getString(R.string.result_timing_late, r.meanOffsetMs)
                r.meanOffsetMs < -60 -> getString(R.string.result_timing_early, -r.meanOffsetMs)
                else -> getString(R.string.result_timing_ok)
            }
            recordMisses(s.durationSec)
            message = getString(R.string.result_score, r.accuracy, r.hits, r.total, r.wrong) +
                (if (timing.isNotEmpty()) "\n" + timing else "") +
                (dynamicsAdvice()?.let { "\n$it." } ?: "") +
                (hot.worst(songUri)?.let { "\n" + getString(R.string.hot_worst, it + 1) } ?: "")
        } else {
            app.stats.markCompleted(songUri, speedPercent)
            message = getString(R.string.result_no_input)
        }
        passActive = false

        val next = Lessons.next(this, songUri)
        val b = AlertDialog.Builder(this)
            .setTitle(R.string.result_title)
            .setMessage(message)
            .setPositiveButton(R.string.result_again) { _, _ -> restart(); togglePlay() }
        if (next != null) {
            b.setNegativeButton(R.string.result_next) { _, _ ->
                settings.addRecentSong(next.uri, next.title)
                startActivity(newIntent(this, next.uri, next.title))
                finish()
            }
        }
        if (coach.lights != Coach.Lights.OFF) {
            // Research: finish with a pass without guidance so the song is learned, not followed.
            b.setNeutralButton(R.string.result_lights_off) { _, _ ->
                settings.lightsMode = "off"
                coach.lights = Coach.Lights.OFF
                applyGuidance()
                restart()
                togglePlay()
            }
        }
        b.show()
    }

    /** Adds the real practice time since the last call; saves every [STATS_FLUSH_SEC]. */
    private fun flushStats(force: Boolean) {
        val now = System.nanoTime()
        if (engine?.isPlaying == true) {
            unsavedSec += ((now - practiceNanos) / 1e9).coerceIn(0.0, 2.0)
        }
        practiceNanos = now
        if (songUri.isNotEmpty() && (force || unsavedSec >= STATS_FLUSH_SEC) && unsavedSec >= 1.0) {
            app.stats.addPractice(songUri, unsavedSec.toInt(), speedPercent)
            unsavedSec -= unsavedSec.toInt()
        }
    }

    // ---- time / keyboard ----

    private fun updateTimeUi(pos: Double) {
        val s = song ?: return
        binding.pianoRoll.setPositionSec(pos)
        if (binding.staff.visibility == View.VISIBLE) binding.staff.setPositionSec(pos)
        if (!userSeeking) {
            binding.txtTimeNow.text = PianoRollView.formatTime(pos)
            if (s.durationSec > 0) {
                binding.seekPosition.progress =
                    ((pos / s.durationSec) * 1000).toInt().coerceIn(0, 1000)
            }
        }
        flushStats(force = false)

        // Highlight keys that should be pressed soon, with finger numbers.
        val upcoming = HashMap<Int, Hand>()
        val fingers = HashMap<Int, Int>(activeFingers)
        val showFingers = settings.showFingers && fingering.isNotEmpty()
        for (n in s.notesByTime) {
            if (n.startSec < pos) continue
            if (n.startSec > pos + LOOKAHEAD_SEC) break
            if (!activeNotes.containsKey(n.pitch)) {
                upcoming[n.pitch] = n.hand
                if (showFingers && !fingers.containsKey(n.pitch)) {
                    noteIndex[n]?.let { idx -> fingering[idx]?.let { fingers[n.pitch] = it } }
                }
            }
        }
        binding.keyboard.setUpcoming(upcoming)
        if (showFingers) binding.keyboard.setFingers(fingers)
    }

    // ---- controls ----

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }

        binding.ledChip.setOnClickListener {
            if (!scanner.hasPermissions()) {
                permissionLauncher.launch(scanner.requiredPermissions())
            } else {
                BleConnectDialog(this, scanner, ledController).show()
            }
        }

        binding.btnOptions.setOnClickListener { showOptions() }
        binding.btnShowControls.setOnClickListener {
            setPanelShown(true)
            scheduleAutoHide(4000)
        }
        binding.btnPlayPause.setOnClickListener { togglePlay() }
        binding.btnRestart.setOnClickListener { restart() }
        binding.btnRepeat.setOnClickListener { toggleRepeat() }
        binding.btnHotSpot.setOnClickListener { practiseHotSpot() }
        binding.btnRecord.setOnClickListener { toggleRecord() }
        applyRecordUi()

        binding.btnHandLeft.setOnClickListener { setPractice(Practice.LEFT) }
        binding.btnHandBoth.setOnClickListener { setPractice(Practice.BOTH) }
        binding.btnHandRight.setOnClickListener { setPractice(Practice.RIGHT) }

        binding.btnSpeedDown.setOnClickListener { setSpeed(speedPercent - SPEED_STEP) }
        binding.btnSpeedUp.setOnClickListener { setSpeed(speedPercent + SPEED_STEP) }

        binding.loopBar.listener = com.ledpiano.app.ui.LoopRangeBar.Listener { start, end ->
            val e = engine ?: return@Listener
            e.loopStartSec = start
            e.loopEndSec = end
            binding.pianoRoll.setLoop(start, end)
            if (e.positionSec < start || e.positionSec >= end) e.seek(start)
            coach.resetStreaks()
            startPass(start)
        }

        binding.seekPosition.max = 1000
        binding.seekPosition.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val s = song ?: return
                if (fromUser) {
                    binding.txtTimeNow.text = PianoRollView.formatTime(progress / 1000.0 * s.durationSec)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                userSeeking = false
                val s = song ?: return
                engine?.seek((sb?.progress ?: 0) / 1000.0 * s.durationSec)
                passActive = false
            }
        })
    }

    private fun togglePlay() {
        val e = engine ?: return
        if (e.isPlaying) {
            e.pause()
            return
        }
        if (settings.practiceMode == "wait" && !hub.isConnected) {
            askWaitWithoutInput()
            return
        }
        applyEngineOptions()
        if (!passActive) startPass(e.positionSec)
        practiceNanos = System.nanoTime()
        if (settings.countIn) e.playWithCountIn() else e.play()
    }

    private fun askWaitWithoutInput() {
        AlertDialog.Builder(this)
            .setTitle(R.string.wait_need_input_title)
            .setMessage(R.string.wait_need_input_msg)
            .setPositiveButton(R.string.wait_use_step) { _, _ ->
                settings.practiceMode = "step"
                updateModeChip()
                togglePlay()
            }
            .setNeutralButton(R.string.opt_title) { _, _ -> showOptions() }
            .setNegativeButton(R.string.lb_cancel, null)
            .show()
    }

    private fun restart() {
        val e = engine ?: return
        e.stop()
        clearRepeat()
        ledController.allNotesOff()
        activeNotes.clear()
        activeOwner.clear()
        activeFingers.clear()
        binding.keyboard.setActive(emptyMap())
        binding.keyboard.setUpcoming(emptyMap())
        hideOverlay()
        passActive = false
        updateTimeUi(0.0)
    }

    private fun setPractice(p: Practice) {
        practice = p
        passActive = false
        coach.resetStreaks()
        applyHandUi()
        applyEngineOptions()
    }

    private fun applyHandUi() {
        binding.btnHandLeft.isSelected = practice == Practice.LEFT
        binding.btnHandBoth.isSelected = practice == Practice.BOTH
        binding.btnHandRight.isSelected = practice == Practice.RIGHT
        binding.pianoRoll.setHandsVisible(showLeft, showRight)
        binding.keyboard.setHandsVisible(showLeft, showRight)
        binding.staff.setHandsVisible(showLeft, showRight)
    }

    private fun setSpeed(percent: Int) {
        speedPercent = percent.coerceIn(PlaybackEngine.MIN_SPEED, PlaybackEngine.MAX_SPEED)
        engine?.speedPercent = speedPercent
        settings.lastSpeedPercent = speedPercent
        applySpeedUi()
    }

    private fun applySpeedUi() {
        binding.txtSpeed.text = "$speedPercent%"
        binding.btnSpeedDown.isEnabled = speedPercent > PlaybackEngine.MIN_SPEED
        binding.btnSpeedUp.isEnabled = speedPercent < PlaybackEngine.MAX_SPEED
        binding.btnSpeedDown.alpha = if (binding.btnSpeedDown.isEnabled) 1f else 0.4f
        binding.btnSpeedUp.alpha = if (binding.btnSpeedUp.isEnabled) 1f else 0.4f
    }

    private fun applyPlayingUi(playing: Boolean) {
        if (playing) {
            scheduleAutoHide(if (panelShown) 2000 else 0)
        } else {
            mainHandler.removeCallbacks(autoHidePanel)
            if (!panelShown) setPanelShown(true)
        }
        binding.btnPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        val label = getString(if (playing) R.string.lb_pause else R.string.lb_play)
        binding.btnPlayPause.contentDescription = label
        binding.txtPlayLabel.text = label
    }

    private fun updateModeChip() {
        val text = when (effectiveMode()) {
            "wait" -> getString(R.string.mode_wait_chip)
            "step" -> getString(R.string.mode_step_chip)
            else -> null
        }
        binding.txtMode.text = text ?: ""
        binding.txtMode.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    // ---- options sheet ----

    private fun showOptions() {
        val s = song
        val sheet = PracticeOptionsSheet(
            context = this,
            settings = settings,
            showSplit = s != null && HandAssigner.usesPitchSplit(s) && !SongFiles.handsFromScore(s),
            onChanged = { key -> onOptionChanged(key) },
            onBluetoothMidi = {
                if (scanner.hasPermissions()) pickBluetoothMidi()
                else blePermissionForMidi.launch(scanner.requiredPermissions())
            },
            onSendEsp32 = { sendSongToEsp32() },
            onMicToggled = { on ->
                if (on) {
                    if (hasMicPermission()) {
                        settings.micInput = true
                        startMic()
                    } else {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                } else {
                    settings.micInput = false
                    app.micInput.stop()
                }
            },
        )
        optionsSheet = sheet
        sheet.show(hub.source, hub.deviceName)
    }

    private fun onOptionChanged(key: String) {
        when (key) {
            "mode" -> {
                passActive = false
                applyEngineOptions()
                updateModeChip()
                if (settings.practiceMode == "wait" && !hub.isConnected) askWaitWithoutInput()
            }
            "lights" -> {
                coach.lights = lightsFromSettings()
                applyGuidance()
            }
            "labels", "staff", "fingers" -> {
                applyDisplayOptions()
                updateTimeUi(engine?.positionSec ?: 0.0)
            }
            "metronome" -> engine?.metronome = settings.metronome
            "led" -> if (!settings.ledPreview) ledController.setPreview(emptyList())
            "instrument" -> {
                val prog = settings.instrument
                synth?.program = prog
                val s = synth
                Thread {
                    app.soundBank?.preload(prog)
                    // Short preview: C E G C.
                    if (s != null && engine?.isPlaying != true) {
                        for (k in intArrayOf(60, 64, 67, 72)) {
                            s.noteOn(k, 96)
                            Thread.sleep(220)
                        }
                        Thread.sleep(400)
                        for (k in intArrayOf(60, 64, 67, 72)) s.noteOff(k)
                    }
                }.start()
            }
            "split" -> {
                val s = song ?: return
                SongFiles.reassign(s, settings.handSplitPitch)
                fingering = fingeringFor(s)
                passActive = false
                applySongToViews()
                applyHandUi()
            }
        }
    }

    // ---- learning features ----

    /** Lesson fingering, or the finger numbers written in an imported score (MusicXML). */
    private fun fingeringFor(s: MidiSong): Map<Int, Int> {
        lesson?.let { return Lessons.fingering(it, s) }
        val out = HashMap<Int, Int>()
        for ((i, n) in s.notes.withIndex()) if (n.finger in 1..5) out[i] = n.finger
        return out
    }

    /** Next note of each practised hand after [after] glows dimly on the strip (option, off by default). */
    private fun updatePreview(after: Double) {
        val s = song ?: return
        if (!settings.ledPreview) return
        var lo = 0
        var hi = startTimes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (startTimes[mid] <= after + 0.02) lo = mid + 1 else hi = mid
        }
        val next = LinkedHashMap<Hand, Int>()
        val list = s.notesByTime
        var i = lo
        while (i < list.size && i < lo + 64 && next.size < 2) {
            val n = list[i++]
            if (!isPractised(n)) continue
            val h = if (n.hand == Hand.LEFT) Hand.LEFT else Hand.RIGHT
            if (h !in next) next[h] = n.pitch
        }
        ledController.setPreview(next.map { (h, p) -> p to h })
    }

    private fun missAt(sec: Double) {
        if (measureSecs.isEmpty()) return
        hot.addMiss(songUri, Learning.barOf(measureSecs, sec))
        applyHotMarks()
    }

    /** Notes not played in this pass count as mistakes of their bar. */
    private fun recordMisses(upToSec: Double) {
        if (measureSecs.isEmpty()) return
        val bars = score.missed(upToSec).groupingBy { Learning.barOf(measureSecs, it.startSec) }.eachCount()
        for ((b, c) in bars) hot.addMiss(songUri, b, c)
        if (bars.isNotEmpty()) applyHotMarks()
    }

    private fun applyHotMarks() {
        val s = song ?: return
        val spans = hot.counts(songUri).keys.filter { it < measureSecs.size }
            .map { Learning.loopForBar(measureSecs, s.durationSec, it) }
        binding.loopBar.setHotSpans(spans)
    }

    private fun dynamicsAdvice(): String? {
        val a = dynamics.advice()
        dynamics.reset()
        return a?.let { Learning.ADVICE_VI[it] }
    }

    /** Loop the bar with the most mistakes at 65 %, auto speed-up after 3 clean loops (Duke et al.). */
    private fun practiseHotSpot() {
        val e = engine ?: return
        val s = song ?: return
        val bar = hot.worst(songUri)
        if (bar == null) {
            showFeedback(getString(R.string.hot_none), good = true)
            return
        }
        val (a, b) = Learning.loopForBar(measureSecs, s.durationSec, bar)
        if (b - a < 0.3) return
        e.loopStartSec = a
        e.loopEndSec = b
        e.looping = true
        hotBar = bar
        settings.autoSpeed = true
        setSpeed(minOf(speedPercent, Learning.HOTSPOT_SPEED))
        e.seek(a)
        coach.resetStreaks()
        startPass(a)
        applyRepeatUi()
        showFeedback(getString(R.string.hot_start, bar + 1, speedPercent), good = true)
    }

    private fun toggleRecord() {
        if (!recorder.active) {
            recorder.start()
            applyRecordUi()
            showFeedback(getString(R.string.rec_started), good = true)
            return
        }
        val n = recorder.stop()
        applyRecordUi()
        if (n == 0) {
            showFeedback(getString(R.string.rec_empty), good = false)
            return
        }
        val s = song
        val bpm = s?.tempoChanges?.firstOrNull()?.let { (60_000_000 / it.usPerQuarter).toInt() } ?: 120
        val stamp = java.text.SimpleDateFormat("dd-MM HH.mm", java.util.Locale.ROOT).format(java.util.Date())
        val base = "${songTitle().ifBlank { "Bản ghi" }} – $stamp"
        val dir = java.io.File(filesDir, "recordings").apply { mkdirs() }
        val file = java.io.File(dir, base.replace(Regex("[\\\\/:*?\"<>|]"), "") + ".mid")
        try {
            file.writeBytes(recorder.midiBytes(bpm))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.lb_error_open_file, Toast.LENGTH_LONG).show()
            return
        }
        val uri = android.net.Uri.fromFile(file).toString()
        val title = getString(R.string.rec_prefix, base)
        settings.addRecentSong(uri, title)
        AlertDialog.Builder(this)
            .setTitle(R.string.rec_saved_title)
            .setMessage(getString(R.string.rec_saved_msg, n))
            .setPositiveButton(R.string.rec_open) { _, _ ->
                startActivity(newIntent(this, uri, title))
            }
            .setNegativeButton(R.string.lb_ok, null)
            .show()
    }

    private fun applyRecordUi() {
        binding.btnRecord.setText(if (recorder.active) R.string.bt_record_stop else R.string.bt_record)
        binding.btnRecord.setTextColor(ContextCompat.getColor(this, if (recorder.active) R.color.ok_fg else R.color.text_hi))
    }

    private fun sendSongToEsp32() {
        val s = song ?: return
        if (!ledController.isEsp32) {
            Toast.makeText(this, R.string.esp32_need, Toast.LENGTH_LONG).show()
            return
        }
        val n = ledController.sendSong(s)
        AlertDialog.Builder(this)
            .setTitle("ESP32")
            .setMessage(getString(R.string.esp32_sent, n))
            .setPositiveButton(R.string.lb_ok, null)
            .show()
    }

    // ---- repeat (A–B loop) ----

    /** Repeat = loop [REPEAT_MEASURES] measures from the current one; drag the bar to change it. */
    private fun toggleRepeat() {
        val e = engine ?: return
        val s = song ?: return
        if (e.looping) {
            clearRepeat()
            Toast.makeText(this, R.string.lb_repeat_off, Toast.LENGTH_SHORT).show()
            return
        }
        val pos = e.positionSec
        val idx = measureSecs.indexOfLast { it <= pos + 0.01 }
        val start: Double
        val end: Double
        if (idx >= 0 && idx + REPEAT_MEASURES < measureSecs.size) {
            start = measureSecs[idx]
            end = measureSecs[idx + REPEAT_MEASURES]
        } else {
            start = pos.coerceAtLeast(0.0)
            end = minOf(start + REPEAT_FALLBACK_SEC, s.durationSec)
        }
        if (end - start < 0.5) return
        e.loopStartSec = start
        e.loopEndSec = end
        e.looping = true
        if (!e.isPlaying) e.seek(start)
        coach.resetStreaks()
        startPass(start)
        applyRepeatUi()
        Toast.makeText(this, R.string.lb_repeat_on, Toast.LENGTH_SHORT).show()
    }

    private fun clearRepeat() {
        hotBar = null
        engine?.let {
            it.looping = false
            it.loopStartSec = null
            it.loopEndSec = null
        }
        applyRepeatUi()
    }

    private fun applyRepeatUi() {
        val e = engine
        val on = e?.looping == true
        binding.btnRepeat.isSelected = on
        binding.loopBar.visibility = if (on) View.VISIBLE else View.GONE
        if (on && e != null) {
            val a = e.loopStartSec ?: 0.0
            val b = e.loopEndSec ?: (song?.durationSec ?: 0.0)
            binding.loopBar.setRange(a, b)
            binding.pianoRoll.setLoop(a, b)
        } else {
            binding.pianoRoll.setLoop(null, null)
        }
    }

    private fun updateLedChip(state: LedController.State, deviceName: String?) {
        val connected = state == LedController.State.CONNECTED
        binding.ledChip.setBackgroundResource(if (connected) R.drawable.bg_chip_ok else R.drawable.bg_chip_off)
        binding.ledChipDot.setBackgroundResource(if (connected) R.drawable.bg_dot_on else R.drawable.bg_dot_off)
        binding.txtLedChip.setTextColor(
            ContextCompat.getColor(this, if (connected) R.color.ok_fg else R.color.text_mid),
        )
        binding.txtLedChip.text = when (state) {
            LedController.State.CONNECTED -> getString(R.string.lb_led_on_short)
            LedController.State.CONNECTING -> getString(R.string.lb_connecting)
            LedController.State.DISCONNECTED -> getString(R.string.lb_led_off_short)
        }
        binding.ledChip.contentDescription = if (connected) {
            getString(R.string.lb_connected_to) + ": " + (deviceName ?: "")
        } else {
            getString(R.string.cd_led) + ": " + binding.txtLedChip.text
        }
    }
}

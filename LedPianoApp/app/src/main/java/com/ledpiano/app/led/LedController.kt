package com.ledpiano.app.led

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.ledpiano.app.led.transport.LedTransport
import com.ledpiano.app.led.transport.NusLegacyTransport
import com.ledpiano.app.led.transport.Sp611eTransport
import com.ledpiano.app.led.transport.Sp630eProtocol
import com.ledpiano.app.led.transport.Sp630eTransport
import com.ledpiano.app.led.transport.PixelTransport
import com.ledpiano.app.led.transport.Esp32Transport
import com.ledpiano.app.led.transport.UsbSerialTransport
import com.ledpiano.app.led.transport.AdalightTransport
import com.ledpiano.app.led.transport.StripWifiTransport
import com.ledpiano.app.led.transport.WledTransport
import com.ledpiano.app.led.transport.Ws2812Transport
import com.ledpiano.app.midi.Hand
import com.ledpiano.app.midi.MidiNote
import com.ledpiano.app.midi.MidiSong
import com.ledpiano.app.model.AppSettings
import com.ledpiano.app.practice.Learning
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Facade between the playback engine and whichever LED transport is selected.
 *
 * The app keeps a per-key model (which keys are lit, with which hand) and
 * translates it to the transport at hand:
 *  - [Sp611eTransport]: per-key energy is resampled into the 16-band spectrum
 *    frames the SP611E understands (25 Hz feed while playing);
 *  - [NusLegacyTransport]: exact per-key frames of the original Led Organ
 *    hardware (`7F [key,state]… 6E`).
 *
 * Also: guidance dimming ([guidance]), a light test, single-key preview for
 * the alignment wizard, and automatic reconnection to the last strip.
 */
class LedController(private val settings: AppSettings) : LedTransport.Listener {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    interface Listener {
        fun onLedStateChanged(state: State, deviceName: String?, message: String?)
    }

    data class ActiveNote(val pitch: Int, val hand: Hand, val black: Boolean, val startMs: Long)

    var listener: Listener? = null

    @Volatile
    var state: State = State.DISCONNECTED
        private set

    @Volatile
    var deviceName: String? = null
        private set

    @Volatile
    private var playing = false

    private val mapper = ZoneMapper(settings.ledZones)
    private val activeNotes = LinkedHashMap<Int, ActiveNote>()
    private var lastNusFrame: ByteArray? = null
    /** SP630E / SP802E: last whole-strip colour sent (-1 = none yet). */
    private var lastStripColor = -1
    /** SP630E units that list service E0FF instead of FFE0. */
    private var sp630eAlt = false

    // ---- learning aids on the WS2812B strip (all guarded by [activeNotes]) ----
    private val velocities = HashMap<Int, Int>()
    private var preview: List<Pair<Int, Hand>> = emptyList()
    /** pitch → (colour, until uptime ms): short green / orange / red flashes. */
    private val flashes = HashMap<Int, Pair<Int, Long>>()
    /** LED → pitch last shown, to blink when a new key reuses a lit LED (sparse strips). */
    private val lastPitchByLed = HashMap<Int, Int>()
    /** pitch → the song note that lit its LED (guarded by [activeNotes]); see [onNoteEnd]. */
    private val owners = HashMap<Int, MidiNote>()
    /** Keys the learner is holding down on the piano right now (guarded by [activeNotes]). */
    private val pressedKeys = LinkedHashMap<Int, ActiveNote>()
    private var lastExpect: Set<Int>? = null
    private val rx = Ws2812.MessageParser()

    /** Notes from a Bluetooth MIDI piano paired with the ESP32 (firmware 2): (pitch, velocity; 0 = released). */
    val pianoListeners = CopyOnWriteArrayList<(Int, Int) -> Unit>()
    /** Called when the ESP32's status changes (piano connected, Wi-Fi, stored song). */
    val esp32Listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile var esp32Flags = 0
        private set
    @Volatile var esp32Ip: String? = null
        private set
    /** Notes per song the ESP32 holds (its STATUS says: 3000, or 20000 on an ESP32-S3 N16R8). */
    @Volatile var esp32SongMax = Ws2812.MAX_SONG_NOTES
        private set
    /** STATUS bit 3: the board is an ESP32-S3 (e.g. ESP32-S3-DevKitC N16R8). */
    val esp32IsS3: Boolean get() = esp32Flags and 8 != 0

    /** STATUS bit 4: the board is an ESP32-C3 (e.g. ESP32-C3 Super Mini). */
    val esp32IsC3: Boolean get() = esp32Flags and 16 != 0
    val esp32PianoConnected: Boolean get() = state == State.CONNECTED && esp32Flags and 1 != 0

    /** True for the ESP32 "LED Piano Strip" over Bluetooth or USB (it can store songs and pair a piano). */
    val isEsp32: Boolean get() = transport is Esp32Transport && state == State.CONNECTED

    private var transport: LedTransport? = null
    private var ticker: Thread? = null
    @Volatile private var tickerRunning = false

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var address: String? = null
    private var userDisconnected = false
    private var reconnectAttempts = 0
    private var autoConnectTried = false
    /** While true, playback events do not drive the strip (wizard / light test own it). */
    @Volatile private var manualOverride = false

    /**
     * How much guidance to show, 0..1 (fading lights). SP611E: scales the
     * brightness. Legacy strip (on/off only): below 0.5 the keys stay dark.
     */
    @Volatile
    var guidance: Float = 1f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (v == field) return
            field = v
            applyGuidance()
        }

    val isPerKey: Boolean get() = transport is NusLegacyTransport || transport is PixelTransport

    /** True when the selected strip type can light one exact key (Led Organ box or WS2812B strip). */
    fun supportsSingleKey(): Boolean = settings.ledTransport == "legacy_nus" || settings.ledTransport in PIXEL_TYPES

    /** True for a WS2812B strip the app drives LED by LED (ESP32 over Bluetooth, or WLED over Wi-Fi). */
    fun isWs2812(): Boolean = settings.ledTransport in PIXEL_TYPES

    /** BanlanX SP630E / SP802E: the whole strip shows the colour of the hand that plays. */
    fun isWholeStrip(): Boolean = settings.ledTransport == "sp630e" || settings.ledTransport == "sp802e"

    /** Connects to a WLED controller on the same Wi-Fi ("192.168.1.50"). */
    fun connectWled(context: Context, ip: String) =
        connect(context, WledTransport.PREFIX + ip.trim(), "WLED " + ip.trim())

    /** Connects to an Art-Net node, e.g. BanlanX SP801E ("192.168.4.1", or "192.168.4.1/1" for universe 1). */
    fun connectArtNet(context: Context, ip: String) =
        connect(context, WledTransport.ARTNET_PREFIX + ip.trim(), "Art-Net " + ip.trim())

    /** Connects to the ESP32 strip on a USB cable ("usb:VVVV:PPPP", or "usb:" for the first one found). */
    fun connectUsb(context: Context, address: String = UsbSerialTransport.PREFIX, name: String? = null) =
        connect(context, address, name ?: "LED Piano Strip (USB)")

    // ---- connection ----

    fun connect(context: Context, address: String, name: String?) {
        main.removeCallbacksAndMessages(null)
        userDisconnected = false
        reconnectAttempts = 0
        startConnect(context, address, name)
    }

    private fun startConnect(context: Context, address: String, name: String?) {
        disconnectInternal(notify = false)
        appContext = context.applicationContext
        this.address = address
        deviceName = name?.takeIf { it.isNotBlank() } ?: address
        val e131 = address.startsWith(WledTransport.E131_PREFIX)
        val ddp = address.startsWith(WledTransport.DDP_PREFIX)
        val artNet = address.startsWith(WledTransport.ARTNET_PREFIX)
        val wled = address.startsWith(WledTransport.PREFIX) || artNet || e131 || ddp
        val usb = address.startsWith(UsbSerialTransport.PREFIX)
        val strip = address.startsWith(StripWifiTransport.PREFIX)
        val ada = address.startsWith(AdalightTransport.PREFIX)
        when {
            e131 -> settings.ledTransport = "e131"
            ddp -> settings.ledTransport = "ddp"
            wled -> settings.ledTransport = if (artNet && settings.ledTransport == "sp801e") "sp801e" else "wled"
            ada -> settings.ledTransport = "adalight" // Arduino Uno/Nano on USB or HC-05
            usb || strip -> settings.ledTransport = "ws2812" // the ESP32 strip on a cable or Wi-Fi: pixel messages
            settings.ledTransport in setOf("wled", "sp801e", "e131", "ddp", "adalight") ->
                settings.ledTransport = "legacy_nus" // Bluetooth LE device: auto-detected
        }
        val t: LedTransport = if (wled) WledTransport { settings.wsNumLeds } else if (ada) AdalightTransport { settings.wsNumLeds }
        else if (strip) StripWifiTransport() else if (usb) UsbSerialTransport() else when (settings.ledTransport) {
            "legacy_nus" -> NusLegacyTransport()
            "ws2812" -> Ws2812Transport()
            "sp630e", "sp802e" -> Sp630eTransport(if (sp630eAlt) Sp630eTransport.SERVICE_UUID_ALT else Sp630eTransport.SERVICE_UUID)
            else -> Sp611eTransport()
        }
        lastStripColor = -1
        transport = t
        t.listener = this
        state = State.CONNECTING
        listener?.onLedStateChanged(State.CONNECTING, deviceName, null)
        t.connect(context, address)
    }

    fun disconnect() {
        userDisconnected = true
        main.removeCallbacksAndMessages(null)
        disconnectInternal(notify = true)
    }

    /**
     * Connects to the last used strip once per app start, if allowed. The
     * caller must already hold the Bluetooth permissions.
     */
    fun autoConnect(context: Context) {
        if (autoConnectTried || state != State.DISCONNECTED || !settings.autoReconnect) return
        val addr = settings.lastLedAddress ?: return
        autoConnectTried = true
        connect(context, addr, settings.lastLedName)
    }

    private fun scheduleReconnect() {
        val ctx = appContext ?: return
        val addr = address ?: return
        if (userDisconnected || !settings.autoReconnect || reconnectAttempts >= MAX_RECONNECTS) return
        reconnectAttempts++
        main.postDelayed({
            if (state == State.DISCONNECTED && !userDisconnected) startConnect(ctx, addr, deviceName)
        }, RECONNECT_DELAY_MS)
    }

    private fun disconnectInternal(notify: Boolean) {
        stopTicker()
        val t = transport
        transport = null
        if (t != null) {
            t.listener = null
            t.disconnect()
        }
        synchronized(activeNotes) {
            activeNotes.clear()
            pressedKeys.clear()
            lastNusFrame = null
            clearLearningState()
        }
        mapper.reset()
        setEsp32Status(0, null)
        val wasDisconnected = state == State.DISCONNECTED
        state = State.DISCONNECTED
        if (notify && !wasDisconnected) {
            listener?.onLedStateChanged(State.DISCONNECTED, null, null)
        }
    }

    // ---- playback events ----

    fun onNoteStart(note: MidiNote) {
        keyOn(note.pitch, note.hand, note.velocity)
        synchronized(activeNotes) { owners[note.pitch] = note }
    }

    /**
     * The LED stays on for as long as the note is held in the song. When the same key is struck
     * again while the earlier note is just ending, only the note that owns the LED may turn it off,
     * so the new (held) note keeps its light.
     */
    fun onNoteEnd(note: MidiNote) {
        synchronized(activeNotes) {
            val owner = owners[note.pitch]
            if (owner != null && owner !== note) return
            owners.remove(note.pitch)
        }
        keyOff(note.pitch)
    }

    /**
     * A key starts sounding (playback, wait-mode prompt, or free play).
     *
     * EVERY key that is down gets its own LED: a chord of four notes lights
     * four LEDs, each in the colour of its hand, and each LED goes dark when
     * its own key ends.
     */
    fun keyOn(pitch: Int, hand: Hand, velocity: Int = 100) {
        if (manualOverride) return
        synchronized(activeNotes) {
            activeNotes.remove(pitch)
            owners.remove(pitch)
            velocities[pitch] = velocity
            activeNotes[pitch] = ActiveNote(pitch, hand, PIANO_BLACK.contains(pitch % 12), SystemClock.uptimeMillis())
            if (guidance > 0f) mapper.noteOn(pitch, velocity)
            flushKeys()
        }
    }

    fun keyOff(pitch: Int) {
        if (manualOverride) return
        synchronized(activeNotes) {
            if (activeNotes.remove(pitch) == null) return
            flushKeys()
        }
    }

    // ---- keys the learner presses on the piano ----

    /**
     * The learner pressed a key on the piano: the LED right above that key lights, and stays on
     * until the key is released ([releaseKey]) — in free play and while a song runs, whatever
     * the guidance level. Colour: the hand of the song note on that key, else [hand].
     */
    fun pressKey(pitch: Int, hand: Hand, velocity: Int = 100) {
        if (manualOverride) return
        synchronized(activeNotes) {
            pressedKeys.remove(pitch)
            pressedKeys[pitch] = ActiveNote(
                pitch, activeNotes[pitch]?.hand ?: hand, PIANO_BLACK.contains(pitch % 12), SystemClock.uptimeMillis(),
            )
            mapper.noteOn(pitch, velocity)
            flushKeys()
        }
    }

    fun releaseKey(pitch: Int) {
        synchronized(activeNotes) {
            if (pressedKeys.remove(pitch) == null) return
            if (!manualOverride) flushKeys()
        }
    }

    /** Forgets every pressed key (the player screen closes, or the piano input changes). */
    fun releaseAllKeys() {
        synchronized(activeNotes) {
            if (pressedKeys.isEmpty()) return
            pressedKeys.clear()
            if (!manualOverride) flushKeys()
        }
    }

    /** Song keys (hidden when guidance is faded out) plus the keys the learner holds. Call with [activeNotes] locked. */
    private fun litNotes(): List<ActiveNote> {
        val song = if (guidance < 0.5f) emptyList() else shownNotes()
        if (pressedKeys.isEmpty()) return song
        return song.filter { it.pitch !in pressedKeys } + pressedKeys.values
    }

    // ---- learning aids ----

    /** Next key of each hand, shown dimly before it is due (only with "led preview" on). */
    fun setPreview(items: List<Pair<Int, Hand>>) {
        synchronized(activeNotes) {
            if (items == preview) return
            preview = items
            if (transport is PixelTransport && !manualOverride && settings.ledPreview) flushKeys()
        }
    }

    /** Short coloured flash on one key: red = wrong key, green = on time, orange = early / late. */
    fun flash(pitch: Int, color: Int, ms: Long = 250) {
        if (transport !is PixelTransport || manualOverride || !settings.ledFeedback) return
        synchronized(activeNotes) {
            flashes[pitch] = color to SystemClock.uptimeMillis() + ms
            flushKeys()
        }
        main.postDelayed({
            synchronized(activeNotes) {
                val now = SystemClock.uptimeMillis()
                flashes.entries.removeAll { it.value.second <= now }
                flushKeys()
            }
        }, ms + 20)
    }

    /** Tells the ESP32 the strip geometry and colours (so it can light keys itself). */
    fun sendConfig() {
        val t = transport
        if (t is Esp32Transport && t.isConnected) {
            t.send(Ws2812.configMessage(settings.wsConfig, settings.colorRight and 0xFFFFFF, settings.colorLeft and 0xFFFFFF))
        }
    }

    /** Stores [song] on the ESP32 to practise without the phone (BOOT button). Returns the notes sent, 0 if not possible. */
    fun sendSong(song: MidiSong): Int {
        val t = transport
        if (t !is Esp32Transport || !t.isConnected) return 0
        sendConfig()
        val notes = song.notesByTime.map { Ws2812.SongNote(it.startSec, it.pitch, it.hand == Hand.LEFT) }
        val cap = esp32SongMax
        for (m in Ws2812.songMessages(notes, song.title, cap)) t.send(m)
        return minOf(notes.size, cap)
    }

    private fun clearLearningState() {
        owners.clear()
        velocities.clear()
        preview = emptyList()
        flashes.clear()
        lastPitchByLed.clear()
        lastExpect = null
    }

    private fun setEsp32Status(flags: Int, ip: String?) {
        if (flags == esp32Flags && ip == esp32Ip) return
        esp32Flags = flags
        esp32Ip = ip
        main.post { for (l in esp32Listeners) l() }
    }

    /** All the pixels for the WS2812B strip: dim preview, the shown keys (loudness), then flashes on top. */
    private fun wsPixels(shown: List<ActiveNote>, skip: Set<Int>): List<Ws2812.Pixel> {
        val px = LinkedHashMap<Int, Int>()
        val cfg = settings.wsConfig
        if (settings.ledPreview && guidance >= 0.5f) {
            for ((p, h) in preview) {
                val q = wsPixel(p, h) ?: continue
                for (led in Ws2812.ledsForPitch(p, cfg)) px[led] = Ws2812.scale(q.color, 0.18f)
            }
        }
        val dynamics = settings.ledDynamics
        for (n in shown) {
            val q = wsPixel(n.pitch, n.hand) ?: continue
            if (q.index in skip) continue
            val color = if (dynamics) Ws2812.scale(q.color, Learning.dynScale(velocities[n.pitch] ?: 100)) else q.color
            // Every LED above the key lights, so keys next to each other light LEDs next to each other.
            for (led in Ws2812.ledsForPitch(n.pitch, cfg)) px[led] = color
        }
        val now = SystemClock.uptimeMillis()
        for ((p, f) in flashes) {
            if (f.second <= now) continue
            for (led in Ws2812.ledsForPitch(p, cfg)) px[led] = f.first
        }
        return px.entries.sortedBy { it.key }.map { Ws2812.Pixel(it.key, it.value) }
    }

    /** The keys that should be pressed now go to the ESP32, which flashes green / red at once. */
    private fun sendExpect(t: LedTransport) {
        if (t !is Esp32Transport) return
        val exp = activeNotes.keys.toSet()
        if (exp != lastExpect) {
            lastExpect = exp
            t.send(Ws2812.expectMessage(exp))
        }
    }

    /** Every key that is down, oldest first: each one gets an LED (see [keyOn]). Call with [activeNotes] locked. */
    private fun shownNotes(): List<ActiveNote> = activeNotes.values.toList()

        // ---- wizard / test ----

    /**
     * Alignment wizard: light exactly [pitches] (legacy strip) or the zones
     * around them (SP611E). Pass an empty list to clear. Takes over the strip
     * until [endManual] is called.
     */
    fun showKeys(pitches: List<Int>) {
        manualOverride = true
        val t = transport ?: return
        when (t) {
            is NusLegacyTransport -> {
                val entries = pitches.sorted().map {
                    NusLegacyTransport.keyByte(it, settings.organOffset) to
                        NusLegacyTransport.stateByte(Hand.RIGHT, PIANO_BLACK.contains(it % 12))
                }
                t.send(NusLegacyTransport.buildFrame(entries))
            }
            is PixelTransport -> t.send(Ws2812.pixels(pitches.mapNotNull { p -> wsPixel(p, Hand.RIGHT) }))
            is Sp611eTransport -> for (p in pitches) mapper.noteOn(p, 127)
        }
    }

    /** Saves and sends a new brightness (0–255). */
    fun setBrightness(level: Int) {
        settings.ledBrightness = level
        applyGuidance()
    }

    /** Saves and sends the number of LEDs on the strip (SP611E). */
    fun setLength(count: Int) {
        settings.ledLength = count
        val t = transport
        if (t is Sp611eTransport && t.isConnected) t.send(Sp611eProtocol.length(settings.ledLength))
    }

    fun endManual() {
        manualOverride = false
        allNotesOff()
    }

    /** Sweeps a light from the lowest to the highest key so the learner can see the strip works. */
    fun testLights() {
        if (state != State.CONNECTED) return
        manualOverride = true
        if (transport is PixelTransport) {
            Thread({ runTutorialTest() }, "LedTest").start()
            return
        }
        (transport as? Sp630eTransport)?.let { t ->
            // Whole-strip controller: right-hand colour, left-hand colour, then off.
            Thread({
                try {
                    t.send(Sp630eProtocol.color(settings.colorRight and 0xFFFFFF, settings.ledBrightness))
                    Thread.sleep(700)
                    t.send(Sp630eProtocol.color(settings.colorLeft and 0xFFFFFF, settings.ledBrightness))
                    Thread.sleep(700)
                } catch (_: InterruptedException) {
                } finally {
                    manualOverride = false
                    lastStripColor = -1
                    allNotesOff()
                }
            }, "LedTest").start()
            return
        }
        Thread({
            try {
                var p = MidiSong.PIANO_LOWEST
                while (p <= MidiSong.PIANO_HIGHEST && state == State.CONNECTED) {
                    val t = transport
                    if (t is NusLegacyTransport) {
                        t.send(
                            NusLegacyTransport.buildFrame(
                                listOf(NusLegacyTransport.keyByte(p, settings.organOffset) to NusLegacyTransport.STATE_RIGHT_WHITE),
                            ),
                        )
                    } else {
                        mapper.noteOn(p, 127)
                    }
                    p += 2
                    Thread.sleep(35)
                }
                Thread.sleep(200)
            } catch (_: InterruptedException) {
            } finally {
                manualOverride = false
                allNotesOff()
            }
        }, "LedTest").start()
    }

    /**
     * WS2812B light test: exactly the HowToMechatronics FastLED example — LEDs 0, 1, 2, 5, 9, 14
     * and 19 turn on one after another, 500 ms apart, in the example's colours — then a light
     * runs over every key so you can check the alignment.
     */
    private fun runTutorialTest() {
        try {
            val t = transport as? PixelTransport ?: return
            for (n in 1..Ws2812.TUTORIAL.size) {
                if (state != State.CONNECTED) return
                t.send(Ws2812.pixels(Ws2812.TUTORIAL.take(n)))
                Thread.sleep(500)
            }
            Thread.sleep(500)
            val cfg = settings.wsConfig
            for (p in cfg.lowestPitch until cfg.lowestPitch + cfg.numKeys) {
                if (state != State.CONNECTED) return
                wsPixel(p, Hand.RIGHT)?.let { t.send(Ws2812.pixels(listOf(it))) }
                Thread.sleep(30)
            }
        } catch (_: InterruptedException) {
        } finally {
            manualOverride = false
            allNotesOff()
        }
    }

    /** The LED and colour for one key on the WS2812B strip (hand colours from Settings). */
    private fun wsPixel(pitch: Int, hand: Hand): Ws2812.Pixel? {
        val led = Ws2812.ledForPitch(pitch, settings.wsConfig)
        if (led < 0) return null
        val base = (if (hand == Hand.LEFT) settings.colorLeft else settings.colorRight) and 0xFFFFFF
        return Ws2812.Pixel(led, if (Ws2812.isBlack(pitch)) Ws2812.blackKeyColor(base) else base)
    }

    private fun applyGuidance() {
        val t = transport ?: return
        when (t) {
            is PixelTransport -> if (t.isConnected) {
                t.send(Ws2812.brightness(settings.ledBrightness))
                synchronized(activeNotes) { lastNusFrame = null; flushKeys() }
            }
            is Sp611eTransport -> if (t.isConnected) {
                val level = (settings.ledBrightness * (0.15f + 0.85f * guidance)).toInt()
                t.send(Sp611eProtocol.brightness(if (guidance <= 0f) 0 else level))
            }
            is NusLegacyTransport -> synchronized(activeNotes) { lastNusFrame = null; flushNusFrame(t) }
            is Sp630eTransport -> synchronized(activeNotes) { lastStripColor = -1; flushWholeStrip(t) }
        }
    }

    fun setPlaying(playing: Boolean) {
        this.playing = playing
        if (playing) mapper.reset()
    }

    /** Clears every lit key; used on stop/seek so no note stays stuck on. */
    fun allNotesOff() {
        mapper.reset()
        val t = transport
        synchronized(activeNotes) {
            activeNotes.clear()
            clearLearningState()
            if (t is NusLegacyTransport) {
                lastNusFrame = null
                t.send(NusLegacyTransport.FRAME_RESET)
                if (pressedKeys.isNotEmpty()) flushNusFrame(t) // keys still held stay lit
            } else if (t is PixelTransport) {
                lastNusFrame = null
                if (pressedKeys.isEmpty()) {
                    t.send(Ws2812.OFF)
                    sendExpect(t)
                } else {
                    flushKeys() // keys still held stay lit
                }
            } else if (t is Sp630eTransport) {
                flushWholeStrip(t)
            }
        }
    }

    /** Sends the current picture to a per-key strip (one LED per key that is down). Call with [activeNotes] locked. */
    private fun flushKeys() {
        when (val t = transport) {
            is NusLegacyTransport -> flushNusFrame(t)
            is Sp630eTransport -> flushWholeStrip(t)
            is PixelTransport -> {
                val shown = litNotes()
                // Sparse strips: two neighbouring keys can sit on the same LED. When a NEW key lands on
                // an LED that was already lit for another key, blink it off for 70 ms so the learner
                // notices the new key.
                val nowByLed = HashMap<Int, Int>()
                for (n in shown) {
                    val q = wsPixel(n.pitch, n.hand) ?: continue
                    nowByLed[q.index] = n.pitch // newest key wins the LED, as in wsPixels()
                }
                val before = lastPitchByLed.values.toSet()
                val blink = HashSet<Int>()
                for ((led, pitch) in nowByLed) {
                    val last = lastPitchByLed[led]
                    if (last != null && last != pitch && pitch !in before) blink.add(led)
                }
                lastPitchByLed.clear()
                lastPitchByLed.putAll(nowByLed)
                if (blink.isNotEmpty()) {
                    t.send(Ws2812.pixels(wsPixels(shown, blink)))
                    lastNusFrame = null
                    main.postDelayed({ synchronized(activeNotes) { if (!manualOverride) flushKeys() } }, 70)
                } else {
                    val frame = Ws2812.pixels(wsPixels(shown, emptySet()))
                    if (!frame.contentEquals(lastNusFrame)) {
                        lastNusFrame = frame
                        t.send(frame)
                    }
                }
                sendExpect(t)
            }
            else -> Unit
        }
    }

    private fun flushNusFrame(t: NusLegacyTransport) {
        val shown = litNotes()
        val entries = shown
            .sortedBy { it.pitch }
            .map {
                NusLegacyTransport.keyByte(it.pitch, settings.organOffset) to
                    NusLegacyTransport.stateByte(it.hand, it.black)
            }
        val frame = NusLegacyTransport.buildFrame(entries)
        if (!frame.contentEquals(lastNusFrame)) {
            lastNusFrame = frame
            t.send(frame)
        }
    }

    /** SP630E / SP802E: the whole strip in the colour of the newest key's hand, dark when nothing plays. */
    private fun flushWholeStrip(t: Sp630eTransport) {
        if (!t.isConnected) return
        val newest = if (guidance < 0.5f) null else shownNotes().maxByOrNull { it.startMs }
        val color = when {
            newest == null -> 0
            newest.hand == Hand.LEFT -> settings.colorLeft and 0xFFFFFF
            else -> settings.colorRight and 0xFFFFFF
        }
        if (color == lastStripColor) return
        lastStripColor = color
        t.send(Sp630eProtocol.color(color, settings.ledBrightness))
    }

    // ---- transport callbacks ----

    override fun onConnected() {
        state = State.CONNECTED
        reconnectAttempts = 0
        address?.let { settings.lastLedAddress = it }
        settings.lastLedName = deviceName
        listener?.onLedStateChanged(State.CONNECTED, deviceName, null)
        val t = transport ?: return
        when (t) {
            is Sp611eTransport -> {
                t.send(Sp611eProtocol.power(true))
                t.send(Sp611eProtocol.effect(effectId()))
                t.send(Sp611eProtocol.brightness((settings.ledBrightness * guidance).toInt()))
                t.send(Sp611eProtocol.length(settings.ledLength))
                t.send(Sp611eProtocol.audioInput(Sp611eProtocol.AUDIO_INPUT_PLAYER))
                t.send(Sp611eProtocol.sensitivity(16))
                t.send(Sp611eProtocol.rgb(0xFF, 0xFF, 0xFF, 0xFF))
            }
            is NusLegacyTransport -> t.send(NusLegacyTransport.FRAME_RESET)
            is PixelTransport -> {
                t.send(Ws2812.brightness(settings.ledBrightness))
                t.send(Ws2812.OFF)
                sendConfig()
            }
            is Sp630eTransport -> {
                t.send(Sp630eProtocol.power(true))
                t.send(Sp630eProtocol.staticMode())
                t.send(Sp630eProtocol.brightness(settings.ledBrightness))
                lastStripColor = 0
                t.send(Sp630eProtocol.color(0, settings.ledBrightness)) // dark until a key plays
            }
        }
        startTicker()
    }

    /**
     * Wrong protocol chosen in Settings: switch to the one the controller
     * really speaks and connect again (Led Organ box = per-key Nordic UART,
     * BanlanX = SP611E), so the learner never has to know which is which.
     */
    override fun onServiceMissing(available: Set<java.util.UUID>) {
        val ctx = appContext
        val addr = address
        // SP630E / SP802E: same service as the SP611E (or E0FF on some units) – keep the chosen type.
        if (isWholeStrip() && ctx != null && addr != null) {
            if (Sp630eTransport.SERVICE_UUID_ALT in available && !sp630eAlt) {
                sp630eAlt = true
                startConnect(ctx, addr, deviceName)
                return
            }
            if (Sp630eTransport.SERVICE_UUID in available && sp630eAlt) {
                sp630eAlt = false
                startConnect(ctx, addr, deviceName)
                return
            }
        }
        val other = when {
            Ws2812Transport.SERVICE_UUID in available -> "ws2812"
            NusLegacyTransport.SERVICE_UUID in available -> "legacy_nus"
            Sp611eTransport.SERVICE_UUID in available -> "sp611e"
            else -> null
        }
        if (ctx == null || addr == null || other == null || other == settings.ledTransport) {
            onError("Thiết bị này không phải bộ điều khiển đèn được hỗ trợ")
            return
        }
        settings.ledTransport = other
        startConnect(ctx, addr, deviceName)
    }

    /** Messages from the ESP32 (firmware 2): piano notes and status. Main thread. */
    override fun onDataReceived(data: ByteArray) {
        for (b in data) {
            if (!rx.feed(b)) continue
            val d = rx.data
            when (rx.op) {
                Ws2812.OP_NOTE -> if (d.size >= 2) {
                    val pitch = d[0].toInt() and 0x7F
                    val vel = d[1].toInt() and 0x7F
                    for (l in pianoListeners) l(pitch, vel)
                }
                Ws2812.OP_STATUS -> if (d.size >= 5) {
                    if (d.size >= 9) {
                        esp32SongMax = ((d[7].toInt() and 0xFF) or ((d[8].toInt() and 0xFF) shl 8)).takeIf { it > 0 }
                            ?: Ws2812.MAX_SONG_NOTES
                    }
                    val ip = (1..4).map { d[it].toInt() and 0xFF }
                    setEsp32Status(d[0].toInt() and 0xFF, if (ip.any { it != 0 }) ip.joinToString(".") else null)
                }
            }
        }
    }

    override fun onDisconnected(reason: String?) {
        stopTicker()
        setEsp32Status(0, null)
        synchronized(activeNotes) {
            activeNotes.clear()
            pressedKeys.clear()
            lastNusFrame = null
            clearLearningState()
        }
        mapper.reset()
        state = State.DISCONNECTED
        listener?.onLedStateChanged(State.DISCONNECTED, null, reason)
        scheduleReconnect()
    }

    override fun onError(message: String) {
        val wasConnecting = state == State.CONNECTING
        if (wasConnecting) {
            state = State.DISCONNECTED
            stopTicker()
        }
        listener?.onLedStateChanged(state, deviceName, message)
        if (wasConnecting) scheduleReconnect()
    }

    // ---- spectrum feed ticker ----

    private fun startTicker() {
        stopTicker()
        tickerRunning = true
        ticker = Thread({ tickerLoop() }, "LedFeed").also {
            it.priority = Thread.NORM_PRIORITY
            it.start()
        }
    }

    private fun stopTicker() {
        tickerRunning = false
        ticker?.join(200)
        ticker = null
    }

    private fun tickerLoop() {
        var last = System.nanoTime()
        while (tickerRunning) {
            val now = System.nanoTime()
            val dt = (now - last) / 1_000_000_000f
            last = now
            mapper.tick(dt)
            val t = transport
            if (t is Sp611eTransport && t.isConnected && (playing || mapper.hasEnergy())) {
                t.send(Sp611eProtocol.audioFeed(mapper.bands16()))
            }
            try {
                Thread.sleep(FEED_INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun effectId(): Int = when (settings.ledEffect) {
        "single" -> Sp611eProtocol.EFFECT_SOLID
        "full" -> Sp611eProtocol.EFFECT_SOUND_RGB
        else -> Sp611eProtocol.EFFECT_SOUND_SINGLE
    }

    companion object {
        /** Connection types where the app sets every LED itself (one LED per key). */
        val PIXEL_TYPES = setOf("ws2812", "wled", "sp801e", "e131", "ddp", "adalight")
        const val FEED_INTERVAL_MS = 40L // 25 Hz
        private const val MAX_RECONNECTS = 3
        private const val RECONNECT_DELAY_MS = 3000L
        private val PIANO_BLACK = setOf(1, 3, 6, 8, 10)
    }
}

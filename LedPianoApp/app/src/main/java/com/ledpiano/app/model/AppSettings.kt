package com.ledpiano.app.model

import com.ledpiano.app.led.Ws2812

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import androidx.core.content.edit

/**
 * Thin wrapper over SharedPreferences for all user-configurable values.
 *
 * Types mirror what the settings screen persists: ListPreference stores
 * strings, SeekBarPreference stores ints. Both write to the same
 * "led_piano_settings" file (see SettingsActivity).
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var ledTransport: String
        get() = prefs.getString(KEY_LED_TRANSPORT, "legacy_nus") ?: "legacy_nus"
        set(value) = prefs.edit { putString(KEY_LED_TRANSPORT, value) }

    var ledEffect: String
        get() = prefs.getString(KEY_LED_EFFECT, "spectrum") ?: "spectrum"
        set(value) = prefs.edit { putString(KEY_LED_EFFECT, value) }

    var ledBrightness: Int
        get() = prefs.getInt(KEY_LED_BRIGHTNESS, 180)
        set(value) = prefs.edit { putInt(KEY_LED_BRIGHTNESS, value.coerceIn(0, 255)) }

    /** Zone count across the keyboard (4–32); stored as a string by the UI. */
    var ledZones: Int
        get() = prefs.getString(KEY_LED_ZONES, "16")?.toIntOrNull()?.coerceIn(4, 32) ?: 16
        set(value) = prefs.edit { putString(KEY_LED_ZONES, value.coerceIn(4, 32).toString()) }

    /** Number of LEDs on the strip (1–150), e.g. 88 = one LED per piano key. */
    var ledLength: Int
        get() = prefs.getInt(KEY_LED_LENGTH, 88)
        set(value) = prefs.edit { putInt(KEY_LED_LENGTH, value.coerceIn(1, 150)) }

    // ---- WS2812B strip (ESP32 + FastLED, "LED Piano Strip" firmware) ----

    /** Strip density: 60, 74, 96 or 144 LEDs per metre (stored as a string by the UI). */
    var wsLedsPerMetre: Int
        get() = prefs.getString(KEY_WS_DENSITY, "144")?.toIntOrNull()?.coerceIn(30, 200) ?: 144
        set(value) = prefs.edit { putString(KEY_WS_DENSITY, value.toString()) }

    /** Keys on the learner's piano: 88, 76, 61 or 49. */
    var wsKeys: Int
        get() = prefs.getString(KEY_WS_KEYS, "88")?.toIntOrNull()?.takeIf { it in setOf(88, 76, 61, 49) } ?: 88
        set(value) = prefs.edit { putString(KEY_WS_KEYS, value.toString()) }

    /** MIDI note of the leftmost key for [wsKeys] (88 = A0, 76 = E1, 61/49 = C2). */
    val wsLowestPitch: Int get() = when (wsKeys) { 76 -> 28; 61, 49 -> 36; else -> 21 }

    /** LEDs on the strip; 0 = work it out from the piano size and density. */
    var wsNumLeds: Int
        get() = prefs.getInt(KEY_WS_NUM_LEDS, 0).takeIf { it > 0 }
            ?: if (wsSequential) 60 else Ws2812.ledsNeeded(wsKeys, wsLowestPitch, wsLedsPerMetre.toFloat())
        set(value) = prefs.edit { putInt(KEY_WS_NUM_LEDS, value.coerceIn(1, 600)) }

    /** LED above the left edge of the lowest key (the ◀ ▶ alignment in the LED wizard). */
    var wsFirstLed: Int
        get() = prefs.getInt(KEY_WS_FIRST_LED, 0)
        set(value) = prefs.edit { putInt(KEY_WS_FIRST_LED, value.coerceIn(-100, 300)) }

    /** True when the strip's input (DIN) end is on the treble (right) side. */
    var wsReversed: Boolean
        get() = prefs.getBoolean(KEY_WS_REVERSED, false)
        set(value) = prefs.edit { putBoolean(KEY_WS_REVERSED, value) }

    /**
     * True (default): one LED per key in order — key 1 = LED 1, the next key = the next LED, none
     * skipped. False: the LED physically above each key (dense strips, from LEDs per metre).
     */
    val wsSequential: Boolean get() = prefs.getString(KEY_WS_LAYOUT, "seq") != "position"

    val wsConfig: Ws2812.Config
        get() = Ws2812.Config(
            numLeds = wsNumLeds, ledsPerMetre = wsLedsPerMetre.toFloat(), lowestPitch = wsLowestPitch,
            numKeys = wsKeys, firstLed = wsFirstLed, reversed = wsReversed, sequential = wsSequential,
        )

    /**
     * Offset for aligning the LED strip with the physical keyboard: the MIDI
     * note number of the first key covered by the strip (21 = A0).
     */
    var organOffset: Int
        get() = prefs.getInt(KEY_ORGAN_OFFSET, MidiSongDefaults.PIANO_LOWEST)
        set(value) = prefs.edit { putInt(KEY_ORGAN_OFFSET, value) }

    /** Hand colours as ARGB ints; stored as "#RRGGBB" strings by the UI. */
    var colorLeft: Int
        get() = parseColor(prefs.getString(KEY_COLOR_LEFT, DEFAULT_LEFT_HEX))
        set(value) = prefs.edit { putString(KEY_COLOR_LEFT, toHex(value)) }

    var colorRight: Int
        get() = parseColor(prefs.getString(KEY_COLOR_RIGHT, DEFAULT_RIGHT_HEX))
        set(value) = prefs.edit { putString(KEY_COLOR_RIGHT, toHex(value)) }

    var volume: Int
        get() = prefs.getInt(KEY_VOLUME, 100)
        set(value) = prefs.edit { putInt(KEY_VOLUME, value.coerceIn(0, 100)) }

    var lastSpeedPercent: Int
        get() = prefs.getInt(KEY_SPEED, 100)
        set(value) = prefs.edit { putInt(KEY_SPEED, value.coerceIn(25, 100)) }

    // ---- practice options (Practice screen › "Tùy chọn") ----

    /** Note labels on keys and notes: "c" (only C keys), "letters", "solfege", "none". */
    var labelMode: String
        get() = prefs.getString(KEY_LABELS, "c") ?: "c"
        set(value) = prefs.edit { putString(KEY_LABELS, value) }

    /** "normal", "wait" (pause until the right key is played) or "step" (tap to advance). */
    var practiceMode: String
        get() = prefs.getString(KEY_MODE, "normal") ?: "normal"
        set(value) = prefs.edit { putString(KEY_MODE, value) }

    /** Guidance lights: "full", "fade" (dim as you get it right) or "off". */
    var lightsMode: String
        get() = prefs.getString(KEY_LIGHTS, "full") ?: "full"
        set(value) = prefs.edit { putString(KEY_LIGHTS, value) }

    var countIn: Boolean
        get() = prefs.getBoolean(KEY_COUNT_IN, true)
        set(value) = prefs.edit { putBoolean(KEY_COUNT_IN, value) }

    var metronome: Boolean
        get() = prefs.getBoolean(KEY_METRONOME, false)
        set(value) = prefs.edit { putBoolean(KEY_METRONOME, value) }

    /** Raise speed after 3 clean loops, lower it after a rough one. */
    var autoSpeed: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SPEED, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_SPEED, value) }

    var showStaff: Boolean
        get() = prefs.getBoolean(KEY_STAFF, false)
        set(value) = prefs.edit { putBoolean(KEY_STAFF, value) }

    var showFingers: Boolean
        get() = prefs.getBoolean(KEY_FINGERS, true)
        set(value) = prefs.edit { putBoolean(KEY_FINGERS, value) }

    /** Lowest note of the right hand for one-track songs (60 = middle C). */
    var handSplitPitch: Int
        get() = prefs.getInt(KEY_SPLIT, 60)
        set(value) = prefs.edit { putInt(KEY_SPLIT, value.coerceIn(36, 84)) }

    /** Listen through the microphone (acoustic piano, single notes only). */
    var micInput: Boolean
        get() = prefs.getBoolean(KEY_MIC, false)
        set(value) = prefs.edit { putBoolean(KEY_MIC, value) }

    /** Instrument sound (General MIDI program number), see SoundBank. 0 = grand piano. */
    var instrument: Int
        get() = prefs.getString(KEY_INSTRUMENT, "0")?.toIntOrNull() ?: 0
        set(value) = prefs.edit { putString(KEY_INSTRUMENT, value.toString()) }

    var dailyGoalMinutes: Int
        get() = prefs.getInt(KEY_GOAL, 10)
        set(value) = prefs.edit { putInt(KEY_GOAL, value.coerceIn(5, 60)) }

    // ---- LED learning aids (Practice options) ----

    /** The next key of each hand glows dimly before it is due (off by default: one LED per key). */
    var ledPreview: Boolean
        get() = prefs.getBoolean(KEY_LED_PREVIEW, false)
        set(value) = prefs.edit { putBoolean(KEY_LED_PREVIEW, value) }

    /** Short flash on the key: green = on time, orange = early/late, red = wrong key. */
    var ledFeedback: Boolean
        get() = prefs.getBoolean(KEY_LED_FEEDBACK, true)
        set(value) = prefs.edit { putBoolean(KEY_LED_FEEDBACK, value) }

    /** Brighter LED for louder notes of the song. */
    var ledDynamics: Boolean
        get() = prefs.getBoolean(KEY_LED_DYNAMICS, false)
        set(value) = prefs.edit { putBoolean(KEY_LED_DYNAMICS, value) }

    // ---- LED auto-reconnect ----

    var lastLedAddress: String?
        get() = prefs.getString(KEY_LED_ADDR, null)
        set(value) = prefs.edit { putString(KEY_LED_ADDR, value) }

    var lastLedName: String?
        get() = prefs.getString(KEY_LED_NAME, null)
        set(value) = prefs.edit { putString(KEY_LED_NAME, value) }

    var autoReconnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RECONNECT, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_RECONNECT, value) }

    /** Recently opened songs: newline separated "uri|displayName" entries, most recent first. */
    var recentSongs: List<String>
        get() = prefs.getString(KEY_RECENT, "")?.lines()?.filter { it.isNotBlank() } ?: emptyList()
        set(value) = prefs.edit { putString(KEY_RECENT, value.joinToString("\n")) }

    fun addRecentSong(uri: String, name: String) {
        val entry = "$uri|$name"
        val current = recentSongs.toMutableList()
        current.remove(entry)
        current.add(0, entry)
        recentSongs = current.take(MAX_RECENT)
    }

    fun removeRecentSong(entry: String) {
        val current = recentSongs.toMutableList()
        current.remove(entry)
        recentSongs = current
    }

    companion object {
        const val PREFS_NAME = "led_piano_settings"

        private const val KEY_LED_TRANSPORT = "led_transport"
        private const val KEY_LED_EFFECT = "led_effect"
        private const val KEY_LED_BRIGHTNESS = "led_brightness"
        private const val KEY_LED_ZONES = "led_zones"
        private const val KEY_LED_LENGTH = "led_length"
        private const val KEY_WS_LAYOUT = "ws_layout"
        private const val KEY_WS_DENSITY = "ws_density"
        private const val KEY_WS_KEYS = "ws_keys"
        private const val KEY_WS_NUM_LEDS = "ws_num_leds"
        private const val KEY_WS_FIRST_LED = "ws_first_led"
        private const val KEY_WS_REVERSED = "ws_reversed"
        private const val KEY_ORGAN_OFFSET = "organ_offset"
        private const val KEY_COLOR_LEFT = "color_left"
        private const val KEY_COLOR_RIGHT = "color_right"
        private const val KEY_VOLUME = "volume"
        private const val KEY_SPEED = "speed"
        private const val KEY_RECENT = "recent_songs"
        private const val KEY_LABELS = "label_mode"
        private const val KEY_MODE = "practice_mode"
        private const val KEY_LIGHTS = "lights_mode"
        private const val KEY_COUNT_IN = "count_in"
        private const val KEY_METRONOME = "metronome"
        private const val KEY_AUTO_SPEED = "auto_speed"
        private const val KEY_STAFF = "show_staff"
        private const val KEY_FINGERS = "show_fingers"
        private const val KEY_SPLIT = "hand_split"
        private const val KEY_MIC = "mic_input"
        private const val KEY_GOAL = "daily_goal"
        private const val KEY_INSTRUMENT = "instrument"
        private const val KEY_LED_PREVIEW = "led_preview"
        private const val KEY_LED_FEEDBACK = "led_feedback"
        private const val KEY_LED_DYNAMICS = "led_dynamics"
        private const val KEY_LED_ADDR = "led_last_address"
        private const val KEY_LED_NAME = "led_last_name"
        private const val KEY_AUTO_RECONNECT = "led_auto_reconnect"

        const val MAX_RECENT = 50

        const val DEFAULT_LEFT = 0xFF4285F4.toInt()
        const val DEFAULT_RIGHT = 0xFFFBBC04.toInt()
        const val DEFAULT_LEFT_HEX = "#4285F4"
        const val DEFAULT_RIGHT_HEX = "#FBBC04"

        fun toHex(color: Int): String = "#%06X".format(color and 0xFFFFFF)

        fun parseColor(hex: String?): Int = try {
            Color.parseColor(hex ?: DEFAULT_LEFT_HEX)
        } catch (_: Exception) {
            Color.parseColor(DEFAULT_LEFT_HEX)
        }
    }
}

object MidiSongDefaults {
    const val PIANO_LOWEST = 21
}

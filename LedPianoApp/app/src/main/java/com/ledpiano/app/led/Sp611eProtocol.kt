package com.ledpiano.app.led

/**
 * Frame builders for the SP611E (BanlanX v2) LED controller.
 *
 * Wire format: `A0 <opcode> <length> <data...>` – no checksum.
 * The data payload must not exceed 17 bytes or the controller hangs.
 */
object Sp611eProtocol {

    // ---- Opcodes ----
    const val OP_POWER = 0x62
    const val OP_EFFECT = 0x63
    const val OP_CHIP_ORDER = 0x64
    const val OP_BRIGHTNESS = 0x66
    const val OP_SPEED = 0x67
    const val OP_LENGTH = 0x68
    const val OP_RGB = 0x69
    const val OP_LIGHT_MODE = 0x6A
    const val OP_SENSITIVITY = 0x6B
    const val OP_AUDIO_INPUT = 0x6C
    const val OP_AUDIO_FEED = 0x6D
    const val OP_QUERY = 0x70
    const val OP_WHITE = 0x76

    // ---- Effect ids ----
    /** Solid single colour (colour set via [rgb]). */
    const val EFFECT_SOLID = 0xBE
    const val EFFECT_WHITE = 0xBF
    /** Sound reactive, single colour rhythm spectrum – driven by [audioFeed]. */
    const val EFFECT_SOUND_SINGLE = 0xCA
    /** Sound reactive, colour rhythm spectrum. */
    const val EFFECT_SOUND_RGB = 0xC9
    /** Dynamic effects live in this inclusive range. */
    const val EFFECT_DYNAMIC_FIRST = 0x01
    const val EFFECT_DYNAMIC_LAST = 0x8E

    // ---- Light modes (OP 0x6A) ----
    const val LIGHT_MODE_SINGLE = 0
    const val LIGHT_MODE_CYCLE_DYNAMIC = 1
    const val LIGHT_MODE_CYCLE_SOUND = 2

    // ---- Audio input (OP 0x6C) ----
    const val AUDIO_INPUT_INTERNAL_MIC = 0
    const val AUDIO_INPUT_PLAYER = 1
    const val AUDIO_INPUT_EXTERNAL_MIC = 2

    /** Payload limit of the controller firmware. */
    const val MAX_DATA_LEN = 17

    /** Status notifications start with ASCII "SC" followed by packet counters. */
    val STATUS_PREFIX = byteArrayOf(0x53, 0x43)

    fun frame(opcode: Int, data: ByteArray): ByteArray {
        require(data.size <= MAX_DATA_LEN) { "SP611E payload too long (${data.size})" }
        val out = ByteArray(3 + data.size)
        out[0] = 0xA0.toByte()
        out[1] = opcode.toByte()
        out[2] = data.size.toByte()
        data.copyInto(out, 3)
        return out
    }

    fun frame(opcode: Int, vararg data: Int): ByteArray {
        require(data.size <= MAX_DATA_LEN) { "SP611E payload too long (${data.size})" }
        val out = ByteArray(3 + data.size)
        out[0] = 0xA0.toByte()
        out[1] = opcode.toByte()
        out[2] = data.size.toByte()
        for (i in data.indices) out[3 + i] = data[i].toByte()
        return out
    }

    fun power(on: Boolean) = frame(OP_POWER, if (on) 1 else 0)

    fun effect(id: Int) = frame(OP_EFFECT, id and 0xFF)

    fun brightness(level: Int) = frame(OP_BRIGHTNESS, level.coerceIn(0, 255))

    fun speed(value: Int) = frame(OP_SPEED, value.coerceIn(1, 10))

    fun length(ledCount: Int) = frame(OP_LENGTH, ledCount.coerceIn(1, 150))

    /** 4 data bytes: red, green, blue and overall level of the single colour. */
    fun rgb(r: Int, g: Int, b: Int, level: Int = 255) =
        frame(OP_RGB, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255), level.coerceIn(0, 255))

    fun lightMode(mode: Int) = frame(OP_LIGHT_MODE, mode.coerceIn(0, 2))

    fun sensitivity(value: Int) = frame(OP_SENSITIVITY, value.coerceIn(1, 16))

    fun audioInput(input: Int) = frame(OP_AUDIO_INPUT, input.coerceIn(0, 2))

    fun white() = frame(OP_WHITE)

    fun query() = frame(OP_QUERY)

    /**
     * 16-band spectrum feed. Only honoured while the audio input is
     * [AUDIO_INPUT_PLAYER]; the controller animates the strip from these levels.
     */
    fun audioFeed(bands: ByteArray): ByteArray {
        require(bands.size == 16) { "audio feed needs exactly 16 bands, got ${bands.size}" }
        return frame(OP_AUDIO_FEED, bands)
    }
}

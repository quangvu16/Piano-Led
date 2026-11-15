package com.ledpiano.app.ui

/**
 * Shared key → x-coordinate math so the piano roll and the keyboard stay
 * aligned. The 88 piano keys are laid out left to right from A0 to C8.
 */
object PianoLayout {

    const val WHITE_KEYS = 52
    const val BLACK_WIDTH_RATIO = 0.62f

    private val BLACK_PITCH_CLASSES = setOf(1, 3, 6, 8, 10)

    /** Number of white keys strictly left of each pitch (0..128). */
    private val WHITE_BEFORE: IntArray = IntArray(129).also { arr ->
        var count = 0
        for (p in 0..128) {
            arr[p] = count
            if (!isBlack(p)) count++
        }
    }

    fun isBlack(pitch: Int): Boolean = ((pitch % 12) + 12) % 12 in BLACK_PITCH_CLASSES

    fun whiteIndexBefore(pitch: Int): Int = WHITE_BEFORE[pitch.coerceIn(0, 128)]

    fun keyLeft(pitch: Int, whiteWidth: Float): Float =
        if (isBlack(pitch)) {
            whiteIndexBefore(pitch) * whiteWidth - BLACK_WIDTH_RATIO * whiteWidth / 2f
        } else {
            whiteIndexBefore(pitch) * whiteWidth
        }

    fun keyWidth(pitch: Int, whiteWidth: Float): Float =
        if (isBlack(pitch)) BLACK_WIDTH_RATIO * whiteWidth else whiteWidth
}

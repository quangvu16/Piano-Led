package com.ledpiano.app.practice

/**
 * Two small teaching rules backed by practice research:
 *
 *  1. Fading guidance — lights dim step by step while the learner plays
 *     correctly and come straight back after mistakes, so they do not become
 *     dependent on the lights (guidance hypothesis; Hsieh et al., CHI 2026).
 *  2. Accuracy-gated tempo — speed goes up 10 % after [CLEAN_PASSES_TO_SPEED_UP]
 *     clean loops in a row and down 10 % after a rough loop: accurate practice
 *     predicts next-day performance better than practice time
 *     (Duke, Simmons & Cash 2009).
 */
class Coach {

    enum class Lights { FULL, FADE, OFF }

    var lights: Lights = Lights.FULL
        set(value) {
            field = value
            guidance = when (value) {
                Lights.FULL, Lights.FADE -> 1f
                Lights.OFF -> 0f
            }
            correctStreak = 0
            errorStreak = 0
        }

    /** 0 = no guidance shown, 1 = full guidance. */
    var guidance: Float = 1f
        private set

    var cleanStreak: Int = 0
        private set

    private var correctStreak = 0
    private var errorStreak = 0

    /** Returns true when [guidance] changed. */
    fun onCorrect(): Boolean {
        errorStreak = 0
        if (lights != Lights.FADE) return false
        correctStreak++
        if (correctStreak >= CORRECT_TO_FADE && guidance > 0f) {
            correctStreak = 0
            guidance = (guidance - FADE_STEP).coerceAtLeast(0f)
            return true
        }
        return false
    }

    /** Returns true when [guidance] changed. */
    fun onError(): Boolean {
        correctStreak = 0
        if (lights != Lights.FADE) return false
        errorStreak++
        if (errorStreak >= ERRORS_TO_RESTORE && guidance < 1f) {
            errorStreak = 0
            guidance = 1f
            return true
        }
        return false
    }

    /**
     * After a loop pass: returns the new speed if it should change, else null.
     */
    fun onPass(result: ScoreKeeper.PassResult, speedPercent: Int, autoSpeed: Boolean): Int? {
        if (result.total == 0) return null
        if (result.isClean) cleanStreak++ else cleanStreak = 0
        if (!autoSpeed) return null
        if (cleanStreak >= CLEAN_PASSES_TO_SPEED_UP && speedPercent < 100) {
            cleanStreak = 0
            return (speedPercent + 10).coerceAtMost(100)
        }
        if (result.accuracy < 70 && speedPercent > 25) {
            cleanStreak = 0
            return (speedPercent - 10).coerceAtLeast(25)
        }
        return null
    }

    fun resetStreaks() {
        cleanStreak = 0
        correctStreak = 0
        errorStreak = 0
    }

    companion object {
        const val CLEAN_PASSES_TO_SPEED_UP = 3
        private const val CORRECT_TO_FADE = 4
        private const val ERRORS_TO_RESTORE = 2
        private const val FADE_STEP = 0.25f
    }
}

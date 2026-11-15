package com.ledpiano.app.util

/** Note names in letters (C D E…) or Vietnamese solfège (Đô Rê Mi…). */
object NoteNames {

    private val LETTERS = arrayOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
    private val SOLFEGE = arrayOf("Đô", "Đô♯", "Rê", "Mi♭", "Mi", "Fa", "Fa♯", "Sol", "La♭", "La", "Si♭", "Si")

    /** [mode]: "letters", "solfege", "c" (letters, C only) or "none". */
    fun name(pitch: Int, mode: String): String? {
        val pc = ((pitch % 12) + 12) % 12
        return when (mode) {
            "letters" -> LETTERS[pc]
            "solfege" -> SOLFEGE[pc]
            "c" -> if (pc == 0) "C" + octave(pitch) else null
            else -> null
        }
    }

    /** Key label: like [name], with the octave number added on every C. */
    fun keyLabel(pitch: Int, mode: String): String? {
        val pc = ((pitch % 12) + 12) % 12
        val base = name(pitch, mode) ?: return null
        return if (pc == 0 && mode != "c") base + octave(pitch) else base
    }

    fun octave(pitch: Int): Int = pitch / 12 - 1
}

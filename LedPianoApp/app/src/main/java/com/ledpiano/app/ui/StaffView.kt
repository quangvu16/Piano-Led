package com.ledpiano.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.ledpiano.app.midi.Hand
import com.ledpiano.app.midi.MidiSong

/**
 * A simplified grand staff (treble + bass) that scrolls with playback, so
 * learners connect the falling notes to written notes.
 *
 * Note heads sit on the correct line or space with a ♯ when needed, and a thin
 * tail shows how long each note lasts. It is a reading aid, not full engraving:
 * there are no stems, beams or rests.
 */
class StaffView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density

    private var song: MidiSong? = null
    private var positionSec = 0.0
    private var measureSecs: List<Double> = emptyList()
    private var colorLeft = 0xFF4285F4.toInt()
    private var colorRight = 0xFFFBBC04.toInt()
    private var showLeft = true
    private var showRight = true
    private var guidance = 1f

    private val pxPerSec get() = 110f * density

    private val bg = Paint().apply { color = 0xFFFFFFFF.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9AA0A6.toInt()
        strokeWidth = 1f * density
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDADCE0.toInt()
        strokeWidth = 1f * density
    }
    private val now = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x991A73E8.toInt()
        strokeWidth = 2f * density
    }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tail = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f * density }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5F6368.toInt()
        textSize = 10f * density
        isFakeBoldText = true
    }
    private val sharp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF202124.toInt() }
    private val oval = RectF()

    fun setSong(s: MidiSong?) {
        song = s
        measureSecs = s?.measureGridTicks()?.map { s.tickToSec(it) } ?: emptyList()
        invalidate()
    }

    fun setPositionSec(sec: Double) {
        positionSec = sec
        invalidate()
    }

    fun setHandColors(left: Int, right: Int) {
        colorLeft = left
        colorRight = right
        invalidate()
    }

    fun setHandsVisible(left: Boolean, right: Boolean) {
        showLeft = left
        showRight = right
        invalidate()
    }

    fun setGuidance(level: Float) {
        guidance = level.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.drawRect(0f, 0f, w, h, bg)

        // Steps: diatonic index (C0 = 0). Treble lines E4..F5 = 30..38, bass G2..A3 = 18..26.
        val gap = h / 15f
        val half = gap / 2f
        val yForStep = { step: Int -> h / 2f - (step - MIDDLE_C_STEP) * half }

        for (step in intArrayOf(30, 32, 34, 36, 38, 18, 20, 22, 24, 26)) {
            val y = yForStep(step)
            canvas.drawLine(0f, y, w, y, line)
        }
        canvas.drawText("Sol", 4f * density, yForStep(34) - 2f * density, text)
        canvas.drawText("Fa", 4f * density, yForStep(22) - 2f * density, text)

        val nowX = w * 0.18f
        val tLeft = positionSec - nowX / pxPerSec
        val tRight = positionSec + (w - nowX) / pxPerSec

        for (sec in measureSecs) {
            if (sec < tLeft || sec > tRight) continue
            val x = nowX + ((sec - positionSec) * pxPerSec).toFloat()
            canvas.drawLine(x, yForStep(38), x, yForStep(18), bar)
        }

        val s = song
        if (s != null) {
            sharp.textSize = gap * 1.5f
            for (n in s.notes) {
                if (n.endSec < tLeft || n.startSec > tRight) continue
                val practised = !(n.hand == Hand.LEFT && !showLeft) && !(n.hand == Hand.RIGHT && !showRight)
                val base = if (n.hand == Hand.LEFT) colorLeft else colorRight
                val alpha = if (!practised) 70 else (255 * (0.15f + 0.85f * guidance)).toInt()
                val color = (base and 0x00FFFFFF) or (alpha shl 24)

                val (step, isSharp) = stepOf(n.pitch)
                val y = yForStep(step)
                val x0 = nowX + ((n.startSec - positionSec) * pxPerSec).toFloat()
                val x1 = nowX + ((n.endSec - positionSec) * pxPerSec).toFloat()

                // Ledger lines outside the staves and for middle C.
                ledgers(canvas, step, x0, gap, yForStep)

                tail.color = (color and 0x00FFFFFF) or ((alpha / 2) shl 24)
                canvas.drawLine(x0 + gap * 0.6f, y, maxOf(x0 + gap * 0.6f, x1), y, tail)
                head.color = color
                oval.set(x0 - gap * 0.65f, y - half * 0.95f, x0 + gap * 0.65f, y + half * 0.95f)
                canvas.drawOval(oval, head)
                if (isSharp) canvas.drawText("♯", x0 - gap * 1.7f, y + half, sharp)
            }
        }

        canvas.drawLine(nowX, 0f, nowX, h, now)
    }

    private fun ledgers(canvas: Canvas, step: Int, x: Float, gap: Float, yForStep: (Int) -> Float) {
        val l = gap * 0.95f
        if (step == MIDDLE_C_STEP) canvas.drawLine(x - l, yForStep(28), x + l, yForStep(28), line)
        var s = 40
        while (s <= step) {
            canvas.drawLine(x - l, yForStep(s), x + l, yForStep(s), line)
            s += 2
        }
        s = 16
        while (s >= step) {
            canvas.drawLine(x - l, yForStep(s), x + l, yForStep(s), line)
            s -= 2
        }
    }

    /** Diatonic step of [pitch] and whether it needs a sharp sign. */
    private fun stepOf(pitch: Int): Pair<Int, Boolean> {
        val octave = pitch / 12 - 1
        val pc = pitch % 12
        val letter = LETTER_OF_PC[pc]
        return (octave * 7 + letter) to SHARP_PC.contains(pc)
    }

    companion object {
        private const val MIDDLE_C_STEP = 28 // C4
        private val LETTER_OF_PC = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)
        private val SHARP_PC = setOf(1, 3, 6, 8, 10)
    }
}

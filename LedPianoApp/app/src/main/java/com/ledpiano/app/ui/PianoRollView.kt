package com.ledpiano.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.ledpiano.app.midi.Hand
import com.ledpiano.app.midi.MidiSong
import com.ledpiano.app.util.NoteNames

/**
 * Falling-notes piano roll.
 *
 * Notes are drawn as coloured bars falling toward the "now" line at the bottom;
 * blue is the left hand, orange the right hand. The hand that is not being
 * practised is drawn faded. Pinch to zoom the time scale and drag vertically
 * to scrub through the song.
 */
class PianoRollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    interface Listener {
        /** Absolute playback position requested by a scrub gesture. */
        fun onScrubRequested(positionSec: Double)

        /** Single tap (Step mode: go to the next note). */
        fun onTap() {}
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density

    private var song: MidiSong? = null
    private var positionSec = 0.0
    private var pxPerSec = 190f
    private var colorLeft: Int = 0xFF4285F4.toInt()
    private var colorRight: Int = 0xFFFBBC04.toInt()
    private var showLeft = true
    private var showRight = true
    private var lowPitch = MidiSong.PIANO_LOWEST
    private var highPitch = MidiSong.PIANO_HIGHEST
    private var labelMode = "none"
    private var fingers: Map<Int, Int> = emptyMap()
    private var guidance = 1f
    private var loopStart: Double? = null
    private var loopEnd: Double? = null

    private var measureSecs: List<Double> = emptyList()
    private var beatSecs: List<Double> = emptyList()

    private val backgroundPaint = Paint().apply { color = 0xFFFFFFFF.toInt() }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDADCE0.toInt()
        strokeWidth = 1f * density
    }
    private val beatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE8EAED.toInt()
        strokeWidth = 1f * density
    }
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99F2F0EA.toInt()
        strokeWidth = 2f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5F6368.toInt()
        textSize = 10f * density
    }
    private val rect = RectF()
    private val noteText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF202124.toInt()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val loopPaint = Paint().apply { color = 0x1AF2F0EA }
    private val loopEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99F2F0EA.toInt()
        strokeWidth = 1.5f * density
    }

    // ---- public API ----

    fun setSong(song: MidiSong?) {
        this.song = song
        measureSecs = song?.measureGridTicks()?.map { song.tickToSec(it) } ?: emptyList()
        beatSecs = emptyList()
        invalidate()
    }

    fun setPositionSec(sec: Double) {
        positionSec = sec
        invalidate()
    }

    fun setHandsVisible(showLeft: Boolean, showRight: Boolean) {
        this.showLeft = showLeft
        this.showRight = showRight
        invalidate()
    }

    /** Labels drawn inside notes: "letters", "solfege" or anything else for none. */
    fun setLabelMode(mode: String) {
        labelMode = mode
        invalidate()
    }

    /** Finger number per note index in [MidiSong.notes]. */
    fun setFingers(map: Map<Int, Int>) {
        fingers = map
        invalidate()
    }

    /** 0..1 — how visible the practised hand's notes are (fading lights). */
    fun setGuidance(level: Float) {
        guidance = level.coerceIn(0f, 1f)
        invalidate()
    }

    fun setLoop(start: Double?, end: Double?) {
        loopStart = start
        loopEnd = end
        invalidate()
    }

    /** Must match [KeyboardView.setRange] so notes line up with their keys. */
    fun setRange(low: Int, high: Int) {
        lowPitch = low.coerceIn(MidiSong.PIANO_LOWEST, MidiSong.PIANO_HIGHEST)
        highPitch = high.coerceIn(lowPitch, MidiSong.PIANO_HIGHEST)
        invalidate()
    }

    fun setHandColors(left: Int, right: Int) {
        colorLeft = left
        colorRight = right
        invalidate()
    }

    // ---- gestures ----

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                pxPerSec = (pxPerSec * d.scaleFactor).coerceIn(MIN_PX_PER_SEC, MAX_PX_PER_SEC)
                invalidate()
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                listener?.onTap()
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (scaleDetector.isInProgress) return false
                val s = song ?: return false
                // Drag up = forward in time, drag down = rewind.
                val target = (positionSec + distanceY / pxPerSec).coerceIn(0.0, s.durationSec)
                listener?.onScrubRequested(target)
                return true
            }
        },
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    // ---- drawing ----

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawRect(0f, 0f, w, h, backgroundPaint)

        val nowY = h - 8f * density
        val whiteCount = PianoLayout.whiteIndexBefore(highPitch + 1) - PianoLayout.whiteIndexBefore(lowPitch)
        val whiteW = w / whiteCount.coerceAtLeast(1)
        val tTop = positionSec + nowY / pxPerSec
        val tBottom = positionSec - (h - nowY) / pxPerSec

        drawGrid(canvas, w, h, nowY, tBottom, tTop)
        drawLoop(canvas, w, nowY)
        drawNotes(canvas, nowY, whiteW, tBottom, tTop)

        // "Now" line.
        canvas.drawLine(0f, nowY, w, nowY, nowPaint)
    }

    private fun drawGrid(
        canvas: Canvas,
        w: Float,
        h: Float,
        nowY: Float,
        tBottom: Double,
        tTop: Double,
    ) {
        for (sec in measureSecs) {
            if (sec < tBottom || sec > tTop) continue
            val y = nowY - ((sec - positionSec) * pxPerSec).toFloat()
            canvas.drawLine(0f, y, w, y, gridPaint)
            val label = formatTime(sec)
            canvas.drawText(label, 4f * density, y - 3f * density, textPaint)
        }
    }

    private fun drawLoop(canvas: Canvas, w: Float, nowY: Float) {
        val a = loopStart ?: return
        val b = loopEnd ?: return
        val yA = nowY - ((a - positionSec) * pxPerSec).toFloat()
        val yB = nowY - ((b - positionSec) * pxPerSec).toFloat()
        canvas.drawRect(0f, yB, w, yA, loopPaint)
        canvas.drawLine(0f, yA, w, yA, loopEdge)
        canvas.drawLine(0f, yB, w, yB, loopEdge)
    }

    private fun drawNotes(
        canvas: Canvas,
        nowY: Float,
        whiteW: Float,
        tBottom: Double,
        tTop: Double,
    ) {
        val s = song ?: return
        val corner = 5f * density
        val inset = 1.5f * density
        val xOff = PianoLayout.whiteIndexBefore(lowPitch) * whiteW
        val labelSize = 11f * density
        noteText.textSize = labelSize
        for ((index, note) in s.notes.withIndex()) {
            if (note.pitch < lowPitch || note.pitch > highPitch) continue
            if (note.endSec < tBottom || note.startSec > tTop) continue
            val practised = !(note.hand == Hand.LEFT && !showLeft) &&
                !(note.hand == Hand.RIGHT && !showRight)

            val yStart = nowY - ((note.startSec - positionSec) * pxPerSec).toFloat()
            val yEnd = nowY - ((note.endSec - positionSec) * pxPerSec).toFloat()
            val top = minOf(yStart, yEnd)
            val bottom = maxOf(yStart, yEnd)

            val left = PianoLayout.keyLeft(note.pitch, whiteW) - xOff + inset
            val right = left + PianoLayout.keyWidth(note.pitch, whiteW) - 2f * inset
            rect.set(left, top, right, bottom)

            val sounding = note.startSec <= positionSec && positionSec < note.endSec
            notePaint.color = handColor(note.hand, sounding)
            if (!practised) {
                // Faded "ghost" note: still visible, but clearly not yours to play.
                notePaint.color = (notePaint.color and 0x00FFFFFF) or (0x40 shl 24)
            } else if (guidance < 1f) {
                // Fading lights: the notes to play fade out as the learner improves.
                val a = (((notePaint.color ushr 24) and 0xFF) * (0.08f + 0.92f * guidance)).toInt()
                notePaint.color = (notePaint.color and 0x00FFFFFF) or (a shl 24)
            }
            canvas.drawRoundRect(rect, corner, corner, notePaint)

            // Finger number, or the note name, at the bottom of the bar.
            if (practised && guidance > 0.3f && rect.height() > labelSize * 1.6f && rect.width() > labelSize * 0.9f) {
                val label = fingers[index]?.toString() ?: NoteNames.name(note.pitch, labelMode).takeIf { labelMode != "c" }
                if (label != null) {
                    noteText.textSize = if (label.length > 1) labelSize * 0.8f else labelSize
                    canvas.drawText(label, rect.centerX(), rect.bottom - 4f * density, noteText)
                }
            }
        }
    }

    private fun handColor(hand: Hand, bright: Boolean): Int {
        val base = when (hand) {
            Hand.LEFT -> colorLeft
            Hand.RIGHT -> colorRight
            Hand.UNKNOWN -> 0xFF9AA0A6.toInt()
        }
        return (base and 0x00FFFFFF) or ((if (bright) 255 else 205) shl 24)
    }

    companion object {
        private const val MIN_PX_PER_SEC = 60f
        private const val MAX_PX_PER_SEC = 900f

        fun formatTime(sec: Double): String {
            val total = sec.toInt().coerceAtLeast(0)
            return "%d:%02d".format(total / 60, total % 60)
        }
    }
}

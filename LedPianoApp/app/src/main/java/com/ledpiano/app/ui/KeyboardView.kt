package com.ledpiano.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.ledpiano.app.midi.Hand
import com.ledpiano.app.midi.MidiSong
import com.ledpiano.app.util.NoteNames

/**
 * The piano keyboard. Shows all 88 keys (A0…C8) by default, or a zoomed
 * range set with [setRange] so keys stay big enough for learners.
 *
 * Extras for learners: note names (letters or Đô Rê Mi), finger numbers on
 * the keys to press, guidance dimming (fading lights), and a green / red mark
 * on keys the learner presses on a connected piano (right / wrong).
 *
 * Keys that should be pressed soon are tinted with the hand colour; keys that
 * are sounding right now are filled solid, so the player can see both hands at
 * a glance.
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density

    private var active: Map<Int, Hand> = emptyMap()
    private var upcoming: Map<Int, Hand> = emptyMap()

    private var colorLeft: Int = 0xFF4285F4.toInt()
    private var colorRight: Int = 0xFFFBBC04.toInt()
    private var showLeft = true
    private var showRight = true
    private var lowPitch = MidiSong.PIANO_LOWEST
    private var highPitch = MidiSong.PIANO_HIGHEST
    private var labelMode = "c"
    private var fingers: Map<Int, Int> = emptyMap()
    private var guidance = 1f
    /** Keys held on the learner's piano: true = expected, false = wrong. */
    private var pressed: Map<Int, Boolean> = emptyMap()

    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF202124.toInt()
        style = Paint.Style.FILL
    }
    private val gapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3C4043.toInt()
        style = Paint.Style.FILL
    }
    private val keyStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDADCE0.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val highlightStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5F6368.toInt()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val fingerBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF202124.toInt() }
    private val fingerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val rect = RectF()

    /**
     * Set to make the keyboard playable with the fingers: called with (pitch, true) when a key
     * goes down and (pitch, false) when it comes up. Several fingers at once and sliding from key
     * to key both work. Null (default): the keyboard only displays.
     */
    var onKeyTouch: ((pitch: Int, down: Boolean) -> Unit)? = null

    /** Finger (pointer id) → the key it holds. */
    private val touchKeys = HashMap<Int, Int>()

    /** The key under (x, y), or -1. Black keys sit on top of the white ones. */
    private fun pitchAt(x: Float, y: Float): Int {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return -1
        val whiteCount = PianoLayout.whiteIndexBefore(highPitch + 1) - PianoLayout.whiteIndexBefore(lowPitch)
        val whiteW = w / whiteCount.coerceAtLeast(1)
        val xOff = PianoLayout.whiteIndexBefore(lowPitch) * whiteW
        if (y <= h * 0.62f) {
            for (p in lowPitch..highPitch) {
                if (!PianoLayout.isBlack(p)) continue
                val left = PianoLayout.keyLeft(p, whiteW) - xOff
                if (x >= left && x < left + PianoLayout.keyWidth(p, whiteW)) return p
            }
        }
        for (p in lowPitch..highPitch) {
            if (PianoLayout.isBlack(p)) continue
            val left = PianoLayout.keyLeft(p, whiteW) - xOff
            if (x >= left && x < left + whiteW) return p
        }
        return -1
    }

    private fun touchMoved(pointerId: Int, pitch: Int) {
        val old = touchKeys[pointerId]
        if (old == pitch) return
        val cb = onKeyTouch ?: return
        if (old != null) {
            touchKeys.remove(pointerId)
            if (!touchKeys.containsValue(old)) cb(old, false)
        }
        if (pitch >= 0) {
            val already = touchKeys.containsValue(pitch)
            touchKeys[pointerId] = pitch
            if (!already) cb(pitch, true)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onKeyTouch == null) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                touchMoved(event.getPointerId(i), pitchAt(event.getX(i), event.getY(i)))
            }
            MotionEvent.ACTION_MOVE ->
                for (i in 0 until event.pointerCount) {
                    touchMoved(event.getPointerId(i), pitchAt(event.getX(i), event.getY(i)))
                }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                touchMoved(event.getPointerId(event.actionIndex), -1)
            MotionEvent.ACTION_CANCEL ->
                for (id in touchKeys.keys.toList()) touchMoved(id, -1)
        }
        return true
    }

    fun setActive(map: Map<Int, Hand>) {
        active = map
        invalidate()
    }

    fun setUpcoming(map: Map<Int, Hand>) {
        upcoming = map
        invalidate()
    }

    fun setHandColors(left: Int, right: Int) {
        colorLeft = left
        colorRight = right
        invalidate()
    }

    /** "c" (C keys only), "letters", "solfege" or "none". */
    fun setLabelMode(mode: String) {
        labelMode = mode
        invalidate()
    }

    /** Finger number (1–5) per pitch for the keys to press now / next. */
    fun setFingers(map: Map<Int, Int>) {
        fingers = map
        invalidate()
    }

    /** 0..1 — how strongly the keys to press are highlighted. */
    fun setGuidance(level: Float) {
        guidance = level.coerceIn(0f, 1f)
        invalidate()
    }

    fun setPressed(map: Map<Int, Boolean>) {
        pressed = map
        invalidate()
    }

    /** Show only keys [low]..[high] (inclusive). Both ends should be white keys. */
    fun setRange(low: Int, high: Int) {
        lowPitch = low.coerceIn(MidiSong.PIANO_LOWEST, MidiSong.PIANO_HIGHEST)
        highPitch = high.coerceIn(lowPitch, MidiSong.PIANO_HIGHEST)
        invalidate()
    }

    fun setHandsVisible(showLeft: Boolean, showRight: Boolean) {
        this.showLeft = showLeft
        this.showRight = showRight
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawRect(0f, 0f, w, h, gapPaint)

        val whiteCount = PianoLayout.whiteIndexBefore(highPitch + 1) - PianoLayout.whiteIndexBefore(lowPitch)
        val whiteW = w / whiteCount.coerceAtLeast(1)
        val xOff = PianoLayout.whiteIndexBefore(lowPitch) * whiteW
        val blackH = h * 0.62f
        val radius = 4f * density
        labelPaint.textSize = minOf(11f * density, whiteW * 0.42f)
        val showLabels = whiteW >= 14f * density

        // White keys (with their highlights) first.
        for (pitch in lowPitch..highPitch) {
            if (PianoLayout.isBlack(pitch)) continue
            val left = PianoLayout.keyLeft(pitch, whiteW) - xOff
            rect.set(left + 0.5f * density, 0f, left + whiteW - 0.5f * density, h)
            canvas.drawRoundRect(rect, radius, radius, whitePaint)
            drawHighlight(canvas, pitch, rect, isBlack = false)
            canvas.drawRoundRect(rect, radius, radius, keyStroke)
            drawPressed(canvas, pitch, rect)
            // Note names ("C4" = middle C) so learners can find their place.
            if (showLabels) {
                NoteNames.keyLabel(pitch, labelMode)?.let {
                    canvas.drawText(it, rect.centerX(), h - 8f * density, labelPaint)
                }
            }
            drawFinger(canvas, pitch, rect.centerX(), h * 0.68f, whiteW)
        }

        // Black keys on top.
        for (pitch in lowPitch..highPitch) {
            if (!PianoLayout.isBlack(pitch)) continue
            val left = PianoLayout.keyLeft(pitch, whiteW) - xOff
            val bw = PianoLayout.keyWidth(pitch, whiteW)
            rect.set(left, 0f, left + bw, blackH)
            path.reset()
            path.addRoundRect(rect, radius, radius, Path.Direction.CW)
            canvas.drawPath(path, blackPaint)
            drawHighlight(canvas, pitch, rect, isBlack = true)
            drawPressed(canvas, pitch, rect)
            drawFinger(canvas, pitch, rect.centerX(), blackH * 0.62f, bw)
        }
    }

    private fun drawHighlight(canvas: Canvas, pitch: Int, rect: RectF, isBlack: Boolean) {
        val hand = active[pitch] ?: upcoming[pitch] ?: return
        if (hand == Hand.LEFT && !showLeft) return
        if (hand == Hand.RIGHT && !showRight) return
        val base = handColor(hand)
        val isActive = active.containsKey(pitch)
        if (guidance <= 0f) return

        highlightPaint.color = withAlpha(base, ((if (isActive) 210 else 105) * guidance).toInt())
        if (isActive) {
            canvas.drawRoundRect(rect, 3f * density, 3f * density, highlightPaint)
            highlightStroke.color = withAlpha(base, (255 * guidance).toInt())
            canvas.drawRoundRect(rect, 3f * density, 3f * density, highlightStroke)
        } else {
            val inset = 3f * density
            val r = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
            canvas.drawRoundRect(r, 3f * density, 3f * density, highlightPaint)
        }
    }

    private fun drawPressed(canvas: Canvas, pitch: Int, rect: RectF) {
        val ok = pressed[pitch] ?: return
        pressPaint.color = if (ok) 0xE622C55E.toInt() else 0xE6EF4444.toInt()
        val barH = minOf(rect.height() * 0.22f, 18f * density)
        canvas.drawRoundRect(
            rect.left + 2f * density, rect.bottom - barH - 2f * density,
            rect.right - 2f * density, rect.bottom - 2f * density,
            3f * density, 3f * density, pressPaint,
        )
    }

    private fun drawFinger(canvas: Canvas, pitch: Int, cx: Float, cy: Float, keyW: Float) {
        if (guidance <= 0f) return
        val f = fingers[pitch] ?: return
        val r = minOf(9f * density, keyW * 0.42f)
        if (r < 5f * density) return
        fingerBg.alpha = (230 * guidance).toInt()
        canvas.drawCircle(cx, cy, r, fingerBg)
        fingerText.textSize = r * 1.25f
        fingerText.alpha = (255 * guidance).toInt()
        canvas.drawText(f.toString(), cx, cy + r * 0.45f, fingerText)
    }

    private fun handColor(hand: Hand): Int =
        if (hand == Hand.LEFT) colorLeft else colorRight

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)
}

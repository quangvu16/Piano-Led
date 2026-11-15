package com.ledpiano.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * The A–B repeat range, shown under the progress bar while Repeat is on.
 * Drag either handle; both snap to measure lines so a loop always starts at
 * the beginning of a bar. Bars where the learner often makes mistakes ("chỗ khó") are marked red.
 */
class LoopRangeBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    fun interface Listener {
        fun onRangeChanged(startSec: Double, endSec: Double)
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private var duration = 1.0
    private var snaps: List<Double> = emptyList()
    private var start = 0.0
    private var end = 1.0
    private var dragging = 0 // 0 none, 1 start, 2 end
    private var hotSpans: List<Pair<Double, Double>> = emptyList()
    private val hotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCE5484D.toInt() }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFDADCE0.toInt() }
    private val range = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66F2F0EA }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A73E8.toInt() }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF747775.toInt()
        strokeWidth = 1f * density
    }

    fun setSong(durationSec: Double, measureSecs: List<Double>) {
        duration = durationSec.coerceAtLeast(0.1)
        snaps = (measureSecs + durationSec).distinct().sorted()
        invalidate()
    }

    fun setRange(startSec: Double, endSec: Double) {
        start = startSec
        end = endSec
        invalidate()
    }

    /** Hard bars (start, end seconds), drawn in red. */
    fun setHotSpans(spans: List<Pair<Double, Double>>) {
        hotSpans = spans
        invalidate()
    }

    private val pad get() = 12f * density
    private fun xOf(sec: Double): Float = pad + ((sec / duration) * (width - 2 * pad)).toFloat()
    private fun secOf(x: Float): Double = ((x - pad) / (width - 2 * pad)).toDouble().coerceIn(0.0, 1.0) * duration

    private fun snap(sec: Double): Double = snaps.minByOrNull { abs(it - sec) } ?: sec

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val cy = h / 2f
        val r = 3f * density
        canvas.drawRoundRect(pad, cy - r, width - pad, cy + r, r, r, track)
        for ((a, b) in hotSpans) canvas.drawRect(xOf(a), cy - r, xOf(b), cy + r, hotPaint)
        if (snaps.size < 400) {
            for (s in snaps) {
                val x = xOf(s)
                canvas.drawLine(x, cy - 5f * density, x, cy + 5f * density, tick)
            }
        }
        canvas.drawRoundRect(xOf(start), cy - r * 1.6f, xOf(end), cy + r * 1.6f, r, r, range)
        canvas.drawCircle(xOf(start), cy, 8f * density, handle)
        canvas.drawCircle(xOf(end), cy, 8f * density, handle)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val ds = abs(e.x - xOf(start))
                val de = abs(e.x - xOf(end))
                dragging = if (ds <= de) 1 else 2
                parent?.requestDisallowInterceptTouchEvent(true)
                move(e.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                move(e.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging != 0) {
                    dragging = 0
                    listener?.onRangeChanged(start, end)
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun move(x: Float) {
        val sec = snap(secOf(x))
        if (dragging == 1) {
            start = if (sec < end) sec else snaps.lastOrNull { it < end } ?: start
        } else if (dragging == 2) {
            end = if (sec > start) sec else snaps.firstOrNull { it > start } ?: end
        }
        invalidate()
    }
}

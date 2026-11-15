package com.ledpiano.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.time.LocalDate

/** Seven small bars: minutes practised on each of the last 7 days (today on the right). */
class WeekBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density
    private var seconds = IntArray(7)
    private var goalSec = 600

    private val barOff = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF242833.toInt() }
    private val barOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2F0EA.toInt() }
    private val barGoal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF34D399.toInt() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFA6ADBB.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 9f * density
    }

    fun setData(lastSevenDaysSec: IntArray, goalMinutes: Int) {
        seconds = lastSevenDaysSec.copyOf(7)
        goalSec = goalMinutes * 60
        val minutes = seconds.map { it / 60 }
        contentDescription = minutes.joinToString(", ") { "$it phút" }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val labelH = 11f * density
        val slot = w / 7f
        val barW = slot * 0.55f
        val maxH = h - labelH - 2f * density
        val today = LocalDate.now()
        for (i in 0 until 7) {
            val cx = slot * i + slot / 2f
            val v = seconds[i]
            val frac = (v.toFloat() / goalSec.coerceAtLeast(60)).coerceIn(0f, 1f)
            val r = 2.5f * density
            canvas.drawRoundRect(cx - barW / 2, 0f, cx + barW / 2, maxH, r, r, barOff)
            if (frac > 0f) {
                val top = maxH * (1f - frac)
                canvas.drawRoundRect(cx - barW / 2, top, cx + barW / 2, maxH, r, r, if (frac >= 1f) barGoal else barOn)
            }
            val day = today.minusDays((6 - i).toLong()).dayOfWeek.value // 1 = Monday
            val name = if (day == 7) "CN" else "T" + (day + 1)
            canvas.drawText(name, cx, h - 1f * density, label)
        }
    }
}

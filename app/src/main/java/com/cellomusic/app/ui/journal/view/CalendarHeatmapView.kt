package com.cellomusic.app.ui.journal.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import java.util.Calendar

/**
 * GitHub-style calendar heatmap showing practice minutes per day.
 * Gold color intensity = practice duration.
 */
class CalendarHeatmapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dp = context.resources.displayMetrics.density
    private val cellSize = 14f * dp
    private val cellGap = 2f * dp

    // Map of day-of-year (0–365) to minutes practiced
    private var dayData = mapOf<Long, Int>() // dayStartMs -> minutes
    private var maxMinutes = 60

    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1C1C1C.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8A6E2A.toInt()
        textSize = 10f * dp
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Gold gradient: darker → brighter
    private val goldLevels = intArrayOf(
        0xFF2A2210.toInt(), // barely practiced
        0xFF5C4A1E.toInt(), // light
        0xFF8A6E2A.toInt(), // medium
        0xFFC9A84C.toInt(), // good
        0xFFD4A42A.toInt()  // excellent (60+ min)
    )

    private val dayLabels = arrayOf("", "Mon", "", "Wed", "", "Fri", "")

    fun setData(dailyMinutes: Map<Long, Int>) {
        dayData = dailyMinutes
        maxMinutes = (dailyMinutes.values.maxOrNull() ?: 60).coerceAtLeast(1)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        // 7 rows (days of week) + label space
        val h = ((cellSize + cellGap) * 7 + 20 * dp).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val labelWidth = 28 * dp
        val startX = labelWidth
        val startY = 16 * dp

        // Draw day labels
        for (i in dayLabels.indices) {
            if (dayLabels[i].isNotEmpty()) {
                canvas.drawText(
                    dayLabels[i], 2 * dp,
                    startY + i * (cellSize + cellGap) + cellSize * 0.8f,
                    textPaint
                )
            }
        }

        // Draw 26 weeks of cells (approx 6 months)
        val cal = Calendar.getInstance()
        // Go back 25 weeks from today
        cal.add(Calendar.WEEK_OF_YEAR, -25)
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        val today = Calendar.getInstance()
        today.set(Calendar.HOUR_OF_DAY, 0)
        today.set(Calendar.MINUTE, 0)
        today.set(Calendar.SECOND, 0)
        today.set(Calendar.MILLISECOND, 0)

        for (week in 0 until 26) {
            for (dayOfWeek in 0 until 7) {
                if (cal.timeInMillis > today.timeInMillis) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                    continue
                }

                val x = startX + week * (cellSize + cellGap)
                val y = startY + dayOfWeek * (cellSize + cellGap)

                val dayMs = cal.timeInMillis
                val minutes = dayData[dayMs] ?: 0

                if (minutes == 0) {
                    cellPaint.color = emptyPaint.color
                } else {
                    val ratio = (minutes.toFloat() / maxMinutes).coerceIn(0f, 1f)
                    val idx = (ratio * (goldLevels.size - 1)).toInt().coerceIn(0, goldLevels.size - 1)
                    cellPaint.color = goldLevels[idx]
                }

                canvas.drawRoundRect(
                    x, y, x + cellSize, y + cellSize,
                    2 * dp, 2 * dp, cellPaint
                )

                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
    }
}

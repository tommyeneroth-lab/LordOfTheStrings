package com.cellomusic.app.ui.journal.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * GitHub-style calendar heatmap showing practice minutes per day.
 * Gold color intensity = practice duration. Tap a day to see details.
 */
class CalendarHeatmapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Callback when a day cell is tapped. Provides dayMs and minutes for that day. */
    var onDayTapped: ((dayMs: Long, minutes: Int) -> Unit)? = null

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

    // Tooltip drawing
    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2C1810.toInt()
        style = Paint.Style.FILL
    }
    private val tooltipBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC9A84C.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * dp
    }
    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4E4C1.toInt()
        textSize = 11f * dp
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC9A84C.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
    }

    // Gold gradient: darker → brighter
    private val goldLevels = intArrayOf(
        0xFF2A2210.toInt(), // barely practiced
        0xFF5C4A1E.toInt(), // light
        0xFF8A6E2A.toInt(), // medium
        0xFFC9A84C.toInt(), // good
        0xFFD4A42A.toInt()  // excellent (60+ min)
    )

    private val dayLabels = arrayOf("", "Mon", "", "Wed", "", "Fri", "")

    // Cell positions for tap detection: maps index -> (x, y, dayMs)
    private val cellPositions = mutableListOf<CellInfo>()
    private data class CellInfo(val x: Float, val y: Float, val dayMs: Long)

    // Tooltip state
    private var tooltipDayMs: Long? = null
    private var tooltipX = 0f
    private var tooltipY = 0f
    private val dateFormat = SimpleDateFormat("EEE, MMM d", Locale.getDefault())

    fun setData(dailyMinutes: Map<Long, Int>) {
        dayData = dailyMinutes
        maxMinutes = (dailyMinutes.values.maxOrNull() ?: 60).coerceAtLeast(1)
        tooltipDayMs = null
        invalidate()
    }

    /** Dismiss any visible tooltip. */
    fun dismissTooltip() {
        if (tooltipDayMs != null) {
            tooltipDayMs = null
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        // 7 rows (days of week) + label space + tooltip room
        val h = ((cellSize + cellGap) * 7 + 46 * dp).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val tx = event.x
            val ty = event.y
            val hitRadius = cellSize * 0.7f

            // Check if tapping an existing tooltip → dismiss
            if (tooltipDayMs != null) {
                tooltipDayMs = null
                invalidate()
                return true
            }

            // Find tapped cell
            for (cell in cellPositions) {
                val cx = cell.x + cellSize / 2
                val cy = cell.y + cellSize / 2
                if (Math.abs(tx - cx) < hitRadius && Math.abs(ty - cy) < hitRadius) {
                    tooltipDayMs = cell.dayMs
                    tooltipX = cell.x
                    tooltipY = cell.y
                    invalidate()
                    onDayTapped?.invoke(cell.dayMs, dayData[cell.dayMs] ?: 0)
                    return true
                }
            }

            // Tapped outside any cell → dismiss
            tooltipDayMs = null
            invalidate()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val labelWidth = 28 * dp
        val startX = labelWidth
        val startY = 16 * dp

        cellPositions.clear()

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

                cellPositions.add(CellInfo(x, y, dayMs))

                // Draw highlight border on selected day
                if (dayMs == tooltipDayMs) {
                    canvas.drawRoundRect(
                        x - dp, y - dp, x + cellSize + dp, y + cellSize + dp,
                        3 * dp, 3 * dp, highlightPaint
                    )
                }

                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        // Draw tooltip for selected day
        tooltipDayMs?.let { dayMs ->
            val minutes = dayData[dayMs] ?: 0
            val dateStr = dateFormat.format(java.util.Date(dayMs))
            val label = if (minutes == 0) "$dateStr — No practice"
                        else "$dateStr — ${minutes}min"

            val textWidth = tooltipTextPaint.measureText(label)
            val padH = 10 * dp
            val padV = 7 * dp
            val boxW = textWidth + padH * 2
            val boxH = tooltipTextPaint.textSize + padV * 2

            // Position tooltip above the cell, clamped to view bounds
            var bx = tooltipX + cellSize / 2 - boxW / 2
            val by = tooltipY - boxH - 6 * dp
            bx = bx.coerceIn(4 * dp, width - boxW - 4 * dp)
            val byFinal = if (by < 2 * dp) tooltipY + cellSize + 4 * dp else by

            val rect = RectF(bx, byFinal, bx + boxW, byFinal + boxH)
            canvas.drawRoundRect(rect, 6 * dp, 6 * dp, tooltipBgPaint)
            canvas.drawRoundRect(rect, 6 * dp, 6 * dp, tooltipBorderPaint)
            canvas.drawText(label, bx + padH, byFinal + padV + tooltipTextPaint.textSize * 0.85f, tooltipTextPaint)
        }
    }
}

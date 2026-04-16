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
    private val cellGap = 2f * dp
    // 13 weeks ≈ 3 months — keeps the grid dense and legible on phones
    // without scrolling into history nobody revisits.
    private val weeksShown = 13

    // Cell size is computed in onMeasure from the available width so the
    // whole 26-week grid fits portrait phones without horizontal scroll.
    // Floor ensures readability on very narrow screens; we'll still show
    // 26 weeks but shrink cells (down to ~8dp) rather than clip columns.
    private var cellSize = 14f * dp

    // Map of day-of-year (0–365) to minutes practiced
    private var dayData = mapOf<Long, Int>() // dayStartMs -> minutes

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

    // Gold gradient: any practice at all jumps to a clearly-visible tone,
    // so a 10-minute session doesn't blend into the empty background. The
    // darkest "barely-practiced" tone used to be within a few shades of the
    // empty-cell colour — we dropped it. Tiers now use fixed minute
    // thresholds (below) rather than a ratio against the user's best day,
    // so one 90-min session doesn't make every other day look faint.
    private val goldLevels = intArrayOf(
        0xFF8A6E2A.toInt(), // tier 0 — any session (≥1 min)
        0xFFA88A3A.toInt(), // tier 1 — ≥ 20 min
        0xFFC9A84C.toInt(), // tier 2 — ≥ 45 min
        0xFFE0BE58.toInt(), // tier 3 — ≥ 75 min
        0xFFF4D464.toInt()  // tier 4 — ≥ 120 min
    )
    private val goldThresholds = intArrayOf(1, 20, 45, 75, 120)

    // Row 0 is Monday, so Mon/Wed/Fri live at indices 0/2/4 — not 1/3/5 as
    // they did before. The old offsets placed labels one row below their
    // actual day, making Mon look like it labelled Tuesday and so on.
    private val dayLabels = arrayOf("Mon", "", "Wed", "", "Fri", "", "")

    // Cell positions for tap detection: maps index -> (x, y, dayMs)
    private val cellPositions = mutableListOf<CellInfo>()
    private data class CellInfo(val x: Float, val y: Float, val dayMs: Long)

    // Tooltip state
    private var tooltipDayMs: Long? = null
    private var tooltipX = 0f
    private var tooltipY = 0f
    // Locale.ENGLISH forces English weekday/month names in the tooltip
    // regardless of the device's system language — matches the rest of the
    // app's copy (the Mon/Wed/Fri row labels etc.) instead of suddenly
    // reading "mån, okt 20" on a Swedish-locale phone.
    private val dateFormat = SimpleDateFormat("EEE, MMM d", Locale.ENGLISH)

    fun setData(dailyMinutes: Map<Long, Int>) {
        dayData = dailyMinutes
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
        // Derive cell size from the available width so the whole 26-week
        // grid fits portrait phones. Labels take ~28dp on the left; the
        // rest divides evenly across the columns.
        val labelReserve = 28f * dp
        val available = (w - labelReserve).coerceAtLeast(1f)
        val fittedCell = (available - cellGap * (weeksShown - 1)) / weeksShown
        // Clamp: never smaller than 8dp (unreadable) and never bigger than
        // 16dp (overly chunky on tablets / landscape).
        cellSize = fittedCell.coerceIn(8f * dp, 16f * dp)
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

        // Draw [weeksShown] weeks of cells (13 ≈ 3 months).
        //
        // We force firstDayOfWeek=MONDAY *before* rolling back 25 weeks so
        // that the subsequent `set(DAY_OF_WEEK, MONDAY)` doesn't bounce us
        // into a different calendar week on locales that default to
        // Sunday-first (US, JP, …). With firstDayOfWeek=MONDAY, the
        // current week's Monday is always in the same week, so the rewind
        // lands cleanly on a Monday 25 weeks ago and each row is
        // Mon → Tue → Wed → … → Sun.
        val cal = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            add(Calendar.WEEK_OF_YEAR, -(weeksShown - 1))
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val today = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        for (week in 0 until weeksShown) {
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
                    // Fixed minute thresholds, not a ratio. One 90-minute
                    // day no longer drags every 15-minute day into the
                    // "barely visible" bucket.
                    var idx = 0
                    for (i in goldThresholds.indices) {
                        if (minutes >= goldThresholds[i]) idx = i
                    }
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

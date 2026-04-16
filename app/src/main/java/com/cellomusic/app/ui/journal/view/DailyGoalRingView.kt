package com.cellomusic.app.ui.journal.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Compact circular progress ring showing today's XP vs. the user's
 * personal daily target.
 *
 * Target is auto-scaled from their rolling 7-day average (computed in
 * the ViewModel) so a beginner practicing 15 min/day gets a ring that
 * fills by day's end, and a serious player practicing 2 hours doesn't
 * see 300% on a trivial target. The ring can fill past 100% — an outer
 * glow ring takes over once the target is crushed, so overachievers
 * still get a visible signal of "you kept going".
 *
 * Colour palette matches [LevelProgressView] — antique gold on the
 * darker card background so it lives visually with the rest of the
 * journal header.
 */
class DailyGoalRingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dp = context.resources.displayMetrics.density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1C1C1C.toInt()
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF333333.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC9A84C.toInt() // antique gold
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    /** Bright outer glow drawn once progress exceeds 100% — "you did it, then some". */
    private val overflowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4D464.toInt() // brighter gold
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val bigTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4E4C1.toInt() // ivory
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8A6E2A.toInt() // dim gold
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC9A84C.toInt()
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val labelSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8A6E2A.toInt()
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
    }

    /** XP earned today. */
    var todayXp: Int = 0
        set(v) { field = v; invalidate() }

    /** Auto-computed personal target. 0 hides the ring entirely (insufficient history). */
    var targetXp: Int = 0
        set(v) { field = v; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (72 * dp).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // Background pill
        canvas.drawRoundRect(0f, 0f, w, h, 8 * dp, 8 * dp, bgPaint)

        // Ring geometry — tucked on the left, inset from the pill edges.
        val ringSize = h - 14 * dp
        val ringLeft = 8 * dp
        val ringTop = 7 * dp
        val stroke = 6f * dp
        trackPaint.strokeWidth = stroke
        fillPaint.strokeWidth = stroke
        overflowPaint.strokeWidth = stroke * 0.6f

        val rect = RectF(
            ringLeft + stroke / 2,
            ringTop + stroke / 2,
            ringLeft + ringSize - stroke / 2,
            ringTop + ringSize - stroke / 2
        )

        // Track (full circle background)
        canvas.drawArc(rect, 0f, 360f, false, trackPaint)

        // Fill — clamped 0..1 so the main ring maxes out at full circle.
        val safeTarget = if (targetXp > 0) targetXp else 1
        val ratio = (todayXp.toFloat() / safeTarget).coerceAtLeast(0f)
        val mainSweep = ratio.coerceAtMost(1f) * 360f
        // Start from 12 o'clock (-90°), sweep clockwise.
        canvas.drawArc(rect, -90f, mainSweep, false, fillPaint)

        // Overflow ring — drawn just outside the main ring for > 100%.
        if (ratio > 1f) {
            val overflow = ((ratio - 1f).coerceAtMost(1f)) * 360f
            val outerRect = RectF(
                rect.left - stroke * 0.8f,
                rect.top - stroke * 0.8f,
                rect.right + stroke * 0.8f,
                rect.bottom + stroke * 0.8f
            )
            canvas.drawArc(outerRect, -90f, overflow, false, overflowPaint)
        }

        // Centre text inside the ring — percentage, nice and legible.
        val cx = ringLeft + ringSize / 2
        val cy = ringTop + ringSize / 2
        val pct = (ratio * 100f).toInt()
        bigTextPaint.textSize = 14f * dp
        val pctText = if (pct > 999) "999%+" else "$pct%"
        val yOffset = (bigTextPaint.descent() + bigTextPaint.ascent()) / 2f
        canvas.drawText(pctText, cx, cy - yOffset, bigTextPaint)

        // Right-hand label block — title + detail.
        val labelX = ringLeft + ringSize + 12 * dp
        labelTextPaint.textSize = 14f * dp
        labelSubPaint.textSize = 11f * dp

        // When target is 0 we show a gentle "getting to know you" state so
        // the first-week user doesn't see a dead ring.
        val titleText: String
        val detailText: String
        if (targetXp <= 0) {
            titleText = "Daily Goal"
            detailText = "Keep practicing — a target will tune itself after a week."
        } else {
            titleText = "Daily Goal · $todayXp / $targetXp XP"
            detailText = when {
                ratio >= 1f -> "🎯 Target met — keep going!"
                ratio >= 0.5f -> "Halfway there."
                ratio > 0f -> "Started. Session in progress…"
                else -> "No practice yet today."
            }
        }
        canvas.drawText(titleText, labelX, ringTop + 20 * dp, labelTextPaint)
        canvas.drawText(detailText, labelX, ringTop + 38 * dp, labelSubPaint)

        // Subtle hint at bottom-left of label: what the target is derived from.
        if (targetXp > 0) {
            smallTextPaint.textSize = 10f * dp
            smallTextPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(
                "target = 7-day avg",
                labelX, ringTop + 54 * dp, smallTextPaint
            )
        }
    }
}

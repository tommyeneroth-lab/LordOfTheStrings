package com.cellomusic.app.ui.journal.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Custom View showing the player's level and XP progress bar.
 * Gold-themed to match the app's design language.
 */
class LevelProgressView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dp = context.resources.displayMetrics.density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1C1C1C.toInt()
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF333333.toInt()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC9A84C.toInt() // antique gold
    }
    private val levelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4E4C1.toInt() // aged ivory
        textSize = 22f * dp
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val pointsTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8A6E2A.toInt() // antique gold dim
        textSize = 12f * dp
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.RIGHT
    }
    private val streakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC9A84C.toInt()
        textSize = 13f * dp
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    var level: Int = 0
        set(v) { field = v; invalidate() }
    var totalPoints: Int = 0
        set(v) { field = v; invalidate() }
    var currentStreakDays: Int = 0
        set(v) { field = v; invalidate() }
    var lifetimeMinutes: Int = 0
        set(v) { field = v; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (80 * dp).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // Background
        canvas.drawRoundRect(0f, 0f, w, h, 8 * dp, 8 * dp, bgPaint)

        // Level text
        val levelText = "Level $level"
        canvas.drawText(levelText, 12 * dp, 30 * dp, levelTextPaint)

        // Points text
        val pointsInLevel = totalPoints % 10_000
        val pointsText = "$totalPoints pts  ($pointsInLevel / 10,000)"
        canvas.drawText(pointsText, w - 12 * dp, 30 * dp, pointsTextPaint)

        // Progress bar track
        val barLeft = 12 * dp
        val barRight = w - 12 * dp
        val barTop = 42 * dp
        val barBottom = 52 * dp
        canvas.drawRoundRect(barLeft, barTop, barRight, barBottom, 5 * dp, 5 * dp, trackPaint)

        // Progress bar fill
        val progress = (pointsInLevel / 10_000f).coerceIn(0f, 1f)
        val fillRight = barLeft + (barRight - barLeft) * progress
        canvas.drawRoundRect(barLeft, barTop, fillRight, barBottom, 5 * dp, 5 * dp, fillPaint)

        // Streak and lifetime
        val streakText = "🔥 $currentStreakDays day streak"
        val lifeText = "⏱ ${lifetimeMinutes / 60}h ${lifetimeMinutes % 60}m total"
        canvas.drawText(streakText, w * 0.25f, 72 * dp, streakPaint)
        canvas.drawText(lifeText, w * 0.75f, 72 * dp, streakPaint)
    }
}

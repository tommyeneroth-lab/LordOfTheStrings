package com.cellomusic.app.ui.journal.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.cellomusic.app.R

/**
 * Prominent streak display for the Stats tab.
 *
 * Layout (horizontal):
 *    [ flame icon ]    7           day streak
 *                                  Longest: 14 days
 *
 * Call [pulse] when the user lands their first session of the day so the
 * flame flashes gold — makes the streak feel *earned*, not just reported.
 */
class StreakBadgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val gold      = 0xFFC9A84C.toInt()
    private val brass     = 0xFFD4A42A.toInt()
    private val ivory     = 0xFFF4E4C1.toInt()
    private val ivoryDim  = 0xFF8E8574.toInt()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1C1C1C.toInt()
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gold
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gold
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textSize = dp(34f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ivory
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        textSize = dp(14f)
        letterSpacing = 0.08f
    }
    private val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ivoryDim
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        textSize = dp(11.5f)
        letterSpacing = 0.06f
    }
    private val flameGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFD166.toInt()
        style = Paint.Style.FILL
    }

    private val flame: Drawable? =
        ContextCompat.getDrawable(context, R.drawable.ic_flame)

    private val bgRect = RectF()

    var currentStreak: Int = 0
        set(v) { field = v; invalidate() }
    var longestStreak: Int = 0
        set(v) { field = v; invalidate() }
    /** If true the flame gets a brighter tint (e.g. after a 2-day grace save). */
    var saved: Boolean = false
        set(v) { field = v; invalidate() }

    private var pulseProgress: Float = 0f  // 0..1 transient brightness pulse
    private var pulseAnimator: ValueAnimator? = null

    /**
     * Briefly pulse the flame — call when the streak increments today.
     * Cheap: just animates an extra radial glow around the flame icon.
     */
    fun pulse() {
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900L
            addUpdateListener {
                pulseProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(dp(260f).toInt(), widthMeasureSpec)
        val h = dp(74f).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val inset = dp(1f)
        bgRect.set(inset, inset, w - inset, h - inset)

        canvas.drawRoundRect(bgRect, dp(8f), dp(8f), bgPaint)
        canvas.drawRoundRect(bgRect, dp(8f), dp(8f), strokePaint)

        // ── Flame ─────────────────────────────────────────────────────
        val flameSize = dp(46f).toInt()
        val flameLeft = dp(16f).toInt()
        val flameTop = ((h - flameSize) / 2f).toInt()
        if (pulseProgress > 0f && pulseProgress < 1f) {
            // Transient halo
            val cx = flameLeft + flameSize / 2f
            val cy = flameTop + flameSize / 2f
            val radius = (flameSize / 2f) * (1f + pulseProgress * 0.8f)
            val alpha = ((1f - pulseProgress) * 180).toInt().coerceIn(0, 255)
            flameGlowPaint.alpha = alpha
            canvas.drawCircle(cx, cy, radius, flameGlowPaint)
        }
        flame?.let {
            val tint = if (saved) brass else gold
            it.setTint(tint)
            it.setBounds(flameLeft, flameTop, flameLeft + flameSize, flameTop + flameSize)
            it.draw(canvas)
        }

        // ── Counter + labels ──────────────────────────────────────────
        val textX = dp(78f)
        val countStr = currentStreak.toString()
        // Vertically center the big count within the badge, shifted up a bit
        // so the smaller label fits beneath it.
        val countY = h * 0.58f
        canvas.drawText(countStr, textX, countY, countPaint)

        val countWidth = countPaint.measureText(countStr)
        val labelX = textX + countWidth + dp(10f)
        val suffix = if (currentStreak == 1) "DAY STREAK" else "DAY STREAK"
        canvas.drawText(suffix, labelX, countY - dp(16f), labelPaint)

        val longestText = if (longestStreak > currentStreak)
            "Longest: $longestStreak days"
        else
            "Keep it going!"
        canvas.drawText(longestText, labelX, countY, subLabelPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator?.cancel()
    }
}

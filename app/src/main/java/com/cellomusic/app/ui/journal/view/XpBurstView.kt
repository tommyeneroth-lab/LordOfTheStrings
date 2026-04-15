package com.cellomusic.app.ui.journal.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

/**
 * Lightweight overlay that floats a "+N XP" label up the screen and fades it
 * out. Cheap enough to fire on every session save — no particles, just one
 * text glyph with a soft gold drop shadow.
 *
 * Usage:
 *   xpBurst.show(xp = 150, subText = "Session saved")
 *
 * The view is transparent until [show] is called, and hides itself at the
 * end of the animation. Place it in the parent layout with match_parent and
 * a high elevation so it sits above other content.
 */
class XpBurstView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val mainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC9A84C.toInt()  // antique gold
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textSize = dp(36f)
        setShadowLayer(dp(6f), 0f, dp(2f), 0xFF000000.toInt())
    }

    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4E4C1.toInt()  // aged ivory
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        textSize = dp(13f)
        setShadowLayer(dp(4f), 0f, dp(1f), 0xFF000000.toInt())
    }

    private var mainText: String = ""
    private var subText: String = ""
    private var progress: Float = 0f
    private var animator: ValueAnimator? = null

    /** Layers: software so shadow layer renders cleanly on all devices. */
    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        visibility = GONE
    }

    /**
     * Show an XP burst. [xp] is displayed as "+N XP". Optional [subText]
     * appears below in smaller text (e.g. "Streak saved!" or "Day 7!").
     */
    fun show(xp: Int, subText: String = "") {
        mainText = "+$xp XP"
        this.subText = subText
        visibility = VISIBLE
        progress = 0f

        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600L
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
                if (progress >= 1f) visibility = GONE
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (progress <= 0f || progress >= 1f) return

        // Rise from 70% → 30% of the view height as progress runs 0 → 1.
        val cx = width / 2f
        val startY = height * 0.70f
        val endY   = height * 0.30f
        val y = startY + (endY - startY) * progress

        // Fade: full opacity for the first 40%, then taper to 0.
        val alphaFrac = if (progress < 0.4f) 1f else min(1f, (1f - progress) / 0.6f)
        val alpha255 = (alphaFrac * 255f).toInt().coerceIn(0, 255)
        mainPaint.alpha = alpha255
        subPaint.alpha = alpha255

        canvas.drawText(mainText, cx, y, mainPaint)
        if (subText.isNotEmpty()) {
            canvas.drawText(subText, cx, y + dp(22f), subPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}

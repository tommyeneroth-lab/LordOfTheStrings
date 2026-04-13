package com.cellomusic.app.ui.journal.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Lightweight particle-based fireworks animation overlay.
 * Call [fire] to trigger the animation. The view is transparent
 * and should overlay the entire screen (match_parent).
 */
class FireworksOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Particle(
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        val color: Int, var alpha: Int = 255,
        val size: Float = 6f
    )

    private val particles = mutableListOf<Particle>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null

    // Gold-themed fireworks palette
    private val colors = intArrayOf(
        0xFFC9A84C.toInt(), // antique gold
        0xFFD4A42A.toInt(), // brass bright
        0xFFF4E4C1.toInt(), // aged ivory
        0xFFFFD700.toInt(), // pure gold
        0xFFFF6B35.toInt(), // orange spark
        0xFFFFFFFF.toInt(), // white
        0xFFB5860D.toInt()  // brass
    )

    fun fire(burstCount: Int = 3) {
        particles.clear()
        animator?.cancel()

        val w = width.toFloat().coerceAtLeast(400f)
        val h = height.toFloat().coerceAtLeast(800f)

        repeat(burstCount) { burst ->
            val cx = w * (0.2f + Random.nextFloat() * 0.6f)
            val cy = h * (0.15f + Random.nextFloat() * 0.35f)
            val count = 40 + Random.nextInt(30)

            repeat(count) {
                val angle = Random.nextFloat() * 2 * Math.PI.toFloat()
                val speed = 3f + Random.nextFloat() * 10f
                particles.add(
                    Particle(
                        x = cx, y = cy,
                        vx = cos(angle) * speed,
                        vy = sin(angle) * speed,
                        color = colors[Random.nextInt(colors.size)],
                        size = 3f + Random.nextFloat() * 5f
                    )
                )
            }
        }

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { anim ->
                val progress = anim.animatedValue as Float
                val gravity = 0.15f

                for (p in particles) {
                    p.x += p.vx
                    p.y += p.vy
                    p.vy += gravity
                    p.vx *= 0.98f
                    p.alpha = (255 * (1f - progress)).toInt().coerceIn(0, 255)
                }
                invalidate()

                if (progress >= 1f) {
                    particles.clear()
                    visibility = GONE
                }
            }
            start()
        }

        visibility = VISIBLE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (p in particles) {
            paint.color = p.color
            paint.alpha = p.alpha
            canvas.drawCircle(p.x, p.y, p.size, paint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}

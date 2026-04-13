package com.cellomusic.app.ui.decibel

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * A horizontal VU-style bar that fills from left to right based on dB level.
 * Green (safe) → Yellow (caution) → Red (loud).
 */
class DecibelBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var level = 0f  // 0..1 fill fraction

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#2C1810")
        style = Paint.Style.FILL
    }
    private val barPaint = Paint().apply { style = Paint.Style.FILL }
    private val borderPaint = Paint().apply {
        color = Color.parseColor("#8A6E2A")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    fun setLevel(db: Float) {
        // Map dB 30..100 to 0..1
        level = ((db - 30f) / 70f).coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 4f

        // Background
        canvas.drawRoundRect(0f, 0f, w, h, r, r, bgPaint)

        // Gradient bar
        if (level > 0f) {
            val fillW = w * level
            val gradient = LinearGradient(0f, 0f, w, 0f,
                intArrayOf(
                    Color.parseColor("#2E7D32"),   // green
                    Color.parseColor("#F57F17"),   // yellow
                    Color.parseColor("#C62828")    // red
                ),
                floatArrayOf(0f, 0.6f, 1f),
                Shader.TileMode.CLAMP
            )
            barPaint.shader = gradient
            canvas.drawRoundRect(0f, 0f, fillW, h, r, r, barPaint)
        }

        // Border
        canvas.drawRoundRect(0f, 0f, w, h, r, r, borderPaint)
    }
}

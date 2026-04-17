package com.cellomusic.app.ui.bow.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

/**
 * Horizontal bow-pressure indicator.
 * pressureScore: -1 = too light, 0 = ideal, +1 = too heavy.
 */
class BowPressureView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var pressureScore: Float = 0f
        set(v) { field = v.coerceIn(-1f, 1f); invalidate() }

    var isSilent: Boolean = true
        set(v) { field = v; invalidate() }

    private val zoneLight  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3D85C8") }
    private val zoneIdeal  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3E7D44") }
    private val zoneHeavy  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#9E3028") }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C9A84C")
        style = Paint.Style.FILL
    }
    private val silentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44FFFFFF")
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A6E2A")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C8B89A")
        textSize = 28f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }

    private val barRect = RectF()
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val barH = h * 0.45f
        val barY = h * 0.08f

        // Three equal zones
        val zoneW = w / 3f
        barRect.set(0f, barY, zoneW, barY + barH)
        canvas.drawRect(barRect, zoneLight)
        barRect.set(zoneW, barY, zoneW * 2f, barY + barH)
        canvas.drawRect(barRect, zoneIdeal)
        barRect.set(zoneW * 2f, barY, w, barY + barH)
        canvas.drawRect(barRect, zoneHeavy)

        // Border around full bar
        barRect.set(0f, barY, w, barY + barH)
        canvas.drawRect(barRect, borderPaint)
        // Zone dividers
        canvas.drawLine(zoneW, barY, zoneW, barY + barH, borderPaint)
        canvas.drawLine(zoneW * 2f, barY, zoneW * 2f, barY + barH, borderPaint)

        // Zone labels
        val labelY = barY + barH + labelPaint.textSize + 4f
        canvas.drawText("Too Light",  zoneW * 0.5f, labelY, labelPaint)
        canvas.drawText("Ideal",      zoneW * 1.5f, labelY, labelPaint)
        canvas.drawText("Too Heavy",  zoneW * 2.5f, labelY, labelPaint)

        // Needle position: map pressureScore (-1..+1) to x (0..w)
        val needleX = ((pressureScore + 1f) / 2f) * w
        val needleBaseY = barY - 4f
        val needleH = barH + 8f
        val needleHalfW = 8f

        if (isSilent) {
            // Dimmed overlay on silent
            canvas.drawRect(0f, barY, w, barY + barH, silentPaint)
        } else {
            // Gold triangle needle pointing down
            path.reset()
            path.moveTo(needleX, needleBaseY + needleH)
            path.lineTo(needleX - needleHalfW, needleBaseY)
            path.lineTo(needleX + needleHalfW, needleBaseY)
            path.close()
            canvas.drawPath(path, needlePaint)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.22f).toInt().coerceAtLeast(90))
    }
}

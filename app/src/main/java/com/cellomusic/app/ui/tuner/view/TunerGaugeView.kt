package com.cellomusic.app.ui.tuner.view

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.cellomusic.app.audio.tuner.CelloString
import com.cellomusic.app.audio.tuner.HpsProcessor
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Semicircular tuner gauge with animated needle.
 * - Green zone: ±5 cents (in tune)
 * - Yellow zone: ±5 to ±20 cents
 * - Red zone: beyond ±20 cents
 * Shows cello open string buttons and current detected pitch.
 */
class TunerGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        const val MAX_CENTS = 50f
        const val NEEDLE_ANIM_DURATION = 120L
    }

    private val dp = context.resources.displayMetrics.density

    private var needleAngle: Float = 0f     // -90° = far flat, 0° = in tune, +90° = far sharp
        set(value) {
            field = value
            invalidate()
        }

    private var activeString: CelloString? = null
    private var detectedHz: Float = 0f
    private var centsOffset: Float = 0f
    private var isActive: Boolean = false

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 20f
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
    }
    private val stringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    fun update(hz: Float, cents: Float, string: CelloString?) {
        isActive = hz > 0f
        detectedHz = hz
        centsOffset = cents
        activeString = string

        // Animate needle to new angle
        val targetAngle = (cents / MAX_CENTS * 90f).coerceIn(-90f, 90f)
        ObjectAnimator.ofFloat(this, "needleAngle", needleAngle, targetAngle).apply {
            duration = NEEDLE_ANIM_DURATION
            start()
        }
    }

    fun setNeedleAngle(angle: Float) {
        needleAngle = angle
    }

    fun reset() {
        isActive = false
        detectedHz = 0f
        update(0f, 0f, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        val cy = h * 0.65f
        val radius = min(w, h * 1.3f) * 0.38f

        // Draw colored arcs (semicircle 180° to 360°)
        drawArcs(canvas, cx, cy, radius)

        // Draw tick marks
        drawTicks(canvas, cx, cy, radius)

        // Draw center label
        textPaint.textSize = 14f * dp
        textPaint.color = Color.DKGRAY
        val centerLabel = if (!isActive) "—" else "%.1f Hz".format(detectedHz)
        canvas.drawText(centerLabel, cx, cy + radius * 0.35f, textPaint)

        // Draw cents offset
        if (isActive) {
            textPaint.textSize = 11f * dp
            val sign = if (centsOffset >= 0) "+" else ""
            val centsText = "${sign}%.1f ¢".format(centsOffset)
            val centsColor = when {
                kotlin.math.abs(centsOffset) < 5f -> Color.rgb(0, 150, 0)
                kotlin.math.abs(centsOffset) < 20f -> Color.rgb(200, 140, 0)
                else -> Color.RED
            }
            textPaint.color = centsColor
            canvas.drawText(centsText, cx, cy + radius * 0.55f, textPaint)
        }

        // Draw note name
        activeString?.let { str ->
            textPaint.color = Color.BLACK
            textPaint.textSize = 18f * dp
            textPaint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText(str.name, cx, cy - radius * 0.25f, textPaint)
            textPaint.typeface = Typeface.DEFAULT
        }

        // Draw needle
        drawNeedle(canvas, cx, cy, radius)

        // Draw center pivot dot
        canvas.drawCircle(cx, cy, 8f * dp, centerDotPaint)

        // Draw string selector buttons at bottom
        drawStringButtons(canvas, w, h)
    }

    private fun drawArcs(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val outerR = radius * 1.15f
        val innerR = radius * 0.85f
        val arcW = outerR - innerR

        arcPaint.strokeWidth = arcW

        // Red zones (far flat/sharp): -90° to -60°, +60° to +90°
        arcPaint.color = Color.rgb(220, 60, 60)
        canvas.drawArc(
            RectF(cx - radius, cy - radius, cx + radius, cy + radius),
            180f, 30f, false, arcPaint   // left red: 180°..210°
        )
        canvas.drawArc(
            RectF(cx - radius, cy - radius, cx + radius, cy + radius),
            330f, 30f, false, arcPaint   // right red: 330°..360°
        )

        // Yellow zones: -60° to -18°, +18° to +60°
        arcPaint.color = Color.rgb(230, 180, 0)
        canvas.drawArc(
            RectF(cx - radius, cy - radius, cx + radius, cy + radius),
            210f, 42f, false, arcPaint
        )
        canvas.drawArc(
            RectF(cx - radius, cy - radius, cx + radius, cy + radius),
            288f, 42f, false, arcPaint
        )

        // Green zone: -18° to +18° (center ±10 cents)
        arcPaint.color = Color.rgb(30, 180, 30)
        canvas.drawArc(
            RectF(cx - radius, cy - radius, cx + radius, cy + radius),
            252f, 36f, false, arcPaint
        )
    }

    private fun drawTicks(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            strokeWidth = 1.5f
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 8f * dp
            textAlign = Paint.Align.CENTER
        }

        for (cents in -50..50 step 10) {
            val angle = cents / MAX_CENTS * 90f
            val rad = Math.toRadians((angle + 270.0))
            val innerR = radius * 0.75f
            val outerR = radius * 1.05f
            val x1 = cx + (innerR * cos(rad)).toFloat()
            val y1 = cy + (innerR * sin(rad)).toFloat()
            val x2 = cx + (outerR * cos(rad)).toFloat()
            val y2 = cy + (outerR * sin(rad)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, tickPaint)

            // Draw cent labels
            val labelR = radius * 1.2f
            val lx = cx + (labelR * cos(rad)).toFloat()
            val ly = cy + (labelR * sin(rad)).toFloat()
            val label = if (cents == 0) "0" else "$cents"
            canvas.drawText(label, lx, ly + 4f * dp, labelPaint)
        }
    }

    private fun drawNeedle(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val rad = Math.toRadians((needleAngle + 270.0))
        val needleLength = radius * 0.82f
        val nx = cx + (needleLength * cos(rad)).toFloat()
        val ny = cy + (needleLength * sin(rad)).toFloat()

        // Needle color based on tuning accuracy
        val needleColor = when {
            !isActive -> Color.LTGRAY
            kotlin.math.abs(centsOffset) < 5f -> Color.rgb(0, 150, 0)
            kotlin.math.abs(centsOffset) < 20f -> Color.rgb(200, 140, 0)
            else -> Color.RED
        }
        needlePaint.color = needleColor
        needlePaint.strokeWidth = 3f * dp

        canvas.drawLine(cx, cy, nx, ny, needlePaint)

        // Small triangle at tip
        val tipAngle = Math.toRadians((needleAngle + 270.0))
        val tipPath = Path()
        val tipX = cx + (radius * 0.88f * cos(tipAngle)).toFloat()
        val tipY = cy + (radius * 0.88f * sin(tipAngle)).toFloat()
        tipPath.moveTo(tipX, tipY)
        val perpAngle = tipAngle + Math.PI / 2
        tipPath.lineTo(
            tipX + (3f * dp * cos(perpAngle)).toFloat(),
            tipY + (3f * dp * sin(perpAngle)).toFloat()
        )
        tipPath.lineTo(
            tipX - (3f * dp * cos(perpAngle)).toFloat(),
            tipY - (3f * dp * sin(perpAngle)).toFloat()
        )
        tipPath.close()
        canvas.drawPath(tipPath, Paint(needlePaint).apply { style = Paint.Style.FILL })
    }

    private fun drawStringButtons(canvas: Canvas, w: Float, h: Float) {
        val buttonY = h - 35f * dp
        val buttonW = w / 5f
        val strings = HpsProcessor.CELLO_STRINGS

        stringPaint.textSize = 13f * dp
        stringPaint.typeface = Typeface.DEFAULT_BOLD

        strings.forEachIndexed { index, str ->
            val bx = buttonW * (index + 0.5f) + buttonW * 0.5f
            val isActive = str == activeString

            // Background
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (isActive) Color.rgb(0, 100, 200) else Color.rgb(220, 220, 220)
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(
                RectF(bx - buttonW * 0.4f, buttonY - 18f * dp, bx + buttonW * 0.4f, buttonY + 10f * dp),
                8f * dp, 8f * dp, bgPaint
            )

            stringPaint.color = if (isActive) Color.WHITE else Color.BLACK
            canvas.drawText(str.name, bx, buttonY, stringPaint)
        }
    }
}

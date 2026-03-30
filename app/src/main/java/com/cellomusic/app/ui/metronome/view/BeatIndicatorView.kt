package com.cellomusic.app.ui.metronome.view

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.cellomusic.app.audio.metronome.MetronomeEngine
import kotlin.math.cos
import kotlin.math.sin

/**
 * Visual metronome indicator showing a swinging pendulum and beat flash.
 */
class BeatIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val dp = context.resources.displayMetrics.density

    private var pendulumAngle: Float = -30f
        set(value) {
            field = value
            invalidate()
        }

    private var flashAlpha: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    private var beatNumber: Int = 0
    private var totalBeats: Int = 4
    private var bpm: Int = 120
    private var isDownbeat: Boolean = false

    private val pendulumRodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val pendulumBobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(50, 100, 200)
        style = Paint.Style.FILL
    }
    private val pivotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        style = Paint.Style.FILL
    }
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val beatDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val bpmTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private var pendulumAnimator: ObjectAnimator? = null
    private var flashAnimator: ValueAnimator? = null

    fun onBeat(state: MetronomeEngine.BeatState, numerator: Int, bpmValue: Int) {
        beatNumber = state.beatNumber
        totalBeats = numerator
        bpm = bpmValue
        isDownbeat = state.isDownbeat

        // Swing pendulum to opposite side
        val targetAngle = if (pendulumAngle > 0) -30f else 30f
        val beatDurationMs = (60000L / bpmValue).toLong()

        pendulumAnimator?.cancel()
        pendulumAnimator = ObjectAnimator.ofFloat(this, "pendulumAngle", pendulumAngle, targetAngle).apply {
            duration = beatDurationMs
            interpolator = android.view.animation.CycleInterpolator(0.5f)
            start()
        }

        // Flash on beat
        val flashColor = if (isDownbeat) Color.rgb(0, 150, 255) else Color.rgb(180, 200, 255)
        flashPaint.color = flashColor

        flashAnimator?.cancel()
        flashAnimator = ValueAnimator.ofInt(200, 0).apply {
            duration = 200L
            addUpdateListener { anim ->
                flashAlpha = anim.animatedValue as Int
            }
            start()
        }

        invalidate()
    }

    fun stop() {
        pendulumAnimator?.cancel()
        flashAnimator?.cancel()
        pendulumAngle = 0f
        flashAlpha = 0
        beatNumber = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2

        // Flash overlay
        if (flashAlpha > 0) {
            flashPaint.alpha = flashAlpha
            canvas.drawRoundRect(
                RectF(cx - 30f * dp, 10f * dp, cx + 30f * dp, 60f * dp),
                15f * dp, 15f * dp, flashPaint
            )
        }

        // BPM display
        bpmTextPaint.textSize = 20f * dp
        if (bpm > 0) canvas.drawText("$bpm", cx, 50f * dp, bpmTextPaint)

        // Pendulum pivot
        val pivotX = cx
        val pivotY = 80f * dp
        canvas.drawCircle(pivotX, pivotY, 7f * dp, pivotPaint)

        // Pendulum rod
        val rodLength = h * 0.45f
        val radians = Math.toRadians(pendulumAngle.toDouble())
        val bobX = pivotX + (rodLength * sin(radians)).toFloat()
        val bobY = pivotY + (rodLength * cos(radians)).toFloat()

        canvas.drawLine(pivotX, pivotY, bobX, bobY, pendulumRodPaint)
        canvas.drawCircle(bobX, bobY, 18f * dp, pendulumBobPaint)

        // Beat number on bob
        if (beatNumber > 0) {
            val beatText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 14f * dp
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText(beatNumber.toString(), bobX, bobY + 5f * dp, beatText)
        }

        // Beat dot indicators at bottom
        drawBeatDots(canvas, cx, h - 25f * dp)
    }

    private fun drawBeatDots(canvas: Canvas, cx: Float, y: Float) {
        if (totalBeats <= 0) return
        val spacing = 18f * dp
        val startX = cx - (totalBeats - 1) * spacing / 2

        for (i in 1..totalBeats) {
            val dotX = startX + (i - 1) * spacing
            beatDotPaint.color = when {
                i == beatNumber && isDownbeat -> Color.rgb(0, 150, 255)
                i == beatNumber -> Color.rgb(0, 100, 200)
                i == 1 -> Color.rgb(150, 150, 200)
                else -> Color.LTGRAY
            }
            val radius = if (i == 1) 7f * dp else 5f * dp
            canvas.drawCircle(dotX, y, radius, beatDotPaint)
        }
    }
}

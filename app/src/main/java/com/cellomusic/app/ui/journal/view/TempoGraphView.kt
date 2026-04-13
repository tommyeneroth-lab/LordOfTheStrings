package com.cellomusic.app.ui.journal.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Simple line chart showing BPM progression over time for a piece.
 */
class TempoGraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dp = context.resources.displayMetrics.density

    data class TempoPoint(val timestampMs: Long, val bpm: Int)

    private var data = listOf<TempoPoint>()
    private var minBpm = 40
    private var maxBpm = 200

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF333333.toInt()
        strokeWidth = 1f * dp
        style = Paint.Style.STROKE
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC9A84C.toInt()
        strokeWidth = 2.5f * dp
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD4A42A.toInt()
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8A6E2A.toInt()
        textSize = 10f * dp
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF666666.toInt()
        textSize = 14f * dp
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    fun setData(points: List<TempoPoint>) {
        data = points.sortedBy { it.timestampMs }
        if (data.isNotEmpty()) {
            minBpm = (data.minOf { it.bpm } - 10).coerceAtLeast(20)
            maxBpm = (data.maxOf { it.bpm } + 10).coerceAtMost(400)
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (160 * dp).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val pad = 32f * dp
        val chartLeft = pad
        val chartRight = width - 12f * dp
        val chartTop = 12f * dp
        val chartBottom = height - pad

        if (data.size < 2) {
            canvas.drawText(
                if (data.isEmpty()) "No tempo data yet" else "Need 2+ data points",
                width / 2f, height / 2f, emptyPaint
            )
            return
        }

        // Grid lines
        val bpmRange = maxBpm - minBpm
        for (i in 0..4) {
            val bpm = minBpm + bpmRange * i / 4
            val y = chartBottom - (bpm - minBpm).toFloat() / bpmRange * (chartBottom - chartTop)
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint)
            canvas.drawText("${bpm}", 2f * dp, y + 4f * dp, labelPaint)
        }

        // Line path
        val timeRange = data.last().timestampMs - data.first().timestampMs
        if (timeRange <= 0) return

        val path = Path()
        for ((i, point) in data.withIndex()) {
            val x = chartLeft + (point.timestampMs - data.first().timestampMs).toFloat() / timeRange * (chartRight - chartLeft)
            val y = chartBottom - (point.bpm - minBpm).toFloat() / bpmRange * (chartBottom - chartTop)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)

        // Dots
        for (point in data) {
            val x = chartLeft + (point.timestampMs - data.first().timestampMs).toFloat() / timeRange * (chartRight - chartLeft)
            val y = chartBottom - (point.bpm - minBpm).toFloat() / bpmRange * (chartBottom - chartTop)
            canvas.drawCircle(x, y, 4f * dp, dotPaint)
        }
    }
}

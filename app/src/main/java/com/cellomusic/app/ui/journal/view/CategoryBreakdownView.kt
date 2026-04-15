package com.cellomusic.app.ui.journal.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Horizontal bar chart showing practice time breakdown by category.
 * Styled to match the dark oak + gold antique theme.
 */
class CategoryBreakdownView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class CategoryData(
        val label: String,
        val minutes: Int,
        val color: Int
    )

    private val dp = context.resources.displayMetrics.density

    private var categories = listOf<CategoryData>()
    private var totalMinutes = 0

    private val barHeight = 18f * dp
    private val barGap = 10f * dp
    private val labelAreaWidth = 100f * dp
    private val valueAreaWidth = 60f * dp

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1C1C1C.toInt()
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4E4C1.toInt()
        textSize = 11f * dp
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8A6E2A.toInt()
        textSize = 11f * dp
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.RIGHT
    }
    private val percentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC9A84C.toInt()
        textSize = 10f * dp
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }

    companion object {
        // Category colors — warm palette matching the app theme
        val CATEGORY_COLORS = mapOf(
            "INTONATION" to 0xFFC9A84C.toInt(),       // antique gold
            "VIBRATO" to 0xFFD4A42A.toInt(),           // brass bright
            "BOW_TECHNIQUE" to 0xFF8A6E2A.toInt(),     // gold dim
            "MEMORIZATION" to 0xFFB5860D.toInt(),      // brass
            "SCALES" to 0xFF5C4A1E.toInt(),            // dark gold
            "SIGHT_READING" to 0xFFC8B89A.toInt(),     // aged ivory dim
            "" to 0xFF5C3A1E.toInt()                   // oak medium (uncategorized)
        )

        fun friendlyName(category: String): String = when (category) {
            "INTONATION" -> "Intonation"
            "VIBRATO" -> "Vibrato"
            "BOW_TECHNIQUE" -> "Bow Technique"
            "MEMORIZATION" -> "Memorization"
            "SCALES" -> "Scales"
            "SIGHT_READING" -> "Sight Reading"
            "" -> "General"
            else -> category.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    fun setData(categoryMinutes: Map<String, Int>) {
        totalMinutes = categoryMinutes.values.sum().coerceAtLeast(1)
        categories = categoryMinutes
            .filter { it.value > 0 }
            .entries
            .sortedByDescending { it.value }
            .map { (cat, mins) ->
                CategoryData(
                    label = friendlyName(cat),
                    minutes = mins,
                    color = CATEGORY_COLORS[cat] ?: 0xFF8A6E2A.toInt()
                )
            }
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val rows = categories.size.coerceAtLeast(1)
        val h = (rows * (barHeight + barGap) + 8 * dp).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        if (categories.isEmpty()) {
            labelPaint.color = 0xFF8A6E2A.toInt()
            canvas.drawText("No practice data yet", 0f, barHeight, labelPaint)
            labelPaint.color = 0xFFF4E4C1.toInt()
            return
        }

        val barLeft = labelAreaWidth
        val barMaxWidth = width - labelAreaWidth - valueAreaWidth - 8 * dp
        val maxMinInCategory = categories.maxOf { it.minutes }.coerceAtLeast(1)

        for ((i, cat) in categories.withIndex()) {
            val y = i * (barHeight + barGap)

            // Label
            canvas.drawText(
                cat.label,
                0f,
                y + barHeight * 0.72f,
                labelPaint
            )

            // Background bar
            canvas.drawRoundRect(
                barLeft, y + 2 * dp,
                barLeft + barMaxWidth, y + barHeight - 2 * dp,
                4 * dp, 4 * dp, barBgPaint
            )

            // Filled bar
            val ratio = cat.minutes.toFloat() / maxMinInCategory
            val filledWidth = (barMaxWidth * ratio).coerceAtLeast(4 * dp)
            barPaint.color = cat.color
            canvas.drawRoundRect(
                barLeft, y + 2 * dp,
                barLeft + filledWidth, y + barHeight - 2 * dp,
                4 * dp, 4 * dp, barPaint
            )

            // Percentage on bar
            val pct = (cat.minutes * 100 / totalMinutes)
            if (filledWidth > 36 * dp) {
                canvas.drawText(
                    "${pct}%",
                    barLeft + 6 * dp,
                    y + barHeight * 0.68f,
                    percentPaint
                )
            }

            // Minutes value on the right
            val minStr = if (cat.minutes >= 60) {
                "${cat.minutes / 60}h ${cat.minutes % 60}m"
            } else {
                "${cat.minutes}m"
            }
            canvas.drawText(
                minStr,
                width.toFloat() - 4 * dp,
                y + barHeight * 0.72f,
                valuePaint
            )
        }
    }
}

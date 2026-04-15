package com.cellomusic.app.ui.journal.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.cellomusic.app.domain.achievement.AchievementCatalog

/**
 * Renders all achievements from [AchievementCatalog] as a tile grid.
 *
 * Locked tiles are dimmed silhouettes; unlocked tiles bloom gold with the
 * achievement icon and title. Tapping a tile invokes [onAchievementTapped]
 * with the catalog [AchievementCatalog.Def], which the host fragment can
 * use to pop a description dialog.
 *
 * Tile sizing is adaptive: as many columns as fit with a ~96dp target width,
 * minimum 3 columns. The view's height grows to fit all rows — put it in a
 * ScrollView for best results.
 */
class AchievementGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val gold    = 0xFFC9A84C.toInt()
    private val goldDim = 0xFF8A6E2A.toInt()
    private val brass   = 0xFFD4A42A.toInt()
    private val ivory   = 0xFFF4E4C1.toInt()
    private val surface = 0xFF161616.toInt()
    private val lockedBg = 0xFF0F0F0F.toInt()
    private val lockedStroke = 0xFF333026.toInt()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(28f)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textSize = dp(10f)
        letterSpacing = 0.04f
    }
    private val lockIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(20f)
        color = goldDim
        alpha = 140
    }

    private val tileRect = RectF()

    private var unlockedIds: Set<String> = emptySet()
    private var tileW = 0f
    private var tileH = 0f
    private val tileGap = dp(8f)

    var onAchievementTapped: ((AchievementCatalog.Def, Boolean) -> Unit)? = null

    fun setUnlockedIds(ids: Set<String>) {
        unlockedIds = ids
        requestLayout()
        invalidate()
    }

    private fun columns(availableWidth: Float): Int {
        val target = dp(96f)
        val byFit = ((availableWidth + tileGap) / (target + tileGap)).toInt()
        return byFit.coerceAtLeast(3)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val w = if (widthMode == MeasureSpec.UNSPECIFIED) dp(360f).toInt() else widthSize

        val cols = columns(w.toFloat())
        val total = AchievementCatalog.ALL.size
        val rows = (total + cols - 1) / cols
        tileW = (w - tileGap * (cols - 1)) / cols
        tileH = tileW * 1.15f  // slightly taller than wide for the title line
        val h = (tileH * rows + tileGap * (rows - 1)).toInt().coerceAtLeast(dp(120f).toInt())
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val cols = columns(width.toFloat())
        for ((index, def) in AchievementCatalog.ALL.withIndex()) {
            val row = index / cols
            val col = index % cols
            val left = col * (tileW + tileGap)
            val top = row * (tileH + tileGap)
            drawTile(canvas, def, left, top)
        }
    }

    private fun drawTile(canvas: Canvas, def: AchievementCatalog.Def, left: Float, top: Float) {
        tileRect.set(left, top, left + tileW, top + tileH)
        val unlocked = def.id in unlockedIds

        // Background
        bgPaint.color = if (unlocked) surface else lockedBg
        canvas.drawRoundRect(tileRect, dp(8f), dp(8f), bgPaint)

        // Tier-coloured stroke when unlocked; dim locked stroke otherwise
        strokePaint.color = if (unlocked) tierColor(def.tier) else lockedStroke
        strokePaint.strokeWidth = if (unlocked) dp(1.5f) else dp(1.0f)
        canvas.drawRoundRect(tileRect, dp(8f), dp(8f), strokePaint)

        val cx = (tileRect.left + tileRect.right) / 2f

        if (unlocked) {
            // Icon + title in full gold
            val iconY = tileRect.top + tileH * 0.52f
            iconPaint.color = ivory
            iconPaint.alpha = 255
            canvas.drawText(def.icon, cx, iconY, iconPaint)

            titlePaint.color = tierColor(def.tier)
            titlePaint.alpha = 255
            drawWrappedTitle(canvas, def.title, cx, tileRect.bottom - dp(10f))
        } else {
            // Locked: big lock emoji, dim title
            val iconY = tileRect.top + tileH * 0.52f
            canvas.drawText("🔒", cx, iconY, lockIconPaint)

            titlePaint.color = goldDim
            titlePaint.alpha = 180
            drawWrappedTitle(canvas, def.title, cx, tileRect.bottom - dp(10f))
        }
    }

    /** Draws the title on one or two lines, centered on [cx] ending at [baselineY]. */
    private fun drawWrappedTitle(canvas: Canvas, title: String, cx: Float, baselineY: Float) {
        val maxWidth = tileW - dp(8f)
        if (titlePaint.measureText(title) <= maxWidth) {
            canvas.drawText(title, cx, baselineY, titlePaint)
            return
        }
        // Naive two-line wrap at the nearest space to the midpoint.
        val mid = title.length / 2
        val spaceBefore = title.lastIndexOf(' ', mid).let { if (it == -1) title.length else it }
        val spaceAfter = title.indexOf(' ', mid).let { if (it == -1) title.length else it }
        val breakAt = if (mid - spaceBefore <= spaceAfter - mid) spaceBefore else spaceAfter
        if (breakAt <= 0 || breakAt >= title.length) {
            canvas.drawText(title, cx, baselineY, titlePaint)
            return
        }
        val line1 = title.substring(0, breakAt).trim()
        val line2 = title.substring(breakAt).trim()
        val lineH = titlePaint.textSize + dp(1f)
        canvas.drawText(line2, cx, baselineY, titlePaint)
        canvas.drawText(line1, cx, baselineY - lineH, titlePaint)
    }

    private fun tierColor(tier: AchievementCatalog.Tier): Int = when (tier) {
        AchievementCatalog.Tier.BRONZE -> 0xFFB07A3E.toInt()
        AchievementCatalog.Tier.SILVER -> 0xFFCFCFCF.toInt()
        AchievementCatalog.Tier.GOLD   -> gold
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val cols = columns(width.toFloat())
        val col = ((event.x / (tileW + tileGap)).toInt()).coerceIn(0, cols - 1)
        val row = (event.y / (tileH + tileGap)).toInt()
        val index = row * cols + col
        val def = AchievementCatalog.ALL.getOrNull(index) ?: return true
        onAchievementTapped?.invoke(def, def.id in unlockedIds)
        performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

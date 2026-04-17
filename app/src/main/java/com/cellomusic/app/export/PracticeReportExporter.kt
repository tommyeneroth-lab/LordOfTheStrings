package com.cellomusic.app.export

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.cellomusic.app.data.db.AppDatabase
import com.cellomusic.app.data.repository.AchievementRepository
import com.cellomusic.app.data.repository.GamificationRepository
import com.cellomusic.app.data.repository.JournalRepository
import com.cellomusic.app.domain.achievement.AchievementCatalog
import com.cellomusic.app.domain.gamification.LevelTitles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Generates a printable PDF practice report. Everything the Stats tab
 * shows — headline numbers, weekly/monthly summary, category breakdown,
 * mini heatmap, achievements — distilled into a one-or-two page document
 * the student can email to a teacher.
 *
 * Uses Android's built-in [PdfDocument] Canvas API (same approach the
 * score exporter uses) so no extra dependencies are pulled in.
 */
class PracticeReportExporter(private val context: Context) {

    // ── Period selector ────────────────────────────────────────────────

    enum class Period(val days: Int, val label: String) {
        LAST_30(30, "Last 30 days"),
        LAST_90(90, "Last 90 days"),
        ALL_TIME(0, "All time")
    }

    // ── Page geometry (A4) ─────────────────────────────────────────────

    private val PAGE_W = 595
    private val PAGE_H = 842
    private val MARGIN_L = 40f
    private val MARGIN_R = 40f
    private val MARGIN_TOP = 40f
    private val MARGIN_BOT = 40f
    private val CONTENT_W get() = PAGE_W - MARGIN_L - MARGIN_R

    // Brand palette — kept in sync with values/colors.xml
    private val COLOR_GOLD = Color.parseColor("#C9A84C")
    private val COLOR_GOLD_DIM = Color.parseColor("#8A6E2A")
    private val COLOR_IVORY = Color.parseColor("#F4E4C1")
    private val COLOR_INK = Color.parseColor("#1C0E07")
    private val COLOR_MUTED = Color.parseColor("#666666")
    private val COLOR_RULE = Color.parseColor("#D0C7B4")

    // ── Entry point ────────────────────────────────────────────────────

    suspend fun exportReport(period: Period): Intent = withContext(Dispatchers.IO) {
        val data = gatherData(period)
        val file = outputFile(period)
        FileOutputStream(file).use { out ->
            val doc = PdfDocument()
            renderReport(doc, data)
            doc.writeTo(out)
            doc.close()
        }
        shareIntent(file)
    }

    // ── Data gathering ────────────────────────────────────────────────

    private data class ReportData(
        val period: Period,
        val rangeStartMs: Long,
        val rangeEndMs: Long,
        val totalMinutes: Int,
        val sessionCount: Int,
        val avgSelfEval: Float,
        val currentStreak: Int,
        val longestStreak: Int,
        val level: Int,
        val levelTitle: String,
        val lifetimeMinutes: Int,
        val totalPoints: Int,
        val weeklyMinutes: Int,
        val monthlyMinutes: Int,
        val categoryTotals: List<Pair<String, Int>>,
        val topPieces: List<Pair<String, Int>>,
        val dailyMinutes: Map<Long, Int>,        // localDayMs → minutes, whole 12-week window
        val unlockedAchievements: List<AchievementCatalog.Def>,
        val lockedAchievementCount: Int
    )

    private suspend fun gatherData(period: Period): ReportData {
        val db = AppDatabase.getInstance(context)
        val journalRepo = JournalRepository(db.practiceSessionDao())
        val gamRepo = GamificationRepository(db.gamificationDao())
        val achRepo = AchievementRepository(
            db.achievementDao(), db.practiceSessionDao(), db.gamificationDao()
        )

        val now = System.currentTimeMillis()
        val rangeStart = if (period == Period.ALL_TIME) 0L
        else Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -period.days) }.timeInMillis

        val totalMinutes = journalRepo.getTotalMinutesInRange(rangeStart, now)
        val sessionCount = journalRepo.countSessionsInRange(rangeStart, now)
        val avgEval = journalRepo.getAvgSelfEvalInRange(rangeStart, now)
        val catTotals = journalRepo.getCategoryTotals(rangeStart, now)
            .map { it.category to it.totalMin }
            .filter { it.first.isNotBlank() }

        // Top pieces: sessions in range, grouped by piece name, summed
        val sessions = journalRepo.getSessionsInRange(rangeStart, now).first()
        val pieceMap = mutableMapOf<String, Int>()
        for (s in sessions) {
            if (s.pieceName.isNotBlank()) {
                pieceMap[s.pieceName] = (pieceMap[s.pieceName] ?: 0) + s.durationMin
            }
        }
        val topPieces = pieceMap.entries.sortedByDescending { it.value }.take(5)
            .map { it.key to it.value }

        // Daily buckets for mini heatmap — always 12 weeks (84 days) regardless of period
        val heatmapStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -83)
        }.timeInMillis
        val heatmapSessions = journalRepo.getSessionsInRange(heatmapStart, now).first()
        val dailyMinutes = mutableMapOf<Long, Int>()
        val bucketCal = Calendar.getInstance()
        for (s in heatmapSessions) {
            bucketCal.timeInMillis = s.timestampMs
            bucketCal.set(Calendar.HOUR_OF_DAY, 0); bucketCal.set(Calendar.MINUTE, 0)
            bucketCal.set(Calendar.SECOND, 0); bucketCal.set(Calendar.MILLISECOND, 0)
            val k = bucketCal.timeInMillis
            dailyMinutes[k] = (dailyMinutes[k] ?: 0) + s.durationMin
        }

        // Weekly / monthly totals (fixed windows, independent of report period)
        val weekStart = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val monthStart = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val weeklyMinutes = journalRepo.getTotalMinutesInRange(weekStart, now)
        val monthlyMinutes = journalRepo.getTotalMinutesInRange(monthStart, now)

        val profile = gamRepo.getProfile().first()
        val unlockedIds = achRepo.getUnlockedIds()
        val unlocked = AchievementCatalog.ALL.filter { it.id in unlockedIds }

        return ReportData(
            period = period,
            rangeStartMs = rangeStart,
            rangeEndMs = now,
            totalMinutes = totalMinutes,
            sessionCount = sessionCount,
            avgSelfEval = avgEval,
            currentStreak = profile?.currentStreakDays ?: 0,
            longestStreak = profile?.longestStreakDays ?: 0,
            level = profile?.currentLevel ?: 0,
            levelTitle = LevelTitles.titleFor(profile?.currentLevel ?: 0),
            lifetimeMinutes = profile?.lifetimeMinutes ?: 0,
            totalPoints = profile?.totalPoints ?: 0,
            weeklyMinutes = weeklyMinutes,
            monthlyMinutes = monthlyMinutes,
            categoryTotals = catTotals,
            topPieces = topPieces,
            dailyMinutes = dailyMinutes,
            unlockedAchievements = unlocked,
            lockedAchievementCount = AchievementCatalog.ALL.size - unlocked.size
        )
    }

    // ── Rendering ──────────────────────────────────────────────────────

    private fun renderReport(doc: PdfDocument, d: ReportData) {
        var pageNum = 1
        val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create()
        val page = doc.startPage(info)
        val canvas = page.canvas

        var y = MARGIN_TOP

        // Header
        y = drawHeader(canvas, d, y)
        y += 16f

        // Big number cards: Total | Sessions | Streak | Level
        y = drawBigNumberRow(canvas, d, y)
        y += 18f

        // Weekly / Monthly row
        y = drawWeeklyMonthlyRow(canvas, d, y)
        y += 18f

        // Category breakdown
        if (d.categoryTotals.isNotEmpty()) {
            y = drawSectionTitle(canvas, "CATEGORY BREAKDOWN", y)
            y = drawCategoryBars(canvas, d.categoryTotals, y)
            y += 16f
        }

        // Top pieces
        if (d.topPieces.isNotEmpty()) {
            y = drawSectionTitle(canvas, "TOP PIECES", y)
            y = drawTopPieces(canvas, d.topPieces, y)
            y += 16f
        }

        // Heatmap
        y = drawSectionTitle(canvas, "PRACTICE CALENDAR (LAST 12 WEEKS)", y)
        y = drawHeatmap(canvas, d.dailyMinutes, y)
        y += 16f

        // Achievements
        y = drawSectionTitle(canvas, "ACHIEVEMENTS", y)
        y = drawAchievements(canvas, d, y)

        // Footer
        drawFooter(canvas, pageNum)

        doc.finishPage(page)
    }

    // ── Header ─────────────────────────────────────────────────────────

    private fun drawHeader(canvas: Canvas, d: ReportData, topY: Float): Float {
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_INK; textSize = 22f; isFakeBoldText = true
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        canvas.drawText("Lord of the Strings", MARGIN_L, topY + 18f, title)

        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_GOLD_DIM; textSize = 11f
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        }
        canvas.drawText("Practice Report", MARGIN_L, topY + 34f, sub)

        val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH)
        val rangeStr = if (d.period == Period.ALL_TIME) {
            "All time · generated ${dateFmt.format(Date(d.rangeEndMs))}"
        } else {
            "${dateFmt.format(Date(d.rangeStartMs))} – ${dateFmt.format(Date(d.rangeEndMs))}"
        }
        val rangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_MUTED; textSize = 9f; textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(rangeStr, PAGE_W - MARGIN_R, topY + 18f, rangePaint)
        val periodPaint = Paint(rangePaint).apply {
            color = COLOR_GOLD; textSize = 10f; isFakeBoldText = true
        }
        canvas.drawText(d.period.label, PAGE_W - MARGIN_R, topY + 34f, periodPaint)

        // Gold divider
        val divider = Paint().apply { color = COLOR_GOLD; strokeWidth = 1.2f }
        canvas.drawLine(MARGIN_L, topY + 44f, PAGE_W - MARGIN_R, topY + 44f, divider)

        return topY + 50f
    }

    // ── Big-number card row ───────────────────────────────────────────

    private fun drawBigNumberRow(canvas: Canvas, d: ReportData, topY: Float): Float {
        val cards = listOf(
            Triple(formatMinutes(d.totalMinutes), "TOTAL TIME", subDetail("${d.sessionCount} sessions")),
            Triple(d.sessionCount.toString(), "SESSIONS",
                subDetail(if (d.avgSelfEval > 0f) "avg ${"%.1f".format(d.avgSelfEval)}/5" else "—")),
            Triple("${d.currentStreak}d", "CURRENT STREAK", subDetail("best ${d.longestStreak}d")),
            Triple(d.level.toString(), "LEVEL", subDetail(d.levelTitle))
        )
        val cardW = (CONTENT_W - 3 * 8f) / 4f
        val cardH = 70f
        var x = MARGIN_L
        for ((big, label, small) in cards) {
            drawCard(canvas, x, topY, cardW, cardH, big, label, small)
            x += cardW + 8f
        }
        return topY + cardH
    }

    private fun subDetail(s: String) = s

    private fun drawCard(
        canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
        big: String, label: String, small: String
    ) {
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_GOLD; style = Paint.Style.STROKE; strokeWidth = 1f
        }
        val rect = RectF(x, y, x + w, y + h)
        canvas.drawRoundRect(rect, 4f, 4f, border)

        val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_INK
            textSize = if (big.length > 6) 20f else 26f
            isFakeBoldText = true
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(big, x + w / 2f, y + 30f, bigPaint)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_GOLD_DIM; textSize = 8f
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.12f
        }
        canvas.drawText(label, x + w / 2f, y + 46f, labelPaint)

        val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_MUTED; textSize = 9f
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(small, x + w / 2f, y + 60f, smallPaint)
    }

    // ── Weekly/Monthly side-by-side cards ─────────────────────────────

    private fun drawWeeklyMonthlyRow(canvas: Canvas, d: ReportData, topY: Float): Float {
        val cardW = (CONTENT_W - 8f) / 2f
        val cardH = 48f
        drawSummaryBox(canvas, MARGIN_L, topY, cardW, cardH, "THIS WEEK", formatMinutes(d.weeklyMinutes))
        drawSummaryBox(canvas, MARGIN_L + cardW + 8f, topY, cardW, cardH, "THIS MONTH", formatMinutes(d.monthlyMinutes))
        return topY + cardH
    }

    private fun drawSummaryBox(
        canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
        label: String, value: String
    ) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFF8E8"); style = Paint.Style.FILL
        }
        val rect = RectF(x, y, x + w, y + h)
        canvas.drawRoundRect(rect, 3f, 3f, fill)

        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_GOLD_DIM; style = Paint.Style.STROKE; strokeWidth = 0.6f
        }
        canvas.drawRoundRect(rect, 3f, 3f, border)

        val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_GOLD_DIM; textSize = 8.5f; letterSpacing = 0.12f
        }
        canvas.drawText(label, x + 10f, y + 15f, lp)

        val vp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_INK; textSize = 18f; isFakeBoldText = true
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        canvas.drawText(value, x + 10f, y + 36f, vp)
    }

    // ── Category bars ─────────────────────────────────────────────────

    private fun drawCategoryBars(canvas: Canvas, cats: List<Pair<String, Int>>, topY: Float): Float {
        val maxMin = cats.maxOf { it.second }.coerceAtLeast(1)
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_INK; textSize = 9.5f
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_MUTED; textSize = 9f; textAlign = Paint.Align.RIGHT
        }
        val barBg = Paint().apply { color = Color.parseColor("#EEE6D2") }
        val barFg = Paint().apply { color = COLOR_GOLD }
        val rowH = 16f
        var y = topY
        val labelW = 110f
        val valueW = 70f
        for ((cat, min) in cats.take(8)) {
            canvas.drawText(prettyCategory(cat), MARGIN_L, y + 11f, labelPaint)
            val barX = MARGIN_L + labelW
            val barW = CONTENT_W - labelW - valueW - 10f
            canvas.drawRoundRect(RectF(barX, y + 3f, barX + barW, y + 13f), 2f, 2f, barBg)
            val fillW = barW * min / maxMin.toFloat()
            canvas.drawRoundRect(RectF(barX, y + 3f, barX + fillW, y + 13f), 2f, 2f, barFg)
            canvas.drawText(formatMinutes(min), PAGE_W - MARGIN_R, y + 11f, valuePaint)
            y += rowH
        }
        return y
    }

    private fun prettyCategory(raw: String): String {
        // Enum names like "BOW_TECHNIQUE" → "Bow Technique"
        return raw.split('_').joinToString(" ") { w ->
            if (w.isEmpty()) "" else w.first().uppercaseChar() + w.drop(1).lowercase()
        }
    }

    // ── Top pieces ────────────────────────────────────────────────────

    private fun drawTopPieces(canvas: Canvas, pieces: List<Pair<String, Int>>, topY: Float): Float {
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_INK; textSize = 10f
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_GOLD_DIM; textSize = 10f; textAlign = Paint.Align.RIGHT
            isFakeBoldText = true
        }
        var y = topY
        for ((name, min) in pieces) {
            val truncated = if (name.length > 60) name.take(57) + "…" else name
            canvas.drawText("• $truncated", MARGIN_L + 4f, y + 12f, labelPaint)
            canvas.drawText(formatMinutes(min), PAGE_W - MARGIN_R, y + 12f, valuePaint)
            y += 15f
        }
        return y
    }

    // ── Mini heatmap (12 weeks × 7 days) ──────────────────────────────

    private fun drawHeatmap(canvas: Canvas, dailyMinutes: Map<Long, Int>, topY: Float): Float {
        val weeks = 12
        val days = 7
        val cell = 12f
        val gap = 2f
        val gridW = weeks * (cell + gap) - gap
        val gridH = days * (cell + gap) - gap

        // Column headers — month labels for each column
        val monthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_MUTED; textSize = 7f
        }
        val dayLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_MUTED; textSize = 7f
        }

        // Start date = Monday, 12 weeks before most recent Monday
        val startCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            // roll back to last Monday
            val dow = get(Calendar.DAY_OF_WEEK)
            val backToMonday = ((dow - Calendar.MONDAY) + 7) % 7
            add(Calendar.DAY_OF_YEAR, -backToMonday)
            add(Calendar.WEEK_OF_YEAR, -(weeks - 1))
        }

        val gridX = MARGIN_L + 22f     // day-of-week label column
        val gridTop = topY + 12f       // space for month labels

        // Day-of-week labels (M/W/F visible is enough)
        val dowNames = listOf("M", "T", "W", "T", "F", "S", "S")
        for (r in 0 until days) {
            if (r == 0 || r == 2 || r == 4) {
                canvas.drawText(dowNames[r], MARGIN_L, gridTop + r * (cell + gap) + 9f, dayLabelPaint)
            }
        }

        val maxMin = dailyMinutes.values.maxOrNull()?.coerceAtLeast(30) ?: 30

        val iterCal = Calendar.getInstance().apply { timeInMillis = startCal.timeInMillis }
        var lastMonth = -1
        for (w in 0 until weeks) {
            for (d in 0 until days) {
                val key = iterCal.timeInMillis
                val mins = dailyMinutes[key] ?: 0
                val cx = gridX + w * (cell + gap)
                val cy = gridTop + d * (cell + gap)
                val rect = RectF(cx, cy, cx + cell, cy + cell)
                val paint = Paint().apply { color = heatColor(mins, maxMin) }
                canvas.drawRoundRect(rect, 1.5f, 1.5f, paint)
                // Month label on the top row when month changes
                if (d == 0) {
                    val month = iterCal.get(Calendar.MONTH)
                    if (month != lastMonth) {
                        val monthName = SimpleDateFormat("MMM", Locale.ENGLISH).format(iterCal.time)
                        canvas.drawText(monthName, cx, gridTop - 3f, monthPaint)
                        lastMonth = month
                    }
                }
                iterCal.add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        // Legend
        val legendY = gridTop + gridH + 8f
        val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_MUTED; textSize = 7.5f
        }
        canvas.drawText("Less", gridX, legendY + 7f, legendPaint)
        var lx = gridX + 26f
        for (lvl in 0..4) {
            val frac = lvl / 4f
            val p = Paint().apply { color = heatColor((frac * maxMin).toInt(), maxMin) }
            canvas.drawRoundRect(RectF(lx, legendY, lx + 8f, legendY + 8f), 1f, 1f, p)
            lx += 10f
        }
        canvas.drawText("More", lx + 2f, legendY + 7f, legendPaint)

        return gridTop + gridH + 22f
    }

    private fun heatColor(minutes: Int, maxMin: Int): Int {
        if (minutes <= 0) return Color.parseColor("#EEE6D2")
        val frac = (minutes.toFloat() / maxMin.toFloat()).coerceIn(0f, 1f)
        // Interpolate from pale gold to rich gold. The in-app view uses a
        // similar antique-gold scale; using ARGB so tints stay controlled.
        val a = 0xFF
        // pale: #E9D89C, rich: #8A6E2A
        val r = (0xE9 + (0x8A - 0xE9) * frac).toInt()
        val g = (0xD8 + (0x6E - 0xD8) * frac).toInt()
        val b = (0x9C + (0x2A - 0x9C) * frac).toInt()
        return Color.argb(a, r, g, b)
    }

    // ── Achievements list ─────────────────────────────────────────────

    private fun drawAchievements(canvas: Canvas, d: ReportData, topY: Float): Float {
        val summary = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_MUTED; textSize = 10f
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        }
        canvas.drawText(
            "${d.unlockedAchievements.size} of ${AchievementCatalog.ALL.size} unlocked",
            MARGIN_L, topY + 12f, summary
        )
        var y = topY + 22f

        if (d.unlockedAchievements.isEmpty()) {
            val none = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_MUTED; textSize = 10f
            }
            canvas.drawText("— No achievements unlocked yet —", MARGIN_L, y + 10f, none)
            return y + 14f
        }

        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_INK; textSize = 10f
            isFakeBoldText = true
        }
        val descPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_MUTED; textSize = 8.5f
        }
        val tierPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 8f; textAlign = Paint.Align.RIGHT; letterSpacing = 0.1f
            isFakeBoldText = true
        }

        // Two-column layout
        val colW = CONTENT_W / 2f - 4f
        val rowH = 26f
        val sorted = d.unlockedAchievements.sortedWith(
            compareByDescending<AchievementCatalog.Def> { it.tier.ordinal }.thenBy { it.title }
        )
        val half = (sorted.size + 1) / 2
        val col1 = sorted.subList(0, half)
        val col2 = sorted.subList(half, sorted.size)

        fun drawCol(list: List<AchievementCatalog.Def>, x: Float): Float {
            var cy = y
            for (def in list) {
                // Icon (emoji)
                val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f }
                canvas.drawText(def.icon, x, cy + 12f, iconPaint)
                canvas.drawText(def.title, x + 18f, cy + 10f, namePaint)
                val shortDesc = if (def.description.length > 48)
                    def.description.take(45) + "…" else def.description
                canvas.drawText(shortDesc, x + 18f, cy + 21f, descPaint)
                tierPaint.color = when (def.tier) {
                    AchievementCatalog.Tier.GOLD -> COLOR_GOLD
                    AchievementCatalog.Tier.SILVER -> Color.parseColor("#9A9A9A")
                    AchievementCatalog.Tier.BRONZE -> Color.parseColor("#A8743A")
                }
                canvas.drawText(def.tier.name, x + colW, cy + 10f, tierPaint)
                cy += rowH
            }
            return cy
        }
        val yL = drawCol(col1, MARGIN_L)
        val yR = drawCol(col2, MARGIN_L + colW + 8f)
        return maxOf(yL, yR)
    }

    // ── Section title ─────────────────────────────────────────────────

    private fun drawSectionTitle(canvas: Canvas, label: String, topY: Float): Float {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_GOLD_DIM; textSize = 9.5f
            letterSpacing = 0.14f
            isFakeBoldText = true
        }
        canvas.drawText(label, MARGIN_L, topY + 10f, p)
        val rule = Paint().apply { color = COLOR_RULE; strokeWidth = 0.5f }
        canvas.drawLine(MARGIN_L, topY + 14f, PAGE_W - MARGIN_R, topY + 14f, rule)
        return topY + 22f
    }

    // ── Footer ────────────────────────────────────────────────────────

    private fun drawFooter(canvas: Canvas, pageNum: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_MUTED; textSize = 7.5f; textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).format(Date())
        canvas.drawText(
            "Lord of the Strings  •  Practice Report  •  Generated $stamp  •  Page $pageNum",
            PAGE_W / 2f, PAGE_H - 18f, p
        )
        val rule = Paint().apply { color = COLOR_RULE; strokeWidth = 0.4f }
        canvas.drawLine(MARGIN_L, PAGE_H - 28f, PAGE_W - MARGIN_R, PAGE_H - 28f, rule)
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun formatMinutes(m: Int): String {
        if (m <= 0) return "0m"
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }

    private fun outputFile(period: Period): File {
        val dir = File(context.cacheDir, "reports").also { it.mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(Date())
        val tag = when (period) {
            Period.LAST_30 -> "30d"
            Period.LAST_90 -> "90d"
            Period.ALL_TIME -> "all"
        }
        return File(dir, "practice_report_${tag}_$stamp.pdf")
    }

    private fun shareIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "My Cello Practice Report")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Attached is my cello practice report — generated by Lord of the Strings."
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share Practice Report"
        )
    }
}

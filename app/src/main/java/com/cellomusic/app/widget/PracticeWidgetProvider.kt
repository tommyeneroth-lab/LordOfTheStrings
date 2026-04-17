package com.cellomusic.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.widget.RemoteViews
import com.cellomusic.app.MainActivity
import com.cellomusic.app.R
import com.cellomusic.app.data.db.AppDatabase
import com.cellomusic.app.domain.gamification.LevelTitles
import kotlinx.coroutines.runBlocking
import java.util.Calendar

class PracticeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    companion object {

        fun requestUpdate(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                ComponentName(context, PracticeWidgetProvider::class.java)
            )
            for (id in ids) updateWidget(context, mgr, id)
        }

        private fun updateWidget(
            context: Context,
            mgr: AppWidgetManager,
            widgetId: Int
        ) {
            val (streak, level, todayXp, targetXp) = runBlocking { loadData(context) }

            val views = RemoteViews(context.packageName, R.layout.widget_practice)

            // Tap opens the app on the Journal tab
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pi = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_ring, pi)

            // Streak
            val streakLabel = if (streak == 1) "🔥 1-day streak" else "🔥 ${streak}-day streak"
            views.setTextViewText(R.id.widget_streak, streakLabel)

            // XP
            views.setTextViewText(R.id.widget_xp, "Today: $todayXp / $targetXp XP")

            // Level + title
            views.setTextViewText(R.id.widget_level, LevelTitles.formatLevelLine(level))

            // Ring bitmap
            val bmp = renderRing(todayXp, targetXp)
            views.setImageViewBitmap(R.id.widget_ring, bmp)

            mgr.updateAppWidget(widgetId, views)
        }

        private data class WidgetData(
            val streak: Int,
            val level: Int,
            val todayXp: Int,
            val targetXp: Int
        )

        private suspend fun loadData(context: Context): WidgetData {
            val db = AppDatabase.getInstance(context)
            val gamification = db.gamificationDao().getProfileSync()
            val streak = gamification?.currentStreakDays ?: 0
            val level = gamification?.currentLevel ?: 0

            // Today's XP = today's sessions × 10 XP per minute
            val cal = Calendar.getInstance()
            val endMs = cal.timeInMillis
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val startMs = cal.timeInMillis
            val todayMin = db.practiceSessionDao().getTotalMinutesInRange(startMs, endMs) ?: 0
            val todayXp = todayMin * 10

            // Target XP: 7-day rolling average × 10, rounded to nearest 50, min 100
            val sevenDaysAgoMs = startMs - 6L * 86_400_000L
            val weekMin = db.practiceSessionDao().getTotalMinutesInRange(sevenDaysAgoMs, endMs) ?: 0
            val avgMinPerDay = weekMin / 7.0
            val raw = (avgMinPerDay * 10).toInt()
            val targetXp = if (raw < 10) 100 else ((raw + 24) / 50 * 50).coerceAtLeast(100)

            return WidgetData(streak, level, todayXp, targetXp)
        }

        private fun renderRing(todayXp: Int, targetXp: Int): Bitmap {
            val size = 128
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val cx = size / 2f
            val cy = size / 2f
            val stroke = size * 0.13f
            val radius = cx - stroke / 2f - 4f
            val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

            // Background ring
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                color = Color.parseColor("#3E2215")
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawArc(oval, -90f, 360f, false, bgPaint)

            // Progress arc
            val frac = if (targetXp > 0) (todayXp.toFloat() / targetXp).coerceAtMost(1f) else 0f
            if (frac > 0f) {
                val goldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = stroke
                    color = Color.parseColor("#C9A84C")
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawArc(oval, -90f, frac * 360f, false, goldPaint)
            }

            // Overflow ring (above 100%)
            if (todayXp > targetXp) {
                val overFrac = ((todayXp - targetXp).toFloat() / targetXp).coerceAtMost(1f)
                val innerRadius = radius - stroke - 2f
                val innerOval = RectF(cx - innerRadius, cy - innerRadius, cx + innerRadius, cy + innerRadius)
                val overPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = stroke * 0.6f
                    color = Color.parseColor("#FFD700")
                    strokeCap = Paint.Cap.ROUND
                    maskFilter = BlurMaskFilter(stroke * 0.4f, BlurMaskFilter.Blur.NORMAL)
                }
                canvas.drawArc(innerOval, -90f, overFrac * 360f, false, overPaint)
            }

            // Center text: percentage or checkmark
            val pct = if (targetXp > 0) (todayXp * 100 / targetXp).coerceAtMost(999) else 0
            val label = if (pct >= 100) "✓" else "$pct%"
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#F4E4C1")
                textSize = size * 0.22f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            }
            val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(label, cx, textY, textPaint)

            return bmp
        }
    }
}

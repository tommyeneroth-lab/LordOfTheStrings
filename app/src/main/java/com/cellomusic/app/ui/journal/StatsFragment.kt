package com.cellomusic.app.ui.journal

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.cellomusic.app.R
import com.cellomusic.app.domain.achievement.AchievementCatalog
import com.cellomusic.app.export.PracticeReportExporter
import com.cellomusic.app.ui.journal.view.AchievementGridView
import com.cellomusic.app.ui.journal.view.CalendarHeatmapView
import com.cellomusic.app.ui.journal.view.CategoryBreakdownView
import com.cellomusic.app.ui.journal.view.DailyGoalRingView
import com.cellomusic.app.ui.journal.view.LevelProgressView
import com.cellomusic.app.ui.journal.view.StreakBadgeView
import com.cellomusic.app.ui.journal.view.TempoGraphView
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StatsFragment : Fragment() {

    private val viewModel: StatsViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_stats, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val levelView = view.findViewById<LevelProgressView>(R.id.level_progress)
        val streakBadge = view.findViewById<StreakBadgeView>(R.id.streak_badge)
        val dailyGoalRing = view.findViewById<DailyGoalRingView>(R.id.daily_goal_ring)
        val calendarView = view.findViewById<CalendarHeatmapView>(R.id.calendar_heatmap)
        val tempoGraph = view.findViewById<TempoGraphView>(R.id.tempo_graph)
        val spinnerPiece = view.findViewById<Spinner>(R.id.spinner_tempo_piece)
        val categoryBreakdown = view.findViewById<CategoryBreakdownView>(R.id.category_breakdown)
        val achievementGrid = view.findViewById<AchievementGridView>(R.id.achievement_grid)
        val tvAchievementsCount = view.findViewById<TextView>(R.id.tv_achievements_count)

        // Summary card views
        val tvWeeklyTime = view.findViewById<TextView>(R.id.tv_weekly_time)
        val tvWeeklyDetail = view.findViewById<TextView>(R.id.tv_weekly_detail)
        val tvWeeklyCompare = view.findViewById<TextView>(R.id.tv_weekly_compare)
        val tvMonthlyTime = view.findViewById<TextView>(R.id.tv_monthly_time)
        val tvMonthlyDetail = view.findViewById<TextView>(R.id.tv_monthly_detail)
        val tvMonthlyCompare = view.findViewById<TextView>(R.id.tv_monthly_compare)

        // Day detail card views
        val dayDetailCard = view.findViewById<LinearLayout>(R.id.day_detail_card)
        val tvDayTitle = view.findViewById<TextView>(R.id.tv_day_title)
        val tvDayDetail = view.findViewById<TextView>(R.id.tv_day_detail)

        // Observe gamification profile — updates both the Level bar and the
        // new streak badge. Pulse the badge whenever the value advances in
        // this collector (i.e. the user landed today's first session).
        var lastStreakSeen = -1
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.gamification.collect { profile ->
                profile?.let {
                    levelView.level = it.currentLevel
                    levelView.totalPoints = it.totalPoints
                    levelView.currentStreakDays = it.currentStreakDays
                    levelView.lifetimeMinutes = it.lifetimeMinutes

                    streakBadge.currentStreak = it.currentStreakDays
                    streakBadge.longestStreak = it.longestStreakDays
                    if (lastStreakSeen >= 0 && it.currentStreakDays > lastStreakSeen) {
                        streakBadge.pulse()
                    }
                    lastStreakSeen = it.currentStreakDays
                }
            }
        }

        // Daily XP goal ring — recomputed every loadData() call (on tab open)
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.dailyGoal.collect { g ->
                dailyGoalRing.todayXp = g.todayXp
                dailyGoalRing.targetXp = g.targetXp
            }
        }

        // Achievements: keep grid in sync with unlocked set
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.unlockedAchievementIds.collect { ids ->
                achievementGrid.setUnlockedIds(ids)
                tvAchievementsCount.text = "${ids.size} / ${AchievementCatalog.ALL.size}"
            }
        }

        achievementGrid.onAchievementTapped = { def, unlocked ->
            val title = if (unlocked) "${def.icon}  ${def.title}" else "🔒  ${def.title}"
            android.app.AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setMessage(def.description)
                .setPositiveButton("OK", null)
                .show()
        }

        // ── Weekly summary ──
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.weeklySummary.collect { summary ->
                summary?.let { s ->
                    tvWeeklyTime.text = formatDuration(s.totalMinutes)
                    tvWeeklyDetail.text = "${s.sessionCount} sessions · avg rating ${String.format("%.1f", s.avgSelfEval)}/5"
                    tvWeeklyCompare.text = buildCompareText(s.totalMinutes, s.prevTotalMinutes, "last week")
                    tvWeeklyCompare.setTextColor(compareColor(s.totalMinutes, s.prevTotalMinutes))
                }
            }
        }

        // ── Monthly summary ──
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.monthlySummary.collect { summary ->
                summary?.let { s ->
                    tvMonthlyTime.text = formatDuration(s.totalMinutes)
                    tvMonthlyDetail.text = "${s.sessionCount} sessions · avg rating ${String.format("%.1f", s.avgSelfEval)}/5"
                    tvMonthlyCompare.text = buildCompareText(s.totalMinutes, s.prevTotalMinutes, "last month")
                    tvMonthlyCompare.setTextColor(compareColor(s.totalMinutes, s.prevTotalMinutes))
                }
            }
        }

        // ── Category breakdown ──
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.categoryData.collect { data ->
                categoryBreakdown.setData(data)
            }
        }

        // Observe calendar data
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.calendarData.collect { data ->
                calendarView.setData(data)
            }
        }

        // ── Tappable heatmap: show day details ──
        calendarView.onDayTapped = { dayMs, _ ->
            viewModel.loadDaySessions(dayMs)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.daySessionsInfo.collect { info ->
                if (info != null && info.totalMinutes > 0) {
                    dayDetailCard.visibility = View.VISIBLE
                    val dateStr = SimpleDateFormat("EEEE, MMM d", Locale.ENGLISH).format(Date(info.dayMs))
                    tvDayTitle.text = "$dateStr — ${formatDuration(info.totalMinutes)}"
                    val pieces = if (info.pieces.isNotEmpty()) info.pieces.joinToString(", ") else "Free practice"
                    tvDayDetail.text = "${info.sessionCount} session${if (info.sessionCount != 1) "s" else ""} · $pieces"
                } else if (info != null) {
                    dayDetailCard.visibility = View.VISIBLE
                    val dateStr = SimpleDateFormat("EEEE, MMM d", Locale.ENGLISH).format(Date(info.dayMs))
                    tvDayTitle.text = dateStr
                    tvDayDetail.text = "No practice this day"
                } else {
                    dayDetailCard.visibility = View.GONE
                }
            }
        }

        // Observe tempo data
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.tempoData.collect { points ->
                tempoGraph.setData(points.map {
                    TempoGraphView.TempoPoint(it.timestampMs, it.bpm)
                })
            }
        }

        // Populate piece name spinner
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pieceNames.collect { names ->
                val items = listOf("All pieces") + names
                spinnerPiece.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, items)
            }
        }

        spinnerPiece.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val selected = if (pos == 0) null else spinnerPiece.getItemAtPosition(pos) as String
                viewModel.selectPiece(selected)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // ── Export report button ──
        val btnExport = view.findViewById<Button>(R.id.btn_export_report)
        btnExport.setOnClickListener { showExportDialog() }

        viewModel.loadData()
    }

    /**
     * Lets the user pick a period for the PDF report. We keep this simple —
     * last 30d, last 90d, or all time. Generating + sharing happens on the
     * IO dispatcher inside the exporter; we just catch failures and surface
     * a toast so a missing FileProvider or full disk doesn't crash the tab.
     */
    private fun showExportDialog() {
        val periods = PracticeReportExporter.Period.values()
        val labels = periods.map { it.label }.toTypedArray()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Export Practice Report")
            .setItems(labels) { _, which ->
                generateAndShare(periods[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun generateAndShare(period: PracticeReportExporter.Period) {
        val ctx = requireContext().applicationContext
        Toast.makeText(requireContext(), "Building report…", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val exporter = PracticeReportExporter(ctx)
                val intent = exporter.exportReport(period)
                startActivity(intent)
            } catch (t: Throwable) {
                Toast.makeText(
                    requireContext(),
                    "Export failed: ${t.message ?: "unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun formatDuration(minutes: Int): String {
        return if (minutes >= 60) {
            "${minutes / 60}h ${minutes % 60}m"
        } else {
            "${minutes}m"
        }
    }

    private fun buildCompareText(current: Int, previous: Int, periodLabel: String): String {
        if (previous == 0) {
            return if (current > 0) "\u2191 Great start!" else "No data yet"
        }
        val pct = ((current - previous) * 100) / previous
        return when {
            pct > 0 -> "\u2191 ${pct}% more than $periodLabel"
            pct < 0 -> "\u2193 ${-pct}% less than $periodLabel"
            else -> "\u2194 Same as $periodLabel"
        }
    }

    private fun compareColor(current: Int, previous: Int): Int {
        return when {
            current > previous -> 0xFF4CAF50.toInt()  // green
            current < previous -> 0xFFD4A42A.toInt()   // brass (soft warning, not red)
            else -> 0xFF8A6E2A.toInt()                  // gold dim (neutral)
        }
    }
}

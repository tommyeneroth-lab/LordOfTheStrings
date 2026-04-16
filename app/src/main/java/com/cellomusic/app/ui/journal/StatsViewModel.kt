package com.cellomusic.app.ui.journal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cellomusic.app.data.db.AppDatabase
import com.cellomusic.app.data.db.entity.GamificationEntity
import com.cellomusic.app.data.db.entity.TempoLogEntity
import com.cellomusic.app.data.repository.AchievementRepository
import com.cellomusic.app.data.repository.GamificationRepository
import com.cellomusic.app.data.repository.JournalRepository
import com.cellomusic.app.data.repository.TempoRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar

class StatsViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val gamificationRepo = GamificationRepository(db.gamificationDao())
    private val journalRepo = JournalRepository(db.practiceSessionDao())
    private val tempoRepo = TempoRepository(db.tempoLogDao())
    private val achievementRepo = AchievementRepository(
        db.achievementDao(),
        db.practiceSessionDao(),
        db.gamificationDao()
    )

    val gamification: StateFlow<GamificationEntity?> =
        gamificationRepo.getProfile()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** IDs of all currently-unlocked achievements (reactive). */
    val unlockedAchievementIds: StateFlow<Set<String>> =
        achievementRepo.observeUnlocked()
            .map { list -> list.map { it.id }.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _calendarData = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val calendarData: StateFlow<Map<Long, Int>> = _calendarData

    private val _tempoData = MutableStateFlow<List<TempoLogEntity>>(emptyList())
    val tempoData: StateFlow<List<TempoLogEntity>> = _tempoData

    private val _pieceNames = MutableStateFlow<List<String>>(emptyList())
    val pieceNames: StateFlow<List<String>> = _pieceNames

    private var selectedPiece: String? = null

    // ── Weekly / Monthly summary ────────────────────────────────
    data class PeriodSummary(
        val totalMinutes: Int,
        val sessionCount: Int,
        val avgSelfEval: Float,
        val prevTotalMinutes: Int   // for comparison
    )

    private val _weeklySummary = MutableStateFlow<PeriodSummary?>(null)
    val weeklySummary: StateFlow<PeriodSummary?> = _weeklySummary

    private val _monthlySummary = MutableStateFlow<PeriodSummary?>(null)
    val monthlySummary: StateFlow<PeriodSummary?> = _monthlySummary

    // ── Category breakdown (this month) ─────────────────────────
    private val _categoryData = MutableStateFlow<Map<String, Int>>(emptyMap())
    val categoryData: StateFlow<Map<String, Int>> = _categoryData

    // ── Daily XP goal ring ──────────────────────────────────────
    /**
     * XP today + auto-computed target (in XP). Target is 10× the rolling
     * 7-day average minutes, rounded to the nearest 50 — gives a ring that
     * asks for "a bit more than your usual day" without being punishing.
     * Set to 0 when there isn't enough history (< 2 days of practice in
     * the last week) so the ring renders a friendly placeholder instead
     * of a random target based on one lucky session.
     */
    data class DailyGoal(val todayXp: Int, val targetXp: Int)
    private val _dailyGoal = MutableStateFlow(DailyGoal(0, 0))
    val dailyGoal: StateFlow<DailyGoal> = _dailyGoal

    // ── Sessions for a tapped heatmap day ───────────────────────
    private val _daySessionsInfo = MutableStateFlow<DayInfo?>(null)
    val daySessionsInfo: StateFlow<DayInfo?> = _daySessionsInfo

    data class DayInfo(
        val dayMs: Long,
        val totalMinutes: Int,
        val sessionCount: Int,
        val pieces: List<String>
    )

    fun loadData() {
        viewModelScope.launch {
            val now = Calendar.getInstance()
            val endMs = now.timeInMillis

            // Calendar: last 3 months of daily totals, bucketed by LOCAL day.
            //
            // We can't use the SQL `timestampMs / 86400000` trick here: that
            // buckets by UTC day, but CalendarHeatmapView draws cells at
            // local-midnight (Calendar.getInstance()). For anyone east or
            // west of UTC the two keysets miss each other and the heatmap
            // stays blank no matter how much they've practiced. Instead we
            // pull raw sessions for the range and bucket by local-day here,
            // using the exact same Calendar rounding the view uses.
            val calStart = Calendar.getInstance().apply { add(Calendar.MONTH, -3) }
            val sessions = journalRepo.getSessionsInRange(calStart.timeInMillis, endMs).first()
            val map = mutableMapOf<Long, Int>()
            val bucketCal = Calendar.getInstance()
            for (s in sessions) {
                bucketCal.timeInMillis = s.timestampMs
                bucketCal.set(Calendar.HOUR_OF_DAY, 0)
                bucketCal.set(Calendar.MINUTE, 0)
                bucketCal.set(Calendar.SECOND, 0)
                bucketCal.set(Calendar.MILLISECOND, 0)
                val dayMs = bucketCal.timeInMillis
                map[dayMs] = (map[dayMs] ?: 0) + s.durationMin
            }
            _calendarData.value = map

            // ── Daily goal ring ──
            // Today's minutes (local day) come straight out of the bucketed
            // map we just built. Target = 10 × rolling 7-day average minutes,
            // rounded to nearest 50 XP. We need at least 2 days of practice
            // history in the last 7 to set a meaningful target — otherwise
            // a single heroic session would set a target nobody can hit.
            val todayCal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val todayMs = todayCal.timeInMillis
            val todayMinutes = map[todayMs] ?: 0
            val todayXp = todayMinutes * 10

            val sevenAgo = Calendar.getInstance().apply {
                timeInMillis = todayMs
                add(Calendar.DAY_OF_YEAR, -7)
            }
            // Count days in the last 7 that had any practice, and their total
            // minutes. Average is taken over 7 (not practicedDays) so rest
            // days drag the target down — matches how the user actually
            // experiences their week.
            var last7TotalMinutes = 0
            var last7PracticedDays = 0
            val scanCal = Calendar.getInstance().apply { timeInMillis = sevenAgo.timeInMillis }
            for (i in 0 until 7) {
                val dayMs = scanCal.timeInMillis
                val mins = map[dayMs] ?: 0
                if (mins > 0) {
                    last7TotalMinutes += mins
                    last7PracticedDays++
                }
                scanCal.add(Calendar.DAY_OF_YEAR, 1)
            }
            val targetXp = if (last7PracticedDays >= 2) {
                val avgMinutes = last7TotalMinutes / 7f
                val rawXp = (avgMinutes * 10f).toInt()
                // Round up to nearest 50, and never below 100 so the target
                // is psychologically a "goal" rather than a trivial bar.
                ((rawXp + 49) / 50 * 50).coerceAtLeast(100)
            } else {
                0 // placeholder state in the view
            }
            _dailyGoal.value = DailyGoal(todayXp = todayXp, targetXp = targetXp)

            // ── Weekly summary ──
            val weekStart = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val prevWeekStart = Calendar.getInstance().apply {
                timeInMillis = weekStart.timeInMillis; add(Calendar.WEEK_OF_YEAR, -1)
            }
            val weekMin = journalRepo.getTotalMinutesInRange(weekStart.timeInMillis, endMs)
            val weekSessions = journalRepo.countSessionsInRange(weekStart.timeInMillis, endMs)
            val weekEval = journalRepo.getAvgSelfEvalInRange(weekStart.timeInMillis, endMs)
            val prevWeekMin = journalRepo.getTotalMinutesInRange(
                prevWeekStart.timeInMillis, weekStart.timeInMillis - 1
            )
            _weeklySummary.value = PeriodSummary(weekMin, weekSessions, weekEval, prevWeekMin)

            // ── Monthly summary ──
            val monthStart = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val prevMonthStart = Calendar.getInstance().apply {
                timeInMillis = monthStart.timeInMillis; add(Calendar.MONTH, -1)
            }
            val monthMin = journalRepo.getTotalMinutesInRange(monthStart.timeInMillis, endMs)
            val monthSessions = journalRepo.countSessionsInRange(monthStart.timeInMillis, endMs)
            val monthEval = journalRepo.getAvgSelfEvalInRange(monthStart.timeInMillis, endMs)
            val prevMonthMin = journalRepo.getTotalMinutesInRange(
                prevMonthStart.timeInMillis, monthStart.timeInMillis - 1
            )
            _monthlySummary.value = PeriodSummary(monthMin, monthSessions, monthEval, prevMonthMin)

            // ── Category breakdown (this month) ──
            val catTotals = journalRepo.getCategoryTotals(monthStart.timeInMillis, endMs)
            _categoryData.value = catTotals.associate { it.category to it.totalMin }

            // Piece names for tempo spinner
            _pieceNames.value = tempoRepo.getDistinctPieceNames()

            // Load all tempo logs
            loadTempoData()
        }
    }

    /** Load session details for a specific day (tapped on heatmap). */
    fun loadDaySessions(dayMs: Long) {
        viewModelScope.launch {
            val dayEnd = dayMs + 86_400_000L - 1
            val count = journalRepo.countSessionsInRange(dayMs, dayEnd)
            val minutes = journalRepo.getTotalMinutesInRange(dayMs, dayEnd)
            // Get piece names for that day
            val sessions = mutableListOf<String>()
            journalRepo.getSessionsInRange(dayMs, dayEnd).first().forEach {
                if (it.pieceName.isNotBlank()) sessions.add(it.pieceName)
            }
            _daySessionsInfo.value = DayInfo(dayMs, minutes, count, sessions.distinct())
        }
    }

    fun selectPiece(piece: String?) {
        selectedPiece = piece
        viewModelScope.launch { loadTempoData() }
    }

    private suspend fun loadTempoData() {
        val piece = selectedPiece
        if (piece != null) {
            tempoRepo.getTempoHistoryByPiece(piece).first().let { _tempoData.value = it }
        } else {
            tempoRepo.getRecentLogs(100).first().let { _tempoData.value = it }
        }
    }
}

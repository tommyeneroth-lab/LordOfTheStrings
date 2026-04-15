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

            // Calendar: last 6 months of daily totals
            val calStart = Calendar.getInstance().apply { add(Calendar.MONTH, -6) }
            val totals = journalRepo.getDailyTotals(calStart.timeInMillis, endMs)
            val map = mutableMapOf<Long, Int>()
            for (t in totals) {
                val dayMs = t.dayKey * 86_400_000L
                map[dayMs] = t.totalMin
            }
            _calendarData.value = map

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

package com.cellomusic.app.ui.journal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cellomusic.app.data.db.AppDatabase
import com.cellomusic.app.data.db.entity.GamificationEntity
import com.cellomusic.app.data.db.entity.TempoLogEntity
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

    val gamification: StateFlow<GamificationEntity?> =
        gamificationRepo.getProfile()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _calendarData = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val calendarData: StateFlow<Map<Long, Int>> = _calendarData

    private val _tempoData = MutableStateFlow<List<TempoLogEntity>>(emptyList())
    val tempoData: StateFlow<List<TempoLogEntity>> = _tempoData

    private val _pieceNames = MutableStateFlow<List<String>>(emptyList())
    val pieceNames: StateFlow<List<String>> = _pieceNames

    private var selectedPiece: String? = null

    fun loadData() {
        viewModelScope.launch {
            // Calendar: last 6 months of daily totals
            val cal = Calendar.getInstance()
            val endMs = cal.timeInMillis
            cal.add(Calendar.MONTH, -6)
            val startMs = cal.timeInMillis

            val totals = journalRepo.getDailyTotals(startMs, endMs)
            val map = mutableMapOf<Long, Int>()
            for (t in totals) {
                // dayKey is days-since-epoch, convert to millis at midnight
                val dayMs = t.dayKey * 86_400_000L
                map[dayMs] = t.totalMin
            }
            _calendarData.value = map

            // Piece names for tempo spinner
            _pieceNames.value = tempoRepo.getDistinctPieceNames()

            // Load all tempo logs
            loadTempoData()
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

package com.cellomusic.app.ui.journal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cellomusic.app.audio.RecordingManager
import com.cellomusic.app.data.db.AppDatabase
import com.cellomusic.app.data.db.entity.PracticeSessionEntity
import com.cellomusic.app.data.db.entity.GamificationEntity
import com.cellomusic.app.data.repository.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Event for one-shot UI triggers (fireworks, toasts). */
sealed class JournalEvent {
    data class LevelUp(val newLevel: Int) : JournalEvent()
    data class GoalCompleted(val goalIds: List<Long>) : JournalEvent()
    object SessionSaved : JournalEvent()
}

class PracticeJournalViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val journalRepo = JournalRepository(db.practiceSessionDao())
    private val gamificationRepo = GamificationRepository(db.gamificationDao())
    private val goalRepo = GoalRepository(db.practiceGoalDao(), db.practiceSessionDao())
    private val healthRepo = HealthRepository(db.healthLogDao())

    val recentSessions: StateFlow<List<PracticeSessionEntity>> =
        journalRepo.getRecentSessions(14)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val gamification: StateFlow<GamificationEntity?> =
        gamificationRepo.getProfile()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _events = MutableSharedFlow<JournalEvent>(extraBufferCapacity = 5)
    val events: SharedFlow<JournalEvent> = _events

    // Recording state
    private var recorder: RecordingManager? = null
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    init {
        viewModelScope.launch {
            gamificationRepo.ensureProfile()
        }
    }

    fun saveSession(
        pieceName: String,
        notes: String,
        durationMin: Int,
        selfEval: Int,
        challenge: String = "",
        nextTimeNote: String = "",
        category: String = "",
        bpm: Int = 0,
        strainLevel: Int = 0,
        strainArea: String = ""
    ) = viewModelScope.launch {
        // Stop recording if active, get path
        val recordingPath = if (_isRecording.value) stopRecording() else null

        // Save session
        val session = journalRepo.saveSession(
            pieceName = pieceName,
            notes = notes,
            durationMin = durationMin,
            selfEval = selfEval,
            challenge = challenge,
            nextTimeNote = nextTimeNote,
            recordingPath = recordingPath,
            category = category,
            bpm = bpm
        )

        // Log health if strain was reported
        if (strainLevel > 0) {
            healthRepo.logHealth(
                sessionId = session.id,
                strainLevel = strainLevel,
                strainArea = strainArea
            )
        }

        // Award points + check level up
        val leveledUp = gamificationRepo.addSessionPoints(durationMin)
        gamificationRepo.updateStreak()

        // Check goal completion
        val completedGoals = goalRepo.refreshGoalProgress()
        for (goalId in completedGoals) {
            val bonusLevelUp = gamificationRepo.addGoalBonus()
            if (bonusLevelUp) {
                val profile = gamification.value
                _events.emit(JournalEvent.LevelUp(profile?.currentLevel ?: 0))
            }
        }

        if (completedGoals.isNotEmpty()) {
            _events.emit(JournalEvent.GoalCompleted(completedGoals))
        }

        if (leveledUp) {
            val profile = gamification.value
            _events.emit(JournalEvent.LevelUp(profile?.currentLevel ?: 0))
        }

        _events.emit(JournalEvent.SessionSaved)
    }

    // --- Recording ---

    fun startRecording() {
        if (_isRecording.value) return
        val mgr = RecordingManager(getApplication())
        mgr.start("practice_clip")
        recorder = mgr
        _isRecording.value = true
    }

    fun stopRecording(): String? {
        val mgr = recorder ?: return null
        val path = mgr.getOutputFilePath()
        mgr.stop()
        recorder = null
        _isRecording.value = false
        return path
    }

    fun cancelRecording() {
        recorder?.cancel()
        recorder = null
        _isRecording.value = false
    }

    override fun onCleared() {
        super.onCleared()
        cancelRecording()
    }
}

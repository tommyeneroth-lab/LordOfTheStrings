package com.cellomusic.app.ui.journal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cellomusic.app.audio.RecordingManager
import com.cellomusic.app.data.db.AppDatabase
import com.cellomusic.app.data.db.entity.PracticeSessionEntity
import com.cellomusic.app.data.db.entity.GamificationEntity
import com.cellomusic.app.data.repository.*
import com.cellomusic.app.domain.achievement.AchievementCatalog
import com.cellomusic.app.domain.gamification.LevelTitles
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Event for one-shot UI triggers (fireworks, toasts, XP bursts). */
sealed class JournalEvent {
    /**
     * Level-up celebration. [title] is the flavour noun for the new tier
     * (e.g. "Journeyman"), and [isNewTier] is true when the user crossed
     * into a fresh title bracket — which the UI uses to decide between a
     * short "+1" toast and a bigger "new title unlocked" one.
     */
    data class LevelUp(val newLevel: Int, val title: String, val isNewTier: Boolean) : JournalEvent()
    /** Returning after a 3+ day gap — surfaces the comeback bonus. */
    data class ComebackBonus(val daysAway: Long, val bonus: Int) : JournalEvent()
    /** Touched a category not practiced in the last 7 days — surfaces the variety bonus. */
    data class VarietyBonus(val category: String, val bonus: Int) : JournalEvent()
    data class GoalCompleted(val goalIds: List<Long>) : JournalEvent()
    /**
     * Fired on every successful save. Carries [xpEarned] (base + any bonuses
     * awarded on this save) and a human-readable [subText] for the XP burst
     * — e.g. empty string, "Day 7!", "Streak saved!".
     */
    data class SessionSaved(val xpEarned: Int, val subText: String) : JournalEvent()
    /** Streak advanced today; subtext describes how (first session today, milestone, etc.). */
    data class StreakAdvanced(val newStreak: Int, val isMilestone: Boolean) : JournalEvent()
    /** Two-day grace kicked in — surfaces the "streak saver" feature to the user. */
    data class StreakSaved(val newStreak: Int) : JournalEvent()
    /** One or more achievements unlocked on this save. */
    data class AchievementsUnlocked(val defs: List<AchievementCatalog.Def>) : JournalEvent()
}

class PracticeJournalViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val journalRepo = JournalRepository(db.practiceSessionDao())
    private val gamificationRepo = GamificationRepository(db.gamificationDao())
    private val goalRepo = GoalRepository(db.practiceGoalDao(), db.practiceSessionDao())
    private val healthRepo = HealthRepository(db.healthLogDao())
    private val achievementRepo = AchievementRepository(
        db.achievementDao(),
        db.practiceSessionDao(),
        db.gamificationDao()
    )

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

    /**
     * Path of the most-recently completed recording, held until the user
     * either saves the session (we attach it) or starts a new recording
     * (we discard this one and cut a fresh file). This is what made the
     * "record then stop then save" flow drop the audio silently — we used
     * to throw the path away as soon as Stop Rec was pressed.
     */
    private val _pendingRecordingPath = MutableStateFlow<String?>(null)
    val pendingRecordingPath: StateFlow<String?> = _pendingRecordingPath

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
        // If recording is still rolling when the user presses Save, stop
        // it now so the file finalizes and we can attach it. Otherwise use
        // whatever recording was completed earlier and is waiting in the
        // pending slot.
        if (_isRecording.value) stopRecording()
        val recordingPath = _pendingRecordingPath.value

        // Variety bonus eligibility is read BEFORE the session is saved: we
        // want to know "did this category appear in the last 7 days prior
        // to this save?" — if the save happened first, the count would
        // always include today's session (1) and never be zero.
        val varietyEligible = if (category.isNotBlank()) {
            val now = System.currentTimeMillis()
            val sevenDaysAgo = now - 7L * 86_400_000L
            journalRepo.countSessionsInRangeByCategory(sevenDaysAgo, now, category) == 0
        } else {
            false
        }

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

        // Read the pre-save gap for the comeback bonus BEFORE updateStreak()
        // mutates lastPracticeDateMs. 3+ days away → flat 500 XP "welcome back".
        val daysAway = gamificationRepo.getDaysSinceLastPractice()

        // Snapshot the current level so we can detect both the level-up AND
        // whether that level crossed into a new title tier.
        val oldLevel = gamification.value?.currentLevel ?: 0

        // Award base points (10 × minutes) and check level-up.
        val basePoints = durationMin * 10
        val leveledUp = gamificationRepo.addSessionPoints(durationMin)

        // Update streak with structured result so UI can surface milestones
        // and the "streak saved!" grace-day feature.
        val streakResult = gamificationRepo.updateStreak()

        // Comeback bonus — only fires on a genuine return from 3+ days off,
        // not first-ever session (-1) and not same-day re-saves (0–2).
        var comebackBonus = 0
        if (daysAway >= 3) {
            comebackBonus = gamificationRepo.addBonusPoints(500)
            _events.emit(JournalEvent.ComebackBonus(daysAway, comebackBonus))
        }

        // Variety bonus — rewards touching a category the user hasn't
        // practiced in the past 7 days. Flat 200 XP. Keeps the player's
        // weekly mix honest (scales, technique, repertoire) without
        // punishing anyone; if you always pick the same thing, the bonus
        // just never fires.
        var varietyBonus = 0
        if (varietyEligible) {
            varietyBonus = gamificationRepo.addBonusPoints(200)
            _events.emit(JournalEvent.VarietyBonus(category, varietyBonus))
        }

        // Emit streak-specific UI events and award milestone bonuses.
        var streakBonus = 0
        var xpSubText = ""
        when (streakResult) {
            is StreakResult.Continued -> {
                _events.emit(JournalEvent.StreakAdvanced(streakResult.newStreak, streakResult.isMilestone))
                if (streakResult.isMilestone) {
                    streakBonus = gamificationRepo.addStreakMilestoneBonus(streakResult.newStreak)
                    xpSubText = "Day ${streakResult.newStreak}!"
                }
            }
            is StreakResult.Saved -> {
                _events.emit(JournalEvent.StreakSaved(streakResult.newStreak))
                xpSubText = "Streak saved!"
                if (streakResult.isMilestone) {
                    streakBonus = gamificationRepo.addStreakMilestoneBonus(streakResult.newStreak)
                }
            }
            is StreakResult.Started -> {
                _events.emit(JournalEvent.StreakAdvanced(streakResult.newStreak, false))
            }
            is StreakResult.SameDay -> { /* nothing special — already counted */ }
            is StreakResult.Reset   -> { /* nothing special */ }
        }

        // Check goal completion (may award bonus + level up again).
        val completedGoals = goalRepo.refreshGoalProgress()
        for (goalId in completedGoals) {
            val bonusLevelUp = gamificationRepo.addGoalBonus()
            if (bonusLevelUp) {
                val newLevel = gamification.value?.currentLevel ?: 0
                _events.emit(JournalEvent.LevelUp(
                    newLevel = newLevel,
                    title = LevelTitles.titleFor(newLevel),
                    isNewTier = LevelTitles.isNewTier(oldLevel, newLevel)
                ))
            }
        }
        if (completedGoals.isNotEmpty()) {
            _events.emit(JournalEvent.GoalCompleted(completedGoals))
        }
        if (leveledUp) {
            val newLevel = gamification.value?.currentLevel ?: 0
            _events.emit(JournalEvent.LevelUp(
                newLevel = newLevel,
                title = LevelTitles.titleFor(newLevel),
                isNewTier = LevelTitles.isNewTier(oldLevel, newLevel)
            ))
        }

        // Evaluate achievements AFTER all stats have been updated for the
        // new session, so streak-based and count-based achievements see the
        // post-save state.
        val unlocked = try {
            achievementRepo.evaluateAfterSession(session.timestampMs)
        } catch (_: Throwable) {
            emptyList()
        }
        if (unlocked.isNotEmpty()) {
            _events.emit(JournalEvent.AchievementsUnlocked(unlocked))
        }

        // Pending recording has now been attached — clear it so the next
        // session starts with a clean slate.
        _pendingRecordingPath.value = null

        // Finally, emit the SessionSaved event for the XP burst.
        // Comeback bonus rolls into the total (and the XP burst sub-text says
        // so) when nothing more specific — streak milestone or streak saver —
        // has claimed the line already.
        if (comebackBonus > 0 && xpSubText.isEmpty()) {
            xpSubText = "Welcome back!"
        }
        if (varietyBonus > 0 && xpSubText.isEmpty()) {
            xpSubText = "Fresh focus!"
        }
        val totalXp = basePoints + streakBonus + comebackBonus + varietyBonus
        _events.emit(JournalEvent.SessionSaved(xpEarned = totalXp, subText = xpSubText))
    }

    /** Clear the recordingPath on a session (file missing, or user tapped 🗑). */
    fun clearRecordingPath(session: PracticeSessionEntity) = viewModelScope.launch {
        journalRepo.clearRecordingPath(session)
    }

    /** Delete the recording file on disk and clear the DB pointer. */
    fun deleteRecording(session: PracticeSessionEntity) = viewModelScope.launch {
        val path = session.recordingPath
        if (path != null) {
            try { java.io.File(path).delete() } catch (_: Throwable) {}
        }
        journalRepo.clearRecordingPath(session)
    }

    // --- Recording ---

    fun startRecording() {
        if (_isRecording.value) return
        // Starting a new take supersedes anything the user recorded but
        // hadn't saved yet — delete that file so we don't accumulate
        // orphaned .m4a's on disk.
        _pendingRecordingPath.value?.let { old ->
            try { java.io.File(old).delete() } catch (_: Throwable) {}
        }
        _pendingRecordingPath.value = null
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
        // Hold the finished file so saveSession() can attach it.
        _pendingRecordingPath.value = path
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

package com.cellomusic.app.ui.scale

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cellomusic.app.audio.metronome.MetronomeEngine
import com.cellomusic.app.audio.playback.ScorePlayer
import com.cellomusic.app.data.db.AppDatabase
import com.cellomusic.app.data.repository.AchievementRepository
import com.cellomusic.app.data.repository.GamificationRepository
import com.cellomusic.app.data.repository.JournalRepository
import com.cellomusic.app.data.repository.StreakResult
import com.cellomusic.app.domain.achievement.AchievementCatalog
import com.cellomusic.app.domain.gamification.LevelTitles
import com.cellomusic.app.domain.model.Score
import com.cellomusic.app.domain.scale.ScaleDef
import com.cellomusic.app.domain.scale.ScaleLibrary
import com.cellomusic.app.domain.scale.ScaleScoreBuilder
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Drives the scale-trainer screen: holds the selected scale, current BPM,
 * the embedded metronome engine, and the practice timer.
 *
 * On "Save", logs a practice session with category=SCALES so the drill
 * counts toward XP, streak, achievements, and per-category stats — the
 * same pipeline that the Journal uses, mirrored inline here instead of
 * going through PracticeJournalViewModel (we don't want to bind to its
 * state or recording slot).
 */
class ScaleTrainerViewModel(app: Application) : AndroidViewModel(app) {

    /** Events we emit for the fragment to surface as toasts. */
    sealed class TrainerEvent {
        data class Saved(val minutes: Int, val xpEarned: Int) : TrainerEvent()
        data class LevelUp(val newLevel: Int, val title: String) : TrainerEvent()
        data class StreakAdvanced(val newStreak: Int, val isMilestone: Boolean) : TrainerEvent()
        data class StreakSaved(val newStreak: Int) : TrainerEvent()
        data class AchievementsUnlocked(val defs: List<AchievementCatalog.Def>) : TrainerEvent()
    }

    private val db = AppDatabase.getInstance(app)
    private val journalRepo = JournalRepository(db.practiceSessionDao())
    private val gamificationRepo = GamificationRepository(db.gamificationDao())
    private val achievementRepo = AchievementRepository(
        db.achievementDao(), db.practiceSessionDao(), db.gamificationDao()
    )

    // ── Metronome ──
    private val engine = MetronomeEngine()
    val beatState: StateFlow<MetronomeEngine.BeatState> = engine.beatState

    private val _bpm = MutableStateFlow(80)
    val bpm: StateFlow<Int> = _bpm

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    // ── Scale selection ──
    private val _selected = MutableStateFlow<ScaleDef?>(null)
    val selected: StateFlow<ScaleDef?> = _selected

    // ── Score rendering + audio playback ──
    // Score engraving of the currently selected scale (bass clef, 4/4, eighth
    // notes).  The fragment feeds this into ScoreCanvasView so the student
    // reads real notation instead of a text blob.
    private val _currentScore = MutableStateFlow<Score?>(null)
    val currentScore: StateFlow<Score?> = _currentScore

    // ScorePlayer owns a CelloSynthesizer and plays the generated score
    // through Android MIDI / AudioTrack.  We wrap its playback state so the
    // Listen button can toggle text/icon cleanly.
    private val scorePlayer = ScorePlayer(app)
    val isPlayingScore: StateFlow<Boolean> = scorePlayer.playbackState
        .map { it == ScorePlayer.PlaybackState.PLAYING }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ── Session timer ──
    /** Elapsed practice time on the current scale, in milliseconds. */
    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs

    private var sessionStartMs: Long = 0L
    private var runningCumulativeMs: Long = 0L

    private val _events = MutableSharedFlow<TrainerEvent>(extraBufferCapacity = 5)
    val events: SharedFlow<TrainerEvent> = _events

    init {
        // Start with something friendly: the first beginner major we have
        selectScale(ScaleLibrary.ALL.first())
        viewModelScope.launch { gamificationRepo.ensureProfile() }

        // Tick the UI elapsed time roughly every 200ms while running.
        // We deliberately poll rather than derive from beatState because the
        // timer should keep ticking during bar counts with no click events.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(200)
                if (_isRunning.value) {
                    val now = System.currentTimeMillis()
                    _elapsedMs.value = runningCumulativeMs + (now - sessionStartMs)
                }
            }
        }
    }

    fun selectScale(def: ScaleDef) {
        _selected.value = def
        _bpm.value = def.suggestedBpm
        engine.setBpm(def.suggestedBpm)
        // Switching scale invalidates any in-flight playback of the previous
        // score — stop it before handing the player a fresh score.
        scorePlayer.stop()
        _currentScore.value = ScaleScoreBuilder.buildScore(def)
    }

    fun setBpm(newBpm: Int) {
        val clamped = newBpm.coerceIn(20, 300)
        _bpm.value = clamped
        engine.setBpm(clamped)
        // Generated scores bake in a 120 BPM TempoMark; multiplier scales
        // relative to that so the audio matches the user's metronome speed.
        scorePlayer.setTempoMultiplier(clamped / 120f)
    }

    /**
     * Toggle audio playback of the engraved scale. Uses the ScorePlayer's
     * synthesizer — independent of the metronome engine, so a student can
     * listen to the scale with or without clicks going at the same time.
     */
    fun toggleListen() {
        if (scorePlayer.playbackState.value == ScorePlayer.PlaybackState.PLAYING) {
            scorePlayer.stop()
        } else {
            val score = _currentScore.value ?: return
            scorePlayer.setTempoMultiplier(_bpm.value / 120f)
            if (scorePlayer.loadScore(score)) {
                scorePlayer.play()
            }
        }
    }

    fun toggleMetronome() {
        if (_isRunning.value) pauseSession() else startSession()
    }

    /** Start or resume the drill — metronome clicks + timer advance. */
    private fun startSession() {
        engine.setTimeSignature(4, 4)
        engine.setBpm(_bpm.value)
        engine.start()
        sessionStartMs = System.currentTimeMillis()
        _isRunning.value = true
    }

    /** Pause — stop the clicks but keep the accumulated time. */
    private fun pauseSession() {
        engine.stop()
        val now = System.currentTimeMillis()
        runningCumulativeMs += (now - sessionStartMs)
        _elapsedMs.value = runningCumulativeMs
        _isRunning.value = false
    }

    /** Reset the session timer to zero without leaving the screen. */
    fun resetTimer() {
        if (_isRunning.value) pauseSession()
        runningCumulativeMs = 0L
        _elapsedMs.value = 0L
    }

    /**
     * Log what's been practiced so far as a full journal session. Returns
     * the number of minutes logged so the fragment can decide whether to
     * toast "nothing to save" vs "saved!".
     */
    fun saveSession() = viewModelScope.launch {
        // Make sure the timer stops so a mid-save "keep running" doesn't add
        // confusing extra seconds after the DB write.
        if (_isRunning.value) pauseSession()
        val def = _selected.value ?: return@launch
        val totalMs = _elapsedMs.value
        // Round up: any started minute counts. 45 seconds of scales still
        // feels like a minute of work to a student.
        val minutes = max(1, ((totalMs + 30_000L) / 60_000L).toInt())

        // Snapshot old level so we can detect a level-up crossing.
        val oldLevel = gamificationRepo.getProfile().first()?.currentLevel ?: 0

        val session = journalRepo.saveSession(
            pieceName = "Scale: ${def.name}",
            notes = "Scale trainer — ${def.type.displayName} in ${def.rootName}",
            durationMin = minutes,
            selfEval = 4,      // default 4/5 for completed drills
            category = "SCALES",
            bpm = _bpm.value
        )

        val basePoints = minutes * 10
        val leveledUp = gamificationRepo.addSessionPoints(minutes)
        val streak = gamificationRepo.updateStreak()

        // Mirror the milestone bonus + UI event flow from the Journal VM,
        // though we skip the comeback + variety bonuses here — they belong
        // to "real" journaling where the user intentionally logs a full
        // session with a piece name, not a quick drill.
        var streakBonus = 0
        when (streak) {
            is StreakResult.Continued -> {
                _events.emit(TrainerEvent.StreakAdvanced(streak.newStreak, streak.isMilestone))
                if (streak.isMilestone) {
                    streakBonus = gamificationRepo.addStreakMilestoneBonus(streak.newStreak)
                }
            }
            is StreakResult.Saved -> {
                _events.emit(TrainerEvent.StreakSaved(streak.newStreak))
                if (streak.isMilestone) {
                    streakBonus = gamificationRepo.addStreakMilestoneBonus(streak.newStreak)
                }
            }
            is StreakResult.Started -> {
                _events.emit(TrainerEvent.StreakAdvanced(streak.newStreak, false))
            }
            is StreakResult.SameDay, is StreakResult.Reset -> { /* no-op */ }
        }

        if (leveledUp) {
            val newLevel = gamificationRepo.getProfile().first()?.currentLevel ?: oldLevel
            _events.emit(TrainerEvent.LevelUp(newLevel, LevelTitles.titleFor(newLevel)))
        }

        val unlocked = try {
            achievementRepo.evaluateAfterSession(session.timestampMs)
        } catch (_: Throwable) { emptyList() }
        if (unlocked.isNotEmpty()) {
            _events.emit(TrainerEvent.AchievementsUnlocked(unlocked))
        }

        _events.emit(TrainerEvent.Saved(minutes, basePoints + streakBonus))

        // Home-screen widget mirrors XP/streak — update it so the user sees
        // their scales drill reflected immediately.
        com.cellomusic.app.widget.PracticeWidgetProvider.requestUpdate(getApplication())

        // Reset the timer for the next drill so consecutive saves don't
        // double-count.
        runningCumulativeMs = 0L
        _elapsedMs.value = 0L
    }

    override fun onCleared() {
        scorePlayer.stop()
        engine.stop()
        super.onCleared()
    }
}

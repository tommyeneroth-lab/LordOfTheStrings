package com.cellomusic.app.ui.viewer

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cellomusic.app.audio.playback.ScorePlayer
import com.cellomusic.app.data.repository.ScoreRepository
import com.cellomusic.app.domain.CelloFingeringAdvisor
import com.cellomusic.app.domain.model.*
import com.cellomusic.app.export.ScoreExporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ScoreViewerViewModel : ViewModel() {

    private val _score = MutableStateFlow<Score?>(null)
    val score: StateFlow<Score?> = _score

    private var player: ScorePlayer? = null
    private var scoreRepository: ScoreRepository? = null
    private val _currentMeasure = MutableStateFlow(1)
    val currentMeasure: StateFlow<Int> = _currentMeasure
    private val _currentNotePosition = MutableStateFlow(Pair(1, 0))
    val currentNotePosition: StateFlow<Pair<Int, Int>> = _currentNotePosition
    private val _playbackState = MutableStateFlow(ScorePlayer.PlaybackState.STOPPED)
    val playbackState: StateFlow<ScorePlayer.PlaybackState> = _playbackState

    fun loadScore(context: Context, scoreId: Long) {
        val repository = ScoreRepository(context)
        scoreRepository = repository
        val scorePlayer = ScorePlayer(context)
        player = scorePlayer

        // Observe player state
        viewModelScope.launch {
            scorePlayer.currentMeasure.collect { _currentMeasure.value = it }
        }
        viewModelScope.launch {
            scorePlayer.playbackState.collect { _playbackState.value = it }
        }
        viewModelScope.launch {
            scorePlayer.currentNotePosition.collect { _currentNotePosition.value = it }
        }

        viewModelScope.launch {
            // Find entity by id from the flow's first emission
            var entity = repository.allScores.let { flow ->
                var found = null as com.cellomusic.app.data.db.entity.ScoreEntity?
                // Collect first value then stop
                var job: kotlinx.coroutines.Job? = null
                job = launch {
                    flow.collect { list ->
                        found = list.firstOrNull { it.id == scoreId }
                        if (found != null) job?.cancel()
                    }
                }
                job.join()
                found
            } ?: return@launch

            val score = repository.loadScore(entity) ?: return@launch
            _score.value = score
            scorePlayer.loadScore(score)
            repository.updateLastOpened(entity.id, _currentMeasure.value)
        }
    }

    // ── Fingering ─────────────────────────────────────────────────────────────
    private val _fingeringsVisible = MutableStateFlow(false)
    val fingeringsVisible: StateFlow<Boolean> = _fingeringsVisible

    /**
     * Toggle fingering hints on/off. On first enable the advisor computes
     * fingerings for all notes; subsequent toggles just show/hide them.
     */
    fun toggleFingerings() {
        val score = _score.value ?: return
        if (!_fingeringsVisible.value) {
            // Compute fingerings if none are present yet
            val hasFingerings = score.parts.any { p ->
                p.measures.any { m -> m.elements.any { e -> e is Note && e.fingering != null } }
            }
            val fingeredScore = if (hasFingerings) score else CelloFingeringAdvisor.suggest(score)
            _score.value = fingeredScore
            player?.loadScore(fingeredScore)
            _fingeringsVisible.value = true
        } else {
            _fingeringsVisible.value = false
        }
    }

    // ── Transpose ─────────────────────────────────────────────────────────────
    private val _transposeSteps = MutableStateFlow(0)
    val transposeSteps: StateFlow<Int> = _transposeSteps

    private val _exportIntent = MutableStateFlow<Intent?>(null)
    val exportIntent: StateFlow<Intent?> = _exportIntent

    /** Whether the currently selected element is a Note, Rest, or nothing. */
    enum class SelectedElementType { NOTE, REST }

    private val _selectedNotePos = MutableStateFlow<Pair<Int, Int>?>(null)
    val selectedNotePos: StateFlow<Pair<Int, Int>?> = _selectedNotePos

    private val _selectedElementType = MutableStateFlow<SelectedElementType?>(null)
    val selectedElementType: StateFlow<SelectedElementType?> = _selectedElementType

    fun selectNote(measureNum: Int, noteIdx: Int) {
        val cur = _selectedNotePos.value
        if (cur?.first == measureNum && cur.second == noteIdx) {
            // Tap same element again → deselect
            _selectedNotePos.value = null
            _selectedElementType.value = null
        } else {
            _selectedNotePos.value = Pair(measureNum, noteIdx)
            val elem = _score.value?.parts?.firstOrNull()
                ?.measures?.firstOrNull { it.number == measureNum }
                ?.elements?.getOrNull(noteIdx)
            _selectedElementType.value = when (elem) {
                is Note -> SelectedElementType.NOTE
                is Rest -> SelectedElementType.REST
                else    -> null
            }
        }
    }

    /** Applies [transform] only when the selected element is a Note. */
    private fun modifySelectedNote(transform: (Note) -> Note) {
        val (measureNum, noteIdx) = _selectedNotePos.value ?: return
        val score = _score.value ?: return
        val newParts = score.parts.map { part ->
            part.copy(measures = part.measures.map { measure ->
                if (measure.number == measureNum) {
                    val elements = measure.elements.toMutableList()
                    val elem = elements.getOrNull(noteIdx)
                    if (elem is Note) elements[noteIdx] = transform(elem)
                    measure.copy(elements = elements)
                } else measure
            })
        }
        _score.value = score.copy(parts = newParts)
        _score.value?.let { player?.loadScore(it) }
    }

    /**
     * Changes the duration of the selected element (works for both Note and Rest).
     * [nextType] maps the current DurationType to the desired one, or null to leave unchanged.
     */
    private fun changeDuration(nextType: (DurationType) -> DurationType?) {
        val (measureNum, noteIdx) = _selectedNotePos.value ?: return
        val score = _score.value ?: return
        val newParts = score.parts.map { part ->
            part.copy(measures = part.measures.map { measure ->
                if (measure.number != measureNum) return@map measure
                val elements = measure.elements.toMutableList()
                when (val elem = elements.getOrNull(noteIdx)) {
                    is Note -> {
                        val nt = nextType(elem.duration.type) ?: return@map measure
                        elements[noteIdx] = elem.copy(duration = NoteDuration(nt))
                    }
                    is Rest -> {
                        val nt = nextType(elem.duration.type) ?: return@map measure
                        elements[noteIdx] = elem.copy(duration = NoteDuration(nt))
                    }
                    else -> return@map measure
                }
                measure.copy(elements = elements)
            })
        }
        _score.value = score.copy(parts = newParts)
        _score.value?.let { player?.loadScore(it) }
    }

    fun pitchUp() = modifySelectedNote { note ->
        val midi = (note.pitch.toMidiNote() + 1).coerceIn(24, 96)
        note.copy(pitch = midiToPitch(midi))
    }

    fun pitchDown() = modifySelectedNote { note ->
        val midi = (note.pitch.toMidiNote() - 1).coerceIn(24, 96)
        note.copy(pitch = midiToPitch(midi))
    }

    fun durationShorter() = changeDuration { type ->
        when (type) {
            DurationType.WHOLE         -> DurationType.HALF
            DurationType.HALF          -> DurationType.QUARTER
            DurationType.QUARTER       -> DurationType.EIGHTH
            DurationType.EIGHTH        -> DurationType.SIXTEENTH
            DurationType.SIXTEENTH     -> DurationType.THIRTY_SECOND
            else                       -> null
        }
    }

    fun durationLonger() = changeDuration { type ->
        when (type) {
            DurationType.THIRTY_SECOND -> DurationType.SIXTEENTH
            DurationType.SIXTEENTH     -> DurationType.EIGHTH
            DurationType.EIGHTH        -> DurationType.QUARTER
            DurationType.QUARTER       -> DurationType.HALF
            DurationType.HALF          -> DurationType.WHOLE
            else                       -> null
        }
    }

    /** Deletes the selected element (Note or Rest) from its measure. */
    fun deleteElement() {
        val (measureNum, noteIdx) = _selectedNotePos.value ?: return
        val score = _score.value ?: return
        val newParts = score.parts.map { part ->
            part.copy(measures = part.measures.map { measure ->
                if (measure.number == measureNum) {
                    val elements = measure.elements.toMutableList()
                    if (noteIdx in elements.indices) elements.removeAt(noteIdx)
                    measure.copy(elements = elements)
                } else measure
            })
        }
        _score.value = score.copy(parts = newParts)
        _selectedNotePos.value = null
        _selectedElementType.value = null
        _score.value?.let { player?.loadScore(it) }
    }

    // Keep old name as alias so nothing else breaks
    fun deleteNote() = deleteElement()

    fun saveScore(context: Context) = viewModelScope.launch {
        val s = _score.value ?: return@launch
        (scoreRepository ?: ScoreRepository(context)).saveScore(s)
    }

    private fun midiToPitch(midi: Int): Pitch {
        val octave = midi / 12 - 1
        val pc     = midi % 12
        return when (pc) {
            0  -> Pitch(PitchStep.C, octave)
            1  -> Pitch(PitchStep.C, octave, Alter.SHARP)
            2  -> Pitch(PitchStep.D, octave)
            3  -> Pitch(PitchStep.D, octave, Alter.SHARP)
            4  -> Pitch(PitchStep.E, octave)
            5  -> Pitch(PitchStep.F, octave)
            6  -> Pitch(PitchStep.F, octave, Alter.SHARP)
            7  -> Pitch(PitchStep.G, octave)
            8  -> Pitch(PitchStep.G, octave, Alter.SHARP)
            9  -> Pitch(PitchStep.A, octave)
            10 -> Pitch(PitchStep.A, octave, Alter.SHARP)
            else -> Pitch(PitchStep.B, octave)
        }
    }

    fun play() = player?.play()
    fun pause() = player?.pause()
    fun stop() = player?.stop()
    fun seekToMeasure(measure: Int) = player?.seekToMeasure(measure)
    fun setTempoMultiplier(multiplier: Float) = player?.setTempoMultiplier(multiplier)
    fun setVolume(volume: Float) = player?.setVolume(volume)
    fun seekToNote(measureNumber: Int, noteIndex: Int) = player?.seekToNote(measureNumber, noteIndex)
    fun setTranspose(steps: Int) {
        _transposeSteps.value = steps
        player?.setTranspose(steps)
    }

    fun exportScore(context: Context, format: String) = viewModelScope.launch {
        val score = _score.value ?: return@launch
        val exporter = ScoreExporter(context)
        try {
            val intent = when (format) {
                "midi"     -> exporter.exportMidi(score, _transposeSteps.value)
                "musicxml" -> exporter.exportMusicXml(score)
                "pdf"      -> exporter.exportPdf(score)
                else       -> return@launch
            }
            _exportIntent.value = intent
        } catch (_: Exception) {
            _exportIntent.value = null
        }
    }

    fun clearExportIntent() { _exportIntent.value = null }

    override fun onCleared() {
        super.onCleared()
        player?.stop()
    }
}

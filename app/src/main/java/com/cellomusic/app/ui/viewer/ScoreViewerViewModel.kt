package com.cellomusic.app.ui.viewer

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cellomusic.app.BuildConfig
import com.cellomusic.app.audio.playback.MidiScoreEncoder
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
            scorePlayer.loopPassCount.collect { _loopPassCount.value = it }
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

    /**
     * Changes the clef for the measure containing the selected element.
     * Converts note pitches so that every note stays on the SAME staff line/space
     * it was on before — i.e. the visual position is preserved and only the
     * sounding pitch changes.  This lets the user correct a wrong OMR clef.
     */
    fun changeClef(clefType: ClefType) {
        val (measureNum, _) = _selectedNotePos.value ?: return
        val score = _score.value ?: return
        val firstPart = score.parts.firstOrNull() ?: return
        val measures = firstPart.measures

        // Determine the effective (old) clef for this measure before the change
        var oldClefType = ClefType.BASS
        for (m in measures) {
            if (m.number >= measureNum) break
            if (m.clef != null) oldClefType = m.clef.type
        }
        val existingClef = measures.firstOrNull { it.number == measureNum }?.clef
        if (existingClef != null) oldClefType = existingClef.type

        if (oldClefType == clefType) return

        // Find the last affected measure (until the next explicit clef change)
        var affectedEnd = Int.MAX_VALUE
        var seenTarget = false
        for (m in measures) {
            if (m.number == measureNum) { seenTarget = true; continue }
            if (seenTarget && m.clef != null) { affectedEnd = m.number; break }
        }

        val newParts = score.parts.map { part ->
            part.copy(measures = part.measures.map { measure ->
                if (measure.number < measureNum || measure.number >= affectedEnd) return@map measure
                val converted = measure.elements.map { elem ->
                    if (elem is Note) {
                        val pos = pitchToStaffPos(elem.pitch, oldClefType)
                        val newMidi = staffPosToMidi(pos, clefType).coerceIn(24, 96)
                        elem.copy(pitch = midiToPitch(newMidi), fingering = null)
                    } else elem
                }
                measure.copy(
                    elements = converted,
                    clef = if (measure.number == measureNum) Clef(clefType) else measure.clef
                )
            })
        }
        _score.value = score.copy(parts = newParts)
        _score.value?.let { player?.loadScore(it) }
    }

    // ── Clef conversion helpers ───────────────────────────────────────────────

    /** Diatonic step number for a MIDI note (C0 = 0, D0 = 1, …). */
    private fun noteToStep(midi: Int): Int {
        val dia = when (midi % 12) {
            0, 1 -> 0; 2, 3 -> 1; 4 -> 2; 5, 6 -> 3; 7, 8 -> 4; 9, 10 -> 5; else -> 6
        }
        return (midi / 12) * 7 + dia
    }

    /**
     * Staff position of [pitch] in [clef].
     * 0 = top staff line, positive = downward (matches ScoreCanvasView convention).
     */
    private fun pitchToStaffPos(pitch: Pitch, clef: ClefType): Int {
        val step = noteToStep(pitch.toMidiNote())
        return when (clef) {
            ClefType.BASS   -> 33 - step   // A3 (step 33) at position 0
            ClefType.TENOR  -> 41 - step   // C4 (step 35) at position 6  →  35+6=41
            ClefType.TREBLE -> 45 - step   // E4 (step 37) at position 8  →  37+8=45
            else            -> 33 - step
        }
    }

    /** MIDI note that sits at staff [pos] (integer staff position) in [clef]. */
    private fun staffPosToMidi(pos: Int, clef: ClefType): Int {
        val step = when (clef) {
            ClefType.BASS   -> 33 - pos
            ClefType.TENOR  -> 41 - pos
            ClefType.TREBLE -> 45 - pos
            else            -> 33 - pos
        }
        val octave = Math.floorDiv(step, 7)
        val dia    = Math.floorMod(step, 7)
        val chromatic = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        return octave * 12 + chromatic[dia]
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

    // ── Loop/Practice mode state ────────────────────────────────────────────
    private val _loopEnabled = MutableStateFlow(false)
    val loopEnabled: StateFlow<Boolean> = _loopEnabled

    private val _loopStartMeasure = MutableStateFlow(-1)
    val loopStartMeasure: StateFlow<Int> = _loopStartMeasure

    private val _loopEndMeasure = MutableStateFlow(-1)
    val loopEndMeasure: StateFlow<Int> = _loopEndMeasure

    private val _countInEnabled = MutableStateFlow(false)
    val countInEnabled: StateFlow<Boolean> = _countInEnabled

    private val _tempoRampEnabled = MutableStateFlow(false)
    val tempoRampEnabled: StateFlow<Boolean> = _tempoRampEnabled

    private val _tempoRampStep = MutableStateFlow(0.05f)
    val tempoRampStep: StateFlow<Float> = _tempoRampStep

    private val _loopPassCount = MutableStateFlow(0)
    val loopPassCount: StateFlow<Int> = _loopPassCount

    /**
     * Returns the score's base BPM — the first tempo marking we can find on the
     * first part, or MidiScoreEncoder.DEFAULT_BPM (120) if none is set.
     * Used by the tempo slider to convert between BPM and the engine's
     * multiplier representation.
     */
    fun scoreBaseBpm(): Int {
        val s = _score.value ?: return MidiScoreEncoder.DEFAULT_BPM
        return s.parts.firstOrNull()
            ?.measures
            ?.firstNotNullOfOrNull { it.tempo?.bpm }
            ?: MidiScoreEncoder.DEFAULT_BPM
    }

    /**
     * Dumps the current score's measure structure to Downloads/cellomusic_score_debug.txt.
     * No-op in release builds — gated on BuildConfig.DEBUG so it never ships to end users.
     * Kept available for future OMR / playback diagnostics.
     */
    private fun dumpScoreDebug(context: Context) {
        if (!BuildConfig.DEBUG) return
        val s = _score.value ?: return
        val sb = StringBuilder()
        sb.appendLine("=== Score Debug Dump ===")
        sb.appendLine("Title: ${s.title}")
        sb.appendLine("Parts: ${s.parts.size}")
        val part = s.parts.firstOrNull() ?: return
        sb.appendLine("Measures: ${part.measures.size}")
        sb.appendLine()
        sb.appendLine("# | notes | chord | rest | totalTicks | details")
        sb.appendLine("--+-------+-------+------+------------+--------")
        for (m in part.measures) {
            var notes = 0; var chords = 0; var rests = 0; var totalTicks = 0
            val details = StringBuilder()
            for (el in m.elements) {
                when (el) {
                    is Note -> {
                        notes++
                        val t = el.duration.toTicksWithDots(el.dotCount)
                        totalTicks += t
                        details.append("${el.duration.type.name.take(3)}${if (el.dotCount > 0) ".".repeat(el.dotCount) else ""}(${t}) ")
                    }
                    is ChordNote -> {
                        chords++
                        val t = el.duration.toTicksWithDots(el.dotCount)
                        totalTicks += t
                        details.append("Ch${el.duration.type.name.take(3)}(${t}) ")
                    }
                    is Rest -> {
                        rests++
                        val t = el.duration.toTicksWithDots(el.dotCount)
                        totalTicks += t
                        details.append("R${el.duration.type.name.take(3)}(${t}) ")
                    }
                    else -> {}
                }
            }
            sb.appendLine("${m.number.toString().padEnd(2)}| ${notes.toString().padEnd(5)} | ${chords.toString().padEnd(5)} | ${rests.toString().padEnd(4)} | ${totalTicks.toString().padEnd(10)} | ${details.toString().trim()}")
        }
        val text = sb.toString()
        Log.i("CelloDebug", text)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "cellomusic_score_debug.txt")
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    context.contentResolver.openOutputStream(it)?.use { os -> os.write(text.toByteArray()) }
                }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                java.io.File(dir, "cellomusic_score_debug.txt").writeText(text)
            }
        } catch (e: Exception) {
            Log.e("CelloDebug", "Failed to write debug dump", e)
        }
    }

    fun play() = player?.play()
    /** Call this with a Context to dump the current score before playing. */
    fun playWithDebugDump(context: Context) {
        dumpScoreDebug(context)
        player?.play()
    }
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

    /** Set loop start to the currently selected measure, or to a specific measure. */
    fun setLoopStart(measure: Int = -1) {
        val m = if (measure > 0) measure else _selectedNotePos.value?.first ?: _currentMeasure.value
        _loopStartMeasure.value = m
        player?.setLoopRange(m, _loopEndMeasure.value)
    }

    fun setLoopEnd(measure: Int = -1) {
        val m = if (measure > 0) measure else _selectedNotePos.value?.first ?: _currentMeasure.value
        _loopEndMeasure.value = m
        player?.setLoopRange(_loopStartMeasure.value, m)
    }

    fun toggleLoop() {
        val newVal = !_loopEnabled.value
        _loopEnabled.value = newVal
        player?.setLoopEnabled(newVal)
        if (!newVal) {
            _loopStartMeasure.value = -1
            _loopEndMeasure.value = -1
            _loopPassCount.value = 0
            player?.clearLoop()
        }
    }

    fun toggleCountIn() {
        val newVal = !_countInEnabled.value
        _countInEnabled.value = newVal
        player?.setCountInEnabled(newVal)
    }

    fun toggleTempoRamp() {
        val newVal = !_tempoRampEnabled.value
        _tempoRampEnabled.value = newVal
        player?.setTempoRamp(newVal, _tempoRampStep.value)
    }

    fun setTempoRampStepValue(step: Float) {
        _tempoRampStep.value = step
        player?.setTempoRamp(_tempoRampEnabled.value, step)
    }

    fun exportScore(context: Context, format: String) = viewModelScope.launch {
        val score = _score.value ?: return@launch
        val exporter = ScoreExporter(context)
        try {
            val intent = when (format) {
                "midi"     -> exporter.exportMidi(score, _transposeSteps.value)
                "musicxml" -> exporter.exportMusicXml(score)
                "pdf"      -> exporter.exportPdf(score, _fingeringsVisible.value)
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

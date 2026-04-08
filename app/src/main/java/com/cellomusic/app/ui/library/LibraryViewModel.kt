package com.cellomusic.app.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cellomusic.app.audio.RecordingManager
import com.cellomusic.app.data.db.entity.ScoreEntity
import com.cellomusic.app.data.repository.ScoreRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = ScoreRepository(app)

    private val _searchQuery = MutableStateFlow("")
    private val _importStatus = MutableStateFlow<String?>(null)
    val importStatus: StateFlow<String?> = _importStatus

    /** Non-null while an OMR job is running; contains the latest progress message. */
    private val _omrProgress = MutableStateFlow<String?>(null)
    val omrProgress: StateFlow<String?> = _omrProgress

    @OptIn(ExperimentalCoroutinesApi::class)
    val scores: StateFlow<List<ScoreEntity>> = _searchQuery
        .debounce(300L)
        .flatMapLatest { query ->
            if (query.isBlank()) repository.allScores
            else repository.searchScores(query)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun search(query: String) {
        _searchQuery.value = query
    }

    fun importMusicXml(uri: Uri) = viewModelScope.launch {
        _importStatus.value = null
        val result = repository.importMusicXml(uri)
        _importStatus.value = result.fold(
            onSuccess = { "Score imported successfully" },
            onFailure = { "Import failed: ${it.message}" }
        )
    }

    fun importPdf(uri: Uri) = viewModelScope.launch {
        _omrProgress.value = "Starting PDF import…"
        val result = repository.importPdf(uri) { msg -> _omrProgress.value = msg }
        _omrProgress.value = null
        _importStatus.value = result.fold(
            onSuccess = { "PDF imported successfully" },
            onFailure = { "PDF import failed: ${it.message}" }
        )
    }

    fun importPdfViaServer(uri: Uri, serverUrl: String) = viewModelScope.launch {
        _omrProgress.value = "Sending to OMR server…"
        val result = repository.importPdfViaServer(uri, serverUrl) { msg ->
            _omrProgress.value = msg
            android.util.Log.d("OMR", "Progress: $msg")
        }
        _omrProgress.value = null
        _importStatus.value = result.fold(
            onSuccess = { "PDF imported via server" },
            onFailure = { e ->
                android.util.Log.e("OMR", "importPdfViaServer failed", e)
                "PDF import failed: ${e.message}"
            }
        )
    }

    fun importJpeg(uri: Uri) = viewModelScope.launch {
        _omrProgress.value = "Starting image import…"
        val result = repository.importJpeg(uri) { msg -> _omrProgress.value = msg }
        _omrProgress.value = null
        _importStatus.value = result.fold(
            onSuccess = { "Image imported successfully" },
            onFailure = { "Image import failed: ${it.message}" }
        )
    }

    fun importJpegViaServer(uri: Uri, serverUrl: String) = viewModelScope.launch {
        _omrProgress.value = "Sending to OMR server…"
        val result = repository.importJpegViaServer(uri, serverUrl) { msg ->
            _omrProgress.value = msg
            android.util.Log.d("OMR", "Progress: $msg")
        }
        _omrProgress.value = null
        _importStatus.value = result.fold(
            onSuccess = { "Image imported via server" },
            onFailure = { e ->
                android.util.Log.e("OMR", "importJpegViaServer failed", e)
                "Image import failed: ${e.message}"
            }
        )
    }

    fun importMp3(uri: Uri) = viewModelScope.launch {
        _omrProgress.value = "Analysing audio…"
        val result = repository.importMp3(uri) { msg -> _omrProgress.value = msg }
        _omrProgress.value = null
        _importStatus.value = result.fold(
            onSuccess = { "MP3 transcribed successfully" },
            onFailure = { "MP3 import failed: ${it.message}" }
        )
    }

    fun importMidi(uri: Uri) = viewModelScope.launch {
        _importStatus.value = "Processing MIDI..."
        val result = repository.importMidi(uri)
        _importStatus.value = result.fold(
            onSuccess = { "MIDI imported successfully" },
            onFailure = { "MIDI import failed: ${it.message}" }
        )
    }

    // ── Microphone recording → transcription ──────────────────────────────────
    private var recordingManager: RecordingManager? = null

    enum class RecordingState { IDLE, RECORDING }

    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    val recordingState: StateFlow<RecordingState> = _recordingState

    fun startRecording() {
        if (_recordingState.value == RecordingState.RECORDING) return
        val mgr = RecordingManager(getApplication())
        recordingManager = mgr
        mgr.start("Recording")
        _recordingState.value = RecordingState.RECORDING
    }

    /** Stop recording and immediately transcribe the audio into a new score. */
    fun stopRecordingAndTranscribe() = viewModelScope.launch {
        val mgr = recordingManager ?: return@launch
        recordingManager = null
        _recordingState.value = RecordingState.IDLE
        val uri = mgr.stop() ?: run {
            _importStatus.value = "Recording failed — no audio saved"
            return@launch
        }
        _omrProgress.value = "Transcribing recording…"
        val result = repository.importMp3(uri) { msg -> _omrProgress.value = msg }
        _omrProgress.value = null
        _importStatus.value = result.fold(
            onSuccess = { "Recording transcribed successfully" },
            onFailure = { "Transcription failed: ${it.message}" }
        )
    }

    fun cancelRecording() {
        recordingManager?.cancel()
        recordingManager = null
        _recordingState.value = RecordingState.IDLE
    }

    override fun onCleared() {
        super.onCleared()
        recordingManager?.cancel()
    }

    fun deleteScore(entity: ScoreEntity) = viewModelScope.launch {
        repository.deleteScore(entity)
    }

    fun toggleFavorite(entity: ScoreEntity) = viewModelScope.launch {
        repository.setFavorite(entity.id, !entity.isFavorite)
    }

    fun renameScore(entity: ScoreEntity, newTitle: String, newComposer: String?) = viewModelScope.launch {
        repository.renameScore(entity.id, newTitle, newComposer)
    }
}

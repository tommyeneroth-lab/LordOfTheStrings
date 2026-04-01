package com.cellomusic.app.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

    fun importJpeg(uri: Uri) = viewModelScope.launch {
        _omrProgress.value = "Starting image import…"
        val result = repository.importJpeg(uri) { msg -> _omrProgress.value = msg }
        _omrProgress.value = null
        _importStatus.value = result.fold(
            onSuccess = { "Image imported successfully" },
            onFailure = { "Image import failed: ${it.message}" }
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

    fun deleteScore(entity: ScoreEntity) = viewModelScope.launch {
        repository.deleteScore(entity)
    }

    fun toggleFavorite(entity: ScoreEntity) = viewModelScope.launch {
        repository.setFavorite(entity.id, !entity.isFavorite)
    }

    fun renameScore(entity: ScoreEntity, newTitle: String) = viewModelScope.launch {
        repository.renameScore(entity.id, newTitle)
    }
}

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
        _importStatus.value = "Processing PDF..."
        val result = repository.importPdf(uri)
        _importStatus.value = result.fold(
            onSuccess = { "PDF imported (OMR processing in background)" },
            onFailure = { "PDF import failed: ${it.message}" }
        )
    }

    fun importJpeg(uri: Uri) = viewModelScope.launch {
        _importStatus.value = "Processing image..."
        val result = repository.importJpeg(uri)
        _importStatus.value = result.fold(
            onSuccess = { "Image imported (OMR processing in background)" },
            onFailure = { "Image import failed: ${it.message}" }
        )
    }

    fun deleteScore(entity: ScoreEntity) = viewModelScope.launch {
        repository.deleteScore(entity)
    }

    fun toggleFavorite(entity: ScoreEntity) = viewModelScope.launch {
        repository.setFavorite(entity.id, !entity.isFavorite)
    }
}

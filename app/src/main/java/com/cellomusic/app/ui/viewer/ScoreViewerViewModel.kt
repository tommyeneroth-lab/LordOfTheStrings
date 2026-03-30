package com.cellomusic.app.ui.viewer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cellomusic.app.audio.playback.ScorePlayer
import com.cellomusic.app.data.repository.ScoreRepository
import com.cellomusic.app.domain.model.Score
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ScoreViewerViewModel : ViewModel() {

    private val _score = MutableStateFlow<Score?>(null)
    val score: StateFlow<Score?> = _score

    private var player: ScorePlayer? = null
    private val _currentMeasure = MutableStateFlow(1)
    val currentMeasure: StateFlow<Int> = _currentMeasure
    private val _playbackState = MutableStateFlow(ScorePlayer.PlaybackState.STOPPED)
    val playbackState: StateFlow<ScorePlayer.PlaybackState> = _playbackState

    fun loadScore(context: Context, scoreId: Long) {
        val repository = ScoreRepository(context)
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
            // Find entity by id from the flow's first emission
            var entity = repository.allScores.let { flow ->
                var found = null as com.cellomusic.app.data.db.entity.ScoreEntity?
                // Collect first value
                val job = launch {
                    flow.collect { list ->
                        found = list.firstOrNull { it.id == scoreId }
                        if (found != null) this.cancel()
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

    fun play() = player?.play()
    fun pause() = player?.pause()
    fun stop() = player?.stop()
    fun seekToMeasure(measure: Int) = player?.seekToMeasure(measure)
    fun setTempoMultiplier(multiplier: Float) = player?.setTempoMultiplier(multiplier)

    override fun onCleared() {
        super.onCleared()
        player?.stop()
    }
}

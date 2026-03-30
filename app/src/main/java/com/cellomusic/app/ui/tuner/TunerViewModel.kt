package com.cellomusic.app.ui.tuner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cellomusic.app.audio.tuner.PitchResult
import com.cellomusic.app.audio.tuner.TunerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class TunerViewModel : ViewModel() {

    private val engine = TunerEngine()

    private val _pitchResult = MutableStateFlow<PitchResult?>(null)
    val pitchResult: StateFlow<PitchResult?> = _pitchResult

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private var tunerJob: Job? = null

    fun startTuner() {
        if (_isRunning.value) return
        _isRunning.value = true

        tunerJob = viewModelScope.launch(Dispatchers.Default) {
            engine.pitchFlow().collect { result ->
                _pitchResult.value = result
            }
        }
    }

    fun stopTuner() {
        engine.stop()
        tunerJob?.cancel()
        tunerJob = null
        _isRunning.value = false
        _pitchResult.value = null
    }

    override fun onCleared() {
        super.onCleared()
        stopTuner()
    }
}

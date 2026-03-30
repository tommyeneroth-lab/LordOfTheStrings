package com.cellomusic.app.ui.metronome

import androidx.lifecycle.ViewModel
import com.cellomusic.app.audio.metronome.MetronomeEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MetronomeViewModel : ViewModel() {

    private val engine = MetronomeEngine()

    val beatState: StateFlow<MetronomeEngine.BeatState> = engine.beatState

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _bpm = MutableStateFlow(120)
    val bpm: StateFlow<Int> = _bpm

    private val _numerator = MutableStateFlow(4)
    val numerator: StateFlow<Int> = _numerator

    private val _denominator = MutableStateFlow(4)
    val denominator: StateFlow<Int> = _denominator

    fun setBpm(bpm: Int) {
        _bpm.value = bpm.coerceIn(20, 300)
        engine.setBpm(_bpm.value)
    }

    fun setTimeSignature(num: Int, den: Int) {
        _numerator.value = num
        _denominator.value = den
        engine.setTimeSignature(num, den)
    }

    fun start() {
        engine.start()
        _isPlaying.value = true
    }

    fun stop() {
        engine.stop()
        _isPlaying.value = false
    }

    override fun onCleared() {
        super.onCleared()
        engine.stop()
    }
}

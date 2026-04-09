package com.cellomusic.app.ui.metronome

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cellomusic.app.audio.metronome.MetronomeEngine
import com.cellomusic.app.data.db.AppDatabase
import com.cellomusic.app.data.repository.TempoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MetronomeViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = MetronomeEngine()
    private val tempoRepo = TempoRepository(AppDatabase.getInstance(app).tempoLogDao())

    val beatState: StateFlow<MetronomeEngine.BeatState> = engine.beatState

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _bpm = MutableStateFlow(120)
    val bpm: StateFlow<Int> = _bpm

    private val _numerator = MutableStateFlow(4)
    val numerator: StateFlow<Int> = _numerator

    private val _denominator = MutableStateFlow(4)
    val denominator: StateFlow<Int> = _denominator

    /** Piece name context for tempo logging. */
    var currentPieceName: String = ""

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

        // Log the tempo when stopping (captures the BPM they practiced at)
        val bpmVal = _bpm.value
        val piece = currentPieceName.ifEmpty { "Free practice" }
        viewModelScope.launch {
            tempoRepo.logTempo(pieceName = piece, bpm = bpmVal)
        }
    }

    override fun onCleared() {
        super.onCleared()
        engine.stop()
    }
}

package com.cellomusic.app.ui.bow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cellomusic.app.audio.bow.BowAnalyticsEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class BowAnalyticsViewModel : ViewModel() {

    private val engine = BowAnalyticsEngine()

    private val _metrics = MutableStateFlow<BowAnalyticsEngine.BowMetrics?>(null)
    val metrics: StateFlow<BowAnalyticsEngine.BowMetrics?> = _metrics.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    // Session statistics
    private val _bowChanges = MutableStateFlow(0)
    val bowChanges: StateFlow<Int> = _bowChanges.asStateFlow()

    private val _avgToneQuality = MutableStateFlow(0f)
    val avgToneQuality: StateFlow<Float> = _avgToneQuality.asStateFlow()

    private var engineJob: Job? = null
    private var lastPressureSign = 0  // track sign flips for bow change detection
    private var qualitySamples = 0
    private var qualitySum = 0f

    fun start() {
        if (_isRunning.value) return
        _isRunning.value = true
        _bowChanges.value = 0
        _avgToneQuality.value = 0f
        qualitySamples = 0
        qualitySum = 0f
        lastPressureSign = 0

        engineJob = viewModelScope.launch {
            engine.metricsFlow().collect { m ->
                _metrics.value = m

                if (!m.isSilent) {
                    // Track bow changes: sign flips in pressure score indicate direction reversal
                    val sign = when {
                        m.pressureScore > 0.15f -> 1
                        m.pressureScore < -0.15f -> -1
                        else -> 0
                    }
                    if (sign != 0 && sign != lastPressureSign && lastPressureSign != 0) {
                        _bowChanges.value++
                    }
                    if (sign != 0) lastPressureSign = sign

                    // Running average tone quality
                    qualitySum += m.toneQuality
                    qualitySamples++
                    _avgToneQuality.value = qualitySum / qualitySamples
                }
            }
        }
    }

    fun stop() {
        engineJob?.cancel()
        engineJob = null
        _isRunning.value = false
        _metrics.value = null
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}

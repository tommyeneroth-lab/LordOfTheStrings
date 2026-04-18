package com.cellomusic.app.audio.bow

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Captures microphone audio and emits [BowMetrics] at ~15 Hz.
 *
 * Bow pressure is inferred from two spectral features:
 *  - Spectral Centroid (SC): centre-of-mass of the magnitude spectrum.
 *    High SC + moderate amplitude → lots of high-frequency noise → over-bowed (scratchy).
 *  - RMS amplitude: very low RMS → under-bowed (too little bow contact or speed).
 *
 * pressureScore: -1.0 = far too light, 0.0 = ideal, +1.0 = far too heavy.
 * toneQuality:  0.0 = poor, 1.0 = excellent (1 - |pressureScore|).
 */
class BowAnalyticsEngine {

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val FFT_SIZE = 2048
        private const val HOP = 1024
        // Spectral centroid thresholds (normalised 0–1 relative to Nyquist)
        private const val SC_IDEAL_MAX = 0.18f   // below this = clean tone
        private const val SC_HEAVY_THRESH = 0.30f // above this = over-bowed
        // RMS thresholds (normalised 0–1 for 16-bit PCM)
        private const val RMS_SILENT = 0.008f
        private const val RMS_LIGHT = 0.035f
    }

    data class BowMetrics(
        val pressureScore: Float,   // -1..+1; 0 = ideal
        val toneQuality: Float,     // 0..1
        val amplitude: Float,       // 0..1 normalised RMS
        val isSilent: Boolean
    )

    private val fft = FloatFFT_1D(FFT_SIZE.toLong())
    private val hannWindow = FloatArray(FFT_SIZE) { i ->
        (0.5f * (1.0 - Math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1)))).toFloat()
    }

    fun metricsFlow(): Flow<BowMetrics> = flow {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return@flow   // device cannot honour this config
        val bufSize = maxOf(minBuf, HOP * 4)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, bufSize
        )
        // If construction failed (permission missing, hardware unavailable) we
        // must NOT call startRecording — that throws IllegalStateException and
        // used to crash the fragment.  Bail quietly instead; the VM will just
        // keep isRunning=true but emit nothing, and the fragment's Stop still
        // works to clear state.
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return@flow
        }
        record.startRecording()

        val pcm16 = ShortArray(HOP)
        val ringBuf = FloatArray(FFT_SIZE)
        var ringPos = 0

        try {
            while (true) {
                // Cooperatively yield cancellation so stop() ends the loop
                // promptly rather than waiting for another mic read.
                currentCoroutineContext().ensureActive()

                val read = record.read(pcm16, 0, HOP)
                // Negative values are AudioRecord error codes (ERROR,
                // ERROR_BAD_VALUE, ERROR_INVALID_OPERATION, ERROR_DEAD_OBJECT).
                // Spinning on `continue` used to peg the CPU without ever
                // recovering — break out instead so the fragment can restart.
                if (read < 0) break
                if (read == 0) continue

                // Fill ring buffer
                for (i in 0 until read) {
                    ringBuf[ringPos % FFT_SIZE] = pcm16[i] / 32768f
                    ringPos++
                }

                // Build windowed frame (oldest → newest from ring)
                val frame = FloatArray(FFT_SIZE * 2) // interleaved real/imag for JTransforms
                val offset = ringPos % FFT_SIZE
                for (i in 0 until FFT_SIZE) {
                    frame[i] = ringBuf[(offset + i) % FFT_SIZE] * hannWindow[i]
                }

                fft.realForward(frame)

                // RMS from raw PCm16
                var sumSq = 0.0
                for (s in pcm16) sumSq += (s / 32768.0) * (s / 32768.0)
                val rms = sqrt(sumSq / read).toFloat()

                // Spectral centroid from magnitude spectrum
                var weightedSum = 0.0
                var magSum = 0.0
                val halfN = FFT_SIZE / 2
                for (k in 1 until halfN) {
                    val re = frame[k * 2]
                    val im = frame[k * 2 + 1]
                    val mag = sqrt((re * re + im * im).toDouble())
                    weightedSum += k * mag
                    magSum += mag
                }
                val scNorm = if (magSum > 0.0) (weightedSum / magSum / halfN).toFloat() else 0f

                // Classify
                val isSilent = rms < RMS_SILENT
                val pressureScore: Float
                val toneQuality: Float

                when {
                    isSilent -> {
                        pressureScore = 0f
                        toneQuality = 0f
                    }
                    rms < RMS_LIGHT -> {
                        // Under-bowed: not enough contact / speed
                        val depth = 1f - (rms / RMS_LIGHT)
                        pressureScore = -depth.coerceIn(0f, 1f)
                        toneQuality = (1f - depth * 0.8f).coerceIn(0f, 1f)
                    }
                    scNorm > SC_HEAVY_THRESH -> {
                        // Over-bowed: high-frequency noise
                        val depth = ((scNorm - SC_HEAVY_THRESH) / (1f - SC_HEAVY_THRESH)).coerceIn(0f, 1f)
                        pressureScore = depth
                        toneQuality = (1f - depth * 0.9f).coerceIn(0f, 1f)
                    }
                    else -> {
                        // Ideal zone — small residual based on SC distance from centre
                        val centre = (SC_IDEAL_MAX + SC_HEAVY_THRESH) / 2f
                        val dev = (scNorm - centre) / (SC_HEAVY_THRESH - SC_IDEAL_MAX)
                        pressureScore = dev.coerceIn(-0.4f, 0.4f)
                        toneQuality = (1f - abs(pressureScore) * 0.5f).coerceIn(0f, 1f)
                    }
                }

                emit(BowMetrics(pressureScore, toneQuality, rms.coerceIn(0f, 1f), isSilent))
            }
        } finally {
            // Guard against stop()/release() throwing when the recorder is
            // already uninitialised (happens when startRecording failed
            // earlier in the same process).
            try { record.stop() } catch (_: IllegalStateException) {}
            record.release()
        }
    }
        // CRITICAL: move the entire mic-read + FFT loop off Main.  Without
        // flowOn, the flow body runs on the collector's dispatcher, which is
        // viewModelScope's Main.immediate — blocking AudioRecord.read() on
        // Main freezes the UI the moment Start is pressed.
        .flowOn(Dispatchers.IO)
}

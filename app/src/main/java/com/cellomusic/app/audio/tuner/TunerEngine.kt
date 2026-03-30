package com.cellomusic.app.audio.tuner

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.sqrt

/**
 * Captures microphone audio, applies Hann windowing, runs FFT, and feeds the
 * magnitude spectrum into HpsProcessor. Emits PitchResult at ~10 Hz.
 */
class TunerEngine {

    companion object {
        const val SAMPLE_RATE = 44100
        const val FFT_SIZE = 8192
        const val HOP_SIZE = 2048        // 75% overlap
        const val ANALYSIS_RATE_HZ = 10  // updates per second
    }

    private val hpsProcessor = HpsProcessor(SAMPLE_RATE, FFT_SIZE)
    private val fft = FloatFFT_1D(FFT_SIZE.toLong())
    private val hannWindow = FloatArray(FFT_SIZE) { i ->
        (0.5f * (1 - Math.cos(2 * Math.PI * i / (FFT_SIZE - 1)))).toFloat()
    }

    private var audioRecord: AudioRecord? = null
    private var isRunning = false
    private val rollingBuffer = FloatArray(FFT_SIZE)
    private var bufferPos = 0

    private fun createAudioRecord(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, HOP_SIZE * 2 * 2) // *2 for 16-bit samples
        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )
    }

    /**
     * Returns a Flow of PitchResults. Collect on an IO dispatcher.
     * Cancel the flow to stop recording.
     */
    fun pitchFlow(): Flow<PitchResult?> = flow {
        val record = createAudioRecord()
        audioRecord = record
        record.startRecording()
        isRunning = true

        val readBuffer = ShortArray(HOP_SIZE)
        var samplesSinceLastEmit = 0

        try {
            while (isRunning) {
                val read = record.read(readBuffer, 0, HOP_SIZE)
                if (read <= 0) continue

                // Convert shorts to floats and fill rolling buffer
                for (i in 0 until read) {
                    rollingBuffer[bufferPos % FFT_SIZE] = readBuffer[i] / 32768f
                    bufferPos++
                }

                samplesSinceLastEmit += read
                val samplesPerEmit = SAMPLE_RATE / ANALYSIS_RATE_HZ

                if (samplesSinceLastEmit >= samplesPerEmit) {
                    samplesSinceLastEmit = 0
                    emit(analyzeCurrentBuffer())
                }
            }
        } finally {
            record.stop()
            record.release()
            audioRecord = null
        }
    }

    fun stop() {
        isRunning = false
    }

    private fun analyzeCurrentBuffer(): PitchResult? {
        // Copy rolling buffer in order starting from oldest sample
        val windowed = FloatArray(FFT_SIZE * 2) // JTransforms uses 2x for complex
        val startPos = bufferPos % FFT_SIZE
        for (i in 0 until FFT_SIZE) {
            val sample = rollingBuffer[(startPos + i) % FFT_SIZE]
            windowed[i] = sample * hannWindow[i]
        }

        // In-place real FFT (output is packed: [Re(0), Re(N/2), Re(1), Im(1), ...])
        fft.realForward(windowed)

        // Compute magnitude spectrum
        val spectrum = FloatArray(FFT_SIZE / 2 + 1)
        spectrum[0] = Math.abs(windowed[0])
        spectrum[FFT_SIZE / 2] = Math.abs(windowed[1])
        for (i in 1 until FFT_SIZE / 2) {
            val re = windowed[2 * i]
            val im = windowed[2 * i + 1]
            spectrum[i] = sqrt(re * re + im * im)
        }

        return hpsProcessor.process(spectrum)
    }
}

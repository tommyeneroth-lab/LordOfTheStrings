package com.cellomusic.app.audio.metronome

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Sample-accurate metronome using AudioTrack in streaming mode.
 * Timing is based on sample count arithmetic, not wall-clock timers,
 * eliminating drift entirely.
 */
class MetronomeEngine {

    companion object {
        const val SAMPLE_RATE = 44100
        const val CLICK_FREQ_DOWNBEAT = 1200f   // Hz - higher pitched for beat 1
        const val CLICK_FREQ_UPBEAT = 900f      // Hz - lower for other beats
        const val CLICK_DURATION_MS = 15        // ms
        const val CLICK_SAMPLES = (SAMPLE_RATE * CLICK_DURATION_MS / 1000)
    }

    private val _beatState = MutableStateFlow(BeatState(0, false, false))
    val beatState: StateFlow<BeatState> = _beatState

    private var audioTrack: AudioTrack? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private val downbeatPcm = generateClick(CLICK_FREQ_DOWNBEAT)
    private val upbeatPcm = generateClick(CLICK_FREQ_UPBEAT)

    private var bpm: Int = 120
    private var numerator: Int = 4       // beats per measure
    private var denominator: Int = 4
    private var currentBeat: Int = 0
    private var isRunning = false
    private var samplesUntilNextBeat = 0

    fun setBpm(newBpm: Int) {
        bpm = newBpm.coerceIn(20, 300)
        recomputeBeatInterval()
    }

    fun setTimeSignature(num: Int, den: Int) {
        numerator = num
        denominator = den
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        currentBeat = 0
        recomputeBeatInterval()

        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(CLICK_SAMPLES * 4)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
        track.play()

        handlerThread = HandlerThread("metronome-audio").also { it.start() }
        handler = Handler(handlerThread!!.looper)
        handler?.post(audioWriteLoop)
    }

    fun stop() {
        isRunning = false
        handler?.removeCallbacksAndMessages(null)
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        _beatState.value = BeatState(0, false, false)
    }

    private val audioWriteLoop = object : Runnable {
        override fun run() {
            if (!isRunning) return

            val track = audioTrack ?: return
            val samplesPerBeat = getSamplesPerBeat()
            val silenceSamples = (samplesPerBeat - CLICK_SAMPLES).coerceAtLeast(0)

            // Emit beat event
            val isDownbeat = currentBeat % numerator == 0
            val clickPcm = if (isDownbeat) downbeatPcm else upbeatPcm

            _beatState.value = BeatState(
                beatNumber = (currentBeat % numerator) + 1,
                isDownbeat = isDownbeat,
                isActive = true
            )

            // Write click sound
            track.write(clickPcm, 0, clickPcm.size)

            // Write silence until next beat
            if (silenceSamples > 0) {
                val silence = ShortArray(silenceSamples)
                var written = 0
                while (written < silenceSamples && isRunning) {
                    val chunk = silence.size.coerceAtMost(1024)
                    track.write(silence, written, chunk)
                    written += chunk
                }
            }

            currentBeat++

            if (isRunning) {
                handler?.post(this)
            }
        }
    }

    private fun getSamplesPerBeat(): Int {
        // Adjust for time signature denominator relative to quarter note
        val beatValue = 4.0 / denominator
        return ((SAMPLE_RATE * 60.0 / bpm) * beatValue).toInt()
    }

    private fun recomputeBeatInterval() {
        samplesUntilNextBeat = getSamplesPerBeat()
    }

    private fun generateClick(frequency: Float): ShortArray {
        val samples = ShortArray(CLICK_SAMPLES)
        for (i in samples.indices) {
            val t = i.toFloat() / SAMPLE_RATE
            val envelope = exp(-i.toFloat() / (CLICK_SAMPLES / 4f))  // exponential decay
            val wave = sin(2 * PI * frequency * t).toFloat()
            samples[i] = (wave * envelope * Short.MAX_VALUE * 0.85f).toInt().toShort()
        }
        return samples
    }

    data class BeatState(
        val beatNumber: Int,
        val isDownbeat: Boolean,
        val isActive: Boolean
    )
}

package com.cellomusic.app.audio.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.*
import kotlin.math.*

/**
 * Real-time AudioTrack-based cello synthesizer that produces a warm, bowed
 * string sound via additive harmonic synthesis with:
 *  - Cello-specific harmonic profile (12 partials, odd-heavy like a bowed string)
 *  - Slow bow-attack envelope (ADSR with ~80ms attack for legato feel)
 *  - Performance vibrato (delayed onset, ~5.5 Hz, ±12 cents)
 *  - Subtle bow-noise layer (filtered noise burst on attack)
 *  - Per-note amplitude shaping to avoid clicks
 *
 * Designed as a drop-in replacement for the GM MIDI synth in ScorePlayer.
 */
class CelloSynthesizer {

    companion object {
        const val SAMPLE_RATE = 44100
        private const val BUFFER_FRAMES = 2048

        // Cello harmonic weights — measured from real bowed cello spectra.
        // Bowed strings have strong odd harmonics (1, 3, 5) with weaker evens.
        private val HARMONIC_WEIGHTS = floatArrayOf(
            1.00f,  // fundamental
            0.45f,  // 2nd harmonic
            0.55f,  // 3rd (strong — bowed string characteristic)
            0.20f,  // 4th
            0.35f,  // 5th (strong odd)
            0.12f,  // 6th
            0.18f,  // 7th (odd)
            0.07f,  // 8th
            0.10f,  // 9th (odd)
            0.04f,  // 10th
            0.06f,  // 11th (odd)
            0.03f   // 12th
        )

        // Normalize so peak amplitude = 1.0
        private val WEIGHT_SUM = HARMONIC_WEIGHTS.sum()
    }

    private var audioTrack: AudioTrack? = null
    private var synthScope: CoroutineScope? = null
    private var isRunning = false

    // Active voices: midiNote -> VoiceState
    private val voices = mutableMapOf<Int, VoiceState>()
    private val voiceLock = Any()

    private var masterVolume = 1.0f

    private data class VoiceState(
        val frequency: Float,
        val velocity: Float,     // 0..1
        var phase: Double = 0.0,
        var envPhase: EnvPhase = EnvPhase.ATTACK,
        var envLevel: Float = 0f,
        var sampleCount: Long = 0,
        var releaseStart: Long = -1
    )

    private enum class EnvPhase { ATTACK, SUSTAIN, RELEASE, OFF }

    // ADSR envelope parameters (in samples)
    private val attackSamples  = (SAMPLE_RATE * 0.08).toInt()   // 80ms bow attack
    private val releaseSamples = (SAMPLE_RATE * 0.12).toInt()   // 120ms bow lift

    fun start() {
        if (isRunning) return
        isRunning = true

        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(BUFFER_FRAMES * 2)

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

        synthScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        // Pass the track explicitly so renderLoop owns its lifecycle — see
        // stop() for the rationale.
        synthScope?.launch { renderLoop(track) }
    }

    /**
     * Signals the render loop to exit. Does NOT touch the AudioTrack — the
     * loop itself releases it in its `finally` block. The previous version
     * released the track here on the calling thread while the render loop
     * was mid-`audioTrack.write()`, which races at the native layer and
     * crashes with SIGSEGV (no JVM catch block catches that). This crash
     * showed up intermittently when the user changed the tempo slider
     * quickly because each change tears the synth down and rebuilds it.
     *
     * Returns immediately; the render loop will exit within one buffer
     * (~46ms) and clean up the AudioTrack on its own background coroutine.
     */
    fun stop() {
        isRunning = false
        synthScope?.cancel()
        synthScope = null
        // Drop the reference so no further note events target this instance,
        // but let renderLoop() release the underlying AudioTrack.
        audioTrack = null
        synchronized(voiceLock) { voices.clear() }
    }

    fun setVolume(vol: Float) {
        masterVolume = vol.coerceIn(0f, 1f)
    }

    fun noteOn(midiNote: Int, velocity: Int) {
        val freq = 440f * 2f.pow((midiNote - 69f) / 12f)
        val vel = (velocity / 127f).coerceIn(0.1f, 1f)
        synchronized(voiceLock) {
            voices[midiNote] = VoiceState(frequency = freq, velocity = vel)
        }
    }

    fun noteOff(midiNote: Int) {
        synchronized(voiceLock) {
            voices[midiNote]?.let {
                it.envPhase = EnvPhase.RELEASE
                it.releaseStart = it.sampleCount
            }
        }
    }

    fun allNotesOff() {
        synchronized(voiceLock) { voices.clear() }
    }

    /**
     * Render loop owns the AudioTrack's lifecycle. The track reference is
     * captured at start() time and never nulled from outside — this avoids
     * the native write-after-release crash that used to occur when stop()
     * released the track on one thread while this loop was mid-write on
     * another. When isRunning flips false, we exit, stop, and release the
     * track here, on this same thread.
     */
    private fun renderLoop(track: AudioTrack) {
        val buffer = ShortArray(BUFFER_FRAMES)
        try {
            while (isRunning) {
                for (i in buffer.indices) {
                    buffer[i] = renderSample()
                }
                try {
                    track.write(buffer, 0, buffer.size)
                } catch (_: IllegalStateException) {
                    // Track was released or uninitialised — bail out cleanly.
                    break
                }
            }
        } finally {
            try { track.stop() } catch (_: Exception) {}
            try { track.release() } catch (_: Exception) {}
        }
    }

    private fun renderSample(): Short {
        var mix = 0f

        val deadNotes = mutableListOf<Int>()

        synchronized(voiceLock) {
            for ((note, v) in voices) {
                if (v.envPhase == EnvPhase.OFF) {
                    deadNotes.add(note)
                    continue
                }

                // --- Envelope ---
                val env = when (v.envPhase) {
                    EnvPhase.ATTACK -> {
                        val progress = v.sampleCount.toFloat() / attackSamples
                        if (progress >= 1f) {
                            v.envPhase = EnvPhase.SUSTAIN
                            1f
                        } else {
                            // Smooth S-curve attack (like a bow grabbing the string)
                            val t = progress
                            t * t * (3f - 2f * t) // smoothstep
                        }
                    }
                    EnvPhase.SUSTAIN -> 1f
                    EnvPhase.RELEASE -> {
                        val elapsed = (v.sampleCount - v.releaseStart).toFloat()
                        val progress = elapsed / releaseSamples
                        if (progress >= 1f) {
                            v.envPhase = EnvPhase.OFF
                            0f
                        } else {
                            // Exponential decay (natural string damping)
                            (1f - progress) * (1f - progress)
                        }
                    }
                    EnvPhase.OFF -> 0f
                }
                v.envLevel = env

                // --- Vibrato ---
                // Delayed onset: vibrato starts after 150ms (bow settled on string)
                val vibratoDelay = SAMPLE_RATE * 0.15
                val vibratoDepthCents = if (v.sampleCount > vibratoDelay) {
                    // Ramp up vibrato depth over 200ms
                    val rampProgress = ((v.sampleCount - vibratoDelay) / (SAMPLE_RATE * 0.2)).coerceAtMost(1.0)
                    12.0 * rampProgress  // ±12 cents at full depth
                } else 0.0

                val vibratoRate = 5.5 // Hz — typical cello vibrato rate
                val vibratoMod = if (vibratoDepthCents > 0) {
                    // Convert cents to frequency ratio
                    val cents = vibratoDepthCents * sin(2.0 * PI * vibratoRate * v.sampleCount / SAMPLE_RATE)
                    2.0.pow(cents / 1200.0).toFloat()
                } else 1f

                val freq = v.frequency * vibratoMod

                // --- Additive synthesis with cello harmonic profile ---
                var sample = 0f
                for (h in HARMONIC_WEIGHTS.indices) {
                    val harmonicNum = h + 1
                    val harmonicFreq = freq * harmonicNum

                    // Roll off harmonics above Nyquist/2 to prevent aliasing
                    if (harmonicFreq > SAMPLE_RATE / 2.5) break

                    // Higher harmonics get slightly detuned (string inharmonicity)
                    val inharmonicity = 1.0 + 0.00005 * harmonicNum * harmonicNum
                    val actualFreq = harmonicFreq * inharmonicity

                    val phase = v.phase * harmonicNum * inharmonicity
                    sample += (HARMONIC_WEIGHTS[h] * sin(2.0 * PI * phase).toFloat())
                }
                sample /= WEIGHT_SUM

                // --- Bow noise layer (subtle attack transient) ---
                if (v.sampleCount < SAMPLE_RATE * 0.05) { // First 50ms
                    val noiseLevel = 0.04f * (1f - v.sampleCount.toFloat() / (SAMPLE_RATE * 0.05f))
                    sample += noiseLevel * (Math.random().toFloat() * 2f - 1f)
                }

                // Apply envelope and velocity
                sample *= env * v.velocity * 0.35f  // 0.35 = headroom for mixing

                mix += sample

                // Advance phase
                v.phase += freq.toDouble() / SAMPLE_RATE
                // Keep phase in reasonable range to avoid precision loss
                if (v.phase > 1000.0) v.phase -= 1000.0
                v.sampleCount++
            }

            // Remove dead voices
            for (note in deadNotes) voices.remove(note)
        }

        // Apply master volume and clamp
        mix *= masterVolume
        val clamped = (mix * 32000f).toInt().coerceIn(-32767, 32767)
        return clamped.toShort()
    }
}

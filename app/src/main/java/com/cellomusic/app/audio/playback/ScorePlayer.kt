package com.cellomusic.app.audio.playback

import android.content.Context
import android.media.midi.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.cellomusic.app.domain.model.Score
import android.media.MediaPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream

/**
 * Plays an encoded Score via Android MIDI API (API 29+) or via MIDI file
 * + MediaPlayer fallback (API 26-28). Reports current measure number for
 * cursor positioning.
 */
class ScorePlayer(private val context: Context) {

    enum class PlaybackState { STOPPED, PLAYING, PAUSED }

    private val _playbackState = MutableStateFlow(PlaybackState.STOPPED)
    val playbackState: StateFlow<PlaybackState> = _playbackState

    private val _currentMeasure = MutableStateFlow(1)
    val currentMeasure: StateFlow<Int> = _currentMeasure

    private val _playbackPosition = MutableStateFlow(0f)  // 0..1
    val playbackPosition: StateFlow<Float> = _playbackPosition

    // (measureNumber, elementIndexInMeasure) — for note-level cursor
    private val _currentNotePosition = MutableStateFlow(Pair(1, 0))
    val currentNotePosition: StateFlow<Pair<Int, Int>> = _currentNotePosition

    // Loop completed counter — increments each time a loop pass finishes
    private val _loopPassCount = MutableStateFlow(0)
    val loopPassCount: StateFlow<Int> = _loopPassCount

    private val encoder = MidiScoreEncoder()
    private var volumeMultiplier: Float = 1.0f
    private var mediaPlayerRef: MediaPlayer? = null
    private var encodeResult: MidiScoreEncoder.EncodeResult? = null
    private var tempoMultiplier: Float = 1.0f
    private var transposeSteps: Int = 0
    private var currentScore: Score? = null
    private var pausedAtTick: Long = 0L
    private var playbackScope: CoroutineScope? = null
    /**
     * Tracks the last-launched playback Job so we can cancelAndJoin before
     * starting a new one.  Without this, stopPlayback() returns immediately
     * after calling cancel(), but the old coroutine's finally block (which
     * releases AudioTrack/CelloSynthesizer resources) may still be running
     * when a new CelloSynthesizer is constructed — a race that has crashed
     * the app on rapid tempo slider changes.
     */
    private var playbackJob: Job? = null

    private var midiManager: MidiManager? = null
    private var midiDevice: MidiDevice? = null
    private var midiInputPort: MidiInputPort? = null
    private var celloSynth: CelloSynthesizer? = null

    // ── Loop mode ────────────────────────────────────────────────────────────
    private var loopEnabled: Boolean = false
    private var loopStartMeasure: Int = -1
    private var loopEndMeasure: Int = -1

    // ── Count-in ─────────────────────────────────────────────────────────────
    private var countInEnabled: Boolean = false

    // ── A/B tempo ramp ───────────────────────────────────────────────────────
    private var tempoRampEnabled: Boolean = false
    private var tempoRampStep: Float = 0.05f      // +5% per loop pass
    private var tempoRampMax: Float = 2.0f         // cap at 200%
    private var baseTempoMultiplier: Float = 1.0f  // user-set tempo before ramp

    // Single reusable handler — creating Handler(Looper.getMainLooper()) on every
    // NOTE_ON event causes GC pressure in a timing-sensitive loop.
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Serializes setTempoMultiplier / setTranspose so rapid slider releases
     * can't interleave and leave two CelloSynthesizers racing against each
     * other on the same AudioTrack lifecycle.
     */
    private val reencodeMutex = kotlinx.coroutines.sync.Mutex()
    private val controlScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun setTempoMultiplier(multiplier: Float) {
        // Accept a wider range so the BPM slider can go down to 20 BPM
        // (that's ~0.17× on a 120-base score) and up to 200 BPM on slow
        // pieces (up to 3.33× on a 60-base score).  The encoder caps the
        // final adjusted BPM at 300, so extreme multipliers self-limit.
        tempoMultiplier = multiplier.coerceIn(0.1f, 4.0f)
        baseTempoMultiplier = tempoMultiplier
        val score = currentScore ?: return
        // Re-encoding and teardown are heavy and race-prone; move them off
        // the main thread (they used to runBlocking {} which could ANR) and
        // serialise through a mutex so overlapping slider events can't
        // build two synths at once.
        controlScope.launch {
            reencodeMutex.withLock {
                val wasPlaying = _playbackState.value == PlaybackState.PLAYING
                val savedTick = if (wasPlaying) estimateCurrentTick() else pausedAtTick
                if (wasPlaying) {
                    stopPlayback()
                    // Wait for the old coroutine's finally block so the old
                    // CelloSynthesizer has released its AudioTrack before we
                    // build a new one. Safe to join here — we're off-main.
                    playbackJob?.let {
                        try { withTimeoutOrNull(500L) { it.join() } } catch (_: Exception) {}
                    }
                }
                val newResult = try {
                    encoder.encode(score, tempoMultiplier, transposeSteps)
                } catch (_: Throwable) { null }
                encodeResult = newResult
                pausedAtTick = savedTick
                if (wasPlaying && newResult != null) {
                    withContext(Dispatchers.Main) {
                        _playbackState.value = PlaybackState.PLAYING
                        try {
                            resumeFrom(newResult, savedTick)
                        } catch (_: Exception) {
                            _playbackState.value = PlaybackState.STOPPED
                        }
                    }
                }
            }
        }
    }

    // ── Loop controls ────────────────────────────────────────────────────────

    fun setLoopEnabled(enabled: Boolean) { loopEnabled = enabled }
    fun getLoopEnabled(): Boolean = loopEnabled

    fun setLoopRange(startMeasure: Int, endMeasure: Int) {
        loopStartMeasure = startMeasure
        loopEndMeasure = endMeasure
    }

    fun getLoopStart(): Int = loopStartMeasure
    fun getLoopEnd(): Int = loopEndMeasure

    fun clearLoop() {
        loopEnabled = false
        loopStartMeasure = -1
        loopEndMeasure = -1
        _loopPassCount.value = 0
    }

    // ── Count-in controls ────────────────────────────────────────────────────

    fun setCountInEnabled(enabled: Boolean) { countInEnabled = enabled }
    fun getCountInEnabled(): Boolean = countInEnabled

    // ── Tempo ramp controls ──────────────────────────────────────────────────

    fun setTempoRamp(enabled: Boolean, step: Float = 0.05f, max: Float = 2.0f) {
        tempoRampEnabled = enabled
        tempoRampStep = step.coerceIn(0.01f, 0.25f)
        tempoRampMax = max.coerceIn(0.5f, 3.0f)
    }

    fun getTempoRampEnabled(): Boolean = tempoRampEnabled
    fun getTempoRampStep(): Float = tempoRampStep

    fun setTranspose(steps: Int) {
        transposeSteps = steps.coerceIn(-12, 12)
        val score = currentScore ?: return
        // Same serialised, off-main teardown pattern as setTempoMultiplier.
        controlScope.launch {
            reencodeMutex.withLock {
                val wasPlaying = _playbackState.value == PlaybackState.PLAYING
                val savedTick = if (wasPlaying) estimateCurrentTick() else pausedAtTick
                if (wasPlaying) {
                    stopPlayback()
                    playbackJob?.let {
                        try { withTimeoutOrNull(500L) { it.join() } } catch (_: Exception) {}
                    }
                }
                val newResult = try {
                    encoder.encode(score, tempoMultiplier, transposeSteps)
                } catch (_: Throwable) { null }
                encodeResult = newResult
                pausedAtTick = savedTick
                if (wasPlaying && newResult != null) {
                    withContext(Dispatchers.Main) {
                        _playbackState.value = PlaybackState.PLAYING
                        try {
                            resumeFrom(newResult, savedTick)
                        } catch (_: Exception) {
                            _playbackState.value = PlaybackState.STOPPED
                        }
                    }
                }
            }
        }
    }

    fun setVolume(volume: Float) {
        volumeMultiplier = volume.coerceIn(0f, 1f)
        mediaPlayerRef?.setVolume(volumeMultiplier, volumeMultiplier)
        celloSynth?.setVolume(volumeMultiplier)
    }

    fun loadScore(score: Score): Boolean {
        currentScore = score
        encodeResult = encoder.encode(score, tempoMultiplier, transposeSteps)
        return encodeResult != null
    }

    fun play() {
        val result = encodeResult ?: return
        if (_playbackState.value == PlaybackState.PLAYING) return

        // When loop is enabled and we're starting fresh, begin at loop start
        val startTick = if (loopEnabled && loopStartMeasure > 0 && pausedAtTick == 0L) {
            result.measureStartTicks[loopStartMeasure] ?: 0L
        } else {
            pausedAtTick
        }

        // Reset loop pass counter on fresh play
        if (pausedAtTick == 0L) _loopPassCount.value = 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            playWithMidiApi(result, startTick)
        } else {
            playWithMidiFile(result, startTick)
        }
    }

    fun pause() {
        if (_playbackState.value != PlaybackState.PLAYING) return
        pausedAtTick = estimateCurrentTick()
        stopPlayback()
        _playbackState.value = PlaybackState.PAUSED
    }

    fun stop() {
        pausedAtTick = 0L
        stopPlayback()
        _playbackState.value = PlaybackState.STOPPED
        _currentMeasure.value = 1
        _currentNotePosition.value = Pair(1, 0)
        _playbackPosition.value = 0f
        _loopPassCount.value = 0
        // Reset tempo ramp back to user's base tempo
        if (tempoRampEnabled && tempoMultiplier != baseTempoMultiplier) {
            tempoMultiplier = baseTempoMultiplier
            currentScore?.let { encodeResult = encoder.encode(it, tempoMultiplier, transposeSteps) }
        }
    }

    fun seekToMeasure(measureNumber: Int) {
        val result = encodeResult ?: return
        pausedAtTick = result.measureStartTicks[measureNumber] ?: 0L
        _currentMeasure.value = measureNumber
        if (_playbackState.value == PlaybackState.PLAYING) {
            stopPlayback()
            resumeFrom(result, pausedAtTick)
        }
    }

    /** Seek to the exact tick of a specific note and restart playback from there. */
    fun seekToNote(measureNumber: Int, noteIndex: Int) {
        val result = encodeResult ?: return
        // Find the tick for this specific note (reverse-lookup noteTickToPosition)
        val tick = result.noteTickToPosition.entries
            .firstOrNull { it.value == Pair(measureNumber, noteIndex) }?.key
            ?: result.measureStartTicks[measureNumber]
            ?: 0L
        pausedAtTick = tick
        _currentMeasure.value = measureNumber
        _currentNotePosition.value = Pair(measureNumber, noteIndex)
        if (_playbackState.value == PlaybackState.PLAYING) {
            stopPlayback()
            resumeFrom(result, tick)
        }
    }

    private fun resumeFrom(result: MidiScoreEncoder.EncodeResult, tick: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            playWithMidiApi(result, tick)
        } else {
            playWithMidiFile(result, tick)
        }
    }

    private fun stopPlayback() {
        playbackScope?.cancel()
        playbackScope = null
        // Flush any pending UI updates from the playback loop so they don't
        // fire after state has been reset (prevents race with scroll-to-start).
        mainHandler.removeCallbacksAndMessages(null)
        try { sendAllNotesOff() } catch (_: Exception) {}
        try { celloSynth?.stop() } catch (_: Exception) {}
        celloSynth = null
        try { mediaPlayerRef?.stop() } catch (_: Exception) {}
        try { mediaPlayerRef?.release() } catch (_: Exception) {}
        mediaPlayerRef = null
    }

    /**
     * Plays the score using CelloSynthesizer (AudioTrack-based additive synthesis)
     * for a realistic bowed cello sound. Falls back to MIDI file + MediaPlayer
     * only if the synthesizer cannot be started.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun playWithMidiApi(result: MidiScoreEncoder.EncodeResult, startTick: Long) {
        _playbackState.value = PlaybackState.PLAYING

        playbackScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        playbackJob = playbackScope?.launch {
            try {
                playWithCelloSynth(result, startTick)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Fallback to MIDI file if synth fails
                withContext(Dispatchers.Main) { playWithMidiFile(result, startTick) }
            }
        }
    }

    /**
     * Core playback loop using the custom CelloSynthesizer. Events are
     * dispatched in real-time using monotonic SystemClock timing, the same
     * wall-clock compensation scheme as the old MIDI device path.
     *
     * Supports count-in, loop mode, and A/B tempo ramp.
     */
    private suspend fun playWithCelloSynth(
        result: MidiScoreEncoder.EncodeResult,
        startTick: Long
    ) {
        // ── Count-in (before synth starts — uses its own AudioTrack) ─────
        if (countInEnabled) {
            playCountIn()
        }

        val synth = CelloSynthesizer().also { celloSynth = it }
        synth.setVolume(volumeMultiplier)
        synth.start()

        try {

            // Determine effective loop boundaries (in ticks)
            val loopStartTick = if (loopEnabled && loopStartMeasure > 0)
                result.measureStartTicks[loopStartMeasure] ?: 0L else -1L
            val loopEndTick = if (loopEnabled && loopEndMeasure > 0) {
                result.measureStartTicks[loopEndMeasure + 1]
                    ?: result.totalTicks
            } else -1L

            val shouldLoop = loopEnabled && loopStartTick >= 0 && loopEndTick > loopStartTick
            var currentResult = result
            var effectiveStartTick = startTick

            do {
                currentCoroutineContext().ensureActive()

                val evStartTick = effectiveStartTick
                val evEndTick = if (shouldLoop) loopEndTick else Long.MAX_VALUE
                val filteredEvents = currentResult.events.filter { ev ->
                    ev.absoluteTick >= evStartTick && ev.absoluteTick < evEndTick
                }

                if (filteredEvents.isEmpty() && shouldLoop) {
                    // No events in loop range — avoid infinite spin
                    delay(500L)
                    break
                }

                val startWallMs = SystemClock.elapsedRealtime()
                val startMs = encoder.ticksToMs(effectiveStartTick, currentResult.tempoMap)

                val sortedMeasures = currentResult.measureStartTicks.entries.sortedBy { it.value }
                var lastDispatchedMeasure = -1

                for (event in filteredEvents) {
                    currentCoroutineContext().ensureActive()

                    val eventMs = encoder.ticksToMs(event.absoluteTick, currentResult.tempoMap)
                    val delayMs = (eventMs - startMs) - (SystemClock.elapsedRealtime() - startWallMs)
                    if (delayMs > 0) delay(delayMs)

                    when (event.type) {
                        MidiScoreEncoder.MidiEventType.NOTE_ON -> {
                            val scaledVel = (event.data2 * volumeMultiplier).toInt().coerceIn(1, 127)
                            synth.noteOn(event.data1, scaledVel)
                        }
                        MidiScoreEncoder.MidiEventType.NOTE_OFF -> {
                            synth.noteOff(event.data1)
                        }
                        MidiScoreEncoder.MidiEventType.PROGRAM_CHANGE -> {}
                        MidiScoreEncoder.MidiEventType.CONTROL_CHANGE -> {}
                        MidiScoreEncoder.MidiEventType.TEMPO_CHANGE -> continue
                    }

                    if (event.type == MidiScoreEncoder.MidiEventType.NOTE_ON) {
                        val notePos = currentResult.noteTickToPosition[event.absoluteTick]
                        var measureNum = 1
                        for (entry in sortedMeasures) {
                            if (entry.value <= event.absoluteTick) measureNum = entry.key else break
                        }
                        val pos = if (currentResult.totalTicks > 0)
                            event.absoluteTick.toFloat() / currentResult.totalTicks else 0f

                        if (measureNum != lastDispatchedMeasure || notePos != null) {
                            lastDispatchedMeasure = measureNum
                            val capturedNotePos = notePos
                            val capturedMeasure = measureNum
                            val capturedPos = pos
                            mainHandler.post {
                                capturedNotePos?.let { (m, e) -> _currentNotePosition.value = Pair(m, e) }
                                _currentMeasure.value = capturedMeasure
                                _playbackPosition.value = capturedPos
                            }
                        }
                    }
                }

                // ── Loop pass completed ──────────────────────────────────
                if (shouldLoop) {
                    synth.allNotesOff()
                    val passCount = _loopPassCount.value + 1
                    mainHandler.post { _loopPassCount.value = passCount }

                    if (tempoRampEnabled) {
                        val newTempo = (baseTempoMultiplier + tempoRampStep * passCount)
                            .coerceAtMost(tempoRampMax)
                        tempoMultiplier = newTempo
                        val score = currentScore
                        if (score != null) {
                            currentResult = encoder.encode(score, tempoMultiplier, transposeSteps)
                            encodeResult = currentResult
                        }
                    }

                    effectiveStartTick = loopStartTick
                    delay(200L)
                }
            } while (shouldLoop)

            withContext(Dispatchers.Main) {
                _playbackState.value = PlaybackState.STOPPED
                _currentMeasure.value = 1
                pausedAtTick = 0L
            }
        } finally {
            try { synth.stop() } catch (_: Exception) {}
            celloSynth = null
        }
    }

    /**
     * Plays a sharp, distinct count-in (one full measure of woodblock-style clicks)
     * using a dedicated AudioTrack that is created, used, and released entirely
     * within this method — completely independent of the CelloSynthesizer.
     *
     * The clicks use the same frequencies as the MetronomeEngine (1200 Hz downbeat,
     * 900 Hz upbeat) with exponential decay for a crisp, percussive sound.
     */
    private suspend fun playCountIn() {
        val score = currentScore ?: return
        val firstMeasure = score.parts.firstOrNull()?.measures?.firstOrNull() ?: return
        val timeSig = firstMeasure.timeSignature
            ?: com.cellomusic.app.domain.model.TimeSignature(4, 4)
        val bpm = ((firstMeasure.tempo?.bpm ?: MidiScoreEncoder.DEFAULT_BPM) * tempoMultiplier)
            .coerceIn(20f, 400f)
        val msPerBeat = (60000.0 / bpm * (4.0 / timeSig.denominator)).toLong()
            .coerceAtLeast(100L)

        val sampleRate = 44100
        val samplesPerBeat = (sampleRate * msPerBeat / 1000).toInt()
        val clickSamples = sampleRate * 18 / 1000  // 18ms click duration
        val totalSamples = samplesPerBeat * timeSig.numerator

        // Pre-render the entire count-in measure into one buffer
        val buffer = ShortArray(totalSamples)
        for (beat in 0 until timeSig.numerator) {
            val isDownbeat = beat == 0
            val freq = if (isDownbeat) 1200.0 else 900.0  // Hz
            val amplitude = if (isDownbeat) 0.95 else 0.75
            val beatOffset = beat * samplesPerBeat

            for (i in 0 until clickSamples) {
                if (beatOffset + i >= totalSamples) break
                val t = i.toDouble() / sampleRate
                val envelope = kotlin.math.exp(-i.toDouble() / (clickSamples / 3.5))
                // Two-harmonic sine for a bright, cutting click
                val wave = kotlin.math.sin(2.0 * Math.PI * freq * t) * 0.80 +
                           kotlin.math.sin(2.0 * Math.PI * freq * 2.0 * t) * 0.20
                val sample = (wave * envelope * amplitude * volumeMultiplier * Short.MAX_VALUE)
                    .toInt().coerceIn(-32767, 32767)
                buffer[beatOffset + i] = sample.toShort()
            }
            // Rest of beat is silence (buffer initialized to 0)
        }

        // Play using a temporary AudioTrack
        withContext(Dispatchers.IO) {
            val bufferSize = android.media.AudioTrack.getMinBufferSize(
                sampleRate,
                android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(buffer.size * 2)

            val track = android.media.AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                .build()

            try {
                track.write(buffer, 0, buffer.size)
                track.play()
                // Wait for the count-in to finish playing
                delay(msPerBeat * timeSig.numerator + 50)
            } finally {
                try { track.stop() } catch (_: Exception) {}
                try { track.release() } catch (_: Exception) {}
            }
        }
    }

    private fun playWithMidiFile(result: MidiScoreEncoder.EncodeResult, startTick: Long) {
        _playbackState.value = PlaybackState.PLAYING
        // Write standard MIDI file and play with MediaPlayer
        val midiFile = File(context.cacheDir, "playback_${System.currentTimeMillis()}.mid")
        writeMidiFile(result, midiFile)

        val mediaPlayer = MediaPlayer().apply {
            setDataSource(midiFile.absolutePath)
            prepare()
        }

        // Seek to position
        val startMs = encoder.ticksToMs(startTick, result.tempoMap).toInt()
        if (startMs > 0) mediaPlayer.seekTo(startMs)

        mediaPlayerRef = mediaPlayer
        mediaPlayer.setVolume(volumeMultiplier, volumeMultiplier)
        playbackScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        mediaPlayer.start()

        playbackScope?.launch {
            while (mediaPlayer.isPlaying) {
                val currentMs = mediaPlayer.currentPosition.toLong()
                val currentTick = msToTick(currentMs, result.tempoMap)

                val measureNum = result.measureStartTicks.entries
                    .filter { it.value <= currentTick }
                    .maxByOrNull { it.value }
                    ?.key ?: 1
                // Binary search for the most recent note at or before currentTick
                val ticks = result.sortedNoteTicks
                if (ticks.isNotEmpty()) {
                    var lo = 0; var hi = ticks.size - 1; var idx = -1
                    while (lo <= hi) {
                        val mid = (lo + hi) ushr 1
                        if (ticks[mid] <= currentTick) { idx = mid; lo = mid + 1 }
                        else hi = mid - 1
                    }
                    if (idx >= 0) {
                        val notePos = result.noteTickToPosition[ticks[idx]]
                        if (notePos != null) _currentNotePosition.value = notePos
                    }
                }
                _currentMeasure.value = measureNum
                _playbackPosition.value = if (result.totalTicks > 0) {
                    currentTick.toFloat() / result.totalTicks
                } else 0f

                delay(16L) // ~60 Hz update rate for smooth cursor
            }
            _playbackState.value = PlaybackState.STOPPED
            mediaPlayer.release()
            midiFile.delete()
        }
    }

    private fun writeMidiFile(result: MidiScoreEncoder.EncodeResult, file: File) {
        FileOutputStream(file).use { out ->
            val writer = StandardMidiFileWriter()
            writer.write(
                result.events,
                result.tempoMap,
                MidiScoreEncoder.TICKS_PER_QUARTER,
                out
            )
        }
    }

    private fun sendAllNotesOff() {
        celloSynth?.allNotesOff()
        val port = midiInputPort ?: return
        try {
            // All notes off on channel 0 (MIDI fallback)
            port.send(byteArrayOf(0xB0.toByte(), 123, 0), 0, 3)
        } catch (_: Exception) {}
    }

    private fun estimateCurrentTick(): Long {
        return encodeResult?.measureStartTicks?.get(_currentMeasure.value) ?: 0L
    }

    private fun msToTick(ms: Long, tempoMap: List<MidiScoreEncoder.TempoEvent>): Long {
        // Inverse of ticksToMs
        var remainingMs = ms
        var tick = 0L
        var prevBpm = MidiScoreEncoder.DEFAULT_BPM
        var prevTick = 0L
        var prevMs = 0L

        for (tempoEvent in tempoMap) {
            val ticksUntilTempo = tempoEvent.absoluteTick - prevTick
            val msUntilTempo = ticksUntilTempo * 60000L / (prevBpm * MidiScoreEncoder.TICKS_PER_QUARTER)
            if (prevMs + msUntilTempo >= ms) {
                val remaining = ms - prevMs
                return tick + remaining * prevBpm * MidiScoreEncoder.TICKS_PER_QUARTER / 60000L
            }
            tick += ticksUntilTempo
            prevMs += msUntilTempo
            prevTick = tempoEvent.absoluteTick
            prevBpm = tempoEvent.bpm
        }

        return tick + (ms - prevMs) * prevBpm * MidiScoreEncoder.TICKS_PER_QUARTER / 60000L
    }
}

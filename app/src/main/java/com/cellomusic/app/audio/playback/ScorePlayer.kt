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

    private val encoder = MidiScoreEncoder()
    private var volumeMultiplier: Float = 1.0f
    private var mediaPlayerRef: MediaPlayer? = null
    private var encodeResult: MidiScoreEncoder.EncodeResult? = null
    private var tempoMultiplier: Float = 1.0f
    private var transposeSteps: Int = 0
    private var currentScore: Score? = null
    private var pausedAtTick: Long = 0L
    private var playbackScope: CoroutineScope? = null

    private var midiManager: MidiManager? = null
    private var midiDevice: MidiDevice? = null
    private var midiInputPort: MidiInputPort? = null

    // Single reusable handler — creating Handler(Looper.getMainLooper()) on every
    // NOTE_ON event causes GC pressure in a timing-sensitive loop.
    private val mainHandler = Handler(Looper.getMainLooper())

    fun setTempoMultiplier(multiplier: Float) {
        tempoMultiplier = multiplier.coerceIn(0.25f, 2.0f)
        val score = currentScore ?: return
        val wasPlaying = _playbackState.value == PlaybackState.PLAYING
        val savedTick = if (wasPlaying) estimateCurrentTick() else pausedAtTick
        if (wasPlaying) stopPlayback()
        encodeResult = encoder.encode(score, tempoMultiplier, transposeSteps)
        pausedAtTick = savedTick
        if (wasPlaying) {
            val result = encodeResult ?: return
            _playbackState.value = PlaybackState.PLAYING
            resumeFrom(result, savedTick)
        }
    }

    fun setTranspose(steps: Int) {
        transposeSteps = steps.coerceIn(-12, 12)
        val score = currentScore ?: return
        val wasPlaying = _playbackState.value == PlaybackState.PLAYING
        val savedTick = if (wasPlaying) estimateCurrentTick() else pausedAtTick
        if (wasPlaying) stopPlayback()
        encodeResult = encoder.encode(score, tempoMultiplier, transposeSteps)
        pausedAtTick = savedTick
        if (wasPlaying) {
            val result = encodeResult ?: return
            _playbackState.value = PlaybackState.PLAYING
            resumeFrom(result, savedTick)
        }
    }

    fun setVolume(volume: Float) {
        volumeMultiplier = volume.coerceIn(0f, 1f)
        mediaPlayerRef?.setVolume(volumeMultiplier, volumeMultiplier)
    }

    fun loadScore(score: Score): Boolean {
        currentScore = score
        encodeResult = encoder.encode(score, tempoMultiplier, transposeSteps)
        return encodeResult != null
    }

    fun play() {
        val result = encodeResult ?: return
        if (_playbackState.value == PlaybackState.PLAYING) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            playWithMidiApi(result, pausedAtTick)
        } else {
            playWithMidiFile(result, pausedAtTick)
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
        sendAllNotesOff()
        try { mediaPlayerRef?.stop() } catch (_: Exception) {}
        try { mediaPlayerRef?.release() } catch (_: Exception) {}
        mediaPlayerRef = null
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun playWithMidiApi(result: MidiScoreEncoder.EncodeResult, startTick: Long) {
        _playbackState.value = PlaybackState.PLAYING

        // Open a virtual MIDI device (Android built-in synth)
        midiManager = context.getSystemService(MidiManager::class.java)

        playbackScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        playbackScope?.launch {
            try {
                // Use the built-in synthesizer via MidiManager
                val devices = midiManager?.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM)
                // Filter for output (playback) devices
                val synthDevice = devices?.firstOrNull { info ->
                    info.inputPortCount > 0
                }

                if (synthDevice != null) {
                    playWithDevice(synthDevice, result, startTick)
                } else {
                    // Fallback: write MIDI file and use MediaPlayer
                    withContext(Dispatchers.Main) {
                        playWithMidiFile(result, startTick)
                    }
                }
            } catch (e: CancellationException) {
                throw e   // always re-throw cancellation
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { _playbackState.value = PlaybackState.STOPPED }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private suspend fun playWithDevice(
        deviceInfo: MidiDeviceInfo,
        result: MidiScoreEncoder.EncodeResult,
        startTick: Long
    ) {
        val device = suspendCancellableCoroutine<MidiDevice?> { cont ->
            midiManager?.openDevice(deviceInfo, { device ->
                cont.resume(device) {}
            }, Handler(Looper.getMainLooper()))
        } ?: return

        midiDevice = device
        val port = device.openInputPort(0) ?: return
        midiInputPort = port

        val filteredEvents = result.events.filter { it.absoluteTick >= startTick }
        // SystemClock.elapsedRealtime() is monotonic — it never jumps backward due
        // to NTP adjustments, unlike System.currentTimeMillis().
        val startWallMs  = SystemClock.elapsedRealtime()
        val startMs      = encoder.ticksToMs(startTick, result.tempoMap)

        // Sort measureStartTicks once for fast lookup (avoid repeated filter+max)
        val sortedMeasures = result.measureStartTicks.entries
            .sortedBy { it.value }   // ascending tick order
        var lastDispatchedMeasure = -1

        for (event in filteredEvents) {
            currentCoroutineContext().ensureActive()

            // How many ms after start should this event fire?
            val eventMs  = encoder.ticksToMs(event.absoluteTick, result.tempoMap)
            val delayMs  = (eventMs - startMs) - (SystemClock.elapsedRealtime() - startWallMs)
            if (delayMs > 0) delay(delayMs)

            // Send MIDI bytes immediately — no coroutine switch, no waiting
            val scaledVel = (event.data2 * volumeMultiplier).toInt().coerceIn(1, 127)
            val bytes = when (event.type) {
                MidiScoreEncoder.MidiEventType.NOTE_ON ->
                    byteArrayOf((0x90 or event.channel).toByte(), event.data1.toByte(), scaledVel.toByte())
                MidiScoreEncoder.MidiEventType.NOTE_OFF ->
                    byteArrayOf((0x80 or event.channel).toByte(), event.data1.toByte(), 0)
                MidiScoreEncoder.MidiEventType.PROGRAM_CHANGE ->
                    byteArrayOf((0xC0 or event.channel).toByte(), event.data1.toByte())
                MidiScoreEncoder.MidiEventType.CONTROL_CHANGE ->
                    byteArrayOf((0xB0 or event.channel).toByte(), event.data1.toByte(), event.data2.toByte())
                MidiScoreEncoder.MidiEventType.TEMPO_CHANGE -> continue
            }
            port.send(bytes, 0, bytes.size)

            // UI updates only on NOTE_ON — use launch (fire-and-forget) so the
            // timing loop is never blocked waiting for the main thread.
            if (event.type == MidiScoreEncoder.MidiEventType.NOTE_ON) {
                val notePos = result.noteTickToPosition[event.absoluteTick]

                // Binary-search the sorted measure list for current measure
                var measureNum = 1
                for (entry in sortedMeasures) {
                    if (entry.value <= event.absoluteTick) measureNum = entry.key else break
                }
                val pos = if (result.totalTicks > 0)
                    event.absoluteTick.toFloat() / result.totalTicks else 0f

                // Only dispatch when something visible actually changed.
                // Handler.post is fire-and-forget and works from any thread
                // without needing a CoroutineScope.
                if (measureNum != lastDispatchedMeasure || notePos != null) {
                    lastDispatchedMeasure = measureNum
                    val capturedNotePos = notePos
                    val capturedMeasure = measureNum
                    val capturedPos     = pos
                    mainHandler.post {
                        capturedNotePos?.let { (m, e) -> _currentNotePosition.value = Pair(m, e) }
                        _currentMeasure.value   = capturedMeasure
                        _playbackPosition.value = capturedPos
                    }
                }
            }
        }

        withContext(Dispatchers.Main) {
            _playbackState.value = PlaybackState.STOPPED
            _currentMeasure.value = 1
            pausedAtTick = 0L
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
        val port = midiInputPort ?: return
        try {
            // All notes off on channel 0
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

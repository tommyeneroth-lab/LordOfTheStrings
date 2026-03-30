package com.cellomusic.app.audio.playback

import android.content.Context
import android.media.midi.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import com.cellomusic.app.domain.model.Score
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

    private val encoder = MidiScoreEncoder()
    private var encodeResult: MidiScoreEncoder.EncodeResult? = null
    private var tempoMultiplier: Float = 1.0f
    private var pausedAtTick: Long = 0L
    private var playbackScope: CoroutineScope? = null

    private var midiManager: MidiManager? = null
    private var midiDevice: MidiDevice? = null
    private var midiInputPort: MidiInputPort? = null

    fun setTempoMultiplier(multiplier: Float) {
        tempoMultiplier = multiplier.coerceIn(0.25f, 2.0f)
        // If playing, re-encode and seek to current position
        encodeResult?.let {
            val currentTick = if (_playbackState.value == PlaybackState.PLAYING) {
                // Estimate current tick from current measure
                val measureNum = _currentMeasure.value
                it.measureStartTicks[measureNum] ?: 0L
            } else pausedAtTick
            stop()
        }
    }

    fun loadScore(score: Score): Boolean {
        encodeResult = encoder.encode(score, tempoMultiplier)
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
        _playbackPosition.value = 0f
    }

    fun seekToMeasure(measureNumber: Int) {
        val result = encodeResult ?: return
        pausedAtTick = result.measureStartTicks[measureNumber] ?: 0L
        _currentMeasure.value = measureNumber
        if (_playbackState.value == PlaybackState.PLAYING) {
            stopPlayback()
            playWithMidiApi(result, pausedAtTick)
        }
    }

    private fun stopPlayback() {
        playbackScope?.cancel()
        playbackScope = null
        sendAllNotesOff()
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
                    info.outputPortCount > 0
                }

                if (synthDevice != null) {
                    playWithDevice(synthDevice, result, startTick)
                } else {
                    // Fallback: write MIDI file and use MediaPlayer
                    withContext(Dispatchers.Main) {
                        playWithMidiFile(result, startTick)
                    }
                }
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
        var prevTick = startTick
        val prevTimeMs = System.currentTimeMillis()

        for (event in filteredEvents) {
            if (!isActive) break

            val eventMs = encoder.ticksToMs(event.absoluteTick, result.tempoMap)
            val startMs = encoder.ticksToMs(startTick, result.tempoMap)
            val delayMs = eventMs - startMs - (System.currentTimeMillis() - prevTimeMs)

            if (delayMs > 0) {
                delay(delayMs)
            }

            // Send MIDI event
            val bytes = when (event.type) {
                MidiScoreEncoder.MidiEventType.NOTE_ON ->
                    byteArrayOf((0x90 or event.channel).toByte(), event.data1.toByte(), event.data2.toByte())
                MidiScoreEncoder.MidiEventType.NOTE_OFF ->
                    byteArrayOf((0x80 or event.channel).toByte(), event.data1.toByte(), 0)
                MidiScoreEncoder.MidiEventType.PROGRAM_CHANGE ->
                    byteArrayOf((0xC0 or event.channel).toByte(), event.data1.toByte())
                MidiScoreEncoder.MidiEventType.CONTROL_CHANGE ->
                    byteArrayOf((0xB0 or event.channel).toByte(), event.data1.toByte(), event.data2.toByte())
                MidiScoreEncoder.MidiEventType.TEMPO_CHANGE -> continue
            }

            port.send(bytes, 0, bytes.size)

            // Update current measure based on tick position
            val currentMeasure = result.measureStartTicks.entries
                .filter { it.value <= event.absoluteTick }
                .maxByOrNull { it.value }
                ?.key ?: 1
            withContext(Dispatchers.Main) {
                _currentMeasure.value = currentMeasure
                _playbackPosition.value = if (result.totalTicks > 0) {
                    event.absoluteTick.toFloat() / result.totalTicks
                } else 0f
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

        val mediaPlayer = android.media.MediaPlayer().apply {
            setDataSource(midiFile.absolutePath)
            prepare()
        }

        // Seek to position
        val startMs = encoder.ticksToMs(startTick, result.tempoMap).toInt()
        if (startMs > 0) mediaPlayer.seekTo(startMs)

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
                _currentMeasure.value = measureNum
                _playbackPosition.value = if (result.totalTicks > 0) {
                    currentTick.toFloat() / result.totalTicks
                } else 0f

                delay(50L) // 20 Hz update rate
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

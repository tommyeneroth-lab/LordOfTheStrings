package com.cellomusic.app.midi

import com.cellomusic.app.domain.model.*
import java.io.DataInputStream
import java.io.InputStream
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Parses a Standard MIDI File (SMF) and converts it to the Score domain model.
 * Supports Format 0 (single track) and Format 1 (multi-track, uses all tracks merged).
 *
 * Key fix: VLQ (variable-length quantity) decoding now uses a while loop with an
 * explicit break, instead of repeat{return@repeat} which does NOT break the loop.
 */
class MidiFileParser {

    companion object {
        private const val TICKS_PER_QUARTER_DEFAULT = 480
        private val SEMITONE_TO_STEP = arrayOf(
            PitchStep.C, PitchStep.C, PitchStep.D, PitchStep.D, PitchStep.E,
            PitchStep.F, PitchStep.F, PitchStep.G, PitchStep.G, PitchStep.A,
            PitchStep.A, PitchStep.B
        )
        private val IS_SHARP = booleanArrayOf(
            false, true, false, true, false,
            false, true, false, true, false, true, false
        )
    }

    data class ParseResult(val score: Score, val warnings: List<String>)

    fun parse(input: InputStream, title: String): ParseResult {
        val warnings = mutableListOf<String>()
        val data = input.readBytes()
        val stream = DataInputStream(data.inputStream())

        // ── MIDI header ──────────────────────────────────────────────────────
        val headerTag = readFourCC(stream)
        if (headerTag != "MThd") return errorResult(title, "Not a MIDI file (missing MThd)")
        val headerLen = stream.readInt()
        if (headerLen < 6) return errorResult(title, "Invalid MIDI header length")
        val format      = stream.readShort().toInt() and 0xFFFF
        val numTracks   = stream.readShort().toInt() and 0xFFFF
        val division    = stream.readShort().toInt() and 0xFFFF
        if (headerLen > 6) stream.skip((headerLen - 6).toLong())

        if (division and 0x8000 != 0) warnings.add("SMPTE timecode not supported — using default 480 PPQ")
        val ticksPerQuarter = if (division and 0x8000 == 0) division else TICKS_PER_QUARTER_DEFAULT
        if (format > 1) warnings.add("MIDI format $format: all tracks merged")

        // ── Read all tracks ──────────────────────────────────────────────────
        data class RawEvent(
            val absoluteTick: Long, val type: Int, val channel: Int,
            val d1: Int, val d2: Int,
            val metaType: Int = -1, val metaData: ByteArray = ByteArray(0)
        )

        val allEvents = mutableListOf<RawEvent>()
        var tempoMicros = 500_000L
        var timeSigNum = 4; var timeSigDen = 4

        val tracksToRead = if (format == 0) 1 else numTracks.coerceAtMost(32)
        repeat(tracksToRead) {
            if (stream.available() < 8) return@repeat
            val tag = readFourCC(stream)
            val len = stream.readInt()
            if (tag != "MTrk" || len < 0) { stream.skip(len.coerceAtLeast(0).toLong()); return@repeat }
            val trackBytes = ByteArray(len)
            stream.readFully(trackBytes)
            parseTrack(trackBytes, warnings).forEach { e ->
                allEvents.add(RawEvent(e.absoluteTick, e.type, e.channel, e.d1, e.d2, e.metaType, e.metaData))
            }
        }
        allEvents.sortBy { it.absoluteTick }

        // Extract global tempo and time signature
        for (e in allEvents) {
            if (e.type == 0xFF) when (e.metaType) {
                0x51 -> if (e.metaData.size >= 3) {
                    tempoMicros = ((e.metaData[0].toLong() and 0xFF) shl 16) or
                                  ((e.metaData[1].toLong() and 0xFF) shl  8) or
                                   (e.metaData[2].toLong() and 0xFF)
                }
                0x58 -> if (e.metaData.size >= 2) {
                    timeSigNum = e.metaData[0].toInt() and 0xFF
                    timeSigDen = 1 shl (e.metaData[1].toInt() and 0xFF)
                }
            }
        }
        val bpm = (60_000_000.0 / tempoMicros).roundToInt().coerceIn(20, 300)

        // ── Pair note-on/off events ──────────────────────────────────────────
        data class ActiveNote(val startTick: Long, val midiNote: Int, val channel: Int)
        data class CompletedNote(val startTick: Long, val endTick: Long, val midiNote: Int)

        val active    = mutableListOf<ActiveNote>()
        val completed = mutableListOf<CompletedNote>()

        for (e in allEvents) {
            when {
                e.type == 0x90 && e.d2 > 0 ->
                    active.add(ActiveNote(e.absoluteTick, e.d1, e.channel))
                e.type == 0x80 || (e.type == 0x90 && e.d2 == 0) -> {
                    val idx = active.indexOfFirst { it.midiNote == e.d1 && it.channel == e.channel }
                    if (idx >= 0) {
                        val an = active.removeAt(idx)
                        completed.add(CompletedNote(an.startTick, e.absoluteTick, an.midiNote))
                    }
                }
            }
        }
        for (an in active) {
            completed.add(CompletedNote(an.startTick, an.startTick + ticksPerQuarter, an.midiNote))
        }
        completed.sortBy { it.startTick }

        if (completed.isEmpty()) {
            warnings.add("No notes found in MIDI file")
            return ParseResult(emptyScore(title), warnings)
        }

        // ── Build measures ───────────────────────────────────────────────────
        val ticksPerMeasure = ticksPerQuarter * 4L * timeSigNum / timeSigDen
        val totalTicks  = completed.maxOf { it.endTick }
        val numMeasures = ((totalTicks + ticksPerMeasure - 1) / ticksPerMeasure).toInt().coerceAtLeast(1)

        val measures = mutableListOf<Measure>()
        for (mIdx in 0 until numMeasures) {
            val mStart = mIdx * ticksPerMeasure
            val mEnd   = mStart + ticksPerMeasure

            val notesInMeasure = completed.filter { it.startTick >= mStart && it.startTick < mEnd }
            val elements = mutableListOf<MusicElement>()
            var prevEnd = mStart

            for (note in notesInMeasure) {
                val gap = note.startTick - prevEnd
                if (gap >= ticksPerQuarter / 8) {
                    elements.add(Rest(
                        id = UUID.randomUUID().toString(),
                        startTick = (prevEnd - mStart).toInt(),
                        duration = quantizeDuration(gap, ticksPerQuarter)
                    ))
                }
                val dur = quantizeDuration(note.endTick - note.startTick, ticksPerQuarter)
                elements.add(Note(
                    id = UUID.randomUUID().toString(),
                    startTick = (note.startTick - mStart).toInt(),
                    duration = dur,
                    pitch = midiNoteToPitch(note.midiNote)
                ))
                prevEnd = note.startTick + dur.toTicks()
            }

            measures.add(Measure(
                number = mIdx + 1,
                clef          = if (mIdx == 0) Clef(ClefType.BASS) else null,
                timeSignature = if (mIdx == 0) TimeSignature(timeSigNum, timeSigDen) else null,
                keySignature  = if (mIdx == 0) KeySignature(0) else null,
                tempo         = if (mIdx == 0) TempoMark(bpm) else null,
                elements      = elements,
                barlineRight  = if (mIdx == numMeasures - 1) Barline.FINAL else Barline.REGULAR
            ))
        }

        return ParseResult(
            Score(id = UUID.randomUUID().toString(), title = title,
                  parts = listOf(Part("P1", "Cello", "Vc.", measures = measures))),
            warnings
        )
    }

    // ── Track parser ─────────────────────────────────────────────────────────

    private data class TrackEvent(
        val absoluteTick: Long, val type: Int, val channel: Int,
        val d1: Int, val d2: Int,
        val metaType: Int = -1, val metaData: ByteArray = ByteArray(0)
    )

    private fun parseTrack(bytes: ByteArray, warnings: MutableList<String>): List<TrackEvent> {
        val events = mutableListOf<TrackEvent>()
        var pos = 0
        var absoluteTick = 0L
        var runningStatus = 0

        while (pos < bytes.size) {
            // Variable-length delta time — MUST use while+break, not repeat{return@repeat}
            var delta = 0L
            var vlqBytes = 0
            while (pos < bytes.size) {
                val b = bytes[pos++].toInt() and 0xFF
                delta = (delta shl 7) or (b and 0x7F).toLong()
                vlqBytes++
                if (b and 0x80 == 0) break   // ← real break, not return@repeat
                if (vlqBytes >= 4) break
            }
            absoluteTick += delta
            if (pos >= bytes.size) break

            var status = bytes[pos].toInt() and 0xFF
            if (status and 0x80 != 0) { runningStatus = status; pos++ }
            else status = runningStatus
            if (pos >= bytes.size) break

            val type    = status and 0xF0
            val channel = status and 0x0F

            when {
                status == 0xFF -> {                      // Meta event
                    val metaType = bytes[pos++].toInt() and 0xFF
                    val metaLen  = readVarLen(bytes, pos)
                    pos += varLenSize(bytes, pos)
                    val end = (pos + metaLen).coerceAtMost(bytes.size)
                    val metaData = bytes.copyOfRange(pos, end)
                    pos = end
                    events.add(TrackEvent(absoluteTick, 0xFF, 0, 0, 0, metaType, metaData))
                    if (metaType == 0x2F) return events   // End of Track
                }
                status == 0xF0 || status == 0xF7 -> {    // SysEx
                    val sysLen = readVarLen(bytes, pos)
                    pos += varLenSize(bytes, pos)
                    pos = (pos + sysLen).coerceAtMost(bytes.size)
                }
                type == 0x90 || type == 0x80 -> {        // Note On / Note Off
                    val d1 = if (pos < bytes.size) bytes[pos++].toInt() and 0xFF else 0
                    val d2 = if (pos < bytes.size) bytes[pos++].toInt() and 0xFF else 0
                    events.add(TrackEvent(absoluteTick, type, channel, d1, d2))
                }
                type == 0xA0 || type == 0xB0 || type == 0xE0 -> {  // 2-byte data
                    if (pos < bytes.size) pos++
                    if (pos < bytes.size) pos++
                }
                type == 0xC0 || type == 0xD0 -> {        // 1-byte data
                    if (pos < bytes.size) pos++
                }
                else -> pos++
            }
        }
        return events
    }

    // ── VLQ helpers ──────────────────────────────────────────────────────────

    /** Read a variable-length quantity from bytes starting at startPos. */
    private fun readVarLen(bytes: ByteArray, startPos: Int): Int {
        var pos = startPos; var value = 0; var count = 0
        while (pos < bytes.size && count < 4) {
            val b = bytes[pos++].toInt() and 0xFF
            value = (value shl 7) or (b and 0x7F)
            count++
            if (b and 0x80 == 0) break
        }
        return value
    }

    /** Number of bytes consumed by the VLQ at startPos. */
    private fun varLenSize(bytes: ByteArray, startPos: Int): Int {
        var pos = startPos; var size = 0
        while (pos < bytes.size && size < 4) {
            size++
            if (bytes[pos++].toInt() and 0x80 == 0) break
        }
        return size
    }

    private fun readFourCC(stream: DataInputStream): String {
        val buf = ByteArray(4); stream.readFully(buf)
        return String(buf, Charsets.US_ASCII)
    }

    // ── Music model helpers ───────────────────────────────────────────────────

    private fun midiNoteToPitch(midiNote: Int): Pitch {
        val octave   = (midiNote / 12) - 1
        val semitone = midiNote % 12
        return Pitch(SEMITONE_TO_STEP[semitone], octave,
                     if (IS_SHARP[semitone]) Alter.SHARP else Alter.NATURAL)
    }

    /**
     * Quantize a tick duration to the nearest standard note value.
     * Threshold: 7/8 of the expected ticks (so notes played slightly short still quantize up).
     */
    private fun quantizeDuration(ticks: Long, tpq: Int): NoteDuration {
        val whole         = tpq * 4L
        val half          = tpq * 2L
        val quarter       = tpq.toLong()
        val eighth        = tpq / 2L
        val sixteenth     = tpq / 4L
        return when {
            ticks >= whole     * 7 / 8 -> NoteDuration(DurationType.WHOLE)
            ticks >= half      * 7 / 8 -> NoteDuration(DurationType.HALF)
            ticks >= quarter   * 7 / 8 -> NoteDuration(DurationType.QUARTER)
            ticks >= eighth    * 7 / 8 -> NoteDuration(DurationType.EIGHTH)
            ticks >= sixteenth * 7 / 8 -> NoteDuration(DurationType.SIXTEENTH)
            else                        -> NoteDuration(DurationType.THIRTY_SECOND)
        }
    }

    private fun emptyScore(title: String) = Score(
        id = UUID.randomUUID().toString(), title = title,
        parts = listOf(Part("P1", "Cello", "Vc.", measures = listOf(
            Measure(1, clef = Clef(ClefType.BASS),
                    timeSignature = TimeSignature(4, 4),
                    keySignature  = KeySignature(0),
                    elements      = emptyList())
        )))
    )

    private fun errorResult(title: String, msg: String) =
        ParseResult(emptyScore(title), listOf(msg))
}

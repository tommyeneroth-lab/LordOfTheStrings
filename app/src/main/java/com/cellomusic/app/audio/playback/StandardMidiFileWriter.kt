package com.cellomusic.app.audio.playback

import java.io.OutputStream

/**
 * Writes a standard MIDI file (SMF format 0) from a list of MidiEvents.
 */
class StandardMidiFileWriter {

    fun write(
        events: List<MidiScoreEncoder.MidiEvent>,
        tempoMap: List<MidiScoreEncoder.TempoEvent>,
        ticksPerQuarter: Int,
        out: OutputStream
    ) {
        val trackData = buildTrackData(events, tempoMap, ticksPerQuarter)

        // MIDI header chunk
        out.write("MThd".toByteArray())
        writeInt32(out, 6)         // header length
        writeInt16(out, 0)         // format 0 (single track)
        writeInt16(out, 1)         // 1 track
        writeInt16(out, ticksPerQuarter)

        // Track chunk
        out.write("MTrk".toByteArray())
        writeInt32(out, trackData.size)
        out.write(trackData)
    }

    private fun buildTrackData(
        events: List<MidiScoreEncoder.MidiEvent>,
        tempoMap: List<MidiScoreEncoder.TempoEvent>,
        ticksPerQuarter: Int
    ): ByteArray {
        val buf = mutableListOf<Byte>()
        var prevTick = 0L

        // Merge tempo events and MIDI events, sort by tick
        data class RawEvent(val tick: Long, val bytes: ByteArray)
        val allEvents = mutableListOf<RawEvent>()

        for (t in tempoMap) {
            val bpm = t.bpm
            val microsPerBeat = 60_000_000 / bpm
            allEvents.add(RawEvent(t.absoluteTick, byteArrayOf(
                0xFF.toByte(), 0x51.toByte(), 0x03.toByte(),
                ((microsPerBeat shr 16) and 0xFF).toByte(),
                ((microsPerBeat shr 8) and 0xFF).toByte(),
                (microsPerBeat and 0xFF).toByte()
            )))
        }

        for (e in events) {
            val bytes = when (e.type) {
                MidiScoreEncoder.MidiEventType.NOTE_ON ->
                    byteArrayOf((0x90 or e.channel).toByte(), e.data1.toByte(), e.data2.toByte())
                MidiScoreEncoder.MidiEventType.NOTE_OFF ->
                    byteArrayOf((0x80 or e.channel).toByte(), e.data1.toByte(), 0)
                MidiScoreEncoder.MidiEventType.PROGRAM_CHANGE ->
                    byteArrayOf((0xC0 or e.channel).toByte(), e.data1.toByte())
                MidiScoreEncoder.MidiEventType.CONTROL_CHANGE ->
                    byteArrayOf((0xB0 or e.channel).toByte(), e.data1.toByte(), e.data2.toByte())
                MidiScoreEncoder.MidiEventType.TEMPO_CHANGE -> continue
            }
            allEvents.add(RawEvent(e.absoluteTick, bytes))
        }

        allEvents.sortBy { it.tick }

        // End of track event
        allEvents.add(RawEvent(allEvents.lastOrNull()?.tick?.plus(1) ?: 0L,
            byteArrayOf(0xFF.toByte(), 0x2F.toByte(), 0x00.toByte())))

        for (event in allEvents) {
            val delta = (event.tick - prevTick).coerceAtLeast(0)
            writeVarLen(buf, delta)
            buf.addAll(event.bytes.toList())
            prevTick = event.tick
        }

        return buf.toByteArray()
    }

    private fun writeVarLen(buf: MutableList<Byte>, value: Long) {
        val bytes = mutableListOf<Byte>()
        var v = value
        bytes.add((v and 0x7F).toByte())
        v = v shr 7
        while (v > 0) {
            bytes.add(((v and 0x7F) or 0x80).toByte())
            v = v shr 7
        }
        bytes.reversed().forEach { buf.add(it) }
    }

    private fun writeInt32(out: OutputStream, value: Int) {
        out.write((value shr 24) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeInt16(out: OutputStream, value: Int) {
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}

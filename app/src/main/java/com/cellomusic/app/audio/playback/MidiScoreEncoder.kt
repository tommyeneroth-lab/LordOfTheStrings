package com.cellomusic.app.audio.playback

import com.cellomusic.app.domain.model.*

/**
 * Converts a Score domain model into a flat list of MIDI events with
 * absolute tick offsets. Handles repeats, dynamics, articulations, and
 * cello-specific techniques (pizzicato program changes, harmonics velocity).
 */
class MidiScoreEncoder {

    companion object {
        const val TICKS_PER_QUARTER = 480
        const val DEFAULT_BPM = 120
        const val CELLO_PROGRAM = 42         // GM: Cello
        const val PIZZICATO_PROGRAM = 45     // GM: Pizzicato Strings
        const val MIDI_CHANNEL = 0
    }

    data class MidiEvent(
        val absoluteTick: Long,
        val type: MidiEventType,
        val channel: Int,
        val data1: Int,    // note number or program
        val data2: Int     // velocity or 0
    )

    enum class MidiEventType {
        NOTE_ON, NOTE_OFF, PROGRAM_CHANGE, CONTROL_CHANGE, TEMPO_CHANGE
    }

    data class TempoEvent(val absoluteTick: Long, val bpm: Int)

    data class EncodeResult(
        val events: List<MidiEvent>,
        val tempoMap: List<TempoEvent>,
        val totalTicks: Long,
        val measureStartTicks: Map<Int, Long>,             // measureNumber -> absoluteTick
        val noteTickToPosition: Map<Long, Pair<Int, Int>>, // tick -> (measureNumber, elementIndex)
        val sortedNoteTicks: List<Long>                    // sorted list of all note-on ticks for binary search
    )

    fun encode(score: Score, tempoMultiplier: Float = 1.0f, transposeSteps: Int = 0): EncodeResult {
        val events = mutableListOf<MidiEvent>()
        val tempoMap = mutableListOf<TempoEvent>()
        val measureStartTicks = mutableMapOf<Int, Long>()

        score.parts.firstOrNull()?.let { part ->
            var absoluteTick = 0L
            var currentBpm = DEFAULT_BPM
            var currentTimeSignature = TimeSignature(4, 4)
            var currentProgram = CELLO_PROGRAM
            var currentDynamicVelocity = DynamicLevel.MF.velocity
            var activeTechnicalState = mutableSetOf<TechnicalMark>()

            // Initial program change
            events.add(MidiEvent(0L, MidiEventType.PROGRAM_CHANGE, MIDI_CHANNEL, CELLO_PROGRAM, 0))
            // CC setup for richer cello tone
            events.add(MidiEvent(0L, MidiEventType.CONTROL_CHANGE, MIDI_CHANNEL, 7, 100))   // channel volume
            events.add(MidiEvent(0L, MidiEventType.CONTROL_CHANGE, MIDI_CHANNEL, 11, 110))  // expression
            events.add(MidiEvent(0L, MidiEventType.CONTROL_CHANGE, MIDI_CHANNEL, 91, 45))   // reverb depth (hall)
            events.add(MidiEvent(0L, MidiEventType.CONTROL_CHANGE, MIDI_CHANNEL, 93, 12))   // chorus (warmth)
            events.add(MidiEvent(0L, MidiEventType.CONTROL_CHANGE, MIDI_CHANNEL, 1,  20))   // modulation (vibrato)

            // Flatten repeats
            val flatMeasures = flattenRepeats(part.measures)

            val noteTickToPositionMap = mutableMapOf<Long, Pair<Int, Int>>()

            for (measure in flatMeasures) {
                measureStartTicks[measure.number] = absoluteTick
                // Record note positions for cursor tracking
                for ((elemIdx, element) in measure.elements.withIndex()) {
                    val elemStartTick = absoluteTick + when (element) {
                        is Note -> element.startTick.toLong()
                        is ChordNote -> element.startTick.toLong()
                        else -> continue
                    }
                    noteTickToPositionMap.putIfAbsent(elemStartTick, Pair(measure.number, elemIdx))
                }

                // Handle clef/time/key changes (no audio effect, tracked for rendering)
                measure.timeSignature?.let { currentTimeSignature = it }
                val ticksPerMeasure = currentTimeSignature.ticksPerMeasure(TICKS_PER_QUARTER)

                // Handle tempo
                measure.tempo?.let { tempoMark ->
                    val adjustedBpm = (tempoMark.bpm * tempoMultiplier).toInt().coerceIn(1, 300)
                    currentBpm = adjustedBpm
                    tempoMap.add(TempoEvent(absoluteTick, adjustedBpm))
                }

                // Handle directions (dynamics, technique words)
                for (dir in measure.directions) {
                    when (dir.type) {
                        DirectionType.DYNAMIC -> {
                            dir.dynamicLevel?.let { currentDynamicVelocity = it.velocity }
                        }
                        DirectionType.WORDS -> {
                            val text = dir.text?.lowercase() ?: ""
                            when {
                                text.contains("pizz") -> {
                                    activeTechnicalState.add(TechnicalMark.PIZZICATO)
                                    activeTechnicalState.remove(TechnicalMark.ARCO)
                                    if (currentProgram != PIZZICATO_PROGRAM) {
                                        currentProgram = PIZZICATO_PROGRAM
                                        events.add(MidiEvent(absoluteTick, MidiEventType.PROGRAM_CHANGE,
                                            MIDI_CHANNEL, PIZZICATO_PROGRAM, 0))
                                    }
                                }
                                text.contains("arco") -> {
                                    activeTechnicalState.remove(TechnicalMark.PIZZICATO)
                                    activeTechnicalState.add(TechnicalMark.ARCO)
                                    if (currentProgram != CELLO_PROGRAM) {
                                        currentProgram = CELLO_PROGRAM
                                        events.add(MidiEvent(absoluteTick, MidiEventType.PROGRAM_CHANGE,
                                            MIDI_CHANNEL, CELLO_PROGRAM, 0))
                                    }
                                }
                            }
                        }
                        else -> {}
                    }
                }

                // Encode notes
                for (element in measure.elements) {
                    when (element) {
                        is Note -> {
                            // Handle technique changes in note
                            for (mark in element.technicalMarks) {
                                when (mark) {
                                    TechnicalMark.PIZZICATO -> {
                                        if (currentProgram != PIZZICATO_PROGRAM) {
                                            currentProgram = PIZZICATO_PROGRAM
                                            events.add(MidiEvent(
                                                absoluteTick + element.startTick,
                                                MidiEventType.PROGRAM_CHANGE,
                                                MIDI_CHANNEL, PIZZICATO_PROGRAM, 0
                                            ))
                                        }
                                    }
                                    TechnicalMark.ARCO -> {
                                        if (currentProgram != CELLO_PROGRAM) {
                                            currentProgram = CELLO_PROGRAM
                                            events.add(MidiEvent(
                                                absoluteTick + element.startTick,
                                                MidiEventType.PROGRAM_CHANGE,
                                                MIDI_CHANNEL, CELLO_PROGRAM, 0
                                            ))
                                        }
                                    }
                                    else -> {}
                                }
                            }

                            // Skip tied notes (continuation)
                            if (element.tie == TieType.STOP || element.tie == TieType.CONTINUE) continue

                            val noteOn = absoluteTick + element.startTick
                            val midiNote = (element.pitch.toMidiNote() + transposeSteps).coerceIn(0, 127)
                            val durationTicks = element.duration.toTicksWithDots(element.dotCount, TICKS_PER_QUARTER)
                            val noteOff = noteOn + calculateNoteOffTick(durationTicks, element)

                            val velocity = calculateVelocity(
                                element, currentDynamicVelocity, activeTechnicalState
                            )

                            if (midiNote in 24..108) { // Extended range with transpose headroom
                                events.add(MidiEvent(noteOn, MidiEventType.NOTE_ON,
                                    MIDI_CHANNEL, midiNote, velocity))
                                events.add(MidiEvent(noteOff, MidiEventType.NOTE_OFF,
                                    MIDI_CHANNEL, midiNote, 0))
                            }
                        }

                        is ChordNote -> {
                            for (note in element.notes) {
                                if (note.tie == TieType.STOP || note.tie == TieType.CONTINUE) continue
                                val noteOn = absoluteTick + element.startTick
                                val midiNote = (note.pitch.toMidiNote() + transposeSteps).coerceIn(0, 127)
                                val durationTicks = element.duration.toTicksWithDots(element.dotCount, TICKS_PER_QUARTER)
                                val noteOff = noteOn + calculateNoteOffTick(durationTicks, note)
                                val velocity = calculateVelocity(note, currentDynamicVelocity, activeTechnicalState)
                                events.add(MidiEvent(noteOn, MidiEventType.NOTE_ON, MIDI_CHANNEL, midiNote, velocity))
                                events.add(MidiEvent(noteOff, MidiEventType.NOTE_OFF, MIDI_CHANNEL, midiNote, 0))
                            }
                        }

                        is Rest -> {} // rests: silence, no MIDI events
                    }
                }

                // Advance by the larger of: the time-signature measure length OR the actual
                // end tick of the last note in the measure.  This prevents notes from being
                // crammed into a shorter window than their durations require (which causes
                // out-of-order playback when OMR over-fills a measure).
                val lastNoteTick = measure.elements.maxOfOrNull { el ->
                    when (el) {
                        is Note -> el.startTick + el.duration.toTicksWithDots(el.dotCount, TICKS_PER_QUARTER)
                        is ChordNote -> el.startTick + el.duration.toTicksWithDots(el.dotCount, TICKS_PER_QUARTER)
                        is Rest -> el.startTick + el.duration.toTicksWithDots(el.dotCount, TICKS_PER_QUARTER)
                        else -> 0
                    }
                } ?: 0
                absoluteTick += maxOf(ticksPerMeasure, lastNoteTick)
            }

            // All notes off at end
            events.add(MidiEvent(absoluteTick, MidiEventType.CONTROL_CHANGE, MIDI_CHANNEL, 123, 0))

            return EncodeResult(
                events = events.sortedBy { it.absoluteTick },
                tempoMap = tempoMap.ifEmpty { listOf(TempoEvent(0L, DEFAULT_BPM)) },
                totalTicks = absoluteTick,
                measureStartTicks = measureStartTicks,
                noteTickToPosition = noteTickToPositionMap,
                sortedNoteTicks = noteTickToPositionMap.keys.sorted()
            )
        }

        return EncodeResult(emptyList(), listOf(TempoEvent(0L, DEFAULT_BPM)), 0L, emptyMap(), emptyMap(), emptyList())
    }

    private fun calculateNoteOffTick(durationTicks: Int, note: Note): Int {
        return when {
            note.articulations.contains(Articulation.STACCATO) -> durationTicks / 2
            note.articulations.contains(Articulation.STACCATISSIMO) -> durationTicks / 4
            note.articulations.contains(Articulation.TENUTO) -> durationTicks
            note.articulations.contains(Articulation.PORTATO) -> (durationTicks * 0.75).toInt()
            else -> (durationTicks * 0.9).toInt()  // slight gap between notes
        }
    }

    private fun calculateVelocity(
        note: Note,
        baseDynamic: Int,
        activeTech: Set<TechnicalMark>
    ): Int {
        var vel = note.dynamicLevel?.velocity ?: baseDynamic

        for (art in note.articulations) {
            vel = when (art) {
                Articulation.ACCENT -> (vel * 1.3f).toInt()
                Articulation.STRONG_ACCENT -> (vel * 1.4f).toInt()
                Articulation.STACCATO -> (vel * 0.9f).toInt()
                else -> vel
            }
        }

        // Harmonics are softer
        if (note.technicalMarks.contains(TechnicalMark.NATURAL_HARMONIC) ||
            note.technicalMarks.contains(TechnicalMark.ARTIFICIAL_HARMONIC)) {
            vel = (vel * 0.7f).toInt()
        }

        // Sul ponticello: brighter/harsher feel - slight velocity boost
        if (note.technicalMarks.contains(TechnicalMark.SUL_PONTICELLO)) {
            vel = (vel * 1.1f).toInt()
        }

        return vel.coerceIn(1, 127)
    }

    /**
     * Flattens measures by expanding repeat structures into a linear sequence.
     */
    private fun flattenRepeats(measures: List<Measure>): List<Measure> {
        val result = mutableListOf<Measure>()
        var repeatStart = 0
        val voltaGroups = mutableListOf<Pair<Int, Int>>() // start/end of each volta

        var i = 0
        while (i < measures.size) {
            val measure = measures[i]
            result.add(measure)

            when (measure.repeatInfo?.type) {
                RepeatType.START -> repeatStart = i
                RepeatType.END -> {
                    // Repeat from repeatStart to current (exclusive of current volta)
                    for (j in repeatStart until i) {
                        val m = measures[j]
                        // Skip volta 2 on first pass (it was already skipped)
                        result.add(m)
                    }
                }
                RepeatType.VOLTA_START -> {
                    // For simplicity, play all voltas in order
                }
                else -> {}
            }
            i++
        }

        return result
    }

    /**
     * Converts absolute ticks to milliseconds using tempo map.
     */
    fun ticksToMs(ticks: Long, tempoMap: List<TempoEvent>): Long {
        if (tempoMap.isEmpty()) return ticks * 60000L / (DEFAULT_BPM * TICKS_PER_QUARTER)

        var elapsedMs = 0L
        var prevTick = 0L
        var prevBpm = DEFAULT_BPM

        for (tempoEvent in tempoMap) {
            if (tempoEvent.absoluteTick >= ticks) break
            val deltaTicks = tempoEvent.absoluteTick - prevTick
            elapsedMs += deltaTicks * 60000L / (prevBpm * TICKS_PER_QUARTER)
            prevTick = tempoEvent.absoluteTick
            prevBpm = tempoEvent.bpm
        }

        val remainingTicks = ticks - prevTick
        elapsedMs += remainingTicks * 60000L / (prevBpm * TICKS_PER_QUARTER)
        return elapsedMs
    }
}

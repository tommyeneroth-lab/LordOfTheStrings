package com.cellomusic.app.domain.scale

import com.cellomusic.app.domain.model.Alter
import com.cellomusic.app.domain.model.Barline
import com.cellomusic.app.domain.model.Clef
import com.cellomusic.app.domain.model.ClefType
import com.cellomusic.app.domain.model.DurationType
import com.cellomusic.app.domain.model.KeyMode
import com.cellomusic.app.domain.model.KeySignature
import com.cellomusic.app.domain.model.Measure
import com.cellomusic.app.domain.model.MusicElement
import com.cellomusic.app.domain.model.Note
import com.cellomusic.app.domain.model.NoteDuration
import com.cellomusic.app.domain.model.Part
import com.cellomusic.app.domain.model.Pitch
import com.cellomusic.app.domain.model.PitchStep
import com.cellomusic.app.domain.model.Rest
import com.cellomusic.app.domain.model.Score
import com.cellomusic.app.domain.model.TempoMark
import com.cellomusic.app.domain.model.TimeSignature

/**
 * Converts a [ScaleDef] into a playable, printable [Score] — bass clef,
 * idiomatic key signature, notes as eighths grouped into 4/4 measures, and
 * a terminal rest to pad the last bar.
 *
 * Why a fixed 120 BPM tempo mark in every generated score?  The trainer
 * drives playback tempo via [com.cellomusic.app.audio.playback.ScorePlayer.setTempoMultiplier],
 * which scales relative to the embedded TempoMark.  Keeping every score at
 * 120 makes `multiplier = userBpm / 120f` the single rule the VM needs.
 */
object ScaleScoreBuilder {

    /** Tempo baked into every generated score; UI overrides via multiplier. */
    private const val BASE_TEMPO = 120

    // 4/4 at ticksPerQuarter=480 → 1920 ticks per measure, 240 per eighth.
    private const val TICKS_PER_QUARTER = 480
    private const val EIGHTH_TICKS = TICKS_PER_QUARTER / 2        // 240
    private const val TICKS_PER_MEASURE = TICKS_PER_QUARTER * 4   // 1920

    fun buildScore(def: ScaleDef): Score {
        val midi = buildMidiPattern(def)
        val fifths = fifthsFor(def)
        val mode = keyModeFor(def)
        // Prefer flat spelling when the key signature has any flats, or when
        // the natural-key's root idiomatically uses flats (e.g. F major).
        val useFlats = fifths < 0 || (fifths == 0 && prefersFlats(def.rootName))

        return Score(
            id = "scale_${def.id}",
            title = def.name,
            composer = "Practice",
            parts = listOf(
                Part(
                    id = "P1",
                    name = "Cello",
                    abbreviation = "Vc.",
                    midiProgram = 42,
                    measures = buildMeasures(midi, fifths, mode, useFlats, def.suggestedBpm)
                )
            )
        )
    }

    // ── Note stream ───────────────────────────────────────────────────────

    /** MIDI sequence: ascending + descending, with the turnaround note once. */
    private fun buildMidiPattern(def: ScaleDef): List<Int> {
        val asc = mutableListOf<Int>()
        for (oct in 0 until def.octaves) {
            for ((idx, semi) in def.type.intervals.withIndex()) {
                // Skip the duplicate root at the start of each subsequent octave
                if (oct > 0 && idx == 0) continue
                asc.add(def.startMidi + oct * 12 + semi)
            }
        }
        // Descending mirrors ascending minus the topmost (avoid double-hitting the peak)
        val desc = asc.dropLast(1).reversed()
        return asc + desc
    }

    // ── Measure packer ────────────────────────────────────────────────────

    private fun buildMeasures(
        midi: List<Int>,
        fifths: Int,
        mode: KeyMode,
        useFlats: Boolean,
        @Suppress("UNUSED_PARAMETER") suggestedBpm: Int  // reserved: for future per-scale tempo override
    ): List<Measure> {
        val measures = mutableListOf<Measure>()
        val total = midi.size
        var idx = 0
        var measureNum = 1

        while (idx < total) {
            val elements = mutableListOf<MusicElement>()
            var tickInMeasure = 0
            while (tickInMeasure < TICKS_PER_MEASURE && idx < total) {
                elements.add(
                    Note(
                        id = "n_${measureNum}_$idx",
                        startTick = tickInMeasure,
                        duration = NoteDuration(DurationType.EIGHTH),
                        pitch = midiToPitch(midi[idx], useFlats)
                    )
                )
                tickInMeasure += EIGHTH_TICKS
                idx++
            }
            // Pad the tail of the final measure with rests so it's metrically complete.
            if (tickInMeasure < TICKS_PER_MEASURE) {
                elements.addAll(
                    restsForRemaining(
                        TICKS_PER_MEASURE - tickInMeasure,
                        tickInMeasure,
                        measureNum
                    )
                )
            }

            val isFirst = measureNum == 1
            val isLast = idx >= total
            measures.add(
                Measure(
                    number = measureNum,
                    clef = if (isFirst) Clef(ClefType.BASS) else null,
                    timeSignature = if (isFirst) TimeSignature(4, 4) else null,
                    keySignature = if (isFirst) KeySignature(fifths, mode) else null,
                    tempo = if (isFirst) TempoMark(BASE_TEMPO) else null,
                    elements = elements,
                    barlineRight = if (isLast) Barline.FINAL else Barline.REGULAR
                )
            )
            measureNum++
        }
        return measures
    }

    /** Greedy HALF → QUARTER → EIGHTH rest fill for the measure tail. */
    private fun restsForRemaining(
        remainingTicks: Int,
        startTick: Int,
        measureNum: Int
    ): List<Rest> {
        val kinds = listOf(
            TICKS_PER_QUARTER * 2 to DurationType.HALF,     // 960
            TICKS_PER_QUARTER to DurationType.QUARTER,      // 480
            EIGHTH_TICKS to DurationType.EIGHTH             // 240
        )
        val out = mutableListOf<Rest>()
        var t = startTick
        var left = remainingTicks
        var i = 0
        while (left > 0) {
            val kind = kinds.firstOrNull { left >= it.first } ?: break
            out.add(
                Rest(
                    id = "r_${measureNum}_$i",
                    startTick = t,
                    duration = NoteDuration(kind.second)
                )
            )
            t += kind.first
            left -= kind.first
            i++
        }
        return out
    }

    // ── Pitch spelling ────────────────────────────────────────────────────

    // Semitone (0..11) → (step, alter), sharp spelling
    private val SHARP_SPELLING = listOf(
        PitchStep.C to Alter.NATURAL,
        PitchStep.C to Alter.SHARP,
        PitchStep.D to Alter.NATURAL,
        PitchStep.D to Alter.SHARP,
        PitchStep.E to Alter.NATURAL,
        PitchStep.F to Alter.NATURAL,
        PitchStep.F to Alter.SHARP,
        PitchStep.G to Alter.NATURAL,
        PitchStep.G to Alter.SHARP,
        PitchStep.A to Alter.NATURAL,
        PitchStep.A to Alter.SHARP,
        PitchStep.B to Alter.NATURAL
    )
    // Semitone (0..11) → (step, alter), flat spelling
    private val FLAT_SPELLING = listOf(
        PitchStep.C to Alter.NATURAL,
        PitchStep.D to Alter.FLAT,
        PitchStep.D to Alter.NATURAL,
        PitchStep.E to Alter.FLAT,
        PitchStep.E to Alter.NATURAL,
        PitchStep.F to Alter.NATURAL,
        PitchStep.G to Alter.FLAT,
        PitchStep.G to Alter.NATURAL,
        PitchStep.A to Alter.FLAT,
        PitchStep.A to Alter.NATURAL,
        PitchStep.B to Alter.FLAT,
        PitchStep.B to Alter.NATURAL
    )

    private fun midiToPitch(midi: Int, useFlats: Boolean): Pitch {
        val pc = ((midi % 12) + 12) % 12
        val octave = midi / 12 - 1   // MIDI 60 = C4
        val (step, alter) = if (useFlats) FLAT_SPELLING[pc] else SHARP_SPELLING[pc]
        return Pitch(step, octave, alter)
    }

    // ── Key signature selection ───────────────────────────────────────────

    /** Circle-of-fifths value for a major-key root (pitch class 0..11). */
    private fun fifthsForMajor(pc: Int): Int = when (((pc % 12) + 12) % 12) {
        0 -> 0            // C
        1 -> -5           // Db
        2 -> 2            // D
        3 -> -3           // Eb
        4 -> 4            // E
        5 -> -1           // F
        6 -> -6           // Gb (prefer flats over 6 sharps)
        7 -> 1            // G
        8 -> -4           // Ab
        9 -> 3            // A
        10 -> -2          // Bb
        11 -> 5           // B
        else -> 0
    }

    /**
     * Map a scale type + root to an idiomatic key signature.  Modes borrow
     * the signature of their parent major (Dorian on D uses C major, etc.);
     * symmetric/chromatic scales default to no accidentals in the signature
     * and spell every note explicitly.
     */
    private fun fifthsFor(def: ScaleDef): Int {
        val pc = def.rootPitchClass
        return when (def.type) {
            ScaleType.MAJOR,
            ScaleType.MAJOR_ARPEGGIO,
            ScaleType.MAJOR_7,
            ScaleType.PENTATONIC_MAJOR -> fifthsForMajor(pc)

            ScaleType.NATURAL_MINOR,
            ScaleType.HARMONIC_MINOR,
            ScaleType.MELODIC_MINOR_ASC,
            ScaleType.MINOR_ARPEGGIO,
            ScaleType.MINOR_7,
            ScaleType.PENTATONIC_MINOR,
            ScaleType.BLUES -> fifthsForMajor((pc + 3) % 12)

            ScaleType.DORIAN -> fifthsForMajor((pc + 10) % 12)
            ScaleType.PHRYGIAN -> fifthsForMajor((pc + 8) % 12)
            ScaleType.LYDIAN -> fifthsForMajor((pc + 7) % 12)
            ScaleType.MIXOLYDIAN -> fifthsForMajor((pc + 5) % 12)
            ScaleType.LOCRIAN -> fifthsForMajor((pc + 1) % 12)

            // V7 of key X → sits in the key whose V it is (root + 5 semitones = IV).
            // Close enough for a practice engraving; the accidental renders
            // either way if it's not in the signature.
            ScaleType.DOMINANT_7 -> fifthsForMajor((pc + 5) % 12)

            ScaleType.DIMINISHED_7,
            ScaleType.AUGMENTED,
            ScaleType.CHROMATIC,
            ScaleType.WHOLE_TONE -> 0
        }
    }

    private fun keyModeFor(def: ScaleDef): KeyMode = when (def.type.category) {
        ScaleCategory.MINOR -> KeyMode.MINOR
        else -> KeyMode.MAJOR
    }
}

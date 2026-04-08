package com.cellomusic.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Score(
    val id: String,
    val title: String,
    val composer: String? = null,
    val arranger: String? = null,
    val copyright: String? = null,
    val workNumber: String? = null,
    val parts: List<Part>,
    val globalTempos: List<TempoMark> = emptyList(),
    val credits: List<String> = emptyList()
)

@Serializable
data class Part(
    val id: String,
    val name: String = "Cello",
    val abbreviation: String = "Vc.",
    val midiProgram: Int = 42,
    val midiChannel: Int = 0,
    val measures: List<Measure>
)

@Serializable
data class Measure(
    val number: Int,
    val timeSignature: TimeSignature? = null,
    val keySignature: KeySignature? = null,
    val clef: Clef? = null,
    val tempo: TempoMark? = null,
    val elements: List<MusicElement>,
    val directions: List<Direction> = emptyList(),
    val barlineLeft: Barline = Barline.REGULAR,
    val barlineRight: Barline = Barline.REGULAR,
    val repeatInfo: RepeatInfo? = null,
    val width: Float? = null
)

@Serializable
sealed class MusicElement {
    abstract val id: String
    abstract val startTick: Int
    abstract val duration: NoteDuration
    abstract val dotCount: Int
}

@Serializable
data class Note(
    override val id: String,
    override val startTick: Int,
    override val duration: NoteDuration,
    override val dotCount: Int = 0,
    val pitch: Pitch,
    val tie: TieType = TieType.NONE,
    val articulations: List<Articulation> = emptyList(),
    val bowingMark: BowingMark? = null,
    val fingering: Fingering? = null,
    val ornament: Ornament? = null,
    val dynamicLevel: DynamicLevel? = null,
    val beamGroup: Int? = null,
    val slurIds: List<String> = emptyList(),
    val technicalMarks: List<TechnicalMark> = emptyList(),
    val graceNotes: List<GraceNote> = emptyList(),
    val stemDirection: StemDirection = StemDirection.AUTO,
    val staff: Int = 1,
    val voice: Int = 1
) : MusicElement()

@Serializable
data class ChordNote(
    override val id: String,
    override val startTick: Int,
    override val duration: NoteDuration,
    override val dotCount: Int = 0,
    val notes: List<Note>,
    val articulations: List<Articulation> = emptyList(),
    val bowingMark: BowingMark? = null
) : MusicElement()

@Serializable
data class Rest(
    override val id: String,
    override val startTick: Int,
    override val duration: NoteDuration,
    override val dotCount: Int = 0,
    val isFullMeasure: Boolean = false,
    val staff: Int = 1,
    val voice: Int = 1
) : MusicElement()

@Serializable
data class Pitch(
    val step: PitchStep,
    val octave: Int,
    val alter: Alter = Alter.NATURAL,
    val displayAccidental: AccidentalDisplay = AccidentalDisplay.AUTO
) {
    fun toMidiNote(): Int {
        val base = when (step) {
            PitchStep.C -> 0; PitchStep.D -> 2; PitchStep.E -> 4; PitchStep.F -> 5
            PitchStep.G -> 7; PitchStep.A -> 9; PitchStep.B -> 11
        }
        return (octave + 1) * 12 + base + alter.semitones.toInt()
    }

    fun toFrequency(): Double {
        val midi = toMidiNote()
        return 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
    }
}

@Serializable
data class NoteDuration(
    val type: DurationType,
    val actualNotes: Int = 1,
    val normalNotes: Int = 1,
    val normalType: DurationType? = null
) {
    fun toTicks(ticksPerQuarter: Int = 480): Int {
        val baseTicks = when (type) {
            DurationType.MAXIMA -> ticksPerQuarter * 32
            DurationType.LONG -> ticksPerQuarter * 16
            DurationType.BREVE -> ticksPerQuarter * 8
            DurationType.WHOLE -> ticksPerQuarter * 4
            DurationType.HALF -> ticksPerQuarter * 2
            DurationType.QUARTER -> ticksPerQuarter
            DurationType.EIGHTH -> ticksPerQuarter / 2
            DurationType.SIXTEENTH -> ticksPerQuarter / 4
            DurationType.THIRTY_SECOND -> ticksPerQuarter / 8
            DurationType.SIXTY_FOURTH -> ticksPerQuarter / 16
            DurationType.HUNDRED_TWENTY_EIGHTH -> ticksPerQuarter / 32
        }
        return if (actualNotes != normalNotes) (baseTicks * normalNotes / actualNotes) else baseTicks
    }

    fun toTicksWithDots(dots: Int, ticksPerQuarter: Int = 480): Int {
        val base = toTicks(ticksPerQuarter)
        return when (dots) {
            0 -> base
            1 -> base + base / 2
            2 -> base + base / 2 + base / 4
            else -> base
        }
    }
}

@Serializable
data class TimeSignature(
    val numerator: Int,
    val denominator: Int,
    val isCutTime: Boolean = false,
    val isCommonTime: Boolean = false
) {
    fun ticksPerMeasure(ticksPerQuarter: Int = 480): Int {
        val quarterEquivalent = 4.0 / denominator
        return (ticksPerQuarter * numerator * quarterEquivalent).toInt()
    }
}

@Serializable
data class KeySignature(
    val fifths: Int,
    val mode: KeyMode = KeyMode.MAJOR
)

@Serializable
data class Clef(
    val type: ClefType,
    val line: Int? = null,
    val octaveChange: Int = 0
)

@Serializable
data class TempoMark(
    val bpm: Int,
    val beatUnit: DurationType = DurationType.QUARTER,
    val textInstruction: String? = null,
    val measure: Int = 1,
    val tick: Int = 0
)

@Serializable
data class Fingering(
    val finger: Int,
    val isThumbPosition: Boolean = false,
    val stringNumber: Int? = null,
    /** Semitones above open string for this hand position.  0 = 1st position. */
    val positionShift: Int = 0
)

@Serializable
data class GraceNote(
    val pitch: Pitch,
    val duration: NoteDuration,
    val slashed: Boolean = true
)

@Serializable
data class Direction(
    val measure: Int,
    val tick: Int,
    val type: DirectionType,
    val text: String? = null,
    val dynamicLevel: DynamicLevel? = null,
    val hairpinType: HairpinType? = null,
    val hairpinEndMeasure: Int? = null,
    val hairpinEndTick: Int? = null,
    val rehearsalMark: String? = null,
    val tempoMark: TempoMark? = null,
    val placement: Placement = Placement.BELOW
)

@Serializable
data class RepeatInfo(
    val type: RepeatType,
    val times: Int = 2,
    val voltaNumber: Int? = null,
    val voltaEndMeasure: Int? = null
)

@Serializable
data class Slur(
    val id: String,
    val type: SlurType,
    val startMeasure: Int,
    val startNoteId: String,
    val endMeasure: Int,
    val endNoteId: String,
    val placement: Placement = Placement.AUTO
)

@Serializable
enum class PitchStep { C, D, E, F, G, A, B }

@Serializable
enum class Alter(val semitones: Float) {
    DOUBLE_FLAT(-2f), FLAT(-1f), NATURAL(0f), SHARP(1f), DOUBLE_SHARP(2f)
}

@Serializable
enum class AccidentalDisplay { AUTO, ALWAYS, NONE }

@Serializable
enum class DurationType {
    MAXIMA, LONG, BREVE, WHOLE, HALF, QUARTER, EIGHTH,
    SIXTEENTH, THIRTY_SECOND, SIXTY_FOURTH, HUNDRED_TWENTY_EIGHTH
}

@Serializable
enum class TieType { NONE, START, STOP, CONTINUE }

@Serializable
enum class StemDirection { UP, DOWN, AUTO }

@Serializable
enum class Barline {
    REGULAR, DOUBLE, FINAL, REPEAT_START, REPEAT_END, REPEAT_BOTH, DASHED, NONE
}

@Serializable
enum class ClefType { BASS, TENOR, TREBLE, ALTO, PERCUSSION }

@Serializable
enum class KeyMode { MAJOR, MINOR }

@Serializable
enum class DirectionType {
    DYNAMIC, HAIRPIN, TEXT, REHEARSAL_MARK, TEMPO, METRONOME, WORDS, PEDAL
}

@Serializable
enum class HairpinType { CRESCENDO, DECRESCENDO }

@Serializable
enum class SlurType { SLUR, TIE }

@Serializable
enum class Placement { ABOVE, BELOW, AUTO }

@Serializable
enum class RepeatType {
    START, END, START_END, SEGNO, CODA, DA_CAPO, DAL_SEGNO,
    DAL_SEGNO_AL_CODA, DA_CAPO_AL_FINE, FINE, VOLTA_START, VOLTA_END
}

@Serializable
enum class Articulation {
    STACCATO, STACCATISSIMO, TENUTO, ACCENT, STRONG_ACCENT,
    FERMATA, FERMATA_SHORT, FERMATA_LONG, BREATH_MARK, CAESURA,
    TREMOLO_1, TREMOLO_2, TREMOLO_3, BOWING_TREMOLO, PORTATO
}

@Serializable
enum class BowingMark {
    UP_BOW, DOWN_BOW, UP_BOW_STACCATO, DOWN_BOW_STACCATO
}

@Serializable
enum class TechnicalMark {
    PIZZICATO, ARCO, COL_LEGNO_TRATTO, COL_LEGNO_BATTUTO,
    SUL_PONTICELLO, SUL_TASTO, NATURAL_HARMONIC, ARTIFICIAL_HARMONIC,
    HALF_HARMONIC, THUMB_POSITION,
    STRING_I, STRING_II, STRING_III, STRING_IV,
    POSITION_I, POSITION_II, POSITION_III, POSITION_IV,
    POSITION_V, POSITION_VI, POSITION_VII,
    JETE, FLAUTANDO, EXTENDED_BOW_PRESSURE, SNAP_PIZZICATO
}

@Serializable
sealed class Ornament {
    @Serializable
    data class Trill(val halfStep: Boolean = false, val hasTurn: Boolean = false) : Ornament()
    @Serializable
    data class Turn(val inverted: Boolean = false) : Ornament()
    @Serializable
    data class Mordent(val inverted: Boolean = false) : Ornament()
    @Serializable
    object Glissando : Ornament()
    @Serializable
    object Portamento : Ornament()
}

@Serializable
enum class DynamicLevel(val velocity: Int, val symbol: String) {
    PPPP(8, "pppp"), PPP(16, "ppp"), PP(28, "pp"), P(42, "p"), MP(56, "mp"),
    MF(71, "mf"), F(85, "f"), FF(99, "ff"), FFF(112, "fff"), FFFF(127, "ffff"),
    SF(95, "sf"), SFZ(98, "sfz"), RFZ(92, "rfz"), FP(85, "fp"), PF(60, "pf")
}

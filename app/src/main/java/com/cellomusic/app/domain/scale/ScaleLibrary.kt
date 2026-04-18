package com.cellomusic.app.domain.scale

/**
 * The app's library of practice scales and arpeggios.
 *
 * Everything is generated procedurally from [ScaleType] interval patterns —
 * we don't want to hand-write 250+ scales and keep them in sync. [ScaleDef]
 * is the "row" a cellist picks in the trainer; [ScaleLibrary] is the catalog
 * and also owns a few helpers for grouping + filtering.
 *
 * Notes are computed lazily and expressed in scientific pitch notation
 * (C2 is the cello's low-C open string, C4 is middle C).
 *
 * ── Cello range anchor ─────────────────────────────────────────────────
 * Open strings: C2 (36), G2 (43), D3 (50), A3 (57). Practical upper limit
 * for most students is around C6. Starting pitches are chosen so scales sit
 * comfortably on the fingerboard rather than being transposed to identical
 * octaves across keys — e.g. D major starts at D2, not D3.
 */

/** Tier the scale by where a cellist will encounter it in their arc. */
enum class ScaleDifficulty(val label: String) {
    BEGINNER("Beginner"),
    INTERMEDIATE("Intermediate"),
    ADVANCED("Advanced")
}

/** Grouping used in the trainer's picker UI. */
enum class ScaleCategory(val label: String) {
    MAJOR("Majors"),
    MINOR("Minors"),
    MODE("Modes"),
    ARPEGGIO("Arpeggios"),
    PENTATONIC("Pentatonic"),
    BLUES("Blues"),
    EXOTIC("Exotic");
}

/**
 * Interval pattern for a scale or arpeggio type, expressed as semitones
 * from the root (ascending, including the octave).
 *
 * We use a single [intervals] list for both scales and arpeggios — the only
 * difference for the trainer is "how many notes to play on the way up".
 */
enum class ScaleType(
    val displayName: String,
    val intervals: List<Int>,
    val category: ScaleCategory,
    val description: String,
    val baseDifficulty: ScaleDifficulty = ScaleDifficulty.INTERMEDIATE
) {
    MAJOR("Major",
        listOf(0, 2, 4, 5, 7, 9, 11, 12),
        ScaleCategory.MAJOR,
        "The bright, familiar 'do re mi' scale. Daily bread for every cellist.",
        ScaleDifficulty.BEGINNER),

    NATURAL_MINOR("Natural Minor",
        listOf(0, 2, 3, 5, 7, 8, 10, 12),
        ScaleCategory.MINOR,
        "Somber, introspective — the relative minor of a major key.",
        ScaleDifficulty.BEGINNER),

    HARMONIC_MINOR("Harmonic Minor",
        listOf(0, 2, 3, 5, 7, 8, 11, 12),
        ScaleCategory.MINOR,
        "Raised 7th gives a tense, exotic leading-tone pull.",
        ScaleDifficulty.INTERMEDIATE),

    MELODIC_MINOR_ASC("Melodic Minor (ascending)",
        listOf(0, 2, 3, 5, 7, 9, 11, 12),
        ScaleCategory.MINOR,
        "Natural minor with raised 6th and 7th. Smooth upward line.",
        ScaleDifficulty.INTERMEDIATE),

    DORIAN("Dorian Mode",
        listOf(0, 2, 3, 5, 7, 9, 10, 12),
        ScaleCategory.MODE,
        "Minor feel with a raised 6th — jazzy, folk, 'Scarborough Fair'.",
        ScaleDifficulty.INTERMEDIATE),

    PHRYGIAN("Phrygian Mode",
        listOf(0, 1, 3, 5, 7, 8, 10, 12),
        ScaleCategory.MODE,
        "Minor with flat 2nd. Spanish / flamenco colour.",
        ScaleDifficulty.INTERMEDIATE),

    LYDIAN("Lydian Mode",
        listOf(0, 2, 4, 6, 7, 9, 11, 12),
        ScaleCategory.MODE,
        "Major with a raised 4th. Floating, dreamlike.",
        ScaleDifficulty.INTERMEDIATE),

    MIXOLYDIAN("Mixolydian Mode",
        listOf(0, 2, 4, 5, 7, 9, 10, 12),
        ScaleCategory.MODE,
        "Major with flat 7th. Bluesy, rock, Celtic.",
        ScaleDifficulty.INTERMEDIATE),

    LOCRIAN("Locrian Mode",
        listOf(0, 1, 3, 5, 6, 8, 10, 12),
        ScaleCategory.MODE,
        "Rare, unstable mode. Great for ear training.",
        ScaleDifficulty.ADVANCED),

    // ── Pentatonic / Blues / Exotic ───────────────────────────────────

    PENTATONIC_MAJOR("Major Pentatonic",
        listOf(0, 2, 4, 7, 9, 12),
        ScaleCategory.PENTATONIC,
        "5-note major. Folk, Eastern, simple beauty.",
        ScaleDifficulty.BEGINNER),

    PENTATONIC_MINOR("Minor Pentatonic",
        listOf(0, 3, 5, 7, 10, 12),
        ScaleCategory.PENTATONIC,
        "5-note minor. Foundation of blues and rock.",
        ScaleDifficulty.BEGINNER),

    BLUES("Blues",
        listOf(0, 3, 5, 6, 7, 10, 12),
        ScaleCategory.BLUES,
        "Minor pentatonic + ♭5 blue note. 12-bar staple.",
        ScaleDifficulty.INTERMEDIATE),

    CHROMATIC("Chromatic",
        listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12),
        ScaleCategory.EXOTIC,
        "All 12 pitches. Essential for finger independence and shifting.",
        ScaleDifficulty.ADVANCED),

    WHOLE_TONE("Whole Tone",
        listOf(0, 2, 4, 6, 8, 10, 12),
        ScaleCategory.EXOTIC,
        "Equal whole-step steps. Debussy's atmospheric trademark.",
        ScaleDifficulty.ADVANCED),

    // ── Arpeggios ─────────────────────────────────────────────────────

    MAJOR_ARPEGGIO("Major Arpeggio",
        listOf(0, 4, 7, 12),
        ScaleCategory.ARPEGGIO,
        "Root, major 3rd, 5th, octave. The most common chord.",
        ScaleDifficulty.BEGINNER),

    MINOR_ARPEGGIO("Minor Arpeggio",
        listOf(0, 3, 7, 12),
        ScaleCategory.ARPEGGIO,
        "Root, minor 3rd, 5th, octave.",
        ScaleDifficulty.BEGINNER),

    DOMINANT_7("Dominant 7th Arpeggio",
        listOf(0, 4, 7, 10, 12),
        ScaleCategory.ARPEGGIO,
        "V7 — drives resolution to the tonic.",
        ScaleDifficulty.INTERMEDIATE),

    MAJOR_7("Major 7th Arpeggio",
        listOf(0, 4, 7, 11, 12),
        ScaleCategory.ARPEGGIO,
        "Lush, jazz-flavoured major chord.",
        ScaleDifficulty.INTERMEDIATE),

    MINOR_7("Minor 7th Arpeggio",
        listOf(0, 3, 7, 10, 12),
        ScaleCategory.ARPEGGIO,
        "Core jazz minor sound.",
        ScaleDifficulty.INTERMEDIATE),

    DIMINISHED_7("Diminished 7th Arpeggio",
        listOf(0, 3, 6, 9, 12),
        ScaleCategory.ARPEGGIO,
        "Stacked minor 3rds. Tense, symmetric.",
        ScaleDifficulty.ADVANCED),

    AUGMENTED("Augmented Triad Arpeggio",
        listOf(0, 4, 8, 12),
        ScaleCategory.ARPEGGIO,
        "Stacked major 3rds. Dreamy, unresolved.",
        ScaleDifficulty.ADVANCED)
}

/**
 * A concrete practice item — e.g. "C Major (2 oct)" starting on C2 at 80 BPM.
 * Generated by [ScaleLibrary] from a [ScaleType] + pitch-class + starting
 * octave + octave span. Notes are expanded lazily to keep the catalog cheap.
 */
data class ScaleDef(
    val id: String,
    val name: String,
    val category: ScaleCategory,
    val type: ScaleType,
    val rootPitchClass: Int,       // 0..11, 0 = C
    val startMidi: Int,            // starting note as MIDI number
    val octaves: Int,              // 1..3
    val suggestedBpm: Int,
    val difficulty: ScaleDifficulty
) {
    val rootName: String get() = pitchClassName(rootPitchClass)

    /** Ascending note sequence in scientific pitch notation. */
    val ascendingNotes: List<String> by lazy { buildAscending() }

    /**
     * Formatted note stream for the drill view — ascending then descending,
     * written with sharps/flats implied by the root's convention.
     */
    val fullPattern: List<String> by lazy {
        val up = ascendingNotes
        // Descending = ascending reversed with the topmost note dropped
        // (so we don't play the top note twice in a row).
        val down = up.dropLast(1).reversed()
        up + down
    }

    private fun buildAscending(): List<String> {
        val notes = mutableListOf<String>()
        val usesFlats = prefersFlats(rootName)
        for (oct in 0 until octaves) {
            for ((idx, semi) in type.intervals.withIndex()) {
                // Skip the duplicate root at the start of each subsequent octave
                if (oct > 0 && idx == 0) continue
                val midi = startMidi + oct * 12 + semi
                notes.add(midiToName(midi, usesFlats))
            }
        }
        return notes
    }
}

object ScaleLibrary {

    /** All scales generated for the cello practice trainer. */
    val ALL: List<ScaleDef> = buildCatalog()

    fun byId(id: String): ScaleDef? = ALL.firstOrNull { it.id == id }

    fun byCategory(cat: ScaleCategory): List<ScaleDef> =
        ALL.filter { it.category == cat }

    fun categories(): List<ScaleCategory> = ScaleCategory.values().toList()

    // ── Catalog construction ───────────────────────────────────────────

    private fun buildCatalog(): List<ScaleDef> {
        val list = mutableListOf<ScaleDef>()

        // Twelve chromatic roots. We use the common display name — flats or
        // sharps — that matches the most idiomatic key signature.
        val roots = listOf(
            0 to "C", 1 to "Db", 2 to "D", 3 to "Eb", 4 to "E", 5 to "F",
            6 to "Gb", 7 to "G", 8 to "Ab", 9 to "A", 10 to "Bb", 11 to "B"
        )

        // Tempo targets by category — what a teacher would assign a student
        // as a starting metronome mark. The trainer lets the user override.
        fun defaultBpm(cat: ScaleCategory, octaves: Int) = when (cat) {
            ScaleCategory.MAJOR, ScaleCategory.MINOR -> if (octaves >= 3) 60 else 80
            ScaleCategory.MODE -> 70
            ScaleCategory.PENTATONIC -> 90
            ScaleCategory.BLUES -> 80
            ScaleCategory.ARPEGGIO -> if (octaves >= 3) 80 else 90
            ScaleCategory.EXOTIC -> 60
        }

        // ── 2-octave Major scales (all 12 keys) ──
        for ((pc, name) in roots) {
            val startMidi = comfortableStartMidi(pc, octaves = 2)
            list.add(ScaleDef(
                id = "maj_${name}_2",
                name = "$name Major (2 oct)",
                category = ScaleCategory.MAJOR,
                type = ScaleType.MAJOR,
                rootPitchClass = pc,
                startMidi = startMidi,
                octaves = 2,
                suggestedBpm = defaultBpm(ScaleCategory.MAJOR, 2),
                difficulty = ScaleDifficulty.BEGINNER
            ))
        }

        // ── 2-octave Natural Minor (all 12 keys) ──
        for ((pc, name) in roots) {
            list.add(ScaleDef(
                id = "nat_min_${name}_2",
                name = "$name Natural Minor (2 oct)",
                category = ScaleCategory.MINOR,
                type = ScaleType.NATURAL_MINOR,
                rootPitchClass = pc,
                startMidi = comfortableStartMidi(pc, 2),
                octaves = 2,
                suggestedBpm = defaultBpm(ScaleCategory.MINOR, 2),
                difficulty = ScaleDifficulty.BEGINNER
            ))
        }

        // ── 2-octave Harmonic Minor (all 12) ──
        for ((pc, name) in roots) {
            list.add(ScaleDef(
                id = "har_min_${name}_2",
                name = "$name Harmonic Minor (2 oct)",
                category = ScaleCategory.MINOR,
                type = ScaleType.HARMONIC_MINOR,
                rootPitchClass = pc,
                startMidi = comfortableStartMidi(pc, 2),
                octaves = 2,
                suggestedBpm = defaultBpm(ScaleCategory.MINOR, 2),
                difficulty = ScaleDifficulty.INTERMEDIATE
            ))
        }

        // ── 2-octave Melodic Minor ascending (all 12) ──
        for ((pc, name) in roots) {
            list.add(ScaleDef(
                id = "mel_min_${name}_2",
                name = "$name Melodic Minor (2 oct, ascending form)",
                category = ScaleCategory.MINOR,
                type = ScaleType.MELODIC_MINOR_ASC,
                rootPitchClass = pc,
                startMidi = comfortableStartMidi(pc, 2),
                octaves = 2,
                suggestedBpm = defaultBpm(ScaleCategory.MINOR, 2),
                difficulty = ScaleDifficulty.INTERMEDIATE
            ))
        }

        // ── 3-octave scales for common cello-friendly keys ──
        val threeOctKeys = listOf(0 to "C", 2 to "D", 3 to "Eb", 5 to "F", 7 to "G", 9 to "A", 10 to "Bb")
        for ((pc, name) in threeOctKeys) {
            list.add(ScaleDef(
                id = "maj_${name}_3",
                name = "$name Major (3 oct)",
                category = ScaleCategory.MAJOR,
                type = ScaleType.MAJOR,
                rootPitchClass = pc,
                startMidi = comfortableStartMidi(pc, 3),
                octaves = 3,
                suggestedBpm = defaultBpm(ScaleCategory.MAJOR, 3),
                difficulty = ScaleDifficulty.ADVANCED
            ))
            list.add(ScaleDef(
                id = "har_min_${name}_3",
                name = "$name Harmonic Minor (3 oct)",
                category = ScaleCategory.MINOR,
                type = ScaleType.HARMONIC_MINOR,
                rootPitchClass = pc,
                startMidi = comfortableStartMidi(pc, 3),
                octaves = 3,
                suggestedBpm = defaultBpm(ScaleCategory.MINOR, 3),
                difficulty = ScaleDifficulty.ADVANCED
            ))
            list.add(ScaleDef(
                id = "mel_min_${name}_3",
                name = "$name Melodic Minor (3 oct)",
                category = ScaleCategory.MINOR,
                type = ScaleType.MELODIC_MINOR_ASC,
                rootPitchClass = pc,
                startMidi = comfortableStartMidi(pc, 3),
                octaves = 3,
                suggestedBpm = defaultBpm(ScaleCategory.MINOR, 3),
                difficulty = ScaleDifficulty.ADVANCED
            ))
        }

        // ── Modes (12 keys × 5 modes = 60) ──
        val modeTypes = listOf(ScaleType.DORIAN, ScaleType.PHRYGIAN,
            ScaleType.LYDIAN, ScaleType.MIXOLYDIAN, ScaleType.LOCRIAN)
        for (mode in modeTypes) {
            for ((pc, name) in roots) {
                list.add(ScaleDef(
                    id = "${mode.name.lowercase()}_${name}_2",
                    name = "$name ${mode.displayName} (2 oct)",
                    category = ScaleCategory.MODE,
                    type = mode,
                    rootPitchClass = pc,
                    startMidi = comfortableStartMidi(pc, 2),
                    octaves = 2,
                    suggestedBpm = defaultBpm(ScaleCategory.MODE, 2),
                    difficulty = mode.baseDifficulty
                ))
            }
        }

        // ── Pentatonics (12 keys × 2 types = 24) ──
        for (penta in listOf(ScaleType.PENTATONIC_MAJOR, ScaleType.PENTATONIC_MINOR)) {
            for ((pc, name) in roots) {
                list.add(ScaleDef(
                    id = "${penta.name.lowercase()}_${name}_2",
                    name = "$name ${penta.displayName} (2 oct)",
                    category = ScaleCategory.PENTATONIC,
                    type = penta,
                    rootPitchClass = pc,
                    startMidi = comfortableStartMidi(pc, 2),
                    octaves = 2,
                    suggestedBpm = defaultBpm(ScaleCategory.PENTATONIC, 2),
                    difficulty = ScaleDifficulty.BEGINNER
                ))
            }
        }

        // ── Blues (12 keys) ──
        for ((pc, name) in roots) {
            list.add(ScaleDef(
                id = "blues_${name}_2",
                name = "$name Blues (2 oct)",
                category = ScaleCategory.BLUES,
                type = ScaleType.BLUES,
                rootPitchClass = pc,
                startMidi = comfortableStartMidi(pc, 2),
                octaves = 2,
                suggestedBpm = defaultBpm(ScaleCategory.BLUES, 2),
                difficulty = ScaleDifficulty.INTERMEDIATE
            ))
        }

        // ── Chromatic (4 starting notes — one per open string) ──
        val chromaticStarts = listOf(0 to "C", 7 to "G", 2 to "D", 9 to "A")
        for ((pc, name) in chromaticStarts) {
            list.add(ScaleDef(
                id = "chrom_${name}",
                name = "$name Chromatic (1 oct)",
                category = ScaleCategory.EXOTIC,
                type = ScaleType.CHROMATIC,
                rootPitchClass = pc,
                startMidi = comfortableStartMidi(pc, 1),
                octaves = 1,
                suggestedBpm = 60,
                difficulty = ScaleDifficulty.ADVANCED
            ))
            list.add(ScaleDef(
                id = "chrom_${name}_2",
                name = "$name Chromatic (2 oct)",
                category = ScaleCategory.EXOTIC,
                type = ScaleType.CHROMATIC,
                rootPitchClass = pc,
                startMidi = comfortableStartMidi(pc, 2),
                octaves = 2,
                suggestedBpm = 50,
                difficulty = ScaleDifficulty.ADVANCED
            ))
        }

        // ── Whole tone (there are only two distinct transpositions) ──
        list.add(ScaleDef(
            id = "whole_tone_C",
            name = "C Whole Tone (2 oct)",
            category = ScaleCategory.EXOTIC,
            type = ScaleType.WHOLE_TONE,
            rootPitchClass = 0,
            startMidi = 36,
            octaves = 2,
            suggestedBpm = 60,
            difficulty = ScaleDifficulty.ADVANCED
        ))
        list.add(ScaleDef(
            id = "whole_tone_Db",
            name = "D♭ Whole Tone (2 oct)",
            category = ScaleCategory.EXOTIC,
            type = ScaleType.WHOLE_TONE,
            rootPitchClass = 1,
            startMidi = 37,
            octaves = 2,
            suggestedBpm = 60,
            difficulty = ScaleDifficulty.ADVANCED
        ))

        // ── Arpeggios: 2 octaves, all 12 keys × 7 types = 84 ──
        val arpeggios = listOf(
            ScaleType.MAJOR_ARPEGGIO to ScaleDifficulty.BEGINNER,
            ScaleType.MINOR_ARPEGGIO to ScaleDifficulty.BEGINNER,
            ScaleType.DOMINANT_7 to ScaleDifficulty.INTERMEDIATE,
            ScaleType.MAJOR_7 to ScaleDifficulty.INTERMEDIATE,
            ScaleType.MINOR_7 to ScaleDifficulty.INTERMEDIATE,
            ScaleType.DIMINISHED_7 to ScaleDifficulty.ADVANCED,
            ScaleType.AUGMENTED to ScaleDifficulty.ADVANCED
        )
        for ((arpType, diff) in arpeggios) {
            for ((pc, name) in roots) {
                list.add(ScaleDef(
                    id = "${arpType.name.lowercase()}_${name}_2",
                    name = "$name ${arpType.displayName} (2 oct)",
                    category = ScaleCategory.ARPEGGIO,
                    type = arpType,
                    rootPitchClass = pc,
                    startMidi = comfortableStartMidi(pc, 2),
                    octaves = 2,
                    suggestedBpm = defaultBpm(ScaleCategory.ARPEGGIO, 2),
                    difficulty = diff
                ))
            }
        }

        // ── 3-octave major / minor arpeggios in friendly keys ──
        for ((pc, name) in threeOctKeys) {
            list.add(ScaleDef(
                id = "major_arpeggio_${name}_3",
                name = "$name Major Arpeggio (3 oct)",
                category = ScaleCategory.ARPEGGIO,
                type = ScaleType.MAJOR_ARPEGGIO,
                rootPitchClass = pc,
                startMidi = comfortableStartMidi(pc, 3),
                octaves = 3,
                suggestedBpm = defaultBpm(ScaleCategory.ARPEGGIO, 3),
                difficulty = ScaleDifficulty.ADVANCED
            ))
            list.add(ScaleDef(
                id = "minor_arpeggio_${name}_3",
                name = "$name Minor Arpeggio (3 oct)",
                category = ScaleCategory.ARPEGGIO,
                type = ScaleType.MINOR_ARPEGGIO,
                rootPitchClass = pc,
                startMidi = comfortableStartMidi(pc, 3),
                octaves = 3,
                suggestedBpm = defaultBpm(ScaleCategory.ARPEGGIO, 3),
                difficulty = ScaleDifficulty.ADVANCED
            ))
        }

        return list
    }

    /**
     * Pick a starting MIDI note that keeps the scale on the fingerboard.
     * 2-octave scales start just above the open C string (C2..B2 → MIDI 36..47).
     * 3-octave scales still start in the same zone; the top of a 3-octave C is
     * C5 (MIDI 72), comfortably below the highest usable thumb-position notes.
     */
    private fun comfortableStartMidi(pitchClass: Int, octaves: Int): Int {
        // MIDI 36 = C2 (open C string).
        // For every pitch class, start at the lowest available copy on the
        // fingerboard. All open-string roots land on MIDI 36..47 this way.
        return 36 + pitchClass
    }
}

// ── Note-name helpers ─────────────────────────────────────────────────

private val SHARP_NAMES = listOf(
    "C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"
)
private val FLAT_NAMES = listOf(
    "C", "D♭", "D", "E♭", "E", "F", "G♭", "G", "A♭", "A", "B♭", "B"
)

/** Convert a MIDI note to its scientific-pitch display name. */
internal fun midiToName(midi: Int, useFlats: Boolean = false): String {
    val pc = ((midi % 12) + 12) % 12
    val octave = midi / 12 - 1   // MIDI 60 = C4
    val step = if (useFlats) FLAT_NAMES[pc] else SHARP_NAMES[pc]
    return "$step$octave"
}

/** Human display of a pitch class (root note) — uses flats for flat keys. */
internal fun pitchClassName(pitchClass: Int): String {
    val pc = ((pitchClass % 12) + 12) % 12
    return when (pc) {
        1 -> "D♭"; 3 -> "E♭"; 6 -> "G♭"; 8 -> "A♭"; 10 -> "B♭"
        else -> SHARP_NAMES[pc]
    }
}

/**
 * Does this root idiomatically spell its scale with flats? Used to steer
 * the display of chromatic pitches in generated notes.
 */
internal fun prefersFlats(rootName: String): Boolean {
    // Flats: F, B♭, E♭, A♭, D♭, G♭ majors
    // Sharps: G, D, A, E, B, F♯, C♯ majors
    return rootName.startsWith("D♭") || rootName.startsWith("E♭") ||
        rootName.startsWith("G♭") || rootName.startsWith("A♭") ||
        rootName.startsWith("B♭") || rootName == "F"
}

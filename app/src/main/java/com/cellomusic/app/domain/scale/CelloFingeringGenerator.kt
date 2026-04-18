package com.cellomusic.app.domain.scale

import com.cellomusic.app.domain.model.Fingering

/**
 * Generate idiomatic first-position cello fingerings for a stream of MIDI
 * pitches.
 *
 * "First position" here means the hand is placed so finger 1 plays the
 * whole step above the open string and finger 4 reaches a perfect 4th
 * above. We allow the usual one-semitone backward/forward extensions so
 * all chromatic pitches inside the position are playable without
 * labelling a shift.  This covers every 2-octave scale whose starting
 * note is on an open string — i.e. the bulk of the practice library.
 *
 * Notes above the 1st-position reach on the A string get `null` here
 * rather than a made-up higher-position fingering: a teacher-supplied
 * 3-octave fingering varies by school (Galamian vs. Feuillard vs.
 * Suzuki), and a wrong guess is worse than blank above the note.  A
 * student practising a 3-octave scale still reads the notation cleanly;
 * they just won't see finger numbers on the top octave.
 */
object CelloFingeringGenerator {

    /** Open-string MIDI values, low-to-high: C2, G2, D3, A3. */
    private val OPEN_STRING_MIDI = intArrayOf(36, 43, 50, 57)

    /**
     * Cello string numbers (MusicXML convention: 1 = highest / A,
     * 4 = lowest / C). Index aligns with [OPEN_STRING_MIDI].
     */
    private val STRING_NUMBER = intArrayOf(4, 3, 2, 1)

    /** Map each MIDI note to a [Fingering], or `null` when out of 1st-position reach. */
    fun fingeringsFor(midi: List<Int>): List<Fingering?> = midi.map { fingeringFor(it) }

    private fun fingeringFor(midi: Int): Fingering? {
        // Prefer the highest string that places this note in 1st position —
        // that's how cellists naturally finger an ascending scale (move up
        // strings rather than stretching the hand).
        var chosenStringIdx = -1
        var chosenOffset = -1
        for (s in OPEN_STRING_MIDI.indices) {
            val offset = midi - OPEN_STRING_MIDI[s]
            if (offset in 0..6 && s > chosenStringIdx) {
                chosenStringIdx = s
                chosenOffset = offset
            }
        }
        if (chosenStringIdx < 0) return null

        val finger = firstPositionFinger(chosenOffset)
        return Fingering(
            finger = finger,
            stringNumber = STRING_NUMBER[chosenStringIdx],
            positionShift = 0   // 1st position — no roman numeral label
        )
    }

    /**
     * Semitones above the open string → the finger that plays that note in
     * 1st position, including the common half-step extensions.
     *
     *   offset   | finger
     *   0 (open) | 0
     *   1 (half) | 1  (backward extension)
     *   2 (M2)   | 1
     *   3 (m3)   | 2
     *   4 (M3)   | 3
     *   5 (P4)   | 4
     *   6 (A4)   | 4  (forward extension / extended 4)
     */
    private fun firstPositionFinger(offset: Int): Int = when (offset) {
        0 -> 0
        1 -> 1
        2 -> 1
        3 -> 2
        4 -> 3
        5 -> 4
        6 -> 4
        else -> 0
    }
}

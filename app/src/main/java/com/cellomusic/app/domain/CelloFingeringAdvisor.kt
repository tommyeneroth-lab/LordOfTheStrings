package com.cellomusic.app.domain

import com.cellomusic.app.domain.model.*
import kotlin.math.abs

/**
 * Suggests cello left-hand fingerings using Viterbi dynamic programming.
 *
 * A greedy note-by-note approach produces inconsistent results because the
 * same note can appear to have equally low cost on several strings/positions
 * depending on context.  Viterbi finds the single globally-optimal path
 * across the whole score, so the hand stays in one region as long as possible
 * and identical melodic patterns always get identical fingerings.
 *
 * Physical model
 * ──────────────
 * 4 strings  : C2=36  G2=43  D3=50  A3=57  (MIDI)
 * 8 positions: hand shift from 1st position in semitones → 0,2,4,5,7,9,12,14
 *   In each position the string covers 8 semitones (open + fingers 1-4):
 *   offset 0→open  1→1  2→1  3→2  4→2  5→3  6→3  7→4
 * Thumb position: posShift≥12 on A string (high register, C5+)
 */
object CelloFingeringAdvisor {

    private val STRING_OPEN     = intArrayOf(36, 43, 50, 57)
    private val SEMITONE_FINGER = intArrayOf(0, 1, 1, 2, 2, 3, 3, 4)   // index 0..7
    private val POSITION_SHIFTS = intArrayOf(0, 2, 4, 5, 7, 9, 12, 14)

    private data class State(val stringIdx: Int, val posShift: Int, val finger: Int) {
        val isThumb: Boolean get() = posShift >= 12 && stringIdx == 3
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Apply Viterbi-optimal fingerings to every note in the score. */
    fun suggest(score: Score): Score {
        val newParts = score.parts.map { part ->
            val notes = part.measures.flatMap { m -> m.elements.filterIsInstance<Note>() }
            val chosen = viterbi(notes)

            var noteIdx = 0
            val newMeasures = part.measures.map { measure ->
                measure.copy(elements = measure.elements.map { elem ->
                    if (elem is Note) {
                        val s = chosen.getOrNull(noteIdx++)
                        if (s != null) elem.copy(fingering = Fingering(
                            finger          = s.finger,
                            isThumbPosition = s.isThumb,
                            stringNumber    = s.stringIdx + 1,
                            positionShift   = s.posShift
                        )) else elem
                    } else elem
                })
            }
            part.copy(measures = newMeasures)
        }
        return score.copy(parts = newParts)
    }

    /** Remove all fingerings from the score. */
    fun clear(score: Score): Score = score.copy(parts = score.parts.map { part ->
        part.copy(measures = part.measures.map { measure ->
            measure.copy(elements = measure.elements.map { elem ->
                if (elem is Note) elem.copy(fingering = null) else elem
            })
        })
    })

    // ── Viterbi ────────────────────────────────────────────────────────────────

    private fun viterbi(notes: List<Note>): List<State?> {
        if (notes.isEmpty()) return emptyList()
        val n = notes.size
        val noteStates = notes.map { optionsFor(it.pitch.toMidiNote()) }

        // cost[i][state] = min total cost to reach `state` at note i
        val cost = Array(n) { mutableMapOf<State, Float>() }
        val prev = Array(n) { mutableMapOf<State, State?>() }

        // Initialise first note
        for (s in noteStates[0]) {
            cost[0][s] = emitCost(s)
            prev[0][s] = null
        }

        // Forward pass
        for (i in 1 until n) {
            for (cur in noteStates[i]) {
                var best = Float.MAX_VALUE
                var bestPrev: State? = null
                for (p in noteStates[i - 1]) {
                    val c = (cost[i - 1][p] ?: continue) + transitionCost(p, cur)
                    if (c < best) { best = c; bestPrev = p }
                }
                cost[i][cur] = best + emitCost(cur)
                prev[i][cur] = bestPrev
            }
        }

        // Backtrack
        val result = arrayOfNulls<State>(n)
        var cur = cost[n - 1].minByOrNull { it.value }?.key
        for (i in n - 1 downTo 0) {
            result[i] = cur
            cur = if (cur != null) prev[i][cur] else null
        }
        return result.toList()
    }

    // ── Cost functions ─────────────────────────────────────────────────────────

    /**
     * Emission cost for being in a particular (string, position, finger) state.
     * Lower = more natural / preferred.
     */
    private fun emitCost(s: State): Float = when (s.finger) {
        0    -> -3f   // open string: strong bonus
        1, 2 ->  0f   // most natural fingers
        3    ->  0.5f // ring finger: slightly less comfortable
        4    ->  2f   // pinky: harder, avoid when possible
        else ->  4f
    }

    /**
     * Cost of moving the hand from one (string, position) to another.
     * Position shifts dominate; string crossings are cheap.
     */
    private fun transitionCost(from: State, to: State): Float {
        val posDiff = abs(to.posShift - from.posShift)
        val strDiff = abs(to.stringIdx - from.stringIdx)

        val posCost = when {
            posDiff == 0 ->  0f    // no shift: free
            posDiff <= 2 ->  5f    // one position (e.g. 1st→2nd)
            posDiff <= 5 -> 11f    // two positions
            else         -> 20f    // large shift
        }

        val strCost = when (strDiff) {
            0    -> 0f
            1    -> 1f   // adjacent string
            2    -> 3f   // skip one string
            else -> 6f
        }

        return posCost + strCost
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun optionsFor(midiNote: Int): List<State> {
        val options = mutableListOf<State>()
        for (sIdx in 3 downTo 0) {          // A > D > G > C (try higher strings first)
            val open = STRING_OPEN[sIdx]
            for (shift in POSITION_SHIFTS) {
                val offset = midiNote - (open + shift)
                if (offset < 0 || offset > 7) continue
                // offset==0 means the note equals the open-string pitch relative to
                // this position window — but a physically open string (finger 0) only
                // makes sense at shift==0.  In any shifted position, offset==0 would
                // incorrectly label a fretted note as "open string", so skip it.
                if (offset == 0 && shift > 0) continue
                options += State(sIdx, shift, SEMITONE_FINGER[offset])
            }
        }
        return options
    }
}

package com.cellomusic.app.util

import com.cellomusic.app.domain.model.*
import kotlin.math.pow

object NoteFrequencyUtil {

    // Standard tuning: A4 = 440 Hz
    private const val A4_FREQUENCY = 440.0
    private const val A4_MIDI = 69

    fun midiToFrequency(midiNote: Int): Double {
        return A4_FREQUENCY * 2.0.pow((midiNote - A4_MIDI) / 12.0)
    }

    fun frequencyToMidi(frequency: Double): Int {
        return (12 * Math.log(frequency / A4_FREQUENCY) / Math.log(2.0) + A4_MIDI).toInt()
    }

    fun pitchToFrequency(pitch: Pitch): Double {
        return midiToFrequency(pitch.toMidiNote())
    }

    /**
     * Returns true if the given MIDI note is within the standard cello range:
     * C2 (36) to B5 (83), or extended up to C7 (96) with harmonics.
     */
    fun isInCelloRange(midiNote: Int, extended: Boolean = false): Boolean {
        val low = 36 // C2
        val high = if (extended) 96 else 83 // C7 or B5
        return midiNote in low..high
    }

    /**
     * Returns the open string MIDI note for a cello string number (1-4).
     * 1=A3(57), 2=D3(50), 3=G2(43), 4=C2(36)
     */
    fun openStringMidi(stringNumber: Int): Int = when (stringNumber) {
        1 -> 57  // A3
        2 -> 50  // D3
        3 -> 43  // G2
        4 -> 36  // C2
        else -> 57
    }

    /**
     * Cello-specific: suggest which string to play a note on.
     */
    fun suggestString(pitch: Pitch): Int {
        val midi = pitch.toMidiNote()
        return when {
            midi >= 57 -> 1  // A string (57+)
            midi >= 50 -> 2  // D string (50-56)
            midi >= 43 -> 3  // G string (43-49)
            else -> 4         // C string (36-42)
        }
    }

    /**
     * Returns recommended position (I-IV) for a pitch on a given string.
     */
    fun suggestPosition(pitch: Pitch, stringNumber: Int): Int {
        val semitoneFromOpen = pitch.toMidiNote() - openStringMidi(stringNumber)
        return when {
            semitoneFromOpen <= 3 -> 1   // First position: up to major 2nd
            semitoneFromOpen <= 7 -> 2   // Second/third position
            semitoneFromOpen <= 12 -> 3  // Fourth position
            else -> 4                     // Thumb position and above
        }
    }
}

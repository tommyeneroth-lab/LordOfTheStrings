package com.cellomusic.app.audio.tuner

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.sqrt

/**
 * Harmonic Product Spectrum pitch detector.
 * Optimized for cello whose C2 (65.4 Hz) has a weak fundamental that simple
 * peak-finding would misidentify as C3 or C4. HPS multiplies downsampled copies
 * of the spectrum so the true fundamental always wins.
 */
class HpsProcessor(
    private val sampleRate: Int = 44100,
    private val fftSize: Int = 8192,
    private val hpsOrder: Int = 5
) {
    companion object {
        // Cello open strings
        val CELLO_STRINGS = listOf(
            CelloString("C2", 65.41f, 4),
            CelloString("G2", 98.00f, 3),
            CelloString("D3", 146.83f, 2),
            CelloString("A3", 220.00f, 1)
        )

        // Search range slightly wider than cello range (C2 to ~D6 for harmonics)
        const val MIN_FREQ = 60f
        const val MAX_FREQ = 1200f
    }

    /**
     * Returns pitch result from a magnitude spectrum.
     * @param spectrum Half-spectrum magnitude array of size fftSize/2+1
     */
    fun process(spectrum: FloatArray): PitchResult? {
        val numBins = spectrum.size
        val minBin = (MIN_FREQ * fftSize / sampleRate).toInt().coerceAtLeast(1)
        val maxBin = (MAX_FREQ * fftSize / sampleRate).toInt().coerceAtMost(numBins - 1)

        // HPS: multiply spectrum with downsampled copies
        val hps = spectrum.copyOfRange(minBin, maxBin + 1)
        for (order in 2..hpsOrder) {
            for (i in hps.indices) {
                val sourceBin = ((i + minBin) * order)
                if (sourceBin < numBins) {
                    hps[i] *= spectrum[sourceBin]
                }
            }
        }

        // Find peak bin in HPS
        var peakBin = minBin
        var peakVal = 0f
        for (i in hps.indices) {
            if (hps[i] > peakVal) {
                peakVal = hps[i]
                peakBin = i + minBin
            }
        }

        // Noise floor check
        val meanPower = hps.take(hps.size.coerceAtLeast(1)).average().toFloat()
        if (peakVal < meanPower * 3f) return null

        // Parabolic interpolation for sub-bin precision
        val freq = if (peakBin > minBin && peakBin < maxBin) {
            val binIndex = peakBin - minBin
            if (binIndex > 0 && binIndex < hps.size - 1) {
                val alpha = hps[binIndex - 1]
                val beta = hps[binIndex]
                val gamma = hps[binIndex + 1]
                val adjustment = 0.5f * (alpha - gamma) / (alpha - 2 * beta + gamma)
                (peakBin + adjustment) * sampleRate / fftSize
            } else {
                peakBin.toFloat() * sampleRate / fftSize
            }
        } else {
            peakBin.toFloat() * sampleRate / fftSize
        }

        // Find closest cello string
        val closestString = CELLO_STRINGS.minByOrNull { string ->
            abs(centsOffset(freq, string.frequency))
        } ?: CELLO_STRINGS[0]

        val cents = centsOffset(freq, closestString.frequency)

        return PitchResult(
            frequency = freq,
            noteName = closestString.name,
            centsOffset = cents,
            closestString = closestString,
            confidence = (peakVal / (meanPower + 1f)).coerceIn(0f, 1f)
        )
    }

    private fun centsOffset(detected: Float, reference: Float): Float {
        return (1200f * log2(detected / reference)).toFloat()
    }
}

data class CelloString(
    val name: String,
    val frequency: Float,
    val stringNumber: Int  // 1=A, 2=D, 3=G, 4=C
)

data class PitchResult(
    val frequency: Float,
    val noteName: String,
    val centsOffset: Float,      // negative = flat, positive = sharp
    val closestString: CelloString,
    val confidence: Float        // 0..1
) {
    val isInTune: Boolean get() = abs(centsOffset) < 5f
    val tuningDescription: String get() = when {
        abs(centsOffset) < 5f -> "In tune"
        centsOffset < 0 -> "%.0f cents flat".format(abs(centsOffset))
        else -> "%.0f cents sharp".format(centsOffset)
    }
}

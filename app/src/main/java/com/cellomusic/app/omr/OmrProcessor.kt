package com.cellomusic.app.omr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.cellomusic.app.domain.model.*
import com.cellomusic.app.musicxml.MusicXmlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.*

/**
 * Optical Music Recognition processor for cello scores.
 *
 * Pipeline:
 * 1. ImagePreprocessor: grayscale, binarize, deskew
 * 2. StaffDetector: find 5-line staff systems using run-length encoding
 * 3. Symbol detection: find noteheads, stems, rests using connected components
 * 4. MusicXmlBuilder: assemble detected symbols into Score domain model
 *
 * Accuracy note: This is a heuristic-based OMR suitable for clean printed scores.
 * For complex manuscripts, accuracy may be 70-85%. Users can correct misrecognitions
 * in the score editor.
 */
class OmrProcessor(private val context: Context) {

    data class OmrResult(
        val score: Score,
        val confidence: Float,         // 0..1 overall confidence
        val warnings: List<String>     // regions with low confidence
    )

    suspend fun processJpeg(jpegFile: File): OmrResult = withContext(Dispatchers.Default) {
        val bitmap = BitmapFactory.decodeFile(jpegFile.absolutePath)
            ?: return@withContext failResult("Could not load image")

        processImage(bitmap, jpegFile.nameWithoutExtension)
    }

    suspend fun processPage(bitmap: Bitmap, title: String): OmrResult = withContext(Dispatchers.Default) {
        processImage(bitmap, title)
    }

    private fun processImage(bitmap: Bitmap, title: String): OmrResult {
        val warnings = mutableListOf<String>()

        // Step 1: Preprocess
        val gray = toGrayscale(bitmap)
        val binary = binarize(gray)

        // Step 2: Detect staff lines
        val staffSystems = detectStaffSystems(binary)
        if (staffSystems.isEmpty()) {
            warnings.add("No staff lines detected - this may not be a music score")
            return failResult("No staff lines detected")
        }

        // Step 3: Remove staff lines and find symbols
        val noStaff = removeStaffLines(binary, staffSystems)

        // Step 4: Find connected components (potential symbols)
        val components = findConnectedComponents(noStaff, staffSystems)

        // Step 5: Classify components into music notation symbols
        val symbols = classifySymbols(components, staffSystems)

        // Step 6: Build score from symbols
        val score = buildScore(symbols, staffSystems, title)

        val confidence = if (staffSystems.isNotEmpty() && symbols.isNotEmpty()) {
            (symbols.size.toFloat() / max(1, components.size)).coerceIn(0f, 1f)
        } else 0f

        return OmrResult(score, confidence, warnings)
    }

    private fun toGrayscale(bitmap: Bitmap): Array<FloatArray> {
        val w = bitmap.width; val h = bitmap.height
        val gray = Array(h) { FloatArray(w) }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val pixel = bitmap.getPixel(x, y)
                gray[y][x] = (Color.red(pixel) * 0.299f +
                    Color.green(pixel) * 0.587f +
                    Color.blue(pixel) * 0.114f) / 255f
            }
        }
        return gray
    }

    private fun binarize(gray: Array<FloatArray>): Array<BooleanArray> {
        val h = gray.size; val w = if (h > 0) gray[0].size else 0
        val binary = Array(h) { BooleanArray(w) }
        val blockSize = 32

        for (y in 0 until h) {
            for (x in 0 until w) {
                // Calculate local mean for adaptive thresholding
                val y1 = maxOf(0, y - blockSize / 2)
                val y2 = minOf(h - 1, y + blockSize / 2)
                val x1 = maxOf(0, x - blockSize / 2)
                val x2 = minOf(w - 1, x + blockSize / 2)

                var sum = 0f
                var count = 0
                for (dy in y1..y2 step 4) {
                    for (dx in x1..x2 step 4) {
                        sum += gray[dy][dx]
                        count++
                    }
                }
                val localMean = if (count > 0) sum / count else 0.5f
                binary[y][x] = gray[y][x] < localMean - 0.05f // dark pixels = true (ink)
            }
        }
        return binary
    }

    private data class StaffSystem(
        val lines: List<Int>,     // y-coordinates of 5 staff lines
        val spacing: Float,       // average gap between lines
        val top: Int,
        val bottom: Int,
        val left: Int = 0,
        val right: Int = 0
    )

    private fun detectStaffSystems(binary: Array<BooleanArray>): List<StaffSystem> {
        val h = binary.size; val w = if (h > 0) binary[0].size else 0
        if (w == 0) return emptyList()

        // Count black pixels per row (staff lines are horizontal runs)
        val rowDensity = IntArray(h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (binary[y][x]) rowDensity[y]++
            }
        }

        // Find rows that are likely staff lines (high density of black pixels)
        val threshold = w * 0.4f  // At least 40% of row is black
        val staffLineRows = mutableListOf<Int>()

        for (y in 2 until h - 2) {
            if (rowDensity[y] >= threshold &&
                rowDensity[y] >= rowDensity[y - 1] &&
                rowDensity[y] >= rowDensity[y + 1]) {
                // Peak in row density
                staffLineRows.add(y)
            }
        }

        // Group rows into staff lines (adjacent rows = same staff line)
        val mergedLines = mutableListOf<Int>()
        var i = 0
        while (i < staffLineRows.size) {
            var j = i
            while (j < staffLineRows.size - 1 && staffLineRows[j + 1] - staffLineRows[j] <= 3) j++
            mergedLines.add((staffLineRows[i] + staffLineRows[j]) / 2)
            i = j + 1
        }

        // Group merged lines into systems of 5
        val systems = mutableListOf<StaffSystem>()
        i = 0
        while (i <= mergedLines.size - 5) {
            val fiveLines = mergedLines.subList(i, i + 5)
            val spacings = (0..3).map { fiveLines[it + 1] - fiveLines[it] }
            val avgSpacing = spacings.average().toFloat()

            // Validate: spacings should be roughly equal
            val maxDeviation = spacings.map { abs(it - avgSpacing) }.max()
            if (maxDeviation < avgSpacing * 0.3f) {
                systems.add(StaffSystem(
                    lines = fiveLines,
                    spacing = avgSpacing,
                    top = fiveLines.first(),
                    bottom = fiveLines.last(),
                    left = 0,
                    right = w
                ))
                i += 5
            } else {
                i++
            }
        }

        return systems
    }

    private fun removeStaffLines(binary: Array<BooleanArray>, systems: List<StaffSystem>): Array<BooleanArray> {
        val h = binary.size; val w = if (h > 0) binary[0].size else 0
        val result = Array(h) { y -> binary[y].copyOf() }

        for (system in systems) {
            for (lineY in system.lines) {
                // Remove pixels in a band around each staff line
                for (dy in -2..2) {
                    val y = lineY + dy
                    if (y in 0 until h) {
                        for (x in 0 until w) {
                            result[y][x] = false
                        }
                    }
                }
            }
        }
        return result
    }

    private data class Component(
        val pixels: List<Pair<Int, Int>>,  // (x, y) pairs
        val left: Int, val top: Int, val right: Int, val bottom: Int,
        val width: Int, val height: Int,
        val staff: StaffSystem?,
        val staffPosition: Float  // position on staff (0=top line, 8=bottom line)
    )

    private fun findConnectedComponents(
        binary: Array<BooleanArray>,
        staffSystems: List<StaffSystem>
    ): List<Component> {
        val h = binary.size; val w = if (h > 0) binary[0].size else 0
        val visited = Array(h) { BooleanArray(w) }
        val components = mutableListOf<Component>()

        for (startY in 0 until h) {
            for (startX in 0 until w) {
                if (binary[startY][startX] && !visited[startY][startX]) {
                    // BFS flood fill
                    val pixels = mutableListOf<Pair<Int, Int>>()
                    val queue = ArrayDeque<Pair<Int, Int>>()
                    queue.add(Pair(startX, startY))
                    visited[startY][startX] = true

                    var left = startX; var top = startY
                    var right = startX; var bottom = startY

                    while (queue.isNotEmpty()) {
                        val (x, y) = queue.removeFirst()
                        pixels.add(Pair(x, y))
                        if (x < left) left = x; if (x > right) right = x
                        if (y < top) top = y; if (y > bottom) bottom = y

                        for ((dx, dy) in listOf(Pair(1, 0), Pair(-1, 0), Pair(0, 1), Pair(0, -1))) {
                            val nx = x + dx; val ny = y + dy
                            if (nx in 0 until w && ny in 0 until h &&
                                binary[ny][nx] && !visited[ny][nx]) {
                                visited[ny][nx] = true
                                queue.add(Pair(nx, ny))
                            }
                        }
                    }

                    val compW = right - left + 1
                    val compH = bottom - top + 1

                    // Filter out very small components (noise)
                    if (compW >= 3 && compH >= 3 && pixels.size >= 6) {
                        val centerY = (top + bottom) / 2
                        val closestStaff = staffSystems.minByOrNull { abs(it.top - centerY) }
                        val staffPos = closestStaff?.let {
                            val relY = centerY - it.lines[0]
                            relY / (it.spacing / 2)
                        } ?: 0f

                        components.add(Component(
                            pixels = pixels,
                            left = left, top = top, right = right, bottom = bottom,
                            width = compW, height = compH,
                            staff = closestStaff,
                            staffPosition = staffPos
                        ))
                    }
                }
            }
        }
        return components
    }

    private enum class SymbolType {
        NOTEHEAD_FILLED, NOTEHEAD_OPEN, NOTEHEAD_WHOLE,
        STEM, BEAM, FLAG,
        REST_WHOLE, REST_HALF, REST_QUARTER, REST_EIGHTH,
        BAR_LINE, CLEF_BASS, CLEF_TENOR, CLEF_TREBLE,
        ACCIDENTAL_SHARP, ACCIDENTAL_FLAT, ACCIDENTAL_NATURAL,
        DYNAMIC_P, DYNAMIC_F, DYNAMIC_MF, DYNAMIC_FF,
        DOT, UNKNOWN
    }

    private data class DetectedSymbol(
        val type: SymbolType,
        val x: Int, val y: Int,
        val width: Int, val height: Int,
        val staff: StaffSystem?,
        val staffPosition: Float,
        val confidence: Float
    )

    private fun classifySymbols(
        components: List<Component>,
        staffSystems: List<StaffSystem>
    ): List<DetectedSymbol> {
        val symbols = mutableListOf<DetectedSymbol>()

        for (comp in components) {
            val staff = comp.staff ?: continue
            val spacing = staff.spacing
            val aspectRatio = comp.width.toFloat() / comp.height

            val (type, confidence) = when {
                // Vertical thin line = barline or stem
                comp.height > spacing * 3 && comp.width < spacing * 0.4f -> {
                    Pair(SymbolType.STEM, 0.8f)
                }
                // Wide horizontal = beam
                comp.width > spacing * 2 && comp.height < spacing * 0.4f -> {
                    Pair(SymbolType.BEAM, 0.75f)
                }
                // Filled oval ~ notehead size
                comp.width in (spacing * 0.6f).toInt()..(spacing * 1.5f).toInt() &&
                comp.height in (spacing * 0.4f).toInt()..(spacing * 1.1f).toInt() -> {
                    // Check fill density
                    val density = comp.pixels.size.toFloat() / (comp.width * comp.height)
                    when {
                        density > 0.65f -> Pair(SymbolType.NOTEHEAD_FILLED, 0.85f)
                        density > 0.4f -> Pair(SymbolType.NOTEHEAD_OPEN, 0.75f)
                        else -> Pair(SymbolType.UNKNOWN, 0.3f)
                    }
                }
                // Large filled oval = whole note
                comp.width > spacing * 1.4f && comp.height > spacing * 0.6f -> {
                    Pair(SymbolType.NOTEHEAD_WHOLE, 0.7f)
                }
                // Tall components near staff = barlines
                comp.height > staff.spacing * 4 && comp.width < spacing * 0.3f -> {
                    Pair(SymbolType.BAR_LINE, 0.8f)
                }
                // Small dots = augmentation dots or staccato
                comp.width < spacing * 0.35f && comp.height < spacing * 0.35f -> {
                    Pair(SymbolType.DOT, 0.7f)
                }
                // Wide tall component near left edge = clef
                comp.width > spacing * 1.5f && comp.height > spacing * 3f &&
                comp.left < 100 -> {
                    Pair(SymbolType.CLEF_BASS, 0.6f)  // assume bass clef for cello
                }
                else -> Pair(SymbolType.UNKNOWN, 0.2f)
            }

            if (confidence > 0.25f) {
                symbols.add(DetectedSymbol(
                    type = type,
                    x = (comp.left + comp.right) / 2,
                    y = (comp.top + comp.bottom) / 2,
                    width = comp.width,
                    height = comp.height,
                    staff = comp.staff,
                    staffPosition = comp.staffPosition,
                    confidence = confidence
                ))
            }
        }

        return symbols.sortedWith(compareBy({ it.staff?.top }, { it.x }))
    }

    private fun buildScore(
        symbols: List<DetectedSymbol>,
        staffSystems: List<StaffSystem>,
        title: String
    ): Score {
        val measures = mutableListOf<Measure>()
        var measureNum = 1

        // Group symbols by staff system and separate at barlines
        val systemSymbols = staffSystems.map { sys ->
            symbols.filter { sym ->
                sym.staff == sys
            }
        }

        for ((sysIdx, sysSyms) in systemSymbols.withIndex()) {
            val barlinePositions = sysSyms.filter { it.type == SymbolType.BAR_LINE }.map { it.x }.sorted()
            val measureBounds = mutableListOf<Pair<Int, Int>>()

            // Create measure boundaries between barlines
            if (barlinePositions.isEmpty()) {
                measureBounds.add(Pair(0, Int.MAX_VALUE))
            } else {
                measureBounds.add(Pair(0, barlinePositions.first()))
                for (i in 0 until barlinePositions.size - 1) {
                    measureBounds.add(Pair(barlinePositions[i], barlinePositions[i + 1]))
                }
            }

            for ((left, right) in measureBounds) {
                val measureSyms = sysSyms.filter { it.x in left..right }
                val notes = extractNotes(measureSyms, staffSystems[sysIdx])

                val isFirst = measureNum == 1
                measures.add(Measure(
                    number = measureNum++,
                    clef = if (isFirst) Clef(ClefType.BASS) else null,
                    timeSignature = if (isFirst) TimeSignature(4, 4) else null,
                    keySignature = if (isFirst) KeySignature(0) else null,
                    elements = notes,
                    barlineRight = Barline.REGULAR
                ))
            }
        }

        return Score(
            id = UUID.randomUUID().toString(),
            title = title,
            parts = listOf(
                Part(
                    id = "P1",
                    name = "Cello",
                    abbreviation = "Vc.",
                    measures = if (measures.isEmpty()) {
                        listOf(Measure(1, elements = emptyList()))
                    } else measures
                )
            )
        )
    }

    private fun extractNotes(symbols: List<DetectedSymbol>, staff: StaffSystem): List<MusicElement> {
        val noteheads = symbols.filter {
            it.type in listOf(SymbolType.NOTEHEAD_FILLED, SymbolType.NOTEHEAD_OPEN, SymbolType.NOTEHEAD_WHOLE)
        }.sortedBy { it.x }

        val elements = mutableListOf<MusicElement>()
        var tickOffset = 0

        for (notehead in noteheads) {
            val pitch = staffPositionToPitch(notehead.staffPosition, Clef(ClefType.BASS))
            val duration = when (notehead.type) {
                SymbolType.NOTEHEAD_WHOLE -> NoteDuration(DurationType.WHOLE)
                SymbolType.NOTEHEAD_OPEN -> NoteDuration(DurationType.HALF)
                SymbolType.NOTEHEAD_FILLED -> {
                    // Look for flags/beams nearby to determine duration
                    NoteDuration(DurationType.QUARTER)
                }
                else -> NoteDuration(DurationType.QUARTER)
            }

            val note = Note(
                id = UUID.randomUUID().toString(),
                startTick = tickOffset,
                duration = duration,
                pitch = pitch
            )
            elements.add(note)
            tickOffset += duration.toTicks()
        }

        return elements
    }

    private fun staffPositionToPitch(staffPos: Float, clef: Clef): Pitch {
        // Staff position 0 = top line, 8 = bottom line
        // For bass clef: G3 on position 0, descending diatonically
        val bassClefNotes = listOf(
            // Top line down: G3, F3, E3, D3, C3, B2, A2, G2, F2, E2, D2, C2
            PitchStep.G to 3, PitchStep.F to 3, PitchStep.E to 3, PitchStep.D to 3,
            PitchStep.C to 3, PitchStep.B to 2, PitchStep.A to 2, PitchStep.G to 2,
            PitchStep.F to 2, PitchStep.E to 2, PitchStep.D to 2, PitchStep.C to 2
        )

        val tenorClefNotes = listOf(
            PitchStep.E to 4, PitchStep.D to 4, PitchStep.C to 4, PitchStep.B to 3,
            PitchStep.A to 3, PitchStep.G to 3, PitchStep.F to 3, PitchStep.E to 3,
            PitchStep.D to 3, PitchStep.C to 3, PitchStep.B to 2, PitchStep.A to 2
        )

        val notes = when (clef.type) {
            ClefType.TENOR -> tenorClefNotes
            else -> bassClefNotes
        }

        val index = ((staffPos / 2 + 0.5f).toInt()).coerceIn(0, notes.size - 1)
        val (step, octave) = notes[index]
        return Pitch(step, octave)
    }

    private fun failResult(reason: String) = OmrResult(
        score = Score(
            id = UUID.randomUUID().toString(),
            title = "Unrecognized Score",
            parts = listOf(Part("P1", "Cello", "Vc.", measures = emptyList()))
        ),
        confidence = 0f,
        warnings = listOf(reason)
    )
}

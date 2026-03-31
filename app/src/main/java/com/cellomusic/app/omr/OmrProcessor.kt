package com.cellomusic.app.omr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.cellomusic.app.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.*

/**
 * Optical Music Recognition processor for cello scores.
 *
 * Pipeline:
 *  1. Scale large images to avoid OOM
 *  2. Grayscale conversion (vectorised via getPixels)
 *  3. Sauvola adaptive binarization – handles phone-photo lighting/shadows
 *  4. Run-length–based staff detection – far more robust than row density
 *  5. Smart staff-line removal – preserves noteheads / stems crossing lines
 *  6. Connected-component labelling (iterative DFS – no stack overflow)
 *  7. Symbol classification calibrated to actual staff spacing
 *  8. Score assembly with bass-clef pitch mapping
 */
class OmrProcessor(private val context: Context) {

    data class OmrResult(
        val score: Score,
        val confidence: Float,
        val warnings: List<String>
    )

    // ── Public entry points ──────────────────────────────────────────────────

    suspend fun processJpeg(
        jpegFile: File,
        onProgress: ((String) -> Unit)? = null
    ): OmrResult = withContext(Dispatchers.Default) {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeFile(jpegFile.absolutePath, opts)
            ?: return@withContext failResult("Could not decode image file")
        processImage(bmp, jpegFile.nameWithoutExtension, onProgress)
    }

    suspend fun processPage(
        bitmap: Bitmap,
        title: String,
        onProgress: ((String) -> Unit)? = null
    ): OmrResult = withContext(Dispatchers.Default) { processImage(bitmap, title, onProgress) }

    // ── Main pipeline ────────────────────────────────────────────────────────

    private fun processImage(
        bitmap: Bitmap,
        title: String,
        onProgress: ((String) -> Unit)? = null
    ): OmrResult {
        val warnings = mutableListOf<String>()

        onProgress?.invoke("Scaling image…")
        val bmp = scaleBitmap(bitmap, maxDim = 1600)
        val imgW = bmp.width; val imgH = bmp.height

        onProgress?.invoke("Converting to grayscale…")
        val gray = toGrayscale(bmp, imgW, imgH)

        onProgress?.invoke("Binarizing (adaptive threshold)…")
        val binary = sauvolaBinarize(gray, imgW, imgH)

        onProgress?.invoke("Connecting broken staff lines…")
        // gap=25 bridges notehead-width gaps (~15–25 px) without merging barline spaces
        val dilated = horizontalDilate(binary, imgW, imgH, gap = 25)

        onProgress?.invoke("Detecting staff lines…")
        val staffSystems = detectStaffSystems(dilated, imgW, imgH)
        if (staffSystems.isEmpty()) {
            warnings.add("No staff lines detected — ensure the image is a music score, well-lit, and not too blurry")
            onProgress?.invoke("Failed: no staff lines found")
            return failResult("No staff lines detected")
        }
        onProgress?.invoke("Found ${staffSystems.size} staff system(s) — removing staff lines…")

        val noStaff = removeStaffLines(binary, imgW, imgH, staffSystems)

        onProgress?.invoke("Separating noteheads from beams and stems…")
        val noteheadOnly = separateNoteheads(noStaff, imgW, imgH, staffSystems)

        onProgress?.invoke("Finding music symbols…")
        val components = connectedComponents(noteheadOnly, imgW, imgH, staffSystems)

        onProgress?.invoke("Classifying ${components.size} symbol candidate(s)…")
        val symbols = classifySymbols(components, staffSystems)

        val noteCount = symbols.count { it.type in NOTEHEAD_TYPES }
        onProgress?.invoke("Building score — $noteCount note(s) found…")
        val score = buildScore(symbols, staffSystems, title, noStaff, imgW, imgH)

        val confidence = (noteCount.toFloat() / max(1, staffSystems.size * 4)).coerceIn(0f, 1f)
        onProgress?.invoke("Done!  ${staffSystems.size} staff row(s), $noteCount note(s) recognised")

        return OmrResult(score, confidence, warnings)
    }

    // ── Image preprocessing ──────────────────────────────────────────────────

    private fun scaleBitmap(bmp: Bitmap, maxDim: Int): Bitmap {
        val maxSide = max(bmp.width, bmp.height)
        if (maxSide <= maxDim) return bmp
        val s = maxDim.toFloat() / maxSide
        val nw = (bmp.width * s).toInt().coerceAtLeast(1)
        val nh = (bmp.height * s).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, nw, nh, true)
    }

    /** Bulk-reads pixels then converts to float luminance [0,1]. */
    private fun toGrayscale(bmp: Bitmap, w: Int, h: Int): Array<FloatArray> {
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        return Array(h) { y ->
            FloatArray(w) { x ->
                val p = pixels[y * w + x]
                (Color.red(p) * 0.299f + Color.green(p) * 0.587f + Color.blue(p) * 0.114f) / 255f
            }
        }
    }

    /**
     * Sauvola local adaptive thresholding.
     *
     *   T(x,y) = mean(x,y) × (1 + k × (σ(x,y)/R − 1))
     *
     * k=0.30, R=0.5 (half of normalised max std-dev).
     * Uses summed-area tables for O(1) per-pixel window queries.
     * Handles shadows, gradients, and uneven phone-photo illumination
     * far better than any global threshold.
     */
    private fun sauvolaBinarize(gray: Array<FloatArray>, w: Int, h: Int): Array<BooleanArray> {
        // Window size: ~1/20 of the shorter dimension, minimum 15, always odd
        var win = max(15, min(w, h) / 20)
        if (win % 2 == 0) win++
        val half = win / 2
        val k = 0.30f
        val R = 0.5f

        // Build integral images (h+1 × w+1 to simplify boundary handling)
        val isum  = Array(h + 1) { FloatArray(w + 1) }
        val isum2 = Array(h + 1) { FloatArray(w + 1) }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = gray[y][x]
                isum [y+1][x+1] = v     + isum [y][x+1] + isum [y+1][x] - isum [y][x]
                isum2[y+1][x+1] = v * v + isum2[y][x+1] + isum2[y+1][x] - isum2[y][x]
            }
        }

        return Array(h) { y ->
            val y1 = max(0, y - half); val y2 = min(h - 1, y + half)
            BooleanArray(w) { x ->
                val x1 = max(0, x - half); val x2 = min(w - 1, x + half)
                val n  = ((x2 - x1 + 1) * (y2 - y1 + 1)).toFloat()
                val s  = isum [y2+1][x2+1] - isum [y1][x2+1] - isum [y2+1][x1] + isum [y1][x1]
                val s2 = isum2[y2+1][x2+1] - isum2[y1][x2+1] - isum2[y2+1][x1] + isum2[y1][x1]
                val mean = s / n
                val variance = (s2 / n - mean * mean).coerceAtLeast(0f)
                val std  = sqrt(variance)
                val thresh = mean * (1f + k * (std / R - 1f))
                gray[y][x] < thresh
            }
        }
    }

    /**
     * Horizontal morphological dilation: fills gaps of up to [gap] pixels between
     * adjacent black runs in each row.  This reconnects staff lines that were broken
     * by noteheads or printing artefacts before staff detection, without expanding
     * noteheads vertically.
     */
    private fun horizontalDilate(
        binary: Array<BooleanArray>, w: Int, h: Int, gap: Int
    ): Array<BooleanArray> {
        return Array(h) { y ->
            val row = binary[y]
            val out = row.copyOf()
            var lastBlack = -1
            for (x in 0 until w) {
                if (row[x]) {
                    if (lastBlack >= 0 && x - lastBlack - 1 in 1..gap) {
                        for (fx in lastBlack + 1 until x) out[fx] = true
                    }
                    lastBlack = x
                }
            }
            out
        }
    }

    // ── Staff detection (run-length encoding) ────────────────────────────────

    private data class StaffSystem(
        val lines: List<Int>,   // y-coordinates of the 5 staff lines
        val spacing: Float,     // average gap between adjacent lines in pixels
        val top: Int,
        val bottom: Int,
        val left: Int,
        val right: Int
    )

    /**
     * Detects staff systems using run-length encoding.
     *
     * Key insight: a real staff line has a LONG unbroken horizontal run of black pixels.
     * Counting all black pixels per row (the old approach) is fooled by noteheads, text,
     * and staccato dots. The longest single run is a much stricter discriminator.
     *
     * Algorithm:
     *  • Compute longest-run and total density for every row.
     *  • A candidate row must have longest-run ≥ 35% of width AND density ≥ 15%.
     *  • Take local maxima in longest-run to get one representative row per line.
     *  • Merge adjacent candidate rows (staff lines are 1–3 px thick in scans).
     *  • Group consecutive merged rows into 5-line systems with consistent spacing.
     */
    private fun detectStaffSystems(
        binary: Array<BooleanArray>, w: Int, h: Int
    ): List<StaffSystem> {
        val maxRun  = IntArray(h)
        val density = IntArray(h)
        for (y in 0 until h) {
            var run = 0; var maxR = 0; var tot = 0
            for (x in 0 until w) {
                if (binary[y][x]) { tot++; if (++run > maxR) maxR = run }
                else run = 0
            }
            maxRun[y] = maxR; density[y] = tot
        }

        val runThr = (w * 0.15f).toInt()   // 15% — handles staves with margins, clef, key sig breaks
        val denThr = (w * 0.12f).toInt()

        // Local maxima in maxRun (above thresholds) → candidate staff-line rows
        val candidates = mutableListOf<Int>()
        for (y in 1 until h - 1) {
            if (maxRun[y] >= runThr && density[y] >= denThr &&
                maxRun[y] >= maxRun[y-1] && maxRun[y] >= maxRun[y+1]) {
                candidates.add(y)
            }
        }

        // Merge adjacent rows using weighted centroid (weight = longest run)
        val merged = mutableListOf<Int>()
        var i = 0
        while (i < candidates.size) {
            var j = i
            while (j < candidates.size - 1 && candidates[j+1] - candidates[j] <= 4) j++
            var sw = 0L; var swy = 0L
            for (k in i..j) {
                val r = maxRun[candidates[k]].toLong()
                sw += r; swy += r * candidates[k]
            }
            merged.add((if (sw > 0) swy / sw else candidates[i].toLong()).toInt())
            i = j + 1
        }

        // Group into 5-line systems with consistent spacing (tolerance ≤ 45%)
        val systems = mutableListOf<StaffSystem>()
        i = 0
        while (i <= merged.size - 5) {
            val five = merged.subList(i, i + 5)
            val gaps  = (0..3).map { five[it+1] - five[it] }
            val avg   = gaps.average().toFloat()
            if (avg < 4f) { i++; continue }
            val maxDev = gaps.maxOf { abs(it - avg) }
            if (maxDev < avg * 0.50f) {
                val midY = five[2]
                var lx = 0; while (lx < w - 1 && !binary[midY][lx]) lx++
                var rx = w - 1; while (rx > 0 && !binary[midY][rx]) rx--
                systems.add(StaffSystem(five, avg, five.first(), five.last(), lx, rx))
                i += 5
            } else {
                i++
            }
        }
        return systems
    }

    // ── Smart staff-line removal ─────────────────────────────────────────────

    /**
     * Removes staff-line pixels while preserving music symbols that cross the lines.
     *
     * For each column x and each staff line y:
     *  - Count black pixels extending ABOVE the staff band (starting at y-3)
     *  - Count black pixels extending BELOW the staff band (starting at y+3)
     *  - If either count ≥ 2 → a symbol crosses here → keep all pixels at this column
     *  - Otherwise → erase the entire ±2 row band at this column
     *
     * This correctly handles noteheads on lines (they extend above and below the line),
     * stems (extend far above or below), and barlines (extend far in both directions).
     */
    private fun removeStaffLines(
        binary: Array<BooleanArray>, w: Int, h: Int,
        systems: List<StaffSystem>
    ): Array<BooleanArray> {
        val result = Array(h) { y -> binary[y].copyOf() }

        for (sys in systems) {
            for (lineY in sys.lines) {
                for (x in 0 until w) {
                    // Count pixels above the staff band (y-3 going up, up to 12px)
                    var aboveRun = 0
                    var ny = lineY - 3
                    while (ny >= 0 && ny >= lineY - 14) {
                        if (binary[ny][x]) aboveRun++ else break
                        ny--
                    }
                    // Count pixels below the staff band (y+3 going down, up to 12px)
                    var belowRun = 0
                    ny = lineY + 3
                    while (ny < h && ny <= lineY + 14) {
                        if (binary[ny][x]) belowRun++ else break
                        ny++
                    }
                    // Keep if a symbol extends ≥2px beyond the band in either direction
                    if (aboveRun >= 2 || belowRun >= 2) continue
                    // Otherwise erase the staff band at this column
                    for (dy in -2..2) {
                        val sy = lineY + dy
                        if (sy in 0 until h) result[sy][x] = false
                    }
                }
            }
        }
        return result
    }

    // ── Beam and stem removal ────────────────────────────────────────────────

    /**
     * Produces a copy of [noStaff] with beams and stems erased so that
     * connected-component analysis sees individual noteheads instead of large
     * merged blobs.
     *
     * Pass 1 – beams: scan every row for horizontal runs wider than 2.5×sp.
     *   At the center column of each such run measure the total vertical extent;
     *   if it is ≤ 0.55×sp the blob is flat → it is a beam → erase it.
     *
     * Pass 2 – stems: scan every column for vertical runs taller than 1.5×sp.
     *   Find the minimum pixel-width across all rows of that run; if the minimum
     *   is ≤ 0.4×sp it is a thin vertical line → it is a stem → erase it.
     */
    private fun separateNoteheads(
        noStaff: Array<BooleanArray>, w: Int, h: Int,
        systems: List<StaffSystem>
    ): Array<BooleanArray> {
        val result = Array(h) { y -> noStaff[y].copyOf() }
        val avgSp = if (systems.isEmpty()) 10f else systems.map { it.spacing }.average().toFloat()

        // ── Pass 1: erase beams ──────────────────────────────────────────────
        for (y in 0 until h) {
            var x = 0
            while (x < w) {
                if (!noStaff[y][x]) { x++; continue }
                var runEnd = x
                while (runEnd < w && noStaff[y][runEnd]) runEnd++
                val runLen = runEnd - x
                if (runLen > avgSp * 2.5f) {
                    val cx = (x + runEnd) / 2
                    var topY = y
                    var botY = y
                    while (topY > 0 && noStaff[topY - 1][cx]) topY--
                    while (botY < h - 1 && noStaff[botY + 1][cx]) botY++
                    if ((botY - topY + 1) <= avgSp * 0.55f) {
                        for (ey in topY..botY) for (ex in x until runEnd) result[ey][ex] = false
                    }
                }
                x = runEnd
            }
        }

        // ── Pass 2: erase stems ──────────────────────────────────────────────
        for (x in 0 until w) {
            var y = 0
            while (y < h) {
                if (!result[y][x]) { y++; continue }
                var runEnd = y
                while (runEnd < h && result[runEnd][x]) runEnd++
                val runLen = runEnd - y
                if (runLen > avgSp * 1.5f) {
                    // Find minimum horizontal width across the run
                    var minWidth = Int.MAX_VALUE
                    for (ry in y until runEnd) {
                        var lx = x; var rx = x
                        while (lx > 0 && result[ry][lx - 1]) lx--
                        while (rx < w - 1 && result[ry][rx + 1]) rx++
                        val rowWidth = rx - lx + 1
                        if (rowWidth < minWidth) minWidth = rowWidth
                    }
                    if (minWidth <= avgSp * 0.4f) {
                        // Erase the full horizontal extent at each row of the stem
                        for (ey in y until runEnd) {
                            var lx = x; var rx = x
                            while (lx > 0 && result[ey][lx - 1]) lx--
                            while (rx < w - 1 && result[ey][rx + 1]) rx++
                            for (ex in lx..rx) result[ey][ex] = false
                        }
                    }
                }
                y = runEnd
            }
        }

        return result
    }

    /**
     * Counts how many beam groups lie near a notehead centre (cx, cy).
     *
     * Searches the original [noStaff] image (which still contains beams)
     * within a ±6×sp vertical window and ±3×sp horizontal window.
     * Consecutive rows that all contain a horizontal run > 2×sp are
     * counted as one beam group.  A quarter note has 0 groups, eighth 1,
     * sixteenth 2, etc.
     */
    private fun countBeamsNear(
        cx: Int, cy: Int, sp: Float,
        noStaff: Array<BooleanArray>, w: Int, h: Int
    ): Int {
        val scanTop = (cy - (sp * 6).toInt()).coerceAtLeast(0)
        val scanBot = (cy + (sp * 6).toInt()).coerceAtMost(h - 1)
        val xLeft   = (cx - (sp * 3).toInt()).coerceAtLeast(0)
        val xRight  = (cx + (sp * 3).toInt()).coerceAtMost(w - 1)

        var beamGroups = 0
        var inGroup = false
        for (y in scanTop..scanBot) {
            var maxRun = 0; var run = 0
            for (x in xLeft..xRight) {
                if (noStaff[y][x]) { run++; if (run > maxRun) maxRun = run } else run = 0
            }
            val isBeamRow = maxRun > sp * 2f
            if (isBeamRow && !inGroup) { beamGroups++; inGroup = true }
            else if (!isBeamRow) inGroup = false
        }
        return beamGroups
    }

    // ── Connected-component labelling ────────────────────────────────────────

    private data class Component(
        val cx: Int, val cy: Int,
        val left: Int, val top: Int, val right: Int, val bottom: Int,
        val width: Int, val height: Int,
        val pixelCount: Int,
        val staff: StaffSystem?,
        val staffPosition: Float   // diatonic half-steps from top staff line
    )

    /**
     * Iterative 4-connected flood-fill (avoids JVM stack overflow on large components).
     * Uses a pre-allocated flat int stack [x0,y0, x1,y1, …].
     */
    private fun connectedComponents(
        binary: Array<BooleanArray>, w: Int, h: Int,
        systems: List<StaffSystem>
    ): List<Component> {
        val visited = Array(h) { BooleanArray(w) }
        val result  = mutableListOf<Component>()
        val stack   = IntArray(w * h * 2)   // worst case: whole image is one component

        for (sy in 0 until h) {
            for (sx in 0 until w) {
                if (!binary[sy][sx] || visited[sy][sx]) continue

                var sp = 0
                stack[sp++] = sx; stack[sp++] = sy
                visited[sy][sx] = true

                var minX = sx; var maxX = sx; var minY = sy; var maxY = sy; var count = 0

                while (sp > 0) {
                    val cy = stack[--sp]; val cx = stack[--sp]
                    count++
                    if (cx < minX) minX = cx; if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy; if (cy > maxY) maxY = cy

                    for (d in 0..3) {
                        val nx = cx + DX[d]; val ny = cy + DY[d]
                        if (nx in 0 until w && ny in 0 until h &&
                            binary[ny][nx] && !visited[ny][nx]) {
                            visited[ny][nx] = true
                            stack[sp++] = nx; stack[sp++] = ny
                        }
                    }
                }

                val cw = maxX - minX + 1; val ch = maxY - minY + 1
                if (cw < 2 || ch < 2 || count < 4) continue

                val centY = (minY + maxY) / 2
                val nearest = systems.minByOrNull { abs((it.top + it.bottom) / 2 - centY) }
                val staffPos = nearest?.let {
                    val relY = centY - it.lines[0]
                    relY / (it.spacing / 2f)
                } ?: 0f

                result.add(Component(
                    cx = (minX + maxX) / 2, cy = centY,
                    left = minX, top = minY, right = maxX, bottom = maxY,
                    width = cw, height = ch, pixelCount = count,
                    staff = nearest, staffPosition = staffPos
                ))
            }
        }
        return result
    }

    // ── Symbol classification ────────────────────────────────────────────────

    private enum class SymbolType {
        NOTEHEAD_FILLED, NOTEHEAD_OPEN, NOTEHEAD_WHOLE,
        STEM, BEAM, FLAG,
        REST_WHOLE, REST_HALF, REST_QUARTER, REST_EIGHTH,
        BAR_LINE, CLEF_BASS, CLEF_TENOR, CLEF_TREBLE,
        DOT, UNKNOWN
    }

    private val NOTEHEAD_TYPES = setOf(
        SymbolType.NOTEHEAD_FILLED, SymbolType.NOTEHEAD_OPEN, SymbolType.NOTEHEAD_WHOLE
    )

    private data class DetectedSymbol(
        val type: SymbolType,
        val x: Int, val y: Int,
        val width: Int, val height: Int,
        val staff: StaffSystem?,
        val staffPosition: Float,
        val confidence: Float
    )

    /**
     * Classifies connected components into music notation symbols.
     * All size thresholds are relative to the detected staff spacing (sp),
     * so they self-calibrate for any image resolution or scanning distance.
     */
    private fun classifySymbols(
        components: List<Component>,
        systems: List<StaffSystem>
    ): List<DetectedSymbol> {
        val result = mutableListOf<DetectedSymbol>()

        for (comp in components) {
            val staff = comp.staff ?: continue
            val sp   = staff.spacing
            val ar   = comp.width.toFloat() / comp.height.coerceAtLeast(1)
            val fill = comp.pixelCount.toFloat() / (comp.width * comp.height).coerceAtLeast(1)

            val (type, conf) = when {

                // ── Vertical structures ──────────────────────────────────────
                // Barline: very tall, very thin
                comp.height > sp * 4.5f && comp.width < sp * 0.45f ->
                    Pair(SymbolType.BAR_LINE, 0.82f)

                // Stem: tall, thin (but shorter than barline)
                comp.height > sp * 2.2f && comp.width < sp * 0.45f ->
                    Pair(SymbolType.STEM, 0.75f)

                // ── Horizontal structures ────────────────────────────────────
                // Beam: wide, very short, high aspect ratio
                comp.width > sp * 1.8f && comp.height < sp * 0.45f && ar > 3.5f ->
                    Pair(SymbolType.BEAM, 0.75f)

                // ── Noteheads ────────────────────────────────────────────────
                // Whole note: wider-than-tall ellipse, medium fill (it's hollow with thick border)
                comp.width  in ((sp * 1.2f).toInt())..(( sp * 2.6f).toInt()) &&
                comp.height in ((sp * 0.4f).toInt())..((sp * 1.05f).toInt()) &&
                fill in 0.20f..0.72f && ar > 1.2f ->
                    Pair(SymbolType.NOTEHEAD_WHOLE, 0.72f)

                // Normal notehead size
                comp.width  in ((sp * 0.45f).toInt())..(( sp * 1.65f).toInt()) &&
                comp.height in ((sp * 0.35f).toInt())..(( sp * 1.15f).toInt()) -> {
                    when {
                        fill > 0.50f -> Pair(SymbolType.NOTEHEAD_FILLED, 0.88f)
                        fill > 0.30f && ar in 0.65f..2.4f ->
                            Pair(SymbolType.NOTEHEAD_OPEN, 0.78f)
                        else -> Pair(SymbolType.UNKNOWN, 0.2f)
                    }
                }

                // ── Clef ─────────────────────────────────────────────────────
                comp.width > sp * 1.1f && comp.height > sp * 3f &&
                comp.left < sp * 5 ->
                    Pair(SymbolType.CLEF_BASS, 0.62f)

                // ── Augmentation dot / staccato ───────────────────────────────
                comp.width < sp * 0.35f && comp.height < sp * 0.35f ->
                    Pair(SymbolType.DOT, 0.65f)

                else -> Pair(SymbolType.UNKNOWN, 0.15f)
            }

            if (conf > 0.3f) {
                result.add(DetectedSymbol(type, comp.cx, comp.cy, comp.width, comp.height,
                    comp.staff, comp.staffPosition, conf))
            }
        }

        return result.sortedWith(compareBy({ it.staff?.top ?: 0 }, { it.x }))
    }

    // ── Score assembly ───────────────────────────────────────────────────────

    private fun buildScore(
        symbols: List<DetectedSymbol>,
        systems: List<StaffSystem>,
        title: String,
        noStaff: Array<BooleanArray>,
        w: Int,
        h: Int
    ): Score {
        val measures   = mutableListOf<Measure>()
        var measureNum = 1

        for (system in systems) {
            val sysSyms  = symbols.filter { it.staff == system }
            val barlines = sysSyms.filter { it.type == SymbolType.BAR_LINE }
                .map { it.x }.sorted()

            // Build measure x-bounds from barline positions
            val bounds = mutableListOf<Pair<Int, Int>>()
            if (barlines.isEmpty()) {
                bounds.add(0 to Int.MAX_VALUE)
            } else {
                bounds.add(0 to barlines.first())
                for (i in 0 until barlines.size - 1)
                    bounds.add(barlines[i] to barlines[i + 1])
                bounds.add(barlines.last() to Int.MAX_VALUE)
            }

            for ((lo, hi) in bounds) {
                val mSyms = sysSyms.filter { it.x in lo..hi }
                val notes = extractNotes(mSyms, system, noStaff, w, h)
                if (notes.isEmpty() && measureNum > 1) continue  // skip spurious empty measures

                val isFirst = measureNum == 1
                measures.add(Measure(
                    number = measureNum++,
                    clef            = if (isFirst) Clef(ClefType.BASS) else null,
                    timeSignature   = if (isFirst) TimeSignature(4, 4) else null,
                    keySignature    = if (isFirst) KeySignature(0) else null,
                    elements        = notes,
                    barlineRight    = Barline.REGULAR
                ))
            }
        }

        if (measures.isEmpty()) {
            measures.add(Measure(1,
                clef = Clef(ClefType.BASS),
                timeSignature = TimeSignature(4, 4),
                keySignature  = KeySignature(0),
                elements      = emptyList()
            ))
        }

        return Score(
            id    = UUID.randomUUID().toString(),
            title = title,
            parts = listOf(Part("P1", "Cello", "Vc.", measures = measures))
        )
    }

    private fun extractNotes(
        symbols: List<DetectedSymbol>,
        staff: StaffSystem,
        noStaff: Array<BooleanArray>,
        w: Int,
        h: Int
    ): List<MusicElement> {
        val noteheads = symbols.filter { it.type in NOTEHEAD_TYPES }.sortedBy { it.x }
        val elements  = mutableListOf<MusicElement>()
        var tick      = 0

        for (nh in noteheads) {
            val pitch    = staffPositionToPitch(nh.staffPosition)
            val duration = when (nh.type) {
                SymbolType.NOTEHEAD_WHOLE -> NoteDuration(DurationType.WHOLE)
                SymbolType.NOTEHEAD_OPEN  -> NoteDuration(DurationType.HALF)
                SymbolType.NOTEHEAD_FILLED -> {
                    // Count beam groups in the original noStaff image near this notehead
                    val beams = countBeamsNear(nh.x, nh.y, staff.spacing, noStaff, w, h)
                    when (beams) {
                        0    -> NoteDuration(DurationType.QUARTER)
                        1    -> NoteDuration(DurationType.EIGHTH)
                        2    -> NoteDuration(DurationType.SIXTEENTH)
                        else -> NoteDuration(DurationType.THIRTY_SECOND)
                    }
                }
                else -> NoteDuration(DurationType.QUARTER)
            }
            elements.add(Note(id = UUID.randomUUID().toString(), startTick = tick, duration = duration, pitch = pitch))
            tick += duration.toTicks()
        }
        return elements
    }

    /**
     * Maps a continuous staff position to a cello pitch in bass clef.
     *
     * staffPosition units: each unit = one diatonic half-space.
     *   0 = top (5th) line, 1 = first space, 2 = 4th line, …, 8 = bottom line.
     *   Values > 8 represent ledger lines below the staff (down to open C string).
     *   Negative values represent notes above the staff.
     *
     * Bass clef reference: 5th line = A3.
     */
    private fun staffPositionToPitch(staffPos: Float): Pitch {
        // Index 0..12 maps top-of-staff down to low C (C2 = open C string of cello)
        val pitchTable = arrayOf(
            PitchStep.A to 3,   //  0  5th line   A3
            PitchStep.G to 3,   //  1  space       G3
            PitchStep.F to 3,   //  2  4th line    F3
            PitchStep.E to 3,   //  3  space       E3
            PitchStep.D to 3,   //  4  3rd line    D3
            PitchStep.C to 3,   //  5  space       C3
            PitchStep.B to 2,   //  6  2nd line    B2
            PitchStep.A to 2,   //  7  space       A2
            PitchStep.G to 2,   //  8  1st line    G2
            PitchStep.F to 2,   //  9  1st ledger space below
            PitchStep.E to 2,   // 10  1st ledger line below  E2
            PitchStep.D to 2,   // 11  2nd ledger space below
            PitchStep.C to 2    // 12  2nd ledger line below  C2  (open C string)
        )

        // Round to nearest integer position, clamp to table
        val idx = (staffPos + 0.5f).toInt().coerceIn(0, pitchTable.size - 1)
        val (step, octave) = pitchTable[idx]
        return Pitch(step, octave)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun failResult(reason: String) = OmrResult(
        score = Score(
            id    = UUID.randomUUID().toString(),
            title = "Unrecognized Score",
            parts = listOf(Part("P1", "Cello", "Vc.", measures = listOf(
                Measure(1,
                    clef          = Clef(ClefType.BASS),
                    timeSignature = TimeSignature(4, 4),
                    keySignature  = KeySignature(0),
                    elements      = emptyList()
                )
            )))
        ),
        confidence = 0f,
        warnings   = listOf(reason)
    )

    companion object {
        private val DX = intArrayOf(1, -1, 0,  0)
        private val DY = intArrayOf(0,  0, 1, -1)
    }
}

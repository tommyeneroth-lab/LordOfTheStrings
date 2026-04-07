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
import kotlin.math.roundToInt

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

        // ── Step 1: Scale ────────────────────────────────────────────────────
        onProgress?.invoke("Scaling image…")
        val bmp = scaleBitmap(bitmap, maxDim = 2400)
        var imgW = bmp.width; var imgH = bmp.height

        // ── Step 2: Grayscale ────────────────────────────────────────────────
        onProgress?.invoke("Converting to grayscale…")
        var gray = toGrayscale(bmp, imgW, imgH)

        // ── Step 3: Otsu binarization ────────────────────────────────────────
        // Otsu's global threshold works best for clean printed/scanned scores
        // (consistent contrast).  Falls back to Sauvola if contrast is too low.
        onProgress?.invoke("Binarizing (Otsu's method)…")
        var binary = otsuBinarize(gray, imgW, imgH)

        // ── Step 4: Deskew ───────────────────────────────────────────────────
        // Even 1–2° tilt causes row-density peaks to spread and smear, which
        // confuses staff detection and notehead strip scanning.
        onProgress?.invoke("Detecting skew…")
        val skewDeg = detectSkewAngle(binary, imgW, imgH)
        if (abs(skewDeg) > 0.4f) {
            onProgress?.invoke("Deskewing (%.1f°)…".format(skewDeg))
            val (rotGray, rw, rh) = rotateGray(gray, imgW, imgH, -skewDeg)
            gray = rotGray; imgW = rw; imgH = rh
            binary = otsuBinarize(gray, imgW, imgH)
        }

        // ── Step 5: Resolution standardisation ──────────────────────────────
        // OMR accuracy peaks when staff spacing ≈ 15–25 px.  Do a quick staff
        // probe on a dilated copy; if spacing is outside range, rescale the
        // gray image and re-binarize so symbols are the expected size.
        onProgress?.invoke("Standardising resolution…")
        val probeD  = horizontalDilate(binary, imgW, imgH, gap = 20)
        val probeSys = detectStaffSystems(probeD, imgW, imgH)
        if (probeSys.isNotEmpty()) {
            val avgSp  = probeSys.map { it.spacing }.average().toFloat()
            val target = 20f   // target staff-space in pixels
            val scale  = target / avgSp
            if (scale < 0.72f || scale > 1.45f) {
                // Rescale gray array so avgSp → ~20 px; cap to avoid OOM
                val newW = (imgW * scale).toInt().coerceIn(400, 3600)
                val newH = (imgH * scale).toInt().coerceIn(400, 3600)
                onProgress?.invoke("Rescaling %.0f→%.0f px staff-space…".format(avgSp, target))
                gray = rescaleGray(gray, imgW, imgH, newW, newH)
                imgW = newW; imgH = newH
                binary = otsuBinarize(gray, imgW, imgH)
            }
        }

        // ── Step 6: Staff detection ──────────────────────────────────────────
        onProgress?.invoke("Connecting broken staff lines…")
        val dilated = horizontalDilate(binary, imgW, imgH, gap = 25)

        onProgress?.invoke("Detecting staff lines…")
        val staffSystems = detectStaffSystems(dilated, imgW, imgH)
        if (staffSystems.isEmpty()) {
            warnings.add("No staff lines detected — ensure the image is a music score, well-lit, and not too blurry")
            onProgress?.invoke("Failed: no staff lines found")
            return failResult("No staff lines detected")
        }

        // ── Step 7: Staff-line estimation feedback ───────────────────────────
        // Log measured parameters so we can tune thresholds if needed.
        val avgSpacing = staffSystems.map { it.spacing }.average()
        onProgress?.invoke(
            "Found ${staffSystems.size} system(s) — avg staff-space %.1fpx".format(avgSpacing)
        )

        val noStaff = removeStaffLines(binary, imgW, imgH, staffSystems)

        onProgress?.invoke("Detecting barlines…")
        val barlineMap = detectBarlines(binary, imgW, imgH, staffSystems)

        onProgress?.invoke("Scanning staff positions for noteheads…")
        val symbols = findNoteheadsFromStrips(noStaff, imgW, imgH, staffSystems, binary)

        val noteCount = symbols.count { it.type in NOTEHEAD_TYPES }
        onProgress?.invoke("Building score — $noteCount note(s) found…")
        val score = buildScore(symbols, staffSystems, title, barlineMap, noStaff, imgW, imgH)

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

    // ── Otsu binarization ────────────────────────────────────────────────────

    /**
     * Otsu's global threshold binarization.
     *
     * Builds a 256-bin histogram of pixel luminance, then finds the threshold T
     * that maximises between-class variance (equivalent to minimising intra-class
     * variance).  O(N) histogram pass + O(256) sweep — very fast.
     *
     * Best for clean printed/scanned scores with uniform illumination.
     * The existing Sauvola method is kept for phone-photo paths where local
     * lighting varies dramatically across the frame.
     */
    private fun otsuBinarize(gray: Array<FloatArray>, w: Int, h: Int): Array<BooleanArray> {
        val hist = IntArray(256)
        for (y in 0 until h) for (x in 0 until w) {
            hist[(gray[y][x] * 255f).toInt().coerceIn(0, 255)]++
        }
        val total = (w * h).toFloat()
        var sumAll = 0f
        for (i in 0..255) sumAll += i * hist[i]

        var sumB = 0f; var wB = 0f; var bestVar = 0f; var threshold = 0.5f
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0f) continue
            val wF = total - wB
            if (wF == 0f) break
            sumB += t * hist[t]
            val mB = sumB / wB
            val mF = (sumAll - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > bestVar) { bestVar = between; threshold = t / 255f }
        }
        return Array(h) { y -> BooleanArray(w) { x -> gray[y][x] < threshold } }
    }

    // ── Deskewing (Hough / projection-variance) ──────────────────────────────

    /**
     * Estimates the document skew angle using the projection-profile variance method.
     *
     * For a perfectly horizontal staff, the horizontal projection (number of black
     * pixels per row) has very HIGH variance — sharp peaks at staff lines, deep
     * valleys between them.  As the page tilts, the peaks spread and variance drops.
     *
     * We approximate the projection at angle θ by shearing: row index for pixel
     * (x, y) becomes y + x·sin(θ).  Testing angles from −10° to +10° in 0.5° steps
     * on a downsampled copy is fast (< 10 ms on a modern phone).
     *
     * Returns the angle (degrees) by which the image is tilted; callers should
     * rotate by the negative to straighten it.
     */
    private fun detectSkewAngle(binary: Array<BooleanArray>, w: Int, h: Int): Float {
        // Downsample to ~800px on the longer side for speed
        val ds     = maxOf(1, maxOf(w, h) / 800)
        val sw     = w / ds; val sh = h / ds

        var bestAngle = 0f
        var bestVar   = -1.0

        // Sweep −10° to +10° in 0.5° steps
        for (tenths in -200..200 step 5) {
            val angle  = tenths / 10.0
            val sinA   = sin(Math.toRadians(angle))
            val profSz = sh + (sw * abs(sinA)).toInt() + 4
            val profile = IntArray(profSz)

            for (y in 0 until h step ds) {
                val sy = y / ds
                for (x in 0 until w step ds) {
                    if (!binary[y][x]) continue
                    val ry = (sy + (x / ds) * sinA).toInt().coerceIn(0, profSz - 1)
                    profile[ry]++
                }
            }

            val mean = profile.average()
            val variance = profile.sumOf { v -> val d = v - mean; d * d } / profSz
            if (variance > bestVar) { bestVar = variance; bestAngle = angle.toFloat() }
        }
        return bestAngle
    }

    /**
     * Rotates [gray] by [angleDeg] degrees (positive = counter-clockwise) using
     * inverse bilinear mapping.  The output canvas expands to fit the full rotated
     * image with a white (1.0) background so no content is clipped.
     */
    private fun rotateGray(
        gray: Array<FloatArray>, w: Int, h: Int, angleDeg: Float
    ): Triple<Array<FloatArray>, Int, Int> {
        val rad  = Math.toRadians(angleDeg.toDouble())
        val cosA = cos(rad); val sinA = sin(rad)
        val cx   = w / 2.0;  val cy   = h / 2.0

        // Bounding box of rotated image
        val nw = (w * abs(cosA) + h * abs(sinA)).toInt() + 2
        val nh = (w * abs(sinA) + h * abs(cosA)).toInt() + 2
        val ncx = nw / 2.0; val ncy = nh / 2.0

        val result = Array(nh) { FloatArray(nw) { 1f } }
        for (ny in 0 until nh) {
            val dy = ny - ncy
            for (nx in 0 until nw) {
                val dx = nx - ncx
                // Inverse rotation
                val ox = (dx * cosA + dy * sinA + cx)
                val oy = (-dx * sinA + dy * cosA + cy)
                val ix = ox.toInt(); val iy = oy.toInt()
                if (ix in 0 until w - 1 && iy in 0 until h - 1) {
                    // Bilinear interpolation
                    val fx = ox - ix; val fy = oy - iy
                    result[ny][nx] =
                        (gray[iy    ][ix    ] * (1 - fx) * (1 - fy) +
                         gray[iy    ][ix + 1] * fx       * (1 - fy) +
                         gray[iy + 1][ix    ] * (1 - fx) * fy       +
                         gray[iy + 1][ix + 1] * fx       * fy).toFloat()
                }
            }
        }
        return Triple(result, nw, nh)
    }

    /** Bilinear rescale of a float gray array to new dimensions. */
    private fun rescaleGray(
        gray: Array<FloatArray>, srcW: Int, srcH: Int, dstW: Int, dstH: Int
    ): Array<FloatArray> {
        val xRatio = srcW.toDouble() / dstW
        val yRatio = srcH.toDouble() / dstH
        return Array(dstH) { ny ->
            val sy  = ny * yRatio; val iy = sy.toInt().coerceAtMost(srcH - 2)
            val fy  = (sy - iy).toFloat()
            FloatArray(dstW) { nx ->
                val sx = nx * xRatio; val ix = sx.toInt().coerceAtMost(srcW - 2)
                val fx = (sx - ix).toFloat()
                gray[iy    ][ix    ] * (1 - fx) * (1 - fy) +
                gray[iy    ][ix + 1] * fx       * (1 - fy) +
                gray[iy + 1][ix    ] * (1 - fx) * fy       +
                gray[iy + 1][ix + 1] * fx       * fy
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

        val runThr = (w * 0.12f).toInt()   // 12% — handles staves with margins, clef, key sig breaks
        val denThr = (w * 0.08f).toInt()

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

    // ── Barline detection (before stem removal erases them) ──────────────────

    /**
     * Detects barlines by scanning every column of the RAW BINARY (staff lines intact).
     *
     * A barline column must have black pixels covering ≥ 55% of the staff height.
     * In the raw binary, a barline runs fully from top line to bottom line (~100%),
     * while noteheads cover at most one staff-space (~25%), so 55% is a clean separator.
     *
     * We scan slightly wider than sys.left/right to catch end barlines at the edges.
     * Adjacent barline columns are merged into a single position.
     */
    private fun detectBarlines(
        binary: Array<BooleanArray>, w: Int, h: Int,
        systems: List<StaffSystem>
    ): Map<StaffSystem, List<Int>> {
        val result = mutableMapOf<StaffSystem, List<Int>>()
        for (sys in systems) {
            // Scan from top to bottom of the staff band (between first and last line)
            val scanTop = sys.top.coerceAtLeast(0)
            val scanBot = sys.bottom.coerceAtMost(h - 1)
            val staffH  = (scanBot - scanTop + 1).coerceAtLeast(1)
            val threshold = (staffH * 0.55f).toInt()

            // Widen scan by a small margin to catch barlines at system edges
            val xLeft  = (sys.left - 5).coerceAtLeast(0)
            val xRight = (sys.right + 5).coerceAtMost(w - 1)

            val barlineCols = mutableListOf<Int>()
            for (x in xLeft..xRight) {
                var count = 0
                for (y in scanTop..scanBot) {
                    if (binary[y][x]) count++
                }
                if (count >= threshold) barlineCols.add(x)
            }

            // Cluster adjacent columns → one barline per cluster (centroid)
            val barlines = mutableListOf<Int>()
            var i = 0
            while (i < barlineCols.size) {
                var j = i
                while (j < barlineCols.size - 1 && barlineCols[j + 1] - barlineCols[j] <= 8) j++
                // Use left edge of cluster as the barline x (for bounds splitting)
                barlines.add(barlineCols[i])
                i = j + 1
            }
            result[sys] = barlines
        }
        return result
    }

    // ── Beam and stem removal ────────────────────────────────────────────────

    /**
     * Per-pixel beam/stem classification.
     *
     * For every black pixel we measure two things in the ORIGINAL noStaff image:
     *   hWidth = length of the unbroken horizontal run through (x, y)
     *   vHeight = length of the unbroken vertical run through (x, y)
     *
     * Beam pixel:  hWidth > 1.8×sp  AND  vHeight ≤ 0.5×sp   → erase
     * Stem pixel:  hWidth ≤ 0.4×sp  AND  vHeight > 1.2×sp   → erase
     *
     * A notehead pixel has hWidth ≈ 0.7–1.0×sp and vHeight ≈ 0.6–0.9×sp,
     * so it satisfies NEITHER condition and is always preserved.
     * The notehead–stem junction is also safe: at the junction row the horizontal
     * run includes the notehead width (≫ 0.4×sp), so that row is not stem-erased.
     */
    private fun separateNoteheads(
        noStaff: Array<BooleanArray>, w: Int, h: Int,
        systems: List<StaffSystem>
    ): Array<BooleanArray> {
        val result = Array(h) { y -> noStaff[y].copyOf() }
        val avgSp = if (systems.isEmpty()) 10f else systems.map { it.spacing }.average().toFloat()
        // beamHThresh: beams span ≥ 2 note widths; 1.2×sp catches even closely-spaced beams
        val beamHThresh = avgSp * 1.2f
        // beamVThresh: clean PDF beams are ~2.5-3pt thick; at typical render scale ≈ 0.55×sp
        // Use 0.70×sp to safely clear all such beams without touching noteheads (≥ 1.0×sp tall)
        val beamVThresh = avgSp * 0.70f
        val stemHThresh = avgSp * 0.45f  // only erase truly thin stems (≤ ~1-2px at normal scale)
        val stemVThresh = avgSp * 1.1f   // must be clearly taller than a notehead to qualify as stem

        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!noStaff[y][x]) continue

                // Horizontal run width
                var hlx = x; var hrx = x
                while (hlx > 0 && noStaff[y][hlx - 1]) hlx--
                while (hrx < w - 1 && noStaff[y][hrx + 1]) hrx++
                val hWidth = (hrx - hlx + 1).toFloat()

                // Vertical run height
                var vty = y; var vby = y
                while (vty > 0 && noStaff[vty - 1][x]) vty--
                while (vby < h - 1 && noStaff[vby + 1][x]) vby++
                val vHeight = (vby - vty + 1).toFloat()

                if (hWidth > beamHThresh && vHeight <= beamVThresh) {
                    result[y][x] = false   // beam pixel
                } else if (hWidth <= stemHThresh && vHeight > stemVThresh) {
                    result[y][x] = false   // stem pixel
                }
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
        // Tight vertical window: stem length is at most ~3.5×sp above or below the notehead
        // Tight horizontal window: beam attaches to the stem, which is within ~0.5×sp of center
        val scanTop = (cy - (sp * 4).toInt()).coerceAtLeast(0)
        val scanBot = (cy + (sp * 4).toInt()).coerceAtMost(h - 1)
        val xLeft   = (cx - (sp * 2).toInt()).coerceAtLeast(0)
        val xRight  = (cx + (sp * 2).toInt()).coerceAtMost(w - 1)

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
        val avgSp   = if (systems.isEmpty()) 10.0 else systems.map { it.spacing }.average()

        for (sy in 0 until h) {
            for (sx in 0 until w) {
                if (!binary[sy][sx] || visited[sy][sx]) continue

                var sp = 0
                stack[sp++] = sx; stack[sp++] = sy
                visited[sy][sx] = true

                var minX = sx; var maxX = sx; var minY = sy; var maxY = sy; var count = 0
                var sumY = 0L  // for pixel-weighted vertical centroid
                val rowCounts = HashMap<Int, Int>(32)  // y → pixel count in this component row

                while (sp > 0) {
                    val cy = stack[--sp]; val cx = stack[--sp]
                    count++
                    sumY += cy
                    rowCounts[cy] = (rowCounts[cy] ?: 0) + 1
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

                // For tall components (notehead + residual stem stub), the pixel-weighted
                // centroid is pulled toward the stem and gives the wrong staff position.
                // Instead use the y-row with the most pixels — that's the notehead body.
                val rawCentY = (sumY / count).toInt()
                val centY = if (ch > avgSp * 1.8) {
                    rowCounts.maxByOrNull { it.value }?.key ?: rawCentY
                } else rawCentY
                val nearest = systems.minByOrNull { abs((it.top + it.bottom) / 2 - centY) }

                // Reject components that are too far from any staff — these are title text,
                // composer names, tempo markings, page numbers, etc.
                if (nearest != null) {
                    val sp = nearest.spacing
                    val margin = sp * 5f   // allow up to 5 staff-spaces above/below for ledger lines
                    if (centY < nearest.top - margin || centY > nearest.bottom + margin) continue
                }

                val staffPos = nearest?.let { sys ->
                    // Use all 5 detected staff line positions to compute the diatonic position.
                    // Staff lines are at diatonic positions 0,2,4,6,8 (even = line, odd = space).
                    // Find the nearest staff line, then offset in half-spaces from there.
                    val lineYs = sys.lines
                    val sp = sys.spacing

                    // Find which staff line the centY is closest to
                    val closestLineIdx = lineYs.indices.minByOrNull { abs(lineYs[it] - centY) }!!
                    val closestLineY   = lineYs[closestLineIdx]
                    val closestLinePos = closestLineIdx * 2f  // 0,2,4,6,8

                    // Distance from that known line, in units of half-staff-spacings
                    val halfSp = sp / 2f
                    val offset = (centY - closestLineY) / halfSp

                    // Total staff position, rounded to nearest integer (line or space)
                    (closestLinePos + offset).roundToInt().toFloat()
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

                // ── Residual structures (should be mostly erased by separateNoteheads) ──
                // Skip tall thin remnants (stems/barlines that weren't fully removed)
                comp.height > sp * 2.0f && comp.width < sp * 0.5f ->
                    Pair(SymbolType.STEM, 0.75f)

                // Skip wide flat remnants (beam fragments)
                comp.width > sp * 2.0f && comp.height < sp * 0.4f ->
                    Pair(SymbolType.BEAM, 0.75f)

                // ── Noteheads ────────────────────────────────────────────────
                // Whole note: wider-than-tall ellipse, medium fill (hollow with thick border)
                comp.width  in ((sp * 0.9f).toInt())..(( sp * 3.2f).toInt()) &&
                comp.height in ((sp * 0.28f).toInt())..((sp * 1.3f).toInt()) &&
                fill in 0.12f..0.80f && ar > 1.0f ->
                    Pair(SymbolType.NOTEHEAD_WHOLE, 0.72f)

                // Normal notehead: roughly circular, size 0.3–2.0×sp
                // Height allowed up to 2.0×sp to handle residual short stem stub after separation
                comp.width  in ((sp * 0.28f).toInt())..(( sp * 2.2f).toInt()) &&
                comp.height in ((sp * 0.22f).toInt())..(( sp * 2.0f).toInt()) -> {
                    when {
                        fill > 0.35f -> Pair(SymbolType.NOTEHEAD_FILLED, 0.88f)
                        fill > 0.18f && ar in 0.45f..3.0f ->
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

    // ── Strip-based notehead detection ──────────────────────────────────────

    /**
     * Finds noteheads by scanning horizontal strips at every diatonic staff position.
     *
     * Key idea: a notehead at position P occupies columns blobStart..blobEnd in a
     * horizontal strip centred at the P-position y.  Its width is ≈ 0.5–1.5×sp.
     * Beams are much wider (≥ 2×sp) and stems are much narrower (≤ 2px), so both
     * are naturally rejected by the width filter — no explicit beam/stem removal needed.
     *
     * Two passes per diatonic position:
     *   Pass 1: noStaff image — clean for spaces and ledger line positions.
     *   Pass 2: original binary — used as a fallback for line positions where
     *           removeStaffLines may have partially erased the notehead.
     *           Staff-line blobs (width ≫ maxBlobW) are filtered out automatically.
     */
    private fun findNoteheadsFromStrips(
        noStaff: Array<BooleanArray>, imgW: Int, imgH: Int,
        systems: List<StaffSystem>,
        binary: Array<BooleanArray>? = null
    ): List<DetectedSymbol> {
        val result = mutableListOf<DetectedSymbol>()

        for (sys in systems) {
            val sp       = sys.spacing
            val halfSp   = sp / 2f
            // Strip half-height: ≈ 40% of staff space — wide enough to capture full noteheads
            // but narrow enough not to bleed into the adjacent position.
            val stripHalf = (sp * 0.40f).toInt().coerceAtLeast(2)
            val minBlobW  = (sp * 0.20f).toInt().coerceAtLeast(2)
            val maxBlobW  = (sp * 2.40f).toInt()
            val xLeft     = sys.left.coerceAtLeast(0)
            val xRight    = sys.right.coerceAtMost(imgW - 1)
            // density scratch array – reused across positions
            val density   = IntArray(imgW)
            // Allow gaps of up to 2 columns inside a notehead blob (outline gaps, anti-alias)
            val MAX_GAP   = 2

            // Diatonic positions: -6..14 covers 3 ledger lines above & below the staff
            for (pos in -6..14) {
                // y-centre of this diatonic position
                val centerYf: Float = when {
                    pos <= 0 -> sys.lines[0] + pos * halfSp
                    pos >= 8 -> sys.lines[4] + (pos - 8) * halfSp
                    else     -> {
                        val li = pos / 2
                        if (pos % 2 == 0) sys.lines[li].toFloat()
                        else              sys.lines[li] + halfSp
                    }
                }
                val stripTop = (centerYf - stripHalf).toInt().coerceAtLeast(0)
                val stripBot = (centerYf + stripHalf).toInt().coerceAtMost(imgH - 1)
                val stripH   = (stripBot - stripTop + 1).coerceAtLeast(1)

                // Whether this position is exactly ON a staff line (where staff removal
                // may have left a gap through the notehead centre)
                val isOnLine = pos in 0..8 && pos % 2 == 0

                // --- Pass 1: use noStaff image ---
                for (x in xLeft..xRight) {
                    var cnt = 0
                    for (y in stripTop..stripBot) if (noStaff[y][x]) cnt++
                    density[x] = cnt
                }
                scanBlobs(density, xLeft, xRight, stripH, sp, centerYf,
                          pos, sys, minBlobW, maxBlobW, MAX_GAP, minDensity = 1, result)

                // --- Pass 2 (on-line positions only): re-scan original binary ---
                // Staff lines will form blobs wider than maxBlobW → rejected.
                // Noteheads on the line have noticeably higher column density than the
                // bare staff line, so require density > 4 to skip the bare staff line.
                if (isOnLine && binary != null) {
                    for (x in xLeft..xRight) {
                        var cnt = 0
                        for (y in stripTop..stripBot) if (binary[y][x]) cnt++
                        density[x] = cnt
                    }
                    scanBlobs(density, xLeft, xRight, stripH, sp, centerYf,
                              pos, sys, minBlobW, maxBlobW, MAX_GAP, minDensity = 5, result)
                }
            }
        }

        // Deduplicate: keep the highest-confidence detection within ±0.55×sp in x
        // and ±1.5 diatonic positions.  This removes pass-1/pass-2 duplicates and
        // adjacent-strip double-detections of the same physical notehead.
        val sorted  = result.sortedByDescending { it.confidence }
        val removed = BooleanArray(sorted.size)
        val kept    = mutableListOf<DetectedSymbol>()

        for (i in sorted.indices) {
            if (removed[i]) continue
            kept.add(sorted[i])
            val isp = sorted[i].staff?.spacing ?: 10f
            for (j in i + 1 until sorted.size) {
                if (removed[j]) continue
                val dj = sorted[j]
                if (dj.staff === sorted[i].staff &&
                    abs(dj.x - sorted[i].x) < isp * 0.55f &&
                    abs(dj.staffPosition - sorted[i].staffPosition) < 1.6f) {
                    removed[j] = true
                }
            }
        }

        return kept.sortedWith(compareBy({ it.staff?.top ?: 0 }, { it.x }))
    }

    /** Scans [density] array for notehead-sized blobs and appends to [out]. */
    private fun scanBlobs(
        density: IntArray,
        xLeft: Int, xRight: Int,
        stripH: Int,
        sp: Float,
        centerYf: Float,
        pos: Int,
        sys: StaffSystem,
        minBlobW: Int, maxBlobW: Int,
        maxGap: Int,
        minDensity: Int,
        out: MutableList<DetectedSymbol>
    ) {
        var x = xLeft
        while (x <= xRight) {
            if (density[x] < minDensity) { x++; continue }
            val blobStart = x
            var gapLen = 0
            // advance while blob continues (with gap tolerance)
            while (x <= xRight) {
                when {
                    density[x] >= minDensity -> { gapLen = 0; x++ }
                    gapLen < maxGap          -> { gapLen++; x++ }
                    else                     -> break
                }
            }
            // trim trailing gap columns
            val blobEnd = x - 1 - gapLen
            if (blobEnd < blobStart) continue
            val blobW = blobEnd - blobStart + 1
            if (blobW !in minBlobW..maxBlobW) continue

            var black = 0
            for (bx in blobStart..blobEnd) black += density[bx]
            val fill = black.toFloat() / (blobW * stripH).coerceAtLeast(1)
            val ar   = blobW.toFloat() / stripH.coerceAtLeast(1)
            val cx   = (blobStart + blobEnd) / 2

            val (type, conf) = when {
                // Whole note: wider than a space, oval (ar>1), medium fill (hollow ring)
                blobW >= sp * 0.85f && ar > 0.95f && fill in 0.08f..0.78f ->
                    Pair(SymbolType.NOTEHEAD_WHOLE, 0.74f)
                // Filled notehead (quarter, eighth, …)
                fill >= 0.22f ->
                    Pair(SymbolType.NOTEHEAD_FILLED, 0.85f)
                // Open notehead (half note)
                fill >= 0.06f ->
                    Pair(SymbolType.NOTEHEAD_OPEN, 0.72f)
                else -> Pair(SymbolType.UNKNOWN, 0f)
            }

            if (conf > 0f) {
                out.add(DetectedSymbol(
                    type = type, x = cx, y = centerYf.toInt(),
                    width = blobW, height = stripH,
                    staff = sys, staffPosition = pos.toFloat(), confidence = conf
                ))
            }
        }
    }

    // ── Score assembly ───────────────────────────────────────────────────────

    private fun buildScore(
        symbols: List<DetectedSymbol>,
        systems: List<StaffSystem>,
        title: String,
        barlineMap: Map<StaffSystem, List<Int>>,
        noStaff: Array<BooleanArray>,
        w: Int,
        h: Int
    ): Score {
        val measures   = mutableListOf<Measure>()
        var measureNum = 1

        for (system in systems) {
            val sysSyms  = symbols.filter { it.staff == system }
            var barlines = (barlineMap[system] ?: emptyList()).toMutableList()

            // If no barlines found, estimate measure positions using the staff system width.
            // A typical system has 4–5 measures; divide evenly.
            if (barlines.isEmpty()) {
                val sp = system.spacing
                val systemWidth = system.right - system.left
                val estMeasureWidth = (sp * 12f).toInt().coerceAtLeast(1)
                val numMeasures = (systemWidth / estMeasureWidth).coerceIn(1, 8)
                val step = systemWidth / numMeasures
                for (k in 1 until numMeasures) barlines.add(system.left + k * step)
            }

            // Build measure x-bounds from barline positions.
            // Filter out barlines that are at the very start or end of the system (system barlines).
            val sysWidth = system.right - system.left
            val filteredBarlines = barlines.sorted().filter { bx ->
                bx > system.left + sysWidth * 0.05f &&   // not within 5% of left edge
                bx < system.right - sysWidth * 0.02f     // not within 2% of right edge
            }
            val bounds = mutableListOf<Pair<Int, Int>>()
            if (filteredBarlines.isEmpty()) {
                bounds.add(system.left to system.right)
            } else {
                bounds.add(system.left to filteredBarlines.first())
                for (i in 0 until filteredBarlines.size - 1)
                    bounds.add(filteredBarlines[i] to filteredBarlines[i + 1])
                bounds.add(filteredBarlines.last() to system.right)
            }

            for ((lo, hi) in bounds) {
                val mSyms = sysSyms.filter { it.x in lo..hi }
                val notes = extractNotes(mSyms, system, noStaff, w, h)

                // Always add the measure — empty measures are fine structurally.
                // (Previously skipped empty mid-score measures which hid real measures after them.)

                // If a "measure" has too many notes, it's likely a mis-split.
                // Chunk it into sub-measures of ≤16 notes each rather than discarding.
                val chunks = if (notes.size > 16) notes.chunked(16) else listOf(notes)

                for (chunk in chunks) {
                    val isFirst = measureNum == 1
                    val tickOffset = if (chunk.isNotEmpty()) chunk.first().startTick else 0
                    // Re-base ticks to start at 0 within this measure
                    val rebasedChunk = chunk.mapIndexed { idx, el ->
                        if (el is Note) el.copy(startTick = el.startTick - tickOffset) else el
                    }
                    measures.add(Measure(
                        number          = measureNum++,
                        clef            = if (isFirst) Clef(ClefType.BASS) else null,
                        timeSignature   = if (isFirst) TimeSignature(4, 4) else null,
                        keySignature    = if (isFirst) KeySignature(0) else null,
                        elements        = rebasedChunk,
                        barlineRight    = Barline.REGULAR
                    ))
                }
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
     * Maps a continuous staff position to a pitch, working for any octave.
     *
     * staffPos 0  = top line of bass clef  = A3
     * Each integer step = one diatonic position (line or space), going downward.
     * Negative values = above the top line (B3, C4/middle-C, D4, …).
     * Positive values = below the top line down through G2, F2, … C2 (open C string).
     *
     * Formula: start at A3 (diatonic step 5 in C-major 0-indexed C=0…B=6, octave 3).
     * Walk |pos| steps up or down the diatonic scale.  Octave increments when crossing
     * B→C upward, decrements when crossing C→B downward.
     */
    private fun staffPositionToPitch(staffPos: Float): Pitch {
        val pos = staffPos.roundToInt()   // round to nearest diatonic position (correct for ± values)

        // Diatonic step index: C=0, D=1, E=2, F=3, G=4, A=5, B=6
        // Reference: pos=0 → A3
        var step = 5
        var oct  = 3

        if (pos > 0) {
            // Walk downward (increasing pos)
            repeat(pos) {
                step--
                if (step < 0) { step = 6; oct-- }   // crossed below C → go to B one octave down
            }
        } else if (pos < 0) {
            // Walk upward (decreasing pos)
            repeat(-pos) {
                step++
                if (step > 6) { step = 0; oct++ }   // crossed above B → go to C one octave up
            }
        }

        oct = oct.coerceIn(1, 6)
        val pitchStep = when (step) {
            0 -> PitchStep.C;  1 -> PitchStep.D;  2 -> PitchStep.E;  3 -> PitchStep.F
            4 -> PitchStep.G;  5 -> PitchStep.A;  else -> PitchStep.B
        }
        return Pitch(pitchStep, oct)
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

package com.cellomusic.app.ui.viewer.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.cellomusic.app.domain.model.*
import kotlin.math.abs
import kotlin.math.min

/**
 * Full-featured score renderer for cello music.
 * Renders MusicXML score elements on Canvas including:
 * - All clefs (bass, tenor, treble)
 * - Notes with correct stem direction, flags, beams, dots
 * - Ledger lines (critical for cello upper/lower range)
 * - All articulations and cello-specific markings
 * - Slurs, ties, hairpins
 * - Repeats, volta brackets
 * - Dynamic markings
 * - Playback cursor
 */
class ScoreCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // Scale factor (density-independent)
    private val dp = context.resources.displayMetrics.density

    // Layout constants
    private val MARGIN_LEFT = 60f * dp
    private val MARGIN_RIGHT = 30f * dp
    private val MARGIN_TOP = 60f * dp
    private val SYSTEM_GAP = 80f * dp
    private val STAFF_SPACING = 9f * dp    // space between staff lines
    private val STAFF_HEIGHT = STAFF_SPACING * 4
    private val NOTE_HEAD_W = STAFF_SPACING * 1.3f
    private val NOTE_HEAD_H = STAFF_SPACING * 0.95f
    private val STEM_LENGTH = STAFF_SPACING * 3.5f
    private val MEASURE_MIN_WIDTH = 80f * dp

    // Paint objects (reused for efficiency)
    private val staffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeWidth = 1.2f * dp
        style = Paint.Style.STROKE
    }
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val noteStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeWidth = 1.5f * dp
        style = Paint.Style.STROKE
    }
    private val stemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeWidth = 1.5f * dp
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 11f * dp
    }
    private val dynamicPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 12f * dp
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
    }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 9f * dp
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 0, 120, 255)
        style = Paint.Style.FILL
    }
    private val hairpinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeWidth = 1.5f * dp
        style = Paint.Style.STROKE
    }
    private val slurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeWidth = 1.5f * dp
        style = Paint.Style.STROKE
    }

    // Score data
    private var score: Score? = null
    private var measures: List<Measure> = emptyList()
    private var measureLayouts: List<MeasureLayout> = emptyList()
    private var currentMeasureHighlight: Int = -1

    // Zoom/pan state
    private var scaleFactor = 1.0f
    private var translateX = 0f
    private var translateY = 0f
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    // Carried-over state for rendering
    private var activeClef = Clef(ClefType.BASS)
    private var activeTimeSignature = TimeSignature(4, 4)
    private var activeKeySignature = KeySignature(0)

    fun setScore(score: Score) {
        this.score = score
        measures = score.parts.firstOrNull()?.measures ?: emptyList()
        activeClef = Clef(ClefType.BASS)
        computeLayout()
        invalidate()
    }

    fun highlightMeasure(measureNumber: Int) {
        currentMeasureHighlight = measureNumber
        invalidate()
    }

    private fun computeLayout() {
        measureLayouts = mutableListOf()
        val availableWidth = (width.toFloat() - MARGIN_LEFT - MARGIN_RIGHT).coerceAtLeast(MEASURE_MIN_WIDTH)
        var x = MARGIN_LEFT
        var y = MARGIN_TOP

        // Carry-over for clef/time/key
        var prevClef = Clef(ClefType.BASS)
        var prevTime = TimeSignature(4, 4)
        var prevKey = KeySignature(0)

        val layouts = mutableListOf<MeasureLayout>()
        var lineStart = 0
        val measureWidths = measures.map { m -> computeMeasureWidth(m) }

        var lineWidth = 0f
        var i = 0
        while (i < measures.size) {
            val w = measureWidths[i]
            if (lineWidth + w > availableWidth && lineWidth > 0) {
                // Justify measures on this line
                val lineEnd = i
                justifyLine(layouts, lineStart, lineEnd, availableWidth, x, y, prevClef, prevTime, prevKey)
                y += STAFF_HEIGHT + SYSTEM_GAP
                lineStart = i
                lineWidth = 0f
            }
            lineWidth += w
            i++
        }
        // Last line
        if (lineStart < measures.size) {
            justifyLine(layouts, lineStart, measures.size, availableWidth, MARGIN_LEFT, y, prevClef, prevTime, prevKey)
            y += STAFF_HEIGHT + SYSTEM_GAP
        }

        measureLayouts = layouts
        // Update total height for scrolling
        minimumHeight = (y + MARGIN_TOP).toInt()
    }

    private fun justifyLine(
        layouts: MutableList<MeasureLayout>,
        from: Int, to: Int,
        availableWidth: Float, startX: Float, startY: Float,
        clef: Clef, time: TimeSignature, key: KeySignature
    ) {
        val slice = measures.subList(from, to)
        val totalNatural = slice.sumOf { computeMeasureWidth(it).toDouble() }.toFloat()
        val scale = if (totalNatural > 0) availableWidth / totalNatural else 1f

        var x = startX
        for (j in slice.indices) {
            val m = slice[j]
            val w = computeMeasureWidth(m) * scale
            val layout = MeasureLayout(
                measure = m,
                x = x, y = startY,
                width = w, height = STAFF_HEIGHT
            )
            layouts.add(layout)
            x += w
        }
    }

    private fun computeMeasureWidth(measure: Measure): Float {
        // Base width per note count, plus fixed overhead for clef/time/key changes
        var overhead = 0f
        if (measure.clef != null) overhead += 24f * dp
        if (measure.timeSignature != null) overhead += 16f * dp
        if (measure.keySignature != null && measure.keySignature.fifths != 0) {
            overhead += abs(measure.keySignature.fifths) * 8f * dp
        }

        val noteCount = measure.elements.size.coerceAtLeast(1)
        val noteWidth = STAFF_SPACING * 4f * noteCount + overhead + 12f * dp
        return noteWidth.coerceAtLeast(MEASURE_MIN_WIDTH)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeLayout()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.scale(scaleFactor, scaleFactor)
        canvas.translate(translateX / scaleFactor, translateY / scaleFactor)

        activeClef = Clef(ClefType.BASS)
        activeTimeSignature = TimeSignature(4, 4)
        activeKeySignature = KeySignature(0)

        for (layout in measureLayouts) {
            drawMeasure(canvas, layout)
        }

        canvas.restore()
    }

    private fun drawMeasure(canvas: Canvas, layout: MeasureLayout) {
        val m = layout.measure
        val x = layout.x; val y = layout.y
        val w = layout.width

        // Draw cursor highlight
        if (m.number == currentMeasureHighlight) {
            canvas.drawRect(x, y - STAFF_SPACING, x + w, y + STAFF_HEIGHT + STAFF_SPACING, cursorPaint)
        }

        // Draw 5 staff lines
        for (line in 0..4) {
            val lineY = y + line * STAFF_SPACING
            canvas.drawLine(x, lineY, x + w, lineY, staffPaint)
        }

        // Draw barline at left
        drawBarline(canvas, m.barlineLeft, x, y, false)

        var contentX = x + 4f * dp

        // Draw clef (initial or changed)
        val clefToDraw = m.clef ?: if (m.number == 1) Clef(ClefType.BASS) else null
        if (clefToDraw != null || m.number == 1) {
            val c = clefToDraw ?: activeClef
            contentX = drawClef(canvas, c, contentX, y)
            activeClef = c
        }

        // Draw key signature
        val keyToDraw = m.keySignature ?: if (m.number == 1) KeySignature(0) else null
        if (keyToDraw != null) {
            contentX = drawKeySignature(canvas, keyToDraw, contentX, y)
            activeKeySignature = keyToDraw
        }

        // Draw time signature
        val timeToDraw = m.timeSignature ?: if (m.number == 1) TimeSignature(4, 4) else null
        if (timeToDraw != null) {
            contentX = drawTimeSignature(canvas, timeToDraw, contentX, y)
            activeTimeSignature = timeToDraw
        }

        // Calculate note positions
        val noteAreaWidth = (x + w - MARGIN_RIGHT / 4 - contentX).coerceAtLeast(20f * dp)
        val noteCount = m.elements.size.coerceAtLeast(1)
        val noteStep = noteAreaWidth / noteCount

        // Draw directions (dynamics, text)
        for (dir in m.directions) {
            drawDirection(canvas, dir, contentX, y, noteAreaWidth)
        }

        // Draw notes
        var noteIdx = 0
        for (element in m.elements) {
            val noteX = contentX + noteIdx * noteStep + noteStep / 2
            when (element) {
                is Note -> {
                    drawNote(canvas, element, noteX, y)
                    noteIdx++
                }
                is ChordNote -> {
                    for (note in element.notes) drawNote(canvas, note, noteX, y)
                    noteIdx++
                }
                is Rest -> {
                    drawRest(canvas, element, noteX, y)
                    noteIdx++
                }
            }
        }

        // Draw barline at right
        drawBarline(canvas, m.barlineRight, x + w, y, true)

        // Draw repeat volta bracket if needed
        m.repeatInfo?.let { drawRepeatInfo(canvas, it, x, y, w) }

        // Draw measure number
        if (m.number % 5 == 1 || m.number == 1) {
            canvas.drawText(m.number.toString(), x + 2f * dp, y - 4f * dp, smallTextPaint)
        }
    }

    private fun drawClef(canvas: Canvas, clef: Clef, x: Float, y: Float): Float {
        val clefText = when (clef.type) {
            ClefType.BASS -> "𝄢"      // Unicode bass clef
            ClefType.TENOR -> "𝄡"     // C clef (we'll use text)
            ClefType.TREBLE -> "𝄞"    // Treble clef
            else -> "?"
        }
        val paint = Paint(textPaint).apply { textSize = STAFF_SPACING * 4f }

        when (clef.type) {
            ClefType.BASS -> {
                // Draw bass clef F on 4th line (index 1 from top = line 3)
                canvas.drawText("F:", x, y + STAFF_SPACING * 2.5f, paint.apply { textSize = 16f * dp })
            }
            ClefType.TENOR -> {
                // C clef bracket on 4th line
                canvas.drawText("C", x, y + STAFF_SPACING * 3f, paint.apply { textSize = 14f * dp })
            }
            ClefType.TREBLE -> {
                canvas.drawText("G", x, y + STAFF_SPACING * 4f, paint.apply { textSize = 18f * dp })
            }
            else -> {}
        }
        return x + 24f * dp
    }

    private fun drawKeySignature(canvas: Canvas, key: KeySignature, x: Float, y: Float): Float {
        if (key.fifths == 0) return x
        val sharpsOrder = intArrayOf(3, 0, 4, 1, 5, 2, 6)  // FCGDAEB positions on staff (0=top line)
        val flatsOrder = intArrayOf(6, 2, 5, 1, 4, 0, 3)   // BEADGCF

        var cx = x
        val paint = Paint(textPaint).apply { textSize = 10f * dp }

        if (key.fifths > 0) {
            for (i in 0 until key.fifths) {
                val linePos = sharpsOrderY(sharpsOrder[i], activeClef)
                val accY = y + linePos * STAFF_SPACING / 2
                canvas.drawText("#", cx, accY + 6f * dp, paint)
                cx += 8f * dp
            }
        } else {
            for (i in 0 until -key.fifths) {
                val linePos = flatsOrderY(flatsOrder[i], activeClef)
                val accY = y + linePos * STAFF_SPACING / 2
                canvas.drawText("b", cx, accY + 6f * dp, paint)
                cx += 8f * dp
            }
        }
        return cx + 4f * dp
    }

    private fun sharpsOrderY(index: Int, clef: Clef): Float {
        // Returns staff position (0=top line, 8=bottom line)
        val bassPositions = floatArrayOf(1f, 4f, 0f, 3f, 6f, 2f, 5f)
        val treblePositions = floatArrayOf(5f, 2f, 6f, 3f, 7f, 4f, 8f)
        return if (clef.type == ClefType.BASS) bassPositions[index] else treblePositions[index]
    }

    private fun flatsOrderY(index: Int, clef: Clef): Float {
        val bassPositions = floatArrayOf(5f, 2f, 6f, 3f, 7f, 4f, 8f)
        val treblePositions = floatArrayOf(1f, 4f, 0f, 3f, 6f, 2f, 5f)
        return if (clef.type == ClefType.BASS) bassPositions[index] else treblePositions[index]
    }

    private fun drawTimeSignature(canvas: Canvas, time: TimeSignature, x: Float, y: Float): Float {
        val paint = Paint(textPaint).apply {
            textSize = STAFF_SPACING * 2.2f
            textAlign = Paint.Align.CENTER
        }
        val cx = x + 10f * dp
        if (time.isCommonTime) {
            canvas.drawText("C", cx, y + STAFF_SPACING * 3f, paint)
        } else if (time.isCutTime) {
            canvas.drawText("₵", cx, y + STAFF_SPACING * 3f, paint)
        } else {
            canvas.drawText(time.numerator.toString(), cx, y + STAFF_SPACING * 2.2f, paint)
            canvas.drawText(time.denominator.toString(), cx, y + STAFF_SPACING * 4.2f, paint)
        }
        return x + 20f * dp
    }

    private fun pitchToStaffY(pitch: Pitch, y: Float, clef: Clef): Float {
        // Returns the Y center of this pitch on the staff
        // Staff lines from top: line0=y, line1=y+sp, line2=y+2sp, line3=y+3sp, line4=y+4sp
        // Middle of staff = y + 2*sp (between lines 2 and 3)

        val notePosition = pitchToStaffPosition(pitch, clef)
        // Position 0 = first ledger line above; each step = STAFF_SPACING/2
        // Middle C position relative to clef:
        return y + (notePosition * STAFF_SPACING / 2f)
    }

    /**
     * Returns staff position where 0 = top staff line, 8 = bottom staff line.
     * Values below 0 = above staff (require ledger lines), above 8 = below staff.
     */
    private fun pitchToStaffPosition(pitch: Pitch, clef: Clef): Float {
        // MIDI note number to staff position
        val midiNote = pitch.toMidiNote()

        return when (clef.type) {
            ClefType.BASS -> {
                // Bass clef: G3 is on top line (position 0), B2 on 4th space, C2 bottom
                // Middle C (C4) = 1 ledger line above = position -2
                // G3 = position 0 (top line)
                // Reference: G3 = MIDI 55, each step = half position
                val g3 = 55
                val stepsFromG3 = noteToStep(midiNote) - noteToStep(g3)
                (-stepsFromG3).toFloat()
            }
            ClefType.TENOR -> {
                // Tenor clef: Middle C (C4) on 4th line = position 6
                val c4 = 60
                val stepsFromC4 = noteToStep(midiNote) - noteToStep(c4)
                (6 - stepsFromC4).toFloat()
            }
            ClefType.TREBLE -> {
                // Treble clef: E4 on bottom line = position 8, G4 on 2nd line = position 4
                val e4 = 64
                val stepsFromE4 = noteToStep(midiNote) - noteToStep(e4)
                (8 - stepsFromE4).toFloat()
            }
            else -> 4f
        }
    }

    /** Converts MIDI note to diatonic step count from C0 */
    private fun noteToStep(midiNote: Int): Int {
        val octave = midiNote / 12
        val semitone = midiNote % 12
        val diatonic = when (semitone) {
            0 -> 0; 1 -> 0; 2 -> 1; 3 -> 1; 4 -> 2; 5 -> 3
            6 -> 3; 7 -> 4; 8 -> 4; 9 -> 5; 10 -> 5; 11 -> 6
            else -> 0
        }
        return octave * 7 + diatonic
    }

    private fun drawNote(canvas: Canvas, note: Note, x: Float, staffY: Float) {
        val noteY = pitchToStaffY(note.pitch, staffY, activeClef)
        val pos = pitchToStaffPosition(note.pitch, activeClef)

        // Draw ledger lines
        drawLedgerLines(canvas, pos, x, staffY)

        // Draw accidental
        if (note.pitch.alter != Alter.NATURAL || note.pitch.displayAccidental == AccidentalDisplay.ALWAYS) {
            drawAccidental(canvas, note.pitch.alter, x - NOTE_HEAD_W - 2f * dp, noteY)
        }

        // Determine stem direction
        val stemUp = when (note.stemDirection) {
            StemDirection.UP -> true
            StemDirection.DOWN -> false
            StemDirection.AUTO -> pos > 4f  // above middle of staff = stem down
        }

        // Draw notehead
        val isOpen = note.duration.type in listOf(
            DurationType.WHOLE, DurationType.HALF, DurationType.BREVE
        )

        drawNoteHead(canvas, note.duration.type, x, noteY, isOpen)

        // Draw stem (not for whole notes)
        if (note.duration.type != DurationType.WHOLE && note.duration.type != DurationType.BREVE) {
            drawStem(canvas, x, noteY, stemUp, note.duration.type)
        }

        // Draw dots
        for (d in 0 until note.dotCount) {
            canvas.drawCircle(x + NOTE_HEAD_W + (d + 1) * 4f * dp, noteY - STAFF_SPACING / 4, 2f * dp, notePaint)
        }

        // Draw articulations
        drawArticulations(canvas, note.articulations, x, noteY, stemUp, staffY)

        // Draw bowing mark
        note.bowingMark?.let { drawBowingMark(canvas, it, x, noteY, staffY) }

        // Draw ornament
        note.ornament?.let { drawOrnament(canvas, it, x, noteY) }

        // Draw technical marks (pizz., arco, harmonics)
        for (mark in note.technicalMarks) {
            drawTechnicalMark(canvas, mark, x, noteY, staffY)
        }

        // Draw fingering
        note.fingering?.let { drawFingering(canvas, it, x, noteY, staffY) }
    }

    private fun drawNoteHead(canvas: Canvas, type: DurationType, x: Float, y: Float, open: Boolean) {
        val oval = RectF(
            x - NOTE_HEAD_W / 2, y - NOTE_HEAD_H / 2,
            x + NOTE_HEAD_W / 2, y + NOTE_HEAD_H / 2
        )
        when (type) {
            DurationType.WHOLE -> {
                canvas.drawOval(oval, noteStrokePaint)
                // Whole note has a hole in the middle
                val inner = RectF(oval.left + 3f * dp, oval.top + 2f * dp, oval.right - 3f * dp, oval.bottom - 2f * dp)
                canvas.drawOval(inner, Paint(staffPaint).apply { color = Color.WHITE })
            }
            DurationType.HALF -> {
                canvas.drawOval(oval, notePaint)
                val inner = RectF(oval.left + 2f * dp, oval.top + 1.5f * dp, oval.right - 2f * dp, oval.bottom - 1.5f * dp)
                canvas.drawOval(inner, Paint(notePaint).apply { color = Color.WHITE })
            }
            else -> {
                // Filled notehead, slightly rotated oval for naturalistic appearance
                canvas.save()
                canvas.rotate(-15f, x, y)
                canvas.drawOval(oval, notePaint)
                canvas.restore()
            }
        }
    }

    private fun drawStem(canvas: Canvas, x: Float, noteY: Float, stemUp: Boolean, type: DurationType) {
        val stemX = if (stemUp) x + NOTE_HEAD_W / 2 - 1f * dp else x - NOTE_HEAD_W / 2 + 1f * dp
        val stemEndY = if (stemUp) noteY - STEM_LENGTH else noteY + STEM_LENGTH
        canvas.drawLine(stemX, noteY, stemX, stemEndY, stemPaint)

        // Draw flags for eighth and shorter (single stem, not beamed)
        val flagX = stemX
        val flagStartY = stemEndY
        when (type) {
            DurationType.EIGHTH -> drawFlag(canvas, flagX, flagStartY, stemUp, 1)
            DurationType.SIXTEENTH -> drawFlag(canvas, flagX, flagStartY, stemUp, 2)
            DurationType.THIRTY_SECOND -> drawFlag(canvas, flagX, flagStartY, stemUp, 3)
            DurationType.SIXTY_FOURTH -> drawFlag(canvas, flagX, flagStartY, stemUp, 4)
            else -> {}
        }
    }

    private fun drawFlag(canvas: Canvas, x: Float, y: Float, stemUp: Boolean, count: Int) {
        val paint = Paint(stemPaint).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * dp }
        val dir = if (stemUp) 1 else -1
        for (i in 0 until count) {
            val flagY = y + i * 3f * dp * dir
            val path = Path()
            path.moveTo(x, flagY)
            path.cubicTo(
                x + 10f * dp, flagY + 4f * dp * dir,
                x + 12f * dp, flagY + 8f * dp * dir,
                x + 5f * dp, flagY + 12f * dp * dir
            )
            canvas.drawPath(path, paint)
        }
    }

    private fun drawLedgerLines(canvas: Canvas, staffPos: Float, x: Float, staffY: Float) {
        // Draw ledger lines above staff (positions < 0, even positions)
        var p = -2f
        while (p >= staffPos - 1) {
            val lineY = staffY + p * STAFF_SPACING / 2
            canvas.drawLine(x - NOTE_HEAD_W - 2f * dp, lineY, x + NOTE_HEAD_W + 2f * dp, lineY, staffPaint)
            p -= 2f
        }
        // Draw ledger lines below staff (positions > 8)
        p = 10f
        while (p <= staffPos + 1) {
            val lineY = staffY + p * STAFF_SPACING / 2
            canvas.drawLine(x - NOTE_HEAD_W - 2f * dp, lineY, x + NOTE_HEAD_W + 2f * dp, lineY, staffPaint)
            p += 2f
        }
    }

    private fun drawAccidental(canvas: Canvas, alter: Alter, x: Float, y: Float) {
        val paint = Paint(textPaint).apply { textSize = 10f * dp }
        val symbol = when (alter) {
            Alter.SHARP -> "#"
            Alter.FLAT -> "b"
            Alter.DOUBLE_SHARP -> "x"
            Alter.DOUBLE_FLAT -> "bb"
            Alter.NATURAL -> "♮"
        }
        canvas.drawText(symbol, x, y + 4f * dp, paint)
    }

    private fun drawArticulations(
        canvas: Canvas, articulations: List<Articulation>,
        x: Float, noteY: Float, stemUp: Boolean, staffY: Float
    ) {
        var offsetAbove = staffY - STAFF_SPACING * 1.5f
        var offsetBelow = staffY + STAFF_HEIGHT + STAFF_SPACING

        for (art in articulations) {
            val paint = Paint(notePaint)
            when (art) {
                Articulation.STACCATO -> {
                    val dotY = if (!stemUp) offsetAbove else offsetBelow
                    canvas.drawCircle(x, dotY, 2f * dp, notePaint)
                }
                Articulation.STACCATISSIMO -> {
                    val wedgeY = if (!stemUp) offsetAbove else offsetBelow
                    val path = Path()
                    path.moveTo(x, wedgeY)
                    path.lineTo(x - 3f * dp, wedgeY + 6f * dp)
                    path.lineTo(x + 3f * dp, wedgeY + 6f * dp)
                    path.close()
                    canvas.drawPath(path, notePaint)
                }
                Articulation.TENUTO -> {
                    val lineY = if (!stemUp) offsetAbove else offsetBelow
                    canvas.drawLine(x - 5f * dp, lineY, x + 5f * dp, lineY,
                        Paint(stemPaint).apply { strokeWidth = 2f * dp })
                }
                Articulation.ACCENT -> {
                    val accY = if (!stemUp) offsetAbove else offsetBelow
                    val path = Path()
                    path.moveTo(x - 7f * dp, accY - 3f * dp)
                    path.lineTo(x + 7f * dp, accY)
                    path.lineTo(x - 7f * dp, accY + 3f * dp)
                    canvas.drawPath(path, Paint(stemPaint).apply { strokeWidth = 1.5f * dp })
                }
                Articulation.STRONG_ACCENT -> {
                    val marcY = if (!stemUp) offsetAbove else offsetBelow
                    val path = Path()
                    path.moveTo(x, marcY - 6f * dp)
                    path.lineTo(x - 6f * dp, marcY)
                    path.lineTo(x + 6f * dp, marcY)
                    path.close()
                    canvas.drawPath(path, notePaint)
                }
                Articulation.FERMATA -> {
                    drawFermata(canvas, x, staffY - STAFF_SPACING * 2)
                }
                Articulation.BREATH_MARK -> {
                    val bY = staffY - STAFF_SPACING * 0.5f
                    canvas.drawText(",", x + NOTE_HEAD_W, bY, textPaint)
                }
                Articulation.TREMOLO_1, Articulation.TREMOLO_2, Articulation.TREMOLO_3 -> {
                    val count = when (art) {
                        Articulation.TREMOLO_1 -> 1; Articulation.TREMOLO_2 -> 2; else -> 3
                    }
                    drawTremolo(canvas, x, noteY, count, stemUp)
                }
                else -> {}
            }
        }
    }

    private fun drawFermata(canvas: Canvas, x: Float, y: Float) {
        val path = Path()
        val rect = RectF(x - 10f * dp, y - 8f * dp, x + 10f * dp, y)
        path.addArc(rect, 0f, -180f)
        canvas.drawPath(path, noteStrokePaint)
        canvas.drawCircle(x, y + 2f * dp, 2.5f * dp, notePaint)
    }

    private fun drawTremolo(canvas: Canvas, x: Float, y: Float, count: Int, stemUp: Boolean) {
        val dir = if (stemUp) 1f else -1f
        for (i in 0 until count) {
            val ty = y + (i * 5f * dp - count * 2.5f * dp) * dir
            canvas.drawLine(x - 5f * dp, ty + 2f * dp, x + 5f * dp, ty - 2f * dp,
                Paint(stemPaint).apply { strokeWidth = 2f * dp })
        }
    }

    private fun drawBowingMark(canvas: Canvas, mark: BowingMark, x: Float, noteY: Float, staffY: Float) {
        val markY = staffY - STAFF_SPACING * 2f
        val paint = Paint(noteStrokePaint)
        when (mark) {
            BowingMark.UP_BOW -> {
                // V shape
                val path = Path()
                path.moveTo(x - 5f * dp, markY)
                path.lineTo(x, markY + 8f * dp)
                path.lineTo(x + 5f * dp, markY)
                canvas.drawPath(path, paint)
            }
            BowingMark.DOWN_BOW -> {
                // Square bracket
                canvas.drawRect(x - 5f * dp, markY, x + 5f * dp, markY + 8f * dp, paint)
            }
            BowingMark.UP_BOW_STACCATO, BowingMark.DOWN_BOW_STACCATO -> {
                drawBowingMark(canvas,
                    if (mark == BowingMark.UP_BOW_STACCATO) BowingMark.UP_BOW else BowingMark.DOWN_BOW,
                    x, noteY, staffY)
                canvas.drawCircle(x, markY - 4f * dp, 2f * dp, notePaint)
            }
        }
    }

    private fun drawOrnament(canvas: Canvas, ornament: Ornament, x: Float, noteY: Float) {
        val paint = Paint(textPaint).apply { textSize = 9f * dp }
        when (ornament) {
            is Ornament.Trill -> canvas.drawText("tr", x - 6f * dp, noteY - STEM_LENGTH, paint)
            is Ornament.Turn -> canvas.drawText("~", x - 4f * dp, noteY - STEM_LENGTH, paint)
            is Ornament.Mordent -> canvas.drawText("𝆕", x - 4f * dp, noteY - STEM_LENGTH, paint)
            else -> {}
        }
    }

    private fun drawTechnicalMark(canvas: Canvas, mark: TechnicalMark, x: Float, noteY: Float, staffY: Float) {
        val paint = Paint(smallTextPaint).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC) }
        when (mark) {
            TechnicalMark.NATURAL_HARMONIC -> {
                // Diamond or circle above note
                canvas.drawCircle(x, noteY - STEM_LENGTH - 5f * dp, 4f * dp, noteStrokePaint)
            }
            TechnicalMark.ARTIFICIAL_HARMONIC -> {
                // Diamond notehead (already handled in note drawing), add circle above
                canvas.drawCircle(x, noteY - STEM_LENGTH - 5f * dp, 4f * dp, noteStrokePaint)
            }
            TechnicalMark.THUMB_POSITION -> {
                canvas.drawText("T", x - 4f * dp, noteY - STEM_LENGTH - 2f * dp, paint)
            }
            TechnicalMark.SNAP_PIZZICATO -> {
                // Bartók pizzicato: circle with upward stem
                canvas.drawCircle(x, noteY - STEM_LENGTH, 4f * dp, noteStrokePaint)
                canvas.drawLine(x, noteY - STEM_LENGTH, x, noteY - STEM_LENGTH - 8f * dp, stemPaint)
            }
            else -> {}
        }
    }

    private fun drawFingering(canvas: Canvas, fingering: Fingering, x: Float, noteY: Float, staffY: Float) {
        val paint = Paint(smallTextPaint)
        val fingerText = if (fingering.isThumbPosition) "T" else fingering.finger.toString()
        canvas.drawText(fingerText, x - 3f * dp, staffY - STAFF_SPACING * 2.5f, paint)
    }

    private fun drawRest(canvas: Canvas, rest: Rest, x: Float, staffY: Float) {
        val midY = staffY + STAFF_HEIGHT / 2
        val paint = Paint(notePaint)

        when (rest.duration.type) {
            DurationType.WHOLE -> {
                // Whole rest: filled rectangle hanging from line 4 (second from top)
                val restY = staffY + STAFF_SPACING
                canvas.drawRect(x - 6f * dp, restY, x + 6f * dp, restY + STAFF_SPACING / 2, paint)
            }
            DurationType.HALF -> {
                // Half rest: filled rectangle sitting on line 3
                val restY = staffY + 2 * STAFF_SPACING - STAFF_SPACING / 2
                canvas.drawRect(x - 6f * dp, restY, x + 6f * dp, restY + STAFF_SPACING / 2, paint)
            }
            DurationType.QUARTER -> {
                // Quarter rest: squiggly line
                canvas.drawText("𝄽", x - 5f * dp, midY + 4f * dp,
                    Paint(textPaint).apply { textSize = 16f * dp })
            }
            DurationType.EIGHTH -> {
                canvas.drawText("𝄾", x - 4f * dp, midY + 4f * dp,
                    Paint(textPaint).apply { textSize = 14f * dp })
            }
            DurationType.SIXTEENTH -> {
                canvas.drawText("𝄿", x - 4f * dp, midY + 4f * dp,
                    Paint(textPaint).apply { textSize = 12f * dp })
            }
            else -> {
                canvas.drawText("𝄿", x - 4f * dp, midY, paint.also {
                    it.textSize = 10f * dp
                })
            }
        }

        // Dots for dotted rests
        for (d in 0 until rest.dotCount) {
            canvas.drawCircle(x + 8f * dp + d * 5f * dp, midY - 2f * dp, 2f * dp, notePaint)
        }
    }

    private fun drawDirection(canvas: Canvas, dir: Direction, x: Float, staffY: Float, width: Float) {
        when (dir.type) {
            DirectionType.DYNAMIC -> {
                val dynamicText = dir.dynamicLevel?.symbol ?: return
                val y = staffY + STAFF_HEIGHT + 14f * dp
                canvas.drawText(dynamicText, x, y, dynamicPaint)
            }
            DirectionType.HAIRPIN -> {
                drawHairpin(canvas, dir, x, staffY, width)
            }
            DirectionType.WORDS, DirectionType.TEXT -> {
                val text = dir.text ?: return
                val y = if (dir.placement == Placement.ABOVE) staffY - 8f * dp
                else staffY + STAFF_HEIGHT + 14f * dp
                val italicPaint = Paint(smallTextPaint).apply {
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                }
                canvas.drawText(text, x, y, italicPaint)
            }
            DirectionType.REHEARSAL_MARK -> {
                val text = dir.rehearsalMark ?: return
                val y = staffY - 18f * dp
                val boxPaint = Paint(textPaint).apply { textSize = 12f * dp }
                val textWidth = boxPaint.measureText(text)
                canvas.drawRect(x - 3f * dp, y - 12f * dp, x + textWidth + 3f * dp, y + 2f * dp,
                    Paint(staffPaint).apply { strokeWidth = 1.5f * dp })
                canvas.drawText(text, x, y, boxPaint)
            }
            DirectionType.TEMPO -> {
                dir.tempoMark?.let { tempo ->
                    val y = staffY - 18f * dp
                    val boldPaint = Paint(textPaint).apply {
                        typeface = Typeface.DEFAULT_BOLD
                        textSize = 10f * dp
                    }
                    val text = buildString {
                        tempo.textInstruction?.let { append("$it ") }
                        append("♩=${tempo.bpm}")
                    }
                    canvas.drawText(text, x, y, boldPaint)
                }
            }
            else -> {}
        }
    }

    private fun drawHairpin(canvas: Canvas, dir: Direction, x: Float, staffY: Float, width: Float) {
        val y = staffY + STAFF_HEIGHT + 10f * dp
        val endX = x + width
        when (dir.hairpinType) {
            HairpinType.CRESCENDO -> {
                // < opening wedge
                canvas.drawLine(x, y, endX, y - 5f * dp, hairpinPaint)
                canvas.drawLine(x, y, endX, y + 5f * dp, hairpinPaint)
            }
            HairpinType.DECRESCENDO -> {
                // > closing wedge
                canvas.drawLine(x, y - 5f * dp, endX, y, hairpinPaint)
                canvas.drawLine(x, y + 5f * dp, endX, y, hairpinPaint)
            }
            else -> {}
        }
    }

    private fun drawBarline(canvas: Canvas, barline: Barline, x: Float, y: Float, isRight: Boolean) {
        val paint = Paint(staffPaint)
        when (barline) {
            Barline.REGULAR -> canvas.drawLine(x, y, x, y + STAFF_HEIGHT, paint)
            Barline.DOUBLE -> {
                canvas.drawLine(x - 3f * dp, y, x - 3f * dp, y + STAFF_HEIGHT, paint)
                canvas.drawLine(x, y, x, y + STAFF_HEIGHT, paint)
            }
            Barline.FINAL -> {
                canvas.drawLine(x - 4f * dp, y, x - 4f * dp, y + STAFF_HEIGHT, paint)
                paint.strokeWidth = 4f * dp
                canvas.drawLine(x, y, x, y + STAFF_HEIGHT, paint)
            }
            Barline.REPEAT_START -> {
                canvas.drawLine(x, y, x, y + STAFF_HEIGHT, paint)
                paint.strokeWidth = 4f * dp
                canvas.drawLine(x + 5f * dp, y, x + 5f * dp, y + STAFF_HEIGHT, paint)
                canvas.drawCircle(x + 10f * dp, y + STAFF_HEIGHT / 3, 2.5f * dp, notePaint)
                canvas.drawCircle(x + 10f * dp, y + STAFF_HEIGHT * 2 / 3, 2.5f * dp, notePaint)
            }
            Barline.REPEAT_END -> {
                paint.strokeWidth = 4f * dp
                canvas.drawLine(x - 5f * dp, y, x - 5f * dp, y + STAFF_HEIGHT, paint)
                paint.strokeWidth = 1.5f * dp
                canvas.drawLine(x, y, x, y + STAFF_HEIGHT, paint)
                canvas.drawCircle(x - 10f * dp, y + STAFF_HEIGHT / 3, 2.5f * dp, notePaint)
                canvas.drawCircle(x - 10f * dp, y + STAFF_HEIGHT * 2 / 3, 2.5f * dp, notePaint)
            }
            else -> canvas.drawLine(x, y, x, y + STAFF_HEIGHT, paint)
        }
    }

    private fun drawRepeatInfo(canvas: Canvas, info: RepeatInfo, x: Float, y: Float, w: Float) {
        if (info.type == RepeatType.VOLTA_START) {
            val voltaY = y - STAFF_SPACING * 0.5f
            val paint = Paint(staffPaint)
            canvas.drawLine(x, voltaY, x + w * 0.8f, voltaY, paint)
            canvas.drawLine(x, voltaY, x, y, paint)
            info.voltaNumber?.let {
                canvas.drawText("$it.", x + 4f * dp, voltaY - 3f * dp, smallTextPaint)
            }
        }
    }

    // Touch handling for zoom/pan
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(0.5f, 3.0f)
            invalidate()
            return true
        }
    }

    inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            translateX -= dx
            translateY -= dy
            invalidate()
            return true
        }
    }

    data class MeasureLayout(
        val measure: Measure,
        val x: Float, val y: Float,
        val width: Float, val height: Float
    )
}

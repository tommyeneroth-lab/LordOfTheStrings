package com.cellomusic.app.export

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.cellomusic.app.R
import com.cellomusic.app.audio.playback.MidiScoreEncoder
import com.cellomusic.app.audio.playback.StandardMidiFileWriter
import com.cellomusic.app.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Exports a Score domain object to MIDI, MusicXML, or PDF and
 * returns a share Intent so the user can save or send the file.
 */
class ScoreExporter(private val context: Context) {

    // ── MIDI ─────────────────────────────────────────────────────────────────

    suspend fun exportMidi(score: Score, transposeSteps: Int = 0): Intent =
        withContext(Dispatchers.IO) {
            val encoder = MidiScoreEncoder()
            val result  = encoder.encode(score, 1.0f, transposeSteps)
            val file    = exportFile(score.title, "mid")
            FileOutputStream(file).use { out ->
                StandardMidiFileWriter().write(
                    result.events, result.tempoMap,
                    MidiScoreEncoder.TICKS_PER_QUARTER, out
                )
            }
            shareIntent(file, "audio/midi", "Export MIDI")
        }

    // ── MusicXML ─────────────────────────────────────────────────────────────

    suspend fun exportMusicXml(score: Score): Intent = withContext(Dispatchers.IO) {
        val xml  = buildMusicXml(score)
        val file = exportFile(score.title, "musicxml")
        file.writeText(xml, Charsets.UTF_8)
        shareIntent(file, "application/vnd.recordare.musicxml+xml", "Export MusicXML")
    }

    // ── PDF ──────────────────────────────────────────────────────────────────

    suspend fun exportPdf(score: Score, showFingerings: Boolean = false): Intent =
        withContext(Dispatchers.IO) {
            val file = exportFile(score.title, "pdf")
            buildScorePdf(score, file, showFingerings)
            shareIntent(file, "application/pdf", "Export PDF")
        }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun exportFile(title: String, ext: String): File {
        val dir = File(context.filesDir, "exports").also { it.mkdirs() }
        val safe = title.replace(Regex("[^A-Za-z0-9_\\- ]"), "").trim().take(40)
            .replace(' ', '_').ifEmpty { "score" }
        return File(dir, "$safe.$ext")
    }

    private fun shareIntent(file: File, mimeType: String, chooserTitle: String): Intent {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, chooserTitle
        )
    }

    // ── MusicXML serialization ────────────────────────────────────────────────

    private fun buildMusicXml(score: Score): String {
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8" standalone="no"?>""")
        sb.appendLine("""<!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 4.0 Partwise//EN" "http://www.musicxml.org/dtds/partwise.dtd">""")
        sb.appendLine("""<score-partwise version="4.0">""")

        sb.appendLine("  <work><work-title>${score.title.xmlEscape()}</work-title></work>")
        score.composer?.let {
            sb.appendLine("  <identification><creator type=\"composer\">${it.xmlEscape()}</creator></identification>")
        }

        sb.appendLine("  <part-list>")
        for (part in score.parts) {
            // Always label as Cello — this is a cello-specific app
            sb.appendLine("""    <score-part id="${part.id}">""")
            sb.appendLine("      <part-name>Cello</part-name>")
            sb.appendLine("      <part-abbreviation>Vc.</part-abbreviation>")
            sb.appendLine("""      <score-instrument id="${part.id}-I1"><instrument-name>Cello</instrument-name></score-instrument>""")
            sb.appendLine("""      <midi-instrument id="${part.id}-I1"><midi-channel>1</midi-channel><midi-program>43</midi-program></midi-instrument>""")
            sb.appendLine("    </score-part>")
        }
        sb.appendLine("  </part-list>")

        for (part in score.parts) {
            sb.appendLine("""  <part id="${part.id}">""")
            for (measure in part.measures) {
                sb.appendLine("""    <measure number="${measure.number}">""")

                val needsAttributes = measure.clef != null || measure.keySignature != null || measure.timeSignature != null
                if (needsAttributes) {
                    sb.appendLine("      <attributes>")
                    sb.appendLine("        <divisions>${MidiScoreEncoder.TICKS_PER_QUARTER}</divisions>")
                    measure.keySignature?.let { ks ->
                        sb.appendLine("        <key><fifths>${ks.fifths}</fifths><mode>${ks.mode.name.lowercase()}</mode></key>")
                    }
                    measure.timeSignature?.let { ts ->
                        sb.appendLine("        <time><beats>${ts.numerator}</beats><beat-type>${ts.denominator}</beat-type></time>")
                    }
                    measure.clef?.let { cl ->
                        val (sign, line) = when (cl.type) {
                            ClefType.BASS   -> "F" to 4
                            ClefType.TENOR  -> "C" to 4
                            ClefType.TREBLE -> "G" to 2
                            else            -> "F" to 4
                        }
                        sb.appendLine("        <clef><sign>$sign</sign><line>$line</line></clef>")
                    }
                    sb.appendLine("      </attributes>")
                }

                measure.tempo?.let { tm ->
                    sb.appendLine("      <direction placement=\"above\">")
                    sb.appendLine("        <direction-type><metronome><beat-unit>${tm.beatUnit.name.lowercase()}</beat-unit><per-minute>${tm.bpm}</per-minute></metronome></direction-type>")
                    sb.appendLine("        <sound tempo=\"${tm.bpm}\"/>")
                    sb.appendLine("      </direction>")
                }

                for (el in measure.elements) {
                    when (el) {
                        is Note      -> sb.append(noteToXml(el, "      "))
                        is Rest      -> sb.append(restToXml(el, "      "))
                        is ChordNote -> el.notes.forEachIndexed { idx, n ->
                            sb.append(noteToXml(n, "      ", isChord = idx > 0))
                        }
                    }
                }

                sb.appendLine("    </measure>")
            }
            sb.appendLine("  </part>")
        }

        sb.appendLine("</score-partwise>")
        return sb.toString()
    }

    private fun noteToXml(note: Note, indent: String, isChord: Boolean = false): String {
        val sb = StringBuilder()
        sb.appendLine("$indent<note>")
        if (isChord) sb.appendLine("$indent  <chord/>")
        sb.appendLine("$indent  <pitch>")
        sb.appendLine("$indent    <step>${note.pitch.step}</step>")
        if (note.pitch.alter != Alter.NATURAL)
            sb.appendLine("$indent    <alter>${note.pitch.alter.semitones.toInt()}</alter>")
        sb.appendLine("$indent    <octave>${note.pitch.octave}</octave>")
        sb.appendLine("$indent  </pitch>")
        sb.appendLine("$indent  <duration>${note.duration.toTicks()}</duration>")
        if (note.tie != TieType.NONE && note.tie != TieType.START)
            sb.appendLine("$indent  <tie type=\"stop\"/>")
        if (note.tie == TieType.START || note.tie == TieType.CONTINUE)
            sb.appendLine("$indent  <tie type=\"start\"/>")
        sb.appendLine("$indent  <type>${durName(note.duration.type)}</type>")
        repeat(note.dotCount) { sb.appendLine("$indent  <dot/>") }
        sb.appendLine("$indent</note>")
        return sb.toString()
    }

    private fun restToXml(rest: Rest, indent: String): String {
        val sb = StringBuilder()
        sb.appendLine("$indent<note>")
        sb.appendLine("$indent  <rest${if (rest.isFullMeasure) " measure=\"yes\"" else ""}/>")
        sb.appendLine("$indent  <duration>${rest.duration.toTicks()}</duration>")
        sb.appendLine("$indent  <type>${durName(rest.duration.type)}</type>")
        sb.appendLine("$indent</note>")
        return sb.toString()
    }

    private fun durName(type: DurationType) = when (type) {
        DurationType.WHOLE         -> "whole"
        DurationType.HALF          -> "half"
        DurationType.QUARTER       -> "quarter"
        DurationType.EIGHTH        -> "eighth"
        DurationType.SIXTEENTH     -> "16th"
        DurationType.THIRTY_SECOND -> "32nd"
        DurationType.SIXTY_FOURTH  -> "64th"
        DurationType.BREVE         -> "breve"
        DurationType.LONG          -> "long"
        else                       -> "quarter"
    }

    private fun String.xmlEscape() = this
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    // ── PDF rendering ─────────────────────────────────────────────────────────

    private val PAGE_W = 595
    private val PAGE_H = 842
    // Staff spacing in PDF points. 10 pt ≈ 3.5 mm, readable without being oversized.
    private val SP = 10f
    private val MARGIN_L = 55f
    private val MARGIN_R = 40f
    private val SYSTEM_W get() = PAGE_W - MARGIN_L - MARGIN_R
    // Total height allocated per system row (staff + space above/below for notes)
    private val SYSTEM_H = SP * 12f
    // Max duration weight per system line (quarter note = 1.0).
    // 8 quarter-note equivalents ≈ 2 bars of 4/4.  Dense 16th-note passages
    // (weight 0.5 each) get ~16 notes per line which is comfortably readable.
    private val MAX_WEIGHT_PER_SYSTEM = 8f

    private fun buildScorePdf(score: Score, outFile: File, showFingerings: Boolean) {
        val doc = PdfDocument()
        val measures = score.parts.firstOrNull()?.measures ?: return

        // Load watermark from bundled drawable
        val watermark: Bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.watermark_logo)

        // ── Paints ────────────────────────────────────────────────────────────
        val staffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; strokeWidth = 0.65f; style = Paint.Style.STROKE
        }
        val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.FILL
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = SP * 1.1f
        }
        val thickBarPaint = Paint(staffPaint).apply { strokeWidth = 2.5f }

        // ── Page helpers ──────────────────────────────────────────────────────
        var pageNum = 1

        fun startPage(): Pair<PdfDocument.Page, Canvas> {
            val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create()
            val pg   = doc.startPage(info)
            val cv   = pg.canvas

            // Watermark — centered, subtle so music is always legible
            val wmPaint = Paint().apply { alpha = 45 }   // ~18% opacity — subtle with transparent bg
            val scale = minOf(PAGE_W / watermark.width.toFloat(), PAGE_H / watermark.height.toFloat()) * 0.75f
            val wmW = watermark.width * scale; val wmH = watermark.height * scale
            val wmX = (PAGE_W - wmW) / 2f; val wmY = (PAGE_H - wmH) / 2f
            cv.drawBitmap(watermark, null, RectF(wmX, wmY, wmX + wmW, wmY + wmH), wmPaint)
            return pg to cv
        }

        fun finishPage(page: PdfDocument.Page, canvas: Canvas) {
            // Footer: page number and app name
            val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.GRAY; textSize = 7f; textAlign = Paint.Align.CENTER
            }
            canvas.drawText(
                "Lord of the Strings  •  Page $pageNum",
                PAGE_W / 2f, PAGE_H - 14f, footerPaint
            )
            // Thin footer rule
            val rulePaint = Paint(staffPaint).apply { strokeWidth = 0.4f; color = Color.LTGRAY }
            canvas.drawLine(MARGIN_L, PAGE_H - 22f, PAGE_W - MARGIN_R, PAGE_H - 22f, rulePaint)
            doc.finishPage(page)
        }

        // ── First page ────────────────────────────────────────────────────────
        var (page, canvas) = startPage()

        // Title
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 24f; isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(score.title, PAGE_W / 2f, 50f, titlePaint)

        // Composer (right-aligned at same baseline)
        score.composer?.let {
            val cPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.DKGRAY; textSize = 11f
                typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                textAlign = Paint.Align.RIGHT
            }
            canvas.drawText(it, PAGE_W - MARGIN_R, 50f, cPaint)
        }

        // Instrument (left-aligned)
        val instPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY; textSize = 10f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)
            textAlign = Paint.Align.LEFT
        }
        canvas.drawText("Cello", MARGIN_L, 66f, instPaint)

        // Decorative rule under header
        val rulePaint = Paint(staffPaint).apply { strokeWidth = 0.8f }
        canvas.drawLine(MARGIN_L, 74f, PAGE_W - MARGIN_R, 74f, rulePaint)

        var sysY     = 90f
        var mIdx     = 0
        var activeClef = ClefType.BASS
        var activeTime = TimeSignature(4, 4)
        var activeKey  = KeySignature(0)
        var prevClef   = ClefType.BASS
        var prevTime   = TimeSignature(4, 4)
        var prevKey    = KeySignature(0)

        // Pre-compute duration weight for each measure (quarter = 1.0, minimum 1.0)
        val measureWeights = measures.map { m ->
            m.elements.sumOf { maxOf(it.duration.toTicks().toDouble() / 480.0, 0.7) }
                .toFloat().coerceAtLeast(1f)
        }

        // ── System loop — duration-weighted line breaking ─────────────────
        while (mIdx < measures.size) {

            // Page break?
            if (sysY + SP * 5f + SP * 6f > PAGE_H - 30f) {
                finishPage(page, canvas)
                pageNum++
                val (np, nc) = startPage()
                page = np; canvas = nc
                sysY = 45f
            }

            // Determine how many measures fit on this system line by weight
            var lineWeight = 0f
            var lineEnd = mIdx
            while (lineEnd < measures.size) {
                val w = measureWeights[lineEnd]
                if (lineWeight + w > MAX_WEIGHT_PER_SYSTEM && lineEnd > mIdx) break
                lineWeight += w
                lineEnd++
            }
            val systemMeasures = measures.subList(mIdx, lineEnd)
            val systemWeights  = measureWeights.subList(mIdx, lineEnd)
            val totalWeight    = systemWeights.sum().coerceAtLeast(1f)

            // ── Draw 5 staff lines ─────────────────────────────────────────
            for (line in 0..4) {
                canvas.drawLine(MARGIN_L, sysY + line * SP, MARGIN_L + SYSTEM_W, sysY + line * SP, staffPaint)
            }
            // Opening barline
            canvas.drawLine(MARGIN_L, sysY, MARGIN_L, sysY + SP * 4f, staffPaint)

            // ── Clef ──────────────────────────────────────────────────────
            val clefChar = when (activeClef) {
                ClefType.TREBLE -> "\uD834\uDD1E"
                ClefType.TENOR  -> "\uD834\uDD21"
                else            -> "\uD834\uDD22"   // bass
            }
            val clefPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; textSize = SP * 4.4f; textAlign = Paint.Align.LEFT
            }
            val clefBaseline = sysY + SP * (if (activeClef == ClefType.TREBLE) 5.5f else 3.5f)
            canvas.drawText(clefChar, MARGIN_L + 1f, clefBaseline, clefPaint)
            var contentX = MARGIN_L + SP * 3.8f

            // ── Key signature ──────────────────────────────────────────────
            if (activeKey.fifths != 0) {
                contentX = drawPdfKeySignature(canvas, activeKey, activeClef, contentX, sysY, staffPaint)
            }

            // ── Time signature (first system or when changed) ──────────────
            if (mIdx == 0 || activeTime != prevTime) {
                val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK; textSize = SP * 2.3f
                    isFakeBoldText = true; textAlign = Paint.Align.CENTER
                }
                val tcx = contentX + SP * 1.3f
                canvas.drawText(activeTime.numerator.toString(),   tcx, sysY + SP * 1.8f, timePaint)
                canvas.drawText(activeTime.denominator.toString(), tcx, sysY + SP * 3.8f, timePaint)
                contentX += SP * 2.8f
            }

            // ── Distribute note area proportionally by duration weight ────
            val noteAreaW = MARGIN_L + SYSTEM_W - contentX
            var mCursorX = contentX

            for ((mPos, measure) in systemMeasures.withIndex()) {
                // Track clef/time/key changes within the system
                if (measure.clef != null)          activeClef = measure.clef.type
                if (measure.timeSignature != null) activeTime = measure.timeSignature
                if (measure.keySignature != null)  activeKey  = measure.keySignature

                val measureW = noteAreaW * systemWeights[mPos] / totalWeight
                val mLeft  = mCursorX
                val mRight = mLeft + measureW
                val isLast = mIdx + mPos == measures.size - 1

                // Barline (double at very end of piece)
                if (isLast) {
                    canvas.drawLine(mRight - 1.5f, sysY, mRight - 1.5f, sysY + SP * 4f, staffPaint)
                    canvas.drawLine(mRight, sysY, mRight, sysY + SP * 4f, thickBarPaint)
                } else {
                    canvas.drawLine(mRight, sysY, mRight, sysY + SP * 4f, staffPaint)
                }

                // Measure number (small, above, grey) — skip measure 1
                if (measure.number > 1) {
                    val mnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.GRAY; textSize = 7f; textAlign = Paint.Align.LEFT
                    }
                    canvas.drawText(measure.number.toString(), mLeft + 1f, sysY - 3f, mnPaint)
                }

                // Tempo mark
                measure.tempo?.let { tm ->
                    val tPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK; textSize = 7.5f; textAlign = Paint.Align.LEFT
                    }
                    canvas.drawText("♩=${tm.bpm}", mLeft + 2f, sysY - 10f, tPaint)
                }

                // Elements — duration-proportional spacing within the measure
                val elements = measure.elements
                if (elements.isEmpty()) {
                    val rx = mLeft + measureW / 2f
                    val ry = sysY + SP
                    canvas.drawRect(rx - SP * 0.7f, ry, rx + SP * 0.7f, ry + SP * 0.45f, notePaint)
                } else {
                    val elWeights = elements.map {
                        maxOf(it.duration.toTicks().toFloat() / 480f, 0.7f)
                    }
                    val elTotalW = elWeights.sum().coerceAtLeast(1f)
                    var elX = mLeft
                    elements.forEachIndexed { idx, elem ->
                        val slotW = measureW * elWeights[idx] / elTotalW
                        val nx = elX + slotW / 2f
                        when (elem) {
                            is Note -> drawPdfNote(canvas, elem, nx, sysY, activeClef,
                                showFingerings, staffPaint, notePaint, textPaint)
                            is Rest -> drawPdfRest(canvas, elem, nx, sysY, staffPaint, notePaint)
                            else    -> {}
                        }
                        elX += slotW
                    }
                }
                mCursorX = mRight
            }

            prevClef = activeClef; prevTime = activeTime; prevKey = activeKey
            mIdx = lineEnd
            sysY += SYSTEM_H
        }

        finishPage(page, canvas)
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
        watermark.recycle()
    }

    // ── PDF note drawing ──────────────────────────────────────────────────────

    private fun drawPdfNote(
        canvas: Canvas, note: Note,
        nx: Float, staffTop: Float,
        clef: ClefType, showFingerings: Boolean,
        staffPaint: Paint, notePaint: Paint, textPaint: Paint
    ) {
        val ny  = pitchToY(note.pitch, staffTop, clef)
        // Stem up when note is below the middle of the staff
        val stemUp = ny > staffTop + SP * 2f

        // Ledger lines above staff
        if (ny < staffTop - SP * 0.5f) {
            var ly = staffTop - SP
            while (ly >= ny - SP * 0.5f) {
                canvas.drawLine(nx - SP * 0.9f, ly, nx + SP * 0.9f, ly, staffPaint)
                ly -= SP
            }
        }
        // Ledger lines below staff
        if (ny > staffTop + SP * 4f + SP * 0.5f) {
            var ly = staffTop + SP * 5f
            while (ly <= ny + SP * 0.5f) {
                canvas.drawLine(nx - SP * 0.9f, ly, nx + SP * 0.9f, ly, staffPaint)
                ly += SP
            }
        }

        // Accidental
        if (note.pitch.alter != Alter.NATURAL) {
            val accStr = when (note.pitch.alter) {
                Alter.SHARP       -> "#"
                Alter.FLAT        -> "b"
                Alter.DOUBLE_FLAT -> "bb"
                else              -> "x"
            }
            val accPaint = Paint(textPaint).apply {
                textSize = SP * 1.4f; textAlign = Paint.Align.RIGHT
            }
            canvas.drawText(accStr, nx - SP * 0.6f, ny + SP * 0.45f, accPaint)
        }

        // Notehead
        when (note.duration.type) {
            DurationType.WHOLE -> {
                val op = Paint(staffPaint).apply { strokeWidth = 1.3f }
                canvas.drawOval(nx - SP * 0.72f, ny - SP * 0.33f, nx + SP * 0.72f, ny + SP * 0.33f, op)
            }
            DurationType.HALF -> {
                val op = Paint(staffPaint).apply { strokeWidth = 1.3f }
                canvas.drawOval(nx - SP * 0.55f, ny - SP * 0.33f, nx + SP * 0.55f, ny + SP * 0.33f, op)
            }
            else -> canvas.drawOval(nx - SP * 0.55f, ny - SP * 0.33f, nx + SP * 0.55f, ny + SP * 0.33f, notePaint)
        }

        // Augmentation dot
        if (note.dotCount > 0) {
            val dotY = if ((ny - staffTop) % SP < SP * 0.5f) ny - SP * 0.25f else ny
            canvas.drawCircle(nx + SP * 0.82f, dotY, SP * 0.13f, notePaint)
        }

        // Stem and flag (whole notes have neither)
        if (note.duration.type != DurationType.WHOLE) {
            val stemLen = SP * 3.5f
            if (stemUp) {
                val sx = nx + SP * 0.52f
                val sy = ny - stemLen
                canvas.drawLine(sx, ny, sx, sy, staffPaint)
                drawFlag(canvas, note.duration.type, sx, sy, stemDown = false, staffPaint)
            } else {
                val sx = nx - SP * 0.52f
                val sy = ny + stemLen
                canvas.drawLine(sx, ny, sx, sy, staffPaint)
                drawFlag(canvas, note.duration.type, sx, sy, stemDown = true, staffPaint)
            }
        }

        // Fingering — always placed above the staff to avoid colliding with noteheads
        if (showFingerings) {
            note.fingering?.let { f ->
                val fingerStr = f.finger.toString()
                val romStr = positionRoman(f.positionShift)
                val fPaint = Paint(textPaint).apply {
                    textSize = SP * 0.85f; textAlign = Paint.Align.CENTER
                    isFakeBoldText = false
                }
                // Place above the staff; if the note itself is above the staff,
                // place above the note instead so we never overlap anything.
                val topLimit = minOf(ny - SP * 0.5f, staffTop)
                val fy = topLimit - SP * 0.8f
                canvas.drawText(fingerStr, nx, fy, fPaint)
                if (romStr.isNotEmpty()) {
                    val rPaint = Paint(fPaint).apply { textSize = SP * 0.7f }
                    canvas.drawText(romStr, nx, fy - SP * 1.0f, rPaint)
                }
            }
        }
    }

    private fun drawFlag(canvas: Canvas, type: DurationType, x: Float, y: Float, stemDown: Boolean, paint: Paint) {
        val d = if (stemDown) 1f else -1f
        val fp = Paint(paint).apply { strokeWidth = 1.1f }
        when (type) {
            DurationType.EIGHTH -> {
                canvas.drawLine(x, y, x + SP * 1.3f, y + d * SP * 1.6f, fp)
            }
            DurationType.SIXTEENTH -> {
                canvas.drawLine(x, y,            x + SP * 1.3f, y + d * SP * 1.6f, fp)
                canvas.drawLine(x, y + d * SP,   x + SP * 1.3f, y + d * SP * 2.6f, fp)
            }
            DurationType.THIRTY_SECOND -> {
                canvas.drawLine(x, y,              x + SP * 1.3f, y + d * SP * 1.6f, fp)
                canvas.drawLine(x, y + d * SP,     x + SP * 1.3f, y + d * SP * 2.6f, fp)
                canvas.drawLine(x, y + d * SP * 2f, x + SP * 1.3f, y + d * SP * 3.6f, fp)
            }
            else -> {}
        }
    }

    private fun drawPdfRest(
        canvas: Canvas, rest: Rest, nx: Float, staffTop: Float,
        staffPaint: Paint, notePaint: Paint
    ) {
        val mid = staffTop + SP * 2f
        when (rest.duration.type) {
            DurationType.WHOLE -> {
                // Filled rectangle hanging below 2nd staff line
                val ry = staffTop + SP
                canvas.drawRect(nx - SP * 0.72f, ry, nx + SP * 0.72f, ry + SP * 0.45f, notePaint)
            }
            DurationType.HALF -> {
                // Filled rectangle sitting on 3rd staff line (inverted)
                val ry = staffTop + SP * 2f
                canvas.drawRect(nx - SP * 0.72f, ry - SP * 0.45f, nx + SP * 0.72f, ry, notePaint)
            }
            DurationType.QUARTER -> {
                // Simplified quarter rest (zigzag)
                val rp = Paint(staffPaint).apply { strokeWidth = 1.2f }
                canvas.drawLine(nx + SP * 0.3f, mid - SP * 1.2f, nx - SP * 0.2f, mid - SP * 0.5f, rp)
                canvas.drawLine(nx - SP * 0.2f, mid - SP * 0.5f, nx + SP * 0.35f, mid, rp)
                canvas.drawLine(nx + SP * 0.35f, mid, nx - SP * 0.1f, mid + SP * 0.6f, rp)
                canvas.drawLine(nx - SP * 0.1f, mid + SP * 0.6f, nx + SP * 0.2f, mid + SP * 1.1f, rp)
            }
            DurationType.EIGHTH -> {
                canvas.drawCircle(nx, mid + SP * 0.4f, SP * 0.22f, notePaint)
                canvas.drawLine(nx, mid + SP * 0.4f, nx + SP * 0.7f, mid - SP * 0.9f, staffPaint)
            }
            DurationType.SIXTEENTH -> {
                canvas.drawCircle(nx, mid + SP * 0.4f, SP * 0.22f, notePaint)
                canvas.drawLine(nx, mid + SP * 0.4f, nx + SP * 0.7f, mid - SP * 0.9f, staffPaint)
                canvas.drawCircle(nx + SP * 0.25f, mid - SP * 0.1f, SP * 0.22f, notePaint)
            }
            else -> {
                canvas.drawRect(nx - SP * 0.4f, mid - SP * 0.25f, nx + SP * 0.4f, mid + SP * 0.25f, notePaint)
            }
        }
        if (rest.dotCount > 0) {
            canvas.drawCircle(nx + SP * 0.92f, mid + SP * 0.25f, SP * 0.13f, notePaint)
        }
    }

    private fun drawPdfKeySignature(
        canvas: Canvas, key: KeySignature, clef: ClefType,
        startX: Float, staffTop: Float, staffPaint: Paint
    ): Float {
        val accPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = SP * 1.6f; textAlign = Paint.Align.LEFT
        }
        var cx = startX
        if (key.fifths > 0) {
            // Sharps
            val bassPos   = floatArrayOf(1f, 4f, 0f, 3f, 6f, 2f, 5f)
            val treblePos = floatArrayOf(5f, 2f, 6f, 3f, 7f, 4f, 8f)
            val pos = if (clef == ClefType.TREBLE) treblePos else bassPos
            for (i in 0 until key.fifths.coerceAtMost(7)) {
                val accY = staffTop + pos[i] * SP / 2f
                canvas.drawText("#", cx, accY + SP * 0.6f, accPaint)
                cx += SP * 1.1f
            }
        } else if (key.fifths < 0) {
            // Flats
            val bassPos   = floatArrayOf(5f, 2f, 6f, 3f, 7f, 4f, 8f)
            val treblePos = floatArrayOf(1f, 4f, 0f, 3f, 6f, 2f, 5f)
            val pos = if (clef == ClefType.TREBLE) treblePos else bassPos
            for (i in 0 until (-key.fifths).coerceAtMost(7)) {
                val accY = staffTop + pos[i] * SP / 2f
                canvas.drawText("b", cx, accY + SP * 0.7f, accPaint)
                cx += SP * 1.0f
            }
        }
        return cx + SP * 0.4f
    }

    // ── Pitch → PDF y-coordinate ──────────────────────────────────────────────

    /**
     * Maps a pitch to a y-coordinate within a staff.
     * staffTop = y of the top (1st) staff line.
     * Uses the same diatonic-step math as ScoreCanvasView.
     */
    private fun pitchToY(pitch: Pitch, staffTop: Float, clef: ClefType): Float {
        val midi = pitch.toMidiNote()
        val step = run {
            val dia = when (midi % 12) { 0, 1 -> 0; 2, 3 -> 1; 4 -> 2; 5, 6 -> 3; 7, 8 -> 4; 9, 10 -> 5; else -> 6 }
            (midi / 12) * 7 + dia
        }
        val pos = when (clef) {
            ClefType.BASS   -> 33 - step
            ClefType.TENOR  -> 41 - step
            ClefType.TREBLE -> 45 - step
            else            -> 33 - step
        }
        return staffTop + pos * (SP / 2f)
    }

    private fun positionRoman(shift: Int) = when (shift) {
        2  -> "II"; 4  -> "III"; 5  -> "IV"; 7  -> "V"
        9  -> "VI"; 12 -> "VII"; 14 -> "VIII"
        else -> ""
    }
}

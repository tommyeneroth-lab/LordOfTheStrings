package com.cellomusic.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.cellomusic.app.data.db.AppDatabase
import com.cellomusic.app.data.db.entity.ScoreEntity
import com.cellomusic.app.domain.model.*
import com.cellomusic.app.musicxml.MusicXmlParser
import com.cellomusic.app.midi.MidiFileParser
import com.cellomusic.app.omr.OmrServerClient
import com.cellomusic.app.omr.OmrProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.roundToInt

class ScoreRepository(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val scoreDao = db.scoreDao()
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }
    private val parser = MusicXmlParser()
    private val omr = OmrProcessor(context)
    private val midiParser = MidiFileParser()

    val allScores: Flow<List<ScoreEntity>> = scoreDao.getAllScores()

    fun searchScores(query: String): Flow<List<ScoreEntity>> = scoreDao.searchScores(query)

    val favorites: Flow<List<ScoreEntity>> = scoreDao.getFavorites()

    suspend fun importMusicXml(uri: Uri): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(Exception("Cannot open file"))

            val score = inputStream.use { parser.parse(it) }
            val entity = saveScoreToStorage(score, uri, "MUSICXML")
            val id = scoreDao.insertScore(entity)
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun importJpeg(
        uri: Uri,
        onProgress: ((String) -> Unit)? = null
    ): Result<Long> = withContext(Dispatchers.IO) {
        // Copy image first so we can read it even if the URI becomes invalid
        val imageFile = copyUriToInternal(uri, "jpg")
        val title = displayNameFromUri(uri) ?: imageFile.nameWithoutExtension

        // 1. Insert placeholder immediately so the card appears with spinner
        val scoreId = UUID.randomUUID().toString()
        val dir = getScoreDir(scoreId); dir.mkdirs()
        val originalFile = File(dir, "original.jpg")
        imageFile.copyTo(originalFile, overwrite = true)
        val placeholder = emptyScorePlaceholder(scoreId, title, originalFile, "JPEG_OMR")
        val dbId = scoreDao.insertScore(placeholder)

        // 2. Run OMR
        return@withContext try {
            val omrResult = omr.processJpeg(imageFile, onProgress)
            updateScoreFromOmr(dbId, scoreId, dir, omrResult.score, "DONE")
            Result.success(dbId)
        } catch (e: Exception) {
            scoreDao.updateOmrResult(dbId, "FAILED", 0, 0, "C major", 4, 4)
            Result.failure(e)
        }
    }

    /**
     * Import a JPEG/photo via the remote OMR server at [serverUrl].
     * Falls back to on-device OMR ([importJpeg]) if the server call fails.
     */
    suspend fun importJpegViaServer(
        uri: Uri,
        serverUrl: String,
        onProgress: ((String) -> Unit)? = null
    ): Result<Long> = withContext(Dispatchers.IO) {
        val imageFile = copyUriToInternal(uri, "jpg")
        val title = displayNameFromUri(uri) ?: imageFile.nameWithoutExtension

        val scoreId = UUID.randomUUID().toString()
        val dir = getScoreDir(scoreId); dir.mkdirs()
        val originalFile = File(dir, "original.jpg")
        imageFile.copyTo(originalFile, overwrite = true)
        val placeholder = emptyScorePlaceholder(scoreId, title, originalFile, "JPEG_OMR")
        val dbId = scoreDao.insertScore(placeholder)

        try {
            onProgress?.invoke("Sending to OMR server…")
            val musicXml = OmrServerClient.submitFile(serverUrl, imageFile)
            onProgress?.invoke("Parsing MusicXML…")
            android.util.Log.d("OMR", "MusicXML preview: ${musicXml.take(300)}")
            val cleanXml = musicXml.replace(Regex("<!DOCTYPE[^>]*>"), "")
            val score = cleanXml.byteInputStream().use { parser.parse(it) }
                .copy(id = scoreId, title = title)
            updateScoreFromOmr(dbId, scoreId, dir, score, "DONE")
            Result.success(dbId)
        } catch (e: Exception) {
            // Server failed — fall back to on-device JPEG OMR
            onProgress?.invoke("Server failed — falling back to on-device OMR…")
            try {
                val omrResult = omr.processJpeg(imageFile, onProgress)
                updateScoreFromOmr(dbId, scoreId, dir, omrResult.score, "DONE")
                Result.success(dbId)
            } catch (e2: Exception) {
                scoreDao.updateOmrResult(dbId, "FAILED", 0, 0, "C major", 4, 4)
                Result.failure(e2)
            }
        }
    }

    suspend fun importPdf(
        uri: Uri,
        onProgress: ((String) -> Unit)? = null
    ): Result<Long> = withContext(Dispatchers.IO) {
        val pdfFile = copyUriToInternal(uri, "pdf")
        val title = (displayNameFromUri(uri) ?: pdfFile.nameWithoutExtension)
            .replace("_", " ").replace("-", " ")

        // Insert placeholder immediately so card appears with spinner
        val scoreId = UUID.randomUUID().toString()
        val dir = getScoreDir(scoreId); dir.mkdirs()
        val originalFile = File(dir, "original.pdf")
        pdfFile.copyTo(originalFile, overwrite = true)
        val placeholder = emptyScorePlaceholder(scoreId, title, originalFile, "PDF_OMR")
        val dbId = scoreDao.insertScore(placeholder)

        return@withContext try {
            val pfd = android.os.ParcelFileDescriptor.open(
                pdfFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = android.graphics.pdf.PdfRenderer(pfd)
            val pageCount = renderer.pageCount
            val allMeasures = mutableListOf<Measure>()
            var measureOffset = 1
            var thumbnailBitmap: Bitmap? = null

            for (pageIndex in 0 until pageCount) {
                val page = renderer.openPage(pageIndex)
                // Render at 3× native size so staff lines are thick enough for detection.
                // A4 PDF = 595×842 pts → 1785×2526 px; OmrProcessor scales back to ≤1600px.
                val renderScale = 3
                val bitmap = Bitmap.createBitmap(
                    page.width * renderScale, page.height * renderScale, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                val matrix = android.graphics.Matrix().apply { setScale(renderScale.toFloat(), renderScale.toFloat()) }
                page.render(bitmap, null, matrix, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                if (pageIndex == 0) thumbnailBitmap = bitmap
                val pageProgress: ((String) -> Unit)? = onProgress?.let { cb ->
                    { msg -> cb("Page ${pageIndex + 1}/$pageCount: $msg") }
                }
                val omrResult = omr.processPage(bitmap, title, pageProgress)
                omrResult.score.parts.firstOrNull()?.measures?.forEach { m ->
                    allMeasures.add(m.copy(number = measureOffset++))
                }
            }
            renderer.close()

            val thumbFile = saveThumbnail(thumbnailBitmap, scoreId)
            val finalScore = Score(id = scoreId, title = title,
                parts = listOf(Part("P1", "Cello", "Vc.", measures = allMeasures.ifEmpty {
                    listOf(Measure(1, elements = emptyList()))
                })))
            updateScoreFromOmr(dbId, scoreId, dir, finalScore, "DONE")
            if (thumbFile != null) {
                val entity = scoreDao.getScoreById(dbId)
                entity?.let { scoreDao.updateScore(it.copy(thumbnailPath = thumbFile.absolutePath)) }
            }
            Result.success(dbId)
        } catch (e: Exception) {
            scoreDao.updateOmrResult(dbId, "FAILED", 0, 0, "C major", 4, 4)
            Result.failure(e)
        }
    }

    /**
     * Imports an MP3 (or any audio format) by decoding it with MediaCodec, then running
     * a simple FFT-based pitch tracker (HPS — Harmonic Product Spectrum) to transcribe
     * the prominent pitch at each 50ms frame into a sequence of notes.
     */
    suspend fun importMp3(
        uri: Uri,
        onProgress: ((String) -> Unit)? = null
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val audioFile = copyUriToInternal(uri, "mp3")
            val title = (displayNameFromUri(uri) ?: audioFile.nameWithoutExtension)
                .replace("_", " ").replace("-", " ")

            onProgress?.invoke("Decoding audio…")
            val pcm = decodeToPcm(audioFile, onProgress)
            if (pcm == null || pcm.samples.isEmpty()) {
                return@withContext Result.failure(Exception("Could not decode audio"))
            }

            onProgress?.invoke("Tracking pitch…")
            val notes = pitchTrack(pcm.samples, pcm.sampleRate, onProgress)

            onProgress?.invoke("Building score…")
            val measures = buildMeasuresFromNotes(notes)
            val scoreId  = java.util.UUID.randomUUID().toString()
            val score    = Score(
                id = scoreId, title = title,
                parts = listOf(Part("P1", "Cello", "Vc.", measures = measures))
            )
            val entity = saveScoreToStorage(score, uri, "MP3_TRANSCRIPTION")
            val id = scoreDao.insertScore(entity)
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private data class PcmData(val samples: FloatArray, val sampleRate: Int)

    private fun decodeToPcm(file: File, onProgress: ((String) -> Unit)?): PcmData? {
        val extractor = android.media.MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        var audioTrack   = -1
        var sampleRate   = 44100
        var channelCount = 1
        for (i in 0 until extractor.trackCount) {
            val fmt  = extractor.getTrackFormat(i)
            val mime = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrack   = i
                sampleRate   = fmt.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                channelCount = if (fmt.containsKey(android.media.MediaFormat.KEY_CHANNEL_COUNT))
                                   fmt.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                               else 1
                break
            }
        }
        if (audioTrack < 0) return null
        extractor.selectTrack(audioTrack)
        val fmt   = extractor.getTrackFormat(audioTrack)
        val mime  = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: return null
        val codec = android.media.MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, null, null, 0)
        codec.start()

        val info   = android.media.MediaCodec.BufferInfo()
        val pcmOut = java.io.ByteArrayOutputStream()
        var eos    = false

        while (!eos) {
            // Feed compressed data into the codec
            val inIdx = codec.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val buf   = codec.getInputBuffer(inIdx) ?: continue
                val bytes = extractor.readSampleData(buf, 0)
                if (bytes < 0) {
                    codec.queueInputBuffer(inIdx, 0, 0, 0,
                        android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    codec.queueInputBuffer(inIdx, 0, bytes, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }

            // Drain decoded PCM output
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Codec tells us the actual output format — update channel count
                    val outFmt = codec.outputFormat
                    if (outFmt.containsKey(android.media.MediaFormat.KEY_CHANNEL_COUNT))
                        channelCount = outFmt.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                    if (outFmt.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE))
                        sampleRate   = outFmt.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                }
                outIdx == android.media.MediaCodec.INFO_TRY_AGAIN_LATER -> { /* spin */ }
                outIdx >= 0 -> {
                    val buf = codec.getOutputBuffer(outIdx) ?: run {
                        codec.releaseOutputBuffer(outIdx, false); continue
                    }
                    val arr = ByteArray(info.size)
                    buf.get(arr)
                    pcmOut.write(arr)
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                        eos = true
                }
            }
        }
        codec.stop(); codec.release(); extractor.release()

        // Convert raw 16-bit little-endian PCM → float, then downmix to mono
        val raw       = pcmOut.toByteArray()
        val totalSamples = raw.size / 2   // total interleaved samples across all channels
        val ch        = channelCount.coerceAtLeast(1)

        val monoLen = totalSamples / ch
        val mono    = FloatArray(monoLen) { frameIdx ->
            // Average the ch interleaved samples that belong to this frame
            var sum = 0f
            for (c in 0 until ch) {
                val byteIdx = (frameIdx * ch + c) * 2
                if (byteIdx + 1 < raw.size) {
                    val lo = raw[byteIdx].toInt() and 0xFF
                    val hi = raw[byteIdx + 1].toInt()
                    sum += (hi shl 8 or lo).toShort() / 32768f
                }
            }
            sum / ch
        }
        return PcmData(mono, sampleRate)
    }

    private data class TrackedNote(val pitch: Pitch, val startMs: Long, val durationMs: Long)

    /**
     * Pitch tracker — three-stage pipeline:
     *
     * 1. Frame analysis: 100 ms windows / 25 ms hop.  Larger windows give
     *    better frequency resolution for the cello's low fundamentals.
     * 2. Median smoothing (window = 7 frames ≈ 175 ms): eliminates one-frame
     *    spikes caused by bow noise, vibrato or transients.
     * 3. Run-length grouping: consecutive frames with the same MIDI value
     *    become one note; runs shorter than MIN_FRAMES (≈ 125 ms) are
     *    discarded as artefacts.
     *
     * This replaces the old frame-by-frame "did it change?" logic which
     * produced hundreds of tiny random-pitch segments.
     */
    private fun pitchTrack(
        samples: FloatArray,
        sampleRate: Int,
        onProgress: ((String) -> Unit)?
    ): List<TrackedNote> {

        // 100 ms frame, rounded up to next power of 2 (≥ 4096 at 44 kHz)
        val frameSize = run {
            var p = 1
            val target = (sampleRate * 0.10).toInt().coerceAtLeast(2048)
            while (p < target) p = p shl 1
            p
        }
        val hopSize = (sampleRate * 0.025).toInt()   // 25 ms hop

        // ── Stage 1: raw frame-level MIDI estimates ───────────────────────────
        val frameMidi = mutableListOf<Int>()
        var pos = 0
        var frameIdx = 0
        while (pos + frameSize <= samples.size) {
            frameMidi += hpsPitch(FloatArray(frameSize) { samples[pos + it] }, sampleRate)
            if (frameIdx % 40 == 0) {
                val ms = pos.toLong() * 1000L / sampleRate
                onProgress?.invoke("Analysing %.1f s…".format(ms / 1000f))
            }
            pos += hopSize
            frameIdx++
        }
        if (frameMidi.isEmpty()) return emptyList()

        // ── Stage 2: median filter to kill single-frame jitter ────────────────
        val smoothed = medianFilter(frameMidi, windowSize = 11)   // ≈ 275 ms window

        // ── Stage 3: run-length grouping ─────────────────────────────────────
        // A "note" is a run of ≥ MIN_FRAMES frames with the same non-zero MIDI.
        val MIN_FRAMES = 8          // ≈ 200 ms — shorter artefacts discarded
        val notes = mutableListOf<TrackedNote>()
        var runStart = 0
        var runMidi  = smoothed[0]

        fun commitRun(endIdx: Int) {
            val length = endIdx - runStart
            if (runMidi > 0 && length >= MIN_FRAMES) {
                val startMs = runStart.toLong() * hopSize * 1000L / sampleRate
                val endMs   = endIdx.toLong()   * hopSize * 1000L / sampleRate
                notes += TrackedNote(midiToPitch(runMidi), startMs, endMs - startMs)
            }
        }

        for (i in 1..smoothed.size) {
            val cur = if (i < smoothed.size) smoothed[i] else Int.MIN_VALUE
            if (cur != runMidi) {
                commitRun(i)
                runStart = i
                runMidi  = cur
            }
        }

        return notes
    }

    /** Applies a sliding-window median filter to an integer list. */
    private fun medianFilter(values: List<Int>, windowSize: Int): List<Int> {
        val half = windowSize / 2
        return values.indices.map { i ->
            val lo  = maxOf(0, i - half)
            val hi  = minOf(values.size - 1, i + half)
            val win = values.subList(lo, hi + 1).sorted()
            win[win.size / 2]
        }
    }

    /**
     * Harmonic Product Spectrum pitch estimator.
     *
     * Improvements over the previous version:
     * - Higher silence threshold (0.015 RMS) to reject background noise.
     * - HPS order 5 (harmonics ×1..×5) — more robust for cello fundamentals
     *   which are often weaker than the 2nd/3rd harmonic.
     * - Parabolic interpolation around the HPS peak for sub-bin accuracy,
     *   reducing octave-rounding errors on low notes.
     *
     * Returns MIDI note number, or 0 for silence/noise.
     */
    private fun hpsPitch(frame: FloatArray, sampleRate: Int): Int {
        val n = frame.size

        // RMS silence gate — reject quiet frames before any FFT work
        var sumSq = 0.0
        for (s in frame) sumSq += s * s
        val rms = kotlin.math.sqrt(sumSq / n)
        if (rms < 0.015) return 0

        // Hann window
        val re = DoubleArray(n) { i -> frame[i] * (0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / n)) }
        val im = DoubleArray(n)

        // Correct radix-2 DIF FFT.
        //
        // Each stage halves the group size.  The butterfly for group size `len`:
        //   sum  → re[k]  = re[k] + re[k2]          (no twiddle on sum)
        //   diff → re[k2] = (re[k] - re[k2]) * W^j  (twiddle on difference)
        // where W = e^{-2πi/len}.
        // Bit-reversal permutation is applied once at the end.
        var len = n
        while (len > 1) {
            val half   = len / 2
            val ang    = -2.0 * Math.PI / len
            val wStepR = kotlin.math.cos(ang)
            val wStepI = kotlin.math.sin(ang)
            var pos = 0
            while (pos < n) {
                var curR = 1.0;  var curI = 0.0
                for (j in 0 until half) {
                    val k  = pos + j
                    val k2 = k + half
                    val tR = re[k] - re[k2]
                    val tI = im[k] - im[k2]
                    re[k] += re[k2];  im[k] += im[k2]          // sum
                    re[k2] = tR * curR - tI * curI              // diff * W^j
                    im[k2] = tR * curI + tI * curR
                    val nR = curR * wStepR - curI * wStepI      // advance twiddle
                    curI   = curR * wStepI + curI * wStepR
                    curR   = nR
                }
                pos += len
            }
            len = half
        }
        // Bit-reversal permutation (output is bit-reversed after DIF stages)
        var j2 = 0
        for (i2 in 1 until n) {
            var bit = n shr 1
            while (j2 and bit != 0) { j2 = j2 xor bit; bit = bit shr 1 }
            j2 = j2 xor bit
            if (i2 < j2) {
                var t = re[i2]; re[i2] = re[j2]; re[j2] = t
                    t = im[i2]; im[i2] = im[j2]; im[j2] = t
            }
        }

        val half = n / 2
        val mag  = DoubleArray(half) { i -> kotlin.math.sqrt(re[i] * re[i] + im[i] * im[i]) }

        // HPS order 5: product of mag, mag[2i], mag[3i], mag[4i], mag[5i]
        val hps = mag.copyOf()
        for (d in 2..5) {
            for (i in hps.indices) {
                val src = i * d
                if (src < half) hps[i] *= mag[src] else hps[i] = 0.0
            }
        }

        // Search only in cello range: C2 ≈ 65 Hz  to  C6 ≈ 1047 Hz
        val minBin = (65.0   * n / sampleRate).toInt().coerceAtLeast(1)
        val maxBin = (1050.0 * n / sampleRate).toInt().coerceAtMost(half - 2)

        var peakBin = minBin
        var peakVal = 0.0
        for (i in minBin..maxBin) {
            if (hps[i] > peakVal) { peakVal = hps[i]; peakBin = i }
        }
        if (peakVal < 1e-10) return 0

        // Parabolic interpolation for fractional bin accuracy
        val freq = if (peakBin in 1 until half - 1) {
            val a = hps[peakBin - 1]
            val b = hps[peakBin]
            val c = hps[peakBin + 1]
            val denom = a - 2 * b + c
            val frac = if (kotlin.math.abs(denom) > 1e-12) 0.5 * (a - c) / denom else 0.0
            (peakBin + frac) * sampleRate / n
        } else {
            peakBin.toDouble() * sampleRate / n
        }

        return (69 + 12 * kotlin.math.log2(freq / 440.0)).roundToInt().coerceIn(24, 96)
    }

    private fun midiToPitch(midi: Int): Pitch {
        val octave = midi / 12 - 1
        return when (midi % 12) {
            0  -> Pitch(PitchStep.C, octave)
            1  -> Pitch(PitchStep.C, octave, Alter.SHARP)
            2  -> Pitch(PitchStep.D, octave)
            3  -> Pitch(PitchStep.D, octave, Alter.SHARP)
            4  -> Pitch(PitchStep.E, octave)
            5  -> Pitch(PitchStep.F, octave)
            6  -> Pitch(PitchStep.F, octave, Alter.SHARP)
            7  -> Pitch(PitchStep.G, octave)
            8  -> Pitch(PitchStep.G, octave, Alter.SHARP)
            9  -> Pitch(PitchStep.A, octave)
            10 -> Pitch(PitchStep.A, octave, Alter.SHARP)
            else -> Pitch(PitchStep.B, octave)
        }
    }

    private fun buildMeasuresFromNotes(tracked: List<TrackedNote>): List<Measure> {
        if (tracked.isEmpty()) return listOf(Measure(1, clef = Clef(ClefType.BASS),
            timeSignature = TimeSignature(4,4), keySignature = KeySignature(0), elements = emptyList()))

        // Quantise durations to standard values (round to nearest)
        val tpq = 480
        val bpm = 80  // assume 80 bpm for transcription tempo
        val msPerTick = 60_000.0 / (bpm * tpq)

        val elements = mutableListOf<MusicElement>()
        var tick = 0
        for (tn in tracked) {
            val durTicks = (tn.durationMs / msPerTick).toInt().coerceAtLeast(tpq / 4)
            val durType = when {
                durTicks >= tpq * 3  -> DurationType.WHOLE
                durTicks >= tpq * 3 / 2 -> DurationType.HALF
                durTicks >= tpq * 3 / 4 -> DurationType.QUARTER
                durTicks >= tpq * 3 / 8 -> DurationType.EIGHTH
                else                 -> DurationType.SIXTEENTH
            }
            elements.add(Note(
                id = java.util.UUID.randomUUID().toString(),
                startTick = tick,
                duration = NoteDuration(durType),
                pitch = tn.pitch
            ))
            tick += NoteDuration(durType).toTicks(tpq)
        }

        // Split into 4/4 measures (16 quarter-note ticks per measure)
        val ticksPerMeasure = tpq * 4
        val measures = mutableListOf<Measure>()
        var measureNum = 1
        var startTick = 0
        while (startTick < tick) {
            val endTick = startTick + ticksPerMeasure
            val chunk = elements.filter { it.startTick >= startTick && it.startTick < endTick }
                .map { if (it is Note) it.copy(startTick = it.startTick - startTick) else it }
            measures.add(Measure(
                number = measureNum++,
                clef = if (measureNum == 2) null else Clef(ClefType.BASS),
                timeSignature = if (measureNum == 2) null else TimeSignature(4, 4),
                keySignature  = if (measureNum == 2) null else KeySignature(0),
                elements = chunk
            ))
            startTick = endTick
        }
        return measures.ifEmpty { listOf(Measure(1, clef = Clef(ClefType.BASS),
            timeSignature = TimeSignature(4,4), keySignature = KeySignature(0), elements = emptyList())) }
    }

    /**
     * Import a PDF via the remote OMR server at [serverUrl].
     * The server returns MusicXML which is parsed directly — no on-device OMR.
     * Falls back to [importPdf] if the server call fails.
     */
    suspend fun importPdfViaServer(
        uri: Uri,
        serverUrl: String,
        onProgress: ((String) -> Unit)? = null
    ): Result<Long> = withContext(Dispatchers.IO) {
        val pdfFile = copyUriToInternal(uri, "pdf")
        val title = (displayNameFromUri(uri) ?: pdfFile.nameWithoutExtension)
            .replace("_", " ").replace("-", " ")

        val scoreId = UUID.randomUUID().toString()
        val dir = getScoreDir(scoreId); dir.mkdirs()
        val originalFile = File(dir, "original.pdf")
        pdfFile.copyTo(originalFile, overwrite = true)
        val placeholder = emptyScorePlaceholder(scoreId, title, originalFile, "PDF_OMR")
        val dbId = scoreDao.insertScore(placeholder)

        try {
            onProgress?.invoke("Sending to OMR server…")
            val musicXml = OmrServerClient.submitFile(serverUrl, pdfFile)
            onProgress?.invoke("Parsing MusicXML…")
            android.util.Log.d("OMR", "MusicXML preview: ${musicXml.take(300)}")
            // Strip DOCTYPE declaration — XmlPullParser tries to fetch the external DTD URL,
            // which blocks the parser indefinitely on Android.
            val cleanXml = musicXml.replace(Regex("<!DOCTYPE[^>]*>"), "")
            val xmlMeasureCount = cleanXml.split("<measure").size - 1
            android.util.Log.d("OMR", "cleanXml length=${cleanXml.length} measures_in_xml=$xmlMeasureCount")
            val score = cleanXml.byteInputStream().use { parser.parse(it) }
                .copy(id = scoreId, title = title)
            val measures = score.parts.sumOf { it.measures.size }
            val notes = score.parts.sumOf { p -> p.measures.sumOf { m -> m.elements.count { it is Note } } }
            android.util.Log.d("OMR", "Parsed: ${score.parts.size} parts, $measures measures, $notes notes")
            updateScoreFromOmr(dbId, scoreId, dir, score, "DONE")
            Result.success(dbId)
        } catch (e: Exception) {
            // Server failed — fall back to on-device PDF OMR using the already-copied file
            onProgress?.invoke("Server failed — falling back to on-device OMR…")
            try {
                val pfd = android.os.ParcelFileDescriptor.open(
                    pdfFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = android.graphics.pdf.PdfRenderer(pfd)
                val allMeasures = mutableListOf<Measure>()
                var measureOffset = 1
                var thumbnailBitmap: android.graphics.Bitmap? = null
                for (pageIndex in 0 until renderer.pageCount) {
                    val page = renderer.openPage(pageIndex)
                    val renderScale = 3
                    val bitmap = android.graphics.Bitmap.createBitmap(
                        page.width * renderScale, page.height * renderScale,
                        android.graphics.Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    val matrix = android.graphics.Matrix().apply {
                        setScale(renderScale.toFloat(), renderScale.toFloat())
                    }
                    page.render(bitmap, null, matrix,
                        android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    if (pageIndex == 0) thumbnailBitmap = bitmap
                    val pageProgress: ((String) -> Unit)? = onProgress?.let { cb ->
                        { msg -> cb("Page ${pageIndex + 1}/${renderer.pageCount}: $msg") }
                    }
                    val omrResult = omr.processPage(bitmap, title, pageProgress)
                    omrResult.score.parts.firstOrNull()?.measures?.forEach { m ->
                        allMeasures.add(m.copy(number = measureOffset++))
                    }
                }
                renderer.close()
                val thumbFile = saveThumbnail(thumbnailBitmap, scoreId)
                val finalScore = Score(id = scoreId, title = title,
                    parts = listOf(Part("P1", "Cello", "Vc.", measures = allMeasures.ifEmpty {
                        listOf(Measure(1, elements = emptyList()))
                    })))
                updateScoreFromOmr(dbId, scoreId, dir, finalScore, "DONE")
                if (thumbFile != null) {
                    val entity = scoreDao.getScoreById(dbId)
                    entity?.let { scoreDao.updateScore(it.copy(thumbnailPath = thumbFile.absolutePath)) }
                }
                Result.success(dbId)
            } catch (e2: Exception) {
                scoreDao.updateOmrResult(dbId, "FAILED", 0, 0, "C major", 4, 4)
                Result.failure(e2)
            }
        }
    }

    suspend fun importMidi(uri: Uri): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val midiFile = copyUriToInternal(uri, "mid")
            val title = midiFile.nameWithoutExtension
                .replace("_", " ").replace("-", " ")
            val result = midiFile.inputStream().use { midiParser.parse(it, title) }
            val entity = saveScoreToStorage(result.score, uri, "MIDI")
            val id = scoreDao.insertScore(entity)
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun loadScore(entity: ScoreEntity): Score? = withContext(Dispatchers.IO) {
        try {
            val file = File(entity.filePathJson)
            if (!file.exists()) return@withContext null
            json.decodeFromString<Score>(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    suspend fun saveScore(score: Score) = withContext(Dispatchers.IO) {
        val dir = getScoreDir(score.id)
        dir.mkdirs()
        File(dir, "score.json").writeText(json.encodeToString(score))
    }

    suspend fun deleteScore(entity: ScoreEntity) = withContext(Dispatchers.IO) {
        getScoreDir(entity.scoreId).deleteRecursively()
        try { File(entity.filePathOriginal).delete() } catch (_: Exception) {}
        scoreDao.deleteScore(entity)
    }

    suspend fun updateLastOpened(id: Long, measure: Int) {
        scoreDao.updateLastOpened(id, System.currentTimeMillis(), measure)
    }

    suspend fun setFavorite(id: Long, favorite: Boolean) {
        scoreDao.setFavorite(id, favorite)
    }

    suspend fun renameScore(id: Long, newTitle: String, newComposer: String? = null) {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return
        val composer = newComposer?.trim()?.takeIf { it.isNotEmpty() }
        scoreDao.updateTitleAndComposer(id, trimmed, composer)
    }

    private fun saveScoreToStorage(score: Score, originalUri: Uri, sourceType: String): ScoreEntity {
        val dir = getScoreDir(score.id)
        dir.mkdirs()

        val jsonFile = File(dir, "score.json")
        jsonFile.writeText(json.encodeToString(score))

        val ext = when (sourceType) { "PDF_OMR" -> "pdf"; "JPEG_OMR" -> "jpg"; else -> "xml" }
        val originalFile = File(dir, "original.$ext")
        copyUriToFile(originalUri, originalFile)

        val measures = score.parts.firstOrNull()?.measures ?: emptyList()
        val firstKey = measures.firstOrNull { it.keySignature != null }?.keySignature
        val firstTime = measures.firstOrNull { it.timeSignature != null }?.timeSignature

        return ScoreEntity(
            scoreId = score.id,
            title = score.title,
            composer = score.composer,
            arranger = score.arranger,
            workNumber = score.workNumber,
            filePathJson = jsonFile.absolutePath,
            filePathOriginal = originalFile.absolutePath,
            thumbnailPath = null,
            sourceType = sourceType,
            measureCount = measures.size,
            noteCount = countNotes(score),
            keySignature = firstKey?.let { keySignatureToString(it) } ?: "C major",
            timeSignatureTop = firstTime?.numerator ?: 4,
            timeSignatureBottom = firstTime?.denominator ?: 4,
            omrStatus = "NONE"
        )
    }

    private fun displayNameFromUri(uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) {
                cursor.getString(idx)?.substringBeforeLast('.')?.replace('_', ' ')?.replace('-', ' ')
            } else null
        }
    } catch (_: Exception) { null }

    private fun emptyScorePlaceholder(
        scoreId: String, title: String, originalFile: File, sourceType: String
    ): ScoreEntity {
        val dir = getScoreDir(scoreId); dir.mkdirs()
        val empty = Score(
            id = scoreId, title = title,
            parts = listOf(Part("P1", "Cello", "Vc.", measures = listOf(
                Measure(1, clef = Clef(ClefType.BASS), timeSignature = TimeSignature(4, 4),
                    keySignature = KeySignature(0), elements = emptyList())
            )))
        )
        val jsonFile = File(dir, "score.json")
        jsonFile.writeText(json.encodeToString(empty))
        return ScoreEntity(
            scoreId = scoreId, title = title, composer = null, arranger = null,
            workNumber = null, filePathJson = jsonFile.absolutePath,
            filePathOriginal = originalFile.absolutePath, thumbnailPath = null,
            sourceType = sourceType, measureCount = 0,
            keySignature = "C major", timeSignatureTop = 4, timeSignatureBottom = 4,
            omrStatus = "PROCESSING"
        )
    }

    private suspend fun updateScoreFromOmr(
        dbId: Long, scoreId: String, dir: File, score: Score, status: String
    ) {
        val real = score.copy(id = scoreId)
        val jsonFile = File(dir, "score.json")
        jsonFile.writeText(json.encodeToString(real))
        val measures  = real.parts.firstOrNull()?.measures ?: emptyList()
        val firstKey  = measures.firstOrNull { it.keySignature  != null }?.keySignature
        val firstTime = measures.firstOrNull { it.timeSignature != null }?.timeSignature
        scoreDao.updateOmrResult(
            id = dbId, status = status,
            measureCount = measures.size,
            noteCount    = countNotes(real),
            keySignature = firstKey?.let { keySignatureToString(it) } ?: "C major",
            tsTop = firstTime?.numerator ?: 4,
            tsBottom = firstTime?.denominator ?: 4
        )
    }

    private fun copyUriToInternal(uri: Uri, extension: String): File {
        val file = File(context.cacheDir, "import_${System.currentTimeMillis()}.$extension")
        copyUriToFile(uri, file)
        return file
    }

    private fun copyUriToFile(uri: Uri, dest: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        }
    }

    private fun saveThumbnail(bitmap: Bitmap?, scoreId: String): File? {
        bitmap ?: return null
        val dir = getScoreDir(scoreId)
        dir.mkdirs()
        val file = File(dir, "thumb.jpg")
        FileOutputStream(file).use { out ->
            Bitmap.createScaledBitmap(bitmap, 200, 280, true)
                .compress(Bitmap.CompressFormat.JPEG, 80, out)
        }
        return file
    }

    private fun getScoreDir(scoreId: String) = File(context.filesDir, "scores/$scoreId")

    private fun countNotes(score: Score): Int =
        score.parts.sumOf { p -> p.measures.sumOf { m -> m.elements.count { it is Note } } }

    private fun keySignatureToString(key: KeySignature): String {
        val majorNames = arrayOf("C", "G", "D", "A", "E", "B", "F#", "C#", "F", "Bb", "Eb", "Ab", "Db", "Gb", "Cb")
        val minorNames = arrayOf("A", "E", "B", "F#", "C#", "G#", "D#", "A#", "D", "G", "C", "F", "Bb", "Eb", "Ab")
        val idx = if (key.fifths >= 0) key.fifths else 8 + (-key.fifths - 1)
        val mode = if (key.mode == KeyMode.MAJOR) "major" else "minor"
        val names = if (key.mode == KeyMode.MAJOR) majorNames else minorNames
        return "${if (idx < names.size) names[idx] else "C"} $mode"
    }
}

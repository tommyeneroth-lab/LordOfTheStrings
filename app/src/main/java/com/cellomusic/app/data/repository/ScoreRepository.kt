package com.cellomusic.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.cellomusic.app.data.db.AppDatabase
import com.cellomusic.app.data.db.entity.ScoreEntity
import com.cellomusic.app.domain.model.*
import com.cellomusic.app.musicxml.MusicXmlParser
import com.cellomusic.app.midi.MidiFileParser
import com.cellomusic.app.omr.OmrProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

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
                val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
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

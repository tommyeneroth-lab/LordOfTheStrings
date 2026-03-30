package com.cellomusic.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.cellomusic.app.data.db.AppDatabase
import com.cellomusic.app.data.db.entity.ScoreEntity
import com.cellomusic.app.domain.model.*
import com.cellomusic.app.musicxml.MusicXmlParser
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

    suspend fun importJpeg(uri: Uri): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val imageFile = copyUriToInternal(uri, "jpg")
            val placeholderScore = Score(
                id = UUID.randomUUID().toString(),
                title = "Imported Score (OMR Pending)",
                parts = listOf(Part("P1", "Cello", "Vc.", measures = emptyList()))
            )
            val entity = saveScoreToStorage(placeholderScore, uri, "JPEG_OMR").copy(
                filePathOriginal = imageFile.absolutePath
            )
            val id = scoreDao.insertScore(entity)
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun importPdf(uri: Uri): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val pdfFile = copyUriToInternal(uri, "pdf")
            val thumbnail = renderPdfPageToBitmap(pdfFile, 0)
            val scoreId = UUID.randomUUID().toString()
            val thumbFile = saveThumbnail(thumbnail, scoreId)

            val placeholderScore = Score(
                id = scoreId,
                title = pdfFile.nameWithoutExtension.replace("_", " ").replace("-", " "),
                parts = listOf(Part("P1", "Cello", "Vc.", measures = emptyList()))
            )
            val entity = saveScoreToStorage(placeholderScore, uri, "PDF_OMR").copy(
                filePathOriginal = pdfFile.absolutePath,
                thumbnailPath = thumbFile?.absolutePath
            )
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
            keySignature = firstKey?.let { keySignatureToString(it) } ?: "C major",
            timeSignatureTop = firstTime?.numerator ?: 4,
            timeSignatureBottom = firstTime?.denominator ?: 4
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

    private fun renderPdfPageToBitmap(pdfFile: File, pageIndex: Int): Bitmap? {
        return try {
            val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            if (renderer.pageCount == 0) { renderer.close(); return null }
            val page = renderer.openPage(pageIndex.coerceIn(0, renderer.pageCount - 1))
            val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            renderer.close()
            bitmap
        } catch (_: Exception) { null }
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

    private fun keySignatureToString(key: KeySignature): String {
        val majorNames = arrayOf("C", "G", "D", "A", "E", "B", "F#", "C#", "F", "Bb", "Eb", "Ab", "Db", "Gb", "Cb")
        val minorNames = arrayOf("A", "E", "B", "F#", "C#", "G#", "D#", "A#", "D", "G", "C", "F", "Bb", "Eb", "Ab")
        val idx = if (key.fifths >= 0) key.fifths else 8 + (-key.fifths - 1)
        val mode = if (key.mode == KeyMode.MAJOR) "major" else "minor"
        val names = if (key.mode == KeyMode.MAJOR) majorNames else minorNames
        return "${if (idx < names.size) names[idx] else "C"} $mode"
    }
}

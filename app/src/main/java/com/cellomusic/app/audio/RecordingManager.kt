package com.cellomusic.app.audio

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Wraps MediaRecorder to record microphone audio to an AAC/M4A file.
 * The file is saved to the app's external Music directory (no storage
 * permission required on Android 10+).
 */
class RecordingManager(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    val isRecording: Boolean get() = recorder != null

    /** Get the output file path (valid after start, before cancel). */
    fun getOutputFilePath(): String? = outputFile?.absolutePath

    /**
     * Start recording. Throws if already recording or if MediaRecorder
     * fails to prepare.
     */
    fun start(scoreTitle: String = "Recording") {
        if (recorder != null) return

        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: context.filesDir
        dir.mkdirs()

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safe = scoreTitle.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
        val file = File(dir, "${safe}_$stamp.m4a")
        outputFile = file

        @Suppress("DEPRECATION")
        val rec = if (Build.VERSION.SDK_INT >= 31) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

        rec.setAudioSource(MediaRecorder.AudioSource.MIC)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        rec.setAudioChannels(1)          // force mono — stereo doubles PCM and breaks pitch detection
        rec.setAudioEncodingBitRate(128_000)
        rec.setAudioSamplingRate(44_100)
        rec.setOutputFile(file.absolutePath)
        rec.prepare()
        rec.start()
        recorder = rec
    }

    /**
     * Stop recording and return a share-ready Uri for the saved file,
     * or null if nothing was recorded.
     */
    fun stop(): Uri? {
        val rec = recorder ?: return null
        recorder = null
        return try {
            rec.stop()
            rec.release()
            outputFile?.let { f ->
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    f
                )
            }
        } catch (_: Exception) {
            outputFile?.delete()
            null
        }
    }

    /** Discard an in-progress recording without saving. */
    fun cancel() {
        val rec = recorder ?: return
        recorder = null
        try { rec.stop() } catch (_: Exception) {}
        rec.release()
        outputFile?.delete()
        outputFile = null
    }

    /** Elapsed seconds since recording started (approximate). */
    fun elapsedSeconds(): Int {
        return if (recorder == null) 0
        else ((System.currentTimeMillis() - (outputFile?.lastModified() ?: 0L)) / 1000).toInt()
    }
}

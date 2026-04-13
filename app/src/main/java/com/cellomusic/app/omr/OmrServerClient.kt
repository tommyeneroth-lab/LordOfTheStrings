package com.cellomusic.app.omr

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "OmrServerClient"

/**
 * HTTP client for the CelloMusic OMR server.
 *
 * POST /omr  — base64-encodes a PDF or image, returns MusicXML string.
 * GET  /health — returns {"status":"ok"} when the server is up.
 */
object OmrServerClient {

    private const val TIMEOUT_MS = 5 * 60 * 1000  // 5 min — oemer can be slow
    private const val USER_AGENT = "CelloMusicApp/1.0"  // Cloudflare blocks default Dalvik agent

    /**
     * Send [file] to [serverUrl]/omr and return the MusicXML string.
     * Throws on network error, non-200 response, or missing musicxml field.
     */
    fun submitFile(serverUrl: String, file: File): String {
        val base = serverUrl.trimEnd('/')
        val bytes = file.readBytes()
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val body = JSONObject().apply {
            put("pdf_base64", b64)
            put("filename", file.name)
        }.toString()

        Log.d(TAG, "POST $base/omr  body=${body.length} bytes")
        val url = URL("$base/omr")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Connection", "close")
            conn.connectTimeout = 15_000
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            Log.d(TAG, "Response HTTP $code")
            val responseBody = if (code == 200) {
                conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                val err = runCatching {
                    conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull() ?: ""
                Log.e(TAG, "Server error $code: $err")
                throw Exception("Server returned HTTP $code: $err")
            }

            val json = JSONObject(responseBody)
            val xml = json.optString("musicxml", "")
            Log.d(TAG, "Received musicxml length=${xml.length}")
            if (xml.isEmpty()) throw Exception("Server returned empty musicxml. Error: ${json.optString("error")}")
            return xml
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Returns true if the server at [serverUrl] responds to GET /health with HTTP 200.
     */
    fun checkHealth(serverUrl: String): Boolean {
        return try {
            val base = serverUrl.trimEnd('/')
            val conn = URL("$base/health").openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        } catch (_: Exception) {
            false
        }
    }
}

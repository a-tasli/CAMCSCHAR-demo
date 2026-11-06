package org.example

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object NetworkUploader {
    private const val TAG = "NetworkUploader"
    private const val TIMEOUT_MS = 6000

    data class UploadResult(val success: Boolean, val message: String, val statusCode: Int? = null)

    suspend fun postJson(serverUrl: String, jsonString: String): UploadResult = withContext(Dispatchers.IO) {
        if (serverUrl.isBlank()) {
            return@withContext UploadResult(false, "No server URL configured")
        }

        try {
            val endpoint = if (serverUrl.endsWith("/api/upload")) serverUrl else "$serverUrl/api/upload"
            val url = URL(endpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true

            conn.outputStream.use { os ->
                val bytes = jsonString.toByteArray(Charsets.UTF_8)
                os.write(bytes, 0, bytes.size)
            }

            val code = conn.responseCode
            val isSuccess = code == 200 || code == 201
            val msg = if (isSuccess) "Uploaded successfully (HTTP $code)" else "Server returned HTTP $code"
            Log.d(TAG, "POST $endpoint -> $code: $msg")
            UploadResult(isSuccess, msg, code)
        } catch (e: Exception) {
            val errorMsg = "Upload failed: ${e.message}"
            Log.w(TAG, errorMsg, e)
            UploadResult(false, errorMsg)
        }
    }
}

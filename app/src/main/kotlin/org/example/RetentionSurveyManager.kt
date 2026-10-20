package org.example

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RetentionSurveyManager(private val context: Context) {

    suspend fun saveSurveyResult(
        result: SurveyScoringResult,
        metadata: SurveyMetadata,
        answers: Map<Int, String>,
        serverUrl: String? = null
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val jsonObject = result.toJsonObject(metadata, answers)
            val jsonString = jsonObject.toString(2)

            val safeParticipantId = metadata.participantId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "survey_${safeParticipantId}_${timestamp}.json"

            // 1. Save to app private external storage
            val appDir = context.getExternalFilesDir(null)
            val appFile = File(appDir, filename)
            FileWriter(appFile, false).use { writer ->
                writer.write(jsonString)
                writer.flush()
            }
            Log.d("RetentionSurveyManager", "Survey saved locally to: ${appFile.absolutePath}")

            // 2. Also save to public Downloads directory for easy access
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (downloadsDir != null && downloadsDir.exists()) {
                    val publicFile = File(downloadsDir, filename)
                    FileWriter(publicFile, false).use { writer ->
                        writer.write(jsonString)
                        writer.flush()
                    }
                    Log.d("RetentionSurveyManager", "Survey saved to Downloads: ${publicFile.absolutePath}")
                }
            } catch (e: Exception) {
                Log.w("RetentionSurveyManager", "Could not save to Downloads: ${e.message}")
            }

            // 3. Optional upload to visualizer server
            if (!serverUrl.isNullOrBlank()) {
                try {
                    val finalUrl = if (serverUrl.endsWith("/api/upload")) serverUrl else "$serverUrl/api/upload"
                    val url = java.net.URL(finalUrl)
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json; utf-8")
                    conn.setRequestProperty("Accept", "application/json")
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.doOutput = true

                    conn.outputStream.use { os ->
                        val bytes = jsonString.toByteArray(Charsets.UTF_8)
                        os.write(bytes, 0, bytes.size)
                    }

                    val code = conn.responseCode
                    Log.d("RetentionSurveyManager", "Upload response code: $code")
                } catch (e: Exception) {
                    Log.w("RetentionSurveyManager", "Server upload failed (will continue): ${e.message}")
                }
            }

            return@withContext Pair(true, appFile.absolutePath)
        } catch (e: Exception) {
            Log.e("RetentionSurveyManager", "Failed to save survey result", e)
            return@withContext Pair(false, e.message ?: "Unknown error")
        }
    }
}

package org.example

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RetentionSurveyManager(
    private val context: Context,
    private val experimentDataManager: ExperimentDataManager? = null
) {

    data class SaveAndUploadResult(
        val saveSuccess: Boolean,
        val filePath: String,
        val uploadSuccess: Boolean,
        val uploadMessage: String
    )

    suspend fun saveAndUploadSurveyAndExperimentData(
        result: SurveyScoringResult,
        metadata: SurveyMetadata,
        answers: Map<Int, String>,
        serverUrl: String? = null
    ): SaveAndUploadResult = withContext(Dispatchers.IO) {
        try {
            val surveyObject = result.toJsonObject(metadata, answers)
            val surveyJsonString = surveyObject.toString(2)

            val safeParticipantId = metadata.participantId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

            // 1. Save standalone survey file
            val surveyFilename = "survey_${safeParticipantId}_${timestamp}.json"
            val appDir = context.getExternalFilesDir(null)
            val surveyFile = File(appDir, surveyFilename)
            FileWriter(surveyFile, false).use { writer ->
                writer.write(surveyJsonString)
                writer.flush()
            }
            Log.d("RetentionSurveyManager", "Survey saved locally to: ${surveyFile.absolutePath}")

            // Save survey to public Downloads
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (downloadsDir != null && downloadsDir.exists()) {
                    val publicSurveyFile = File(downloadsDir, surveyFilename)
                    FileWriter(publicSurveyFile, false).use { writer ->
                        writer.write(surveyJsonString)
                        writer.flush()
                    }
                    Log.d("RetentionSurveyManager", "Survey saved to Downloads: ${publicSurveyFile.absolutePath}")
                }
            } catch (e: Exception) {
                Log.w("RetentionSurveyManager", "Could not save survey to Downloads: ${e.message}")
            }

            // 2. Fetch recorded tour experiment data and combine
            val experimentData: JSONArray = experimentDataManager?.getRecordedDataArray() ?: JSONArray()
            val unifiedObject = JSONObject().apply {
                put("participant_id", metadata.participantId)
                put("condition", metadata.condition.label)
                put("date_time", metadata.dateTime)
                put("phase_order", metadata.phaseOrder)
                put("survey", surveyObject)
                put("experiment_data", experimentData)
            }
            val unifiedJsonString = unifiedObject.toString(2)

            // Save unified experiment file locally
            val unifiedFilename = "experiment_unified_${safeParticipantId}_${timestamp}.json"
            val unifiedFile = File(appDir, unifiedFilename)
            FileWriter(unifiedFile, false).use { writer ->
                writer.write(unifiedJsonString)
                writer.flush()
            }
            Log.d("RetentionSurveyManager", "Unified experiment file saved to: ${unifiedFile.absolutePath}")

            // Update experimentDataManager's file with unified data
            experimentDataManager?.updateWithUnifiedData(unifiedJsonString)

            // Save unified experiment file to public Downloads
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (downloadsDir != null && downloadsDir.exists()) {
                    val publicUnifiedFile = File(downloadsDir, unifiedFilename)
                    FileWriter(publicUnifiedFile, false).use { writer ->
                        writer.write(unifiedJsonString)
                        writer.flush()
                    }
                    Log.d("RetentionSurveyManager", "Unified experiment file saved to Downloads: ${publicUnifiedFile.absolutePath}")
                }
            } catch (e: Exception) {
                Log.w("RetentionSurveyManager", "Could not save unified data to Downloads: ${e.message}")
            }

            // 3. Perform JSON upload now that survey is complete!
            var uploadSuccess = false
            var uploadMsg = "No server configured"
            if (!serverUrl.isNullOrBlank()) {
                try {
                    val finalUrl = if (serverUrl.endsWith("/api/upload")) serverUrl else "$serverUrl/api/upload"
                    val url = java.net.URL(finalUrl)
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json; utf-8")
                    conn.setRequestProperty("Accept", "application/json")
                    conn.connectTimeout = 6000
                    conn.readTimeout = 6000
                    conn.doOutput = true

                    conn.outputStream.use { os ->
                        val bytes = unifiedJsonString.toByteArray(Charsets.UTF_8)
                        os.write(bytes, 0, bytes.size)
                    }

                    val code = conn.responseCode
                    uploadSuccess = (code == 200 || code == 201)
                    uploadMsg = if (uploadSuccess) "Uploaded successfully (HTTP $code)" else "Server returned HTTP $code"
                    Log.d("RetentionSurveyManager", "Upload response: $uploadMsg")
                } catch (e: Exception) {
                    uploadMsg = "Upload failed: ${e.message}"
                    Log.w("RetentionSurveyManager", uploadMsg)
                }
            }

            return@withContext SaveAndUploadResult(
                saveSuccess = true,
                filePath = unifiedFile.absolutePath,
                uploadSuccess = uploadSuccess,
                uploadMessage = uploadMsg
            )
        } catch (e: Exception) {
            Log.e("RetentionSurveyManager", "Failed to save and upload survey and experiment data", e)
            return@withContext SaveAndUploadResult(
                saveSuccess = false,
                filePath = "",
                uploadSuccess = false,
                uploadMessage = e.message ?: "Unknown error"
            )
        }
    }

    suspend fun saveSurveyResult(
        result: SurveyScoringResult,
        metadata: SurveyMetadata,
        answers: Map<Int, String>,
        serverUrl: String? = null
    ): Pair<Boolean, String> {
        val res = saveAndUploadSurveyAndExperimentData(result, metadata, answers, serverUrl)
        return Pair(res.saveSuccess, res.filePath.ifEmpty { res.uploadMessage })
    }
}

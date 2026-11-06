package org.example

import android.content.Context
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
import java.util.Objects

class ExperimentDataManager(private val context: Context) {
    private var dataArray = JSONArray()
    private var lastRecordedStateHash: Int = 0
    private var lastRecordTime: Long = 0
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
    private var currentFile: File? = null
    var isRecording = false
        private set

    fun startRecording() {
        if (isRecording) return
        dataArray = JSONArray()
        val filename = "experiment_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.json"
        currentFile = File(context.getExternalFilesDir(null), filename)
        isRecording = true
        Log.d("ExperimentDataManager", "Started recording to ${currentFile?.absolutePath}")
    }

    fun stopRecording() {
        if (!isRecording) return
        saveToFile()
        isRecording = false
        Log.d("ExperimentDataManager", "Stopped recording.")
    }

    suspend fun recordData(
        hr: Int?,
        hrv: Double, // Using rmssd
        stepVariance: Double,
        isSpeaking: Boolean,
        lat: Double?,
        lon: Double?,
        poi: String,
        loadLevel: String,
        combinedLoadIndex: Double?,
        physicalStrain: Double,
        mentalHr: Double,
        isAutoMode: Boolean,
        isDetailed: Boolean,
        isLargeImageMode: Boolean,
        scriptLine: Int,
        // v2 audio-first pivot fields (pipeline-spec.md §8)
        ttsSpeed: Float,
        interLineDelayMs: Long,
        isCheckpointActive: Boolean,
        presentationMode: String
    ) = withContext(Dispatchers.IO) {
        if (!isRecording) return@withContext

        val currentTime = System.currentTimeMillis()

        // Hash the state parameters to detect changes
        val stateHash = Objects.hash(
            poi, loadLevel, combinedLoadIndex, isAutoMode, isDetailed, isLargeImageMode,
            isSpeaking, scriptLine, ttsSpeed, interLineDelayMs, isCheckpointActive, presentationMode
        )
        
        val isStateChange = stateHash != lastRecordedStateHash
        val isPeriodic = currentTime - lastRecordTime >= 1000
        
        if (isStateChange || isPeriodic) {
            val obj = JSONObject().apply {
                put("timestamp", dateFormat.format(Date(currentTime)))
                put("hr", hr ?: JSONObject.NULL)
                put("hrv_rmssd", hrv)
                put("step_variance", stepVariance)
                put("is_speaking", isSpeaking)
                put("lat", lat ?: JSONObject.NULL)
                put("lon", lon ?: JSONObject.NULL)
                put("poi", poi)
                put("load_level", loadLevel)
                put("combined_load_index", combinedLoadIndex ?: JSONObject.NULL)
                put("physical_strain_score", physicalStrain)
                put("mental_hr_bpm", mentalHr)
                put("is_auto_mode", isAutoMode)
                put("is_detailed_script", isDetailed)
                put("is_large_image_mode", isLargeImageMode)
                put("script_line", scriptLine)
                put("tts_speed", ttsSpeed)
                put("inter_line_delay_ms", interLineDelayMs)
                put("is_checkpoint_active", isCheckpointActive)
                put("presentation_mode", presentationMode)
                put("trigger", if (isStateChange) "event" else "periodic")
            }
            
            dataArray.put(obj)
            lastRecordedStateHash = stateHash
            lastRecordTime = currentTime
            
            // Periodically save to file to avoid data loss
            if (dataArray.length() % 5 == 0 || isStateChange) {
                saveToFile()
            }
        }
    }

    private fun saveToFile() {
        try {
            currentFile?.let {
                val writer = FileWriter(it, false)
                writer.write(dataArray.toString(2))
                writer.flush()
                writer.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("ExperimentDataManager", "Error saving data: ${e.message}")
        }
    }

    fun getRecordedDataArray(): JSONArray {
        if (dataArray.length() > 0) return dataArray
        val fileToRead = currentFile ?: context.getExternalFilesDir(null)?.listFiles { file -> 
            file.name.startsWith("experiment_") && file.name.endsWith(".json")
        }?.maxByOrNull { it.lastModified() }
        
        if (fileToRead != null && fileToRead.exists()) {
            try {
                val content = fileToRead.readText()
                val parsed = org.json.JSONTokener(content).nextValue()
                if (parsed is JSONArray) return parsed
                if (parsed is JSONObject) {
                    if (parsed.has("experiment_data")) return parsed.getJSONArray("experiment_data")
                    if (parsed.has("sensor_data")) return parsed.getJSONArray("sensor_data")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return JSONArray()
    }

    fun updateWithUnifiedData(unifiedJsonString: String) {
        try {
            currentFile?.let {
                val writer = FileWriter(it, false)
                writer.write(unifiedJsonString)
                writer.flush()
                writer.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("ExperimentDataManager", "Error updating file with unified data: ${e.message}")
        }
    }

    suspend fun uploadLatestFile(urlStr: String): Boolean = withContext(Dispatchers.IO) {
        var fileToUpload = currentFile
        if (fileToUpload == null || !fileToUpload.exists()) {
            val files = context.getExternalFilesDir(null)?.listFiles { file -> 
                file.name.startsWith("experiment_") && file.name.endsWith(".json")
            }
            if (files.isNullOrEmpty()) return@withContext false
            fileToUpload = files.maxByOrNull { it.lastModified() }
        }
        
        if (fileToUpload == null || !fileToUpload.exists()) return@withContext false
        
        try {
            val fileContent = fileToUpload.readText()
            val finalUrlStr = if(urlStr.endsWith("/api/upload")) urlStr else "$urlStr/api/upload"
            val url = java.net.URL(finalUrlStr)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            
            connection.outputStream.use { os ->
                val input = fileContent.toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }
            
            val code = connection.responseCode
            return@withContext code == 200 || code == 201
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("ExperimentDataManager", "Upload failed: ${e.message}")
            return@withContext false
        }
    }
    suspend fun saveLatestToDownloads(): Boolean = withContext(Dispatchers.IO) {
        var fileToSave = currentFile
        if (fileToSave == null || !fileToSave.exists()) {
            val files = context.getExternalFilesDir(null)?.listFiles { file -> 
                file.name.startsWith("experiment_") && file.name.endsWith(".json")
            }
            if (files.isNullOrEmpty()) return@withContext false
            fileToSave = files.maxByOrNull { it.lastModified() }
        }
        
        if (fileToSave == null || !fileToSave.exists()) return@withContext false
        
        try {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val destFile = java.io.File(downloadsDir, fileToSave.name)
            fileToSave.copyTo(destFile, overwrite = true)
            return@withContext true
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("ExperimentDataManager", "Save to downloads failed: ${e.message}")
            return@withContext false
        }
    }

    fun resetState() {
        isRecording = false
        dataArray = JSONArray()
        currentFile = null
        lastRecordedStateHash = 0
        lastRecordTime = 0
    }
}

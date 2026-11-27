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
        presentationMode: String,
        // v3 IRB-safe ambient covariates (aggregate scalars only, zero PII)
        ambientContext: AmbientContextSnapshot? = null
    ) = withContext(Dispatchers.IO) {
        if (!isRecording) return@withContext

        val currentTime = System.currentTimeMillis()

        // Hash the state parameters to detect structural/mode changes
        val stateHash = Objects.hash(
            poi, loadLevel, combinedLoadIndex, isAutoMode, isDetailed, isLargeImageMode,
            isSpeaking, scriptLine, ttsSpeed, interLineDelayMs, isCheckpointActive, presentationMode,
            ambientContext?.isAmbientSensingEnabled, ambientContext?.envBusynessTier,
            ambientContext?.bleRecallProbe?.probeTimestampMs
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
                putAmbientFields(ambientContext)
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

    private fun JSONObject.putFiniteDouble(key: String, value: Double?) {
        if (value == null || !value.isFinite()) {
            put(key, JSONObject.NULL)
        } else {
            put(key, value)
        }
    }

    private fun JSONObject.putAmbientFields(ctx: AmbientContextSnapshot?) {
        if (ctx == null) return
        val enabled = ctx.isAmbientSensingEnabled
        put("ambient_sensing_enabled", enabled)
        put("env_enclosure_hint", ctx.envEnclosureHint)
        put("env_busyness_tier", ctx.envBusynessTier)

        // BLE aggregate covariates (zero MACs / zero device names / zero payload bytes)
        put("ble_filter_mode", ctx.bleFilterMode)
        put("ble_filter_count", ctx.bleFilterCount)
        put("ble_total_count", if (enabled) ctx.bleTotalCount else JSONObject.NULL)
        put("ble_near_count", if (enabled) ctx.bleNearCount else JSONObject.NULL)
        put("ble_mid_count", if (enabled) ctx.bleMidCount else JSONObject.NULL)
        put("ble_far_count", if (enabled) ctx.bleFarCount else JSONObject.NULL)
        putFiniteDouble("ble_mean_rssi", if (enabled) ctx.bleMeanRssi else null)
        put("ble_max_rssi", if (enabled) (ctx.bleMaxRssi ?: JSONObject.NULL) else JSONObject.NULL)
        putFiniteDouble("ble_turnover_rate", if (enabled) ctx.bleTurnoverRate else null)
        put("ble_persistent_count", if (enabled) ctx.blePersistentCount else JSONObject.NULL)
        putFiniteDouble("ble_packet_rate_hz", if (enabled) ctx.blePacketRateHz else null)
        put("ble_phone_ecosystem_count", if (enabled) ctx.blePhoneEcosystemCount else JSONObject.NULL)
        put("ble_audio_wearable_count", if (enabled) ctx.bleAudioWearableCount else JSONObject.NULL)
        put("ble_tracker_beacon_count", if (enabled) ctx.bleTrackerBeaconCount else JSONObject.NULL)
        put("ble_chipset_other_count", if (enabled) ctx.bleChipsetOtherCount else JSONObject.NULL)

        // BLE screen-on recall probe summary
        put("ble_probe_total_count", ctx.bleRecallProbe?.probeTotalCount ?: JSONObject.NULL)
        put("ble_probe_matched_count", ctx.bleRecallProbe?.probeMatchedCount ?: JSONObject.NULL)
        putFiniteDouble("ble_probe_coverage_ratio", ctx.bleRecallProbe?.probeCoverageRatio)

        // Wi-Fi AP aggregate covariates (100% SSID-blind, zero BSSIDs)
        put("wifi_ap_count", if (enabled) (ctx.wifiApCount ?: JSONObject.NULL) else JSONObject.NULL)
        put("wifi_strong_ap_count", if (enabled) (ctx.wifiStrongApCount ?: JSONObject.NULL) else JSONObject.NULL)
        putFiniteDouble("wifi_5ghz_ratio", if (enabled) ctx.wifi5GhzRatio else null)
        putFiniteDouble("wifi_mean_rssi", if (enabled) ctx.wifiMeanRssi else null)
        putFiniteDouble("wifi_rssi_std", if (enabled) ctx.wifiRssiStd else null)
        putFiniteDouble("wifi_rssi_delta_abs_mean", if (enabled) ctx.wifiRssiDeltaAbsMean else null)
        put("wifi_laa_bssid_count", if (enabled) (ctx.wifiLaaBssidCount ?: JSONObject.NULL) else JSONObject.NULL)
        put("wifi_transient_ap_count", if (enabled) (ctx.wifiTransientApCount ?: JSONObject.NULL) else JSONObject.NULL)
        put("wifi_rtt_ap_count", if (enabled) (ctx.wifiRttApCount ?: JSONObject.NULL) else JSONObject.NULL)
        put("wifi_scan_age_ms", if (enabled) (ctx.wifiScanAgeMs ?: JSONObject.NULL) else JSONObject.NULL)

        // Acoustic non-speech covariates (RAM-only scalars)
        putFiniteDouble("ambient_noise_dbfs", ctx.ambientNoiseDbfs)
        putFiniteDouble("ambient_noise_peak_dbfs", ctx.ambientNoisePeakDbfs)
        putFiniteDouble("ambient_midband_ratio", ctx.ambientMidbandRatio)
        putFiniteDouble("speech_duty_cycle_10s", ctx.speechDutyCycle10s?.toDouble())
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
            val result = NetworkUploader.postJson(urlStr, fileContent)
            return@withContext result.success
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

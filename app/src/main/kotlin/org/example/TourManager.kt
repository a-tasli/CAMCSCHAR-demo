package org.example

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import kotlin.math.hypot

data class ScriptLine(val index: Int, val text: String)

class TourManager(private val context: Context, private val ttsManager: TTSManager) {
    private val detailedScript = mutableListOf<ScriptLine>()
    private val simplifiedScript = mutableListOf<ScriptLine>()
    
    val availableLocations = listOf("guide", "amphitheatre", "lake_memorial", "tosun_terzioglu_garden")
    private val _currentLocation = MutableStateFlow("guide")
    val currentLocation: StateFlow<String> = _currentLocation.asStateFlow()

    private val _currentArea = MutableStateFlow("None")
    val currentArea: StateFlow<String> = _currentArea.asStateFlow()

    private var currentIndex = 0
    private var maxIndex = 0
    private var currentUtteranceId: String? = null
    
    private var guideFinished = false
    
    private val _history = MutableStateFlow<List<String>>(listOf("Welcome to the Campus Tour! Press 'Start Tour' to begin."))
    val history: StateFlow<List<String>> = _history.asStateFlow()
    
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    
    private val _isDetailed = MutableStateFlow(true)
    val isDetailed: StateFlow<Boolean> = _isDetailed.asStateFlow()
    
    private val _isAutoMode = MutableStateFlow(false)
    val isAutoMode: StateFlow<Boolean> = _isAutoMode.asStateFlow()

    private val _isManualGpsMode = MutableStateFlow(false)
    val isManualGpsMode: StateFlow<Boolean> = _isManualGpsMode.asStateFlow()

    private val _manualLocationLat = MutableStateFlow<Double?>(null)
    val manualLocationLat: StateFlow<Double?> = _manualLocationLat.asStateFlow()

    private val _manualLocationLon = MutableStateFlow<Double?>(null)
    val manualLocationLon: StateFlow<Double?> = _manualLocationLon.asStateFlow()

    private val _manualGpsResetTrigger = MutableStateFlow(0)
    val manualGpsResetTrigger: StateFlow<Int> = _manualGpsResetTrigger.asStateFlow()

    fun setManualGpsMode(enabled: Boolean, defaultLat: Double?, defaultLon: Double?) {
        _isManualGpsMode.value = enabled
        if (enabled && _manualLocationLat.value == null) {
            _manualLocationLat.value = defaultLat ?: 40.892
            _manualLocationLon.value = defaultLon ?: 29.377
        }
    }

    fun resetManualLocation(defaultLat: Double?, defaultLon: Double?) {
        _manualLocationLat.value = defaultLat ?: 40.892
        _manualLocationLon.value = defaultLon ?: 29.377
        _manualGpsResetTrigger.value += 1
        checkLocation(_manualLocationLat.value!!, _manualLocationLon.value!!)
    }

    fun updateManualLocation(lat: Double, lon: Double) {
        _manualLocationLat.value = lat
        _manualLocationLon.value = lon
        checkLocation(lat, lon)
    }

    private var currentLoadLevel = "Waiting..."
    private var delayJob: Job? = null
    private var currentDelay = 0L

    private val scope = CoroutineScope(Dispatchers.Main)

    data class POI(val id: Int, val name: String, val lat: Double, val lon: Double, val radiusMeters: Double)
    
    private val pois = listOf(
        POI(1, "amphitheatre", 40.89320979780269, 29.379169091952324, 80.0),
        POI(2, "lake_memorial", 40.892876775980646, 29.375505980922984, 60.0),
        POI(3, "tosun_terzioglu_garden", 40.890581851850961, 29.377186219901855, 80.0)
    )

    fun checkLocation(lat: Double, lon: Double) {
        var foundPoi: String? = null
        val results = FloatArray(1)
        for (poi in pois) {
            android.location.Location.distanceBetween(lat, lon, poi.lat, poi.lon, results)
            val distMeters = results[0]
            val effectiveRadius = if (_currentArea.value == poi.name) poi.radiusMeters + 5.0 else poi.radiusMeters
            if (distMeters <= effectiveRadius) {
                foundPoi = poi.name
                
                // If we are currently in "guide" mode and enter a POI, we MUST switch to the POI.
                // Or if we change from one POI to another.
                if (_currentLocation.value == "guide" || _currentLocation.value != poi.name) {
                    val wasPlaying = _isPlaying.value
                    selectLocation(poi.name)
                    if (wasPlaying || _isAutoMode.value) {
                        _isPlaying.value = true
                        playCurrentIndexWithDelay()
                    }
                } else if (_currentArea.value == "None" && _isAutoMode.value && !_isPlaying.value) {
                    // Entered the current POI from outside (None) while in Auto Mode and not already playing
                    _isPlaying.value = true
                    playCurrentIndexWithDelay()
                }
                break
            }
        }
        _currentArea.value = foundPoi ?: "None"
    }

    init {
        loadScripts("guide")
        
        scope.launch {
            ttsManager.onUtteranceCompleted.collect { utteranceId ->
                if (utteranceId == currentUtteranceId) {
                    currentIndex++
                    if (_isPlaying.value) {
                        if (currentIndex < maxIndex) {
                            playCurrentIndexWithDelay()
                        } else {
                            _isPlaying.value = false
                            _history.value = _history.value + "Tour finished."
                        }
                    }
                }
            }
        }
    }

    private fun loadScripts(location: String) {
        detailedScript.clear()
        simplifiedScript.clear()
        
        detailedScript.addAll(parseScript("scripts/${location}_detailed.txt"))
        simplifiedScript.addAll(parseScript("scripts/${location}_simplified.txt"))
        
        maxIndex = detailedScript.size.coerceAtLeast(simplifiedScript.size)
        currentIndex = 0
        _currentLocation.value = location
    }

    fun selectLocation(location: String) {
        if (_isPlaying.value) {
            delayJob?.cancel()
            ttsManager.stop()
            _isPlaying.value = false
        }
        loadScripts(location)
        _history.value = listOf("Selected ${location.replace('_', ' ')}. Press 'Start Tour' to begin.")
    }

    private fun parseScript(assetPath: String): List<ScriptLine> {
        val lines = mutableListOf<ScriptLine>()
        try {
            val inputStream = context.assets.open(assetPath)
            val reader = BufferedReader(InputStreamReader(inputStream))
            var index = 1
            reader.forEachLine { line ->
                if (line.isNotBlank()) {
                    lines.add(ScriptLine(index++, line.trim()))
                }
            }
            reader.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return lines
    }

    fun toggleAutoMode() {
        _isAutoMode.value = !_isAutoMode.value
    }

    fun setAutoMode(enabled: Boolean) {
        _isAutoMode.value = enabled
    }

    fun startTour() {
        if (!_isPlaying.value) {
            _isPlaying.value = true
            if (currentIndex < maxIndex) {
                playCurrentIndexWithDelay()
            } else {
                currentIndex = 0
                playCurrentIndexWithDelay()
            }
        }
    }

    fun resetTour() {
        delayJob?.cancel()
        ttsManager.stop()
        _isPlaying.value = false
        guideFinished = false
        _currentLocation.value = "guide"
        loadScripts("guide")
        _history.value = listOf("Welcome to the Campus Tour! Press 'Start Tour' to begin.")
    }

    fun nextIndex() {
        if (currentIndex < maxIndex - 1) {
            currentIndex++
            if (_isPlaying.value) {
                playCurrentIndexWithDelay()
            } else {
                playCurrentIndex()
            }
        }
    }

    fun prevIndex() {
        if (currentIndex > 0) {
            currentIndex--
            if (_isPlaying.value) {
                playCurrentIndexWithDelay()
            } else {
                playCurrentIndex()
            }
        }
    }

    fun updateContext(level: String, isSpeaking: Boolean) {
        val previousLevel = currentLoadLevel
        currentLoadLevel = level
        
        ttsManager.setDucking(isSpeaking)

        if (_isAutoMode.value) {
            when (level) {
                "Low", "Waiting..." -> {
                    ttsManager.setSpeed(1.0f)
                    currentDelay = 0L
                    _isDetailed.value = true
                }
                "Medium" -> {
                    ttsManager.setSpeed(0.75f)
                    currentDelay = 1500L
                    _isDetailed.value = true
                }
                "High" -> {
                    ttsManager.setSpeed(0.75f)
                    currentDelay = 1500L
                    _isDetailed.value = false
                }
            }
            
            if (previousLevel == "Overload" && level != "Overload" && level != "Waiting..." && !_isPlaying.value) {
                if (currentIndex < maxIndex) {
                    _isPlaying.value = true
                    playCurrentIndexWithDelay()
                }
            }
        }
    }

    private fun playCurrentIndexWithDelay() {
        delayJob?.cancel()
        delayJob = scope.launch {
            if (currentDelay > 0) {
                delay(currentDelay)
            }
            playCurrentIndex()
        }
    }

    fun togglePlayPause() {
        if (_isPlaying.value) {
            _isPlaying.value = false
            delayJob?.cancel()
        } else {
            _isPlaying.value = true
            if (currentIndex < maxIndex) {
                playCurrentIndexWithDelay()
            } else {
                currentIndex = 0
                playCurrentIndexWithDelay()
            }
        }
    }

    fun toggleScript() {
        _isDetailed.value = !_isDetailed.value
    }

    private fun playCurrentIndex() {
        if (_isAutoMode.value) {
            when (currentLoadLevel) {
                "Low", "Medium", "Waiting..." -> _isDetailed.value = true
                "High" -> _isDetailed.value = false
                "Overload" -> {
                    _isPlaying.value = false
                    return
                }
            }
        }

        val script = if (_isDetailed.value) detailedScript else simplifiedScript
        if (currentIndex < script.size) {
            val line = script[currentIndex]
            val displayMode = if (_isDetailed.value) "Detailed" else "Simplified"
            val newText = "[$displayMode - ${currentIndex + 1}/$maxIndex]\n\n${line.text}"
            
            val updatedHistory = _history.value.toMutableList()
            if (currentIndex == 0) {
                updatedHistory.clear()
            }
            updatedHistory.add(newText)
            _history.value = updatedHistory
            
            currentUtteranceId = UUID.randomUUID().toString()
            ttsManager.speak(line.text, currentUtteranceId!!)
        } else {
            // In case the scripts have different lengths
            _isPlaying.value = false
            _history.value = _history.value + "End of script reached."
        }
    }
}
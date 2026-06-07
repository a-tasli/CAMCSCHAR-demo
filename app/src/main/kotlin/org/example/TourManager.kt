package org.example

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

data class ScriptLine(val index: Int, val text: String)

class TourManager(private val context: Context, private val ttsManager: TTSManager) {
    private val detailedScript = mutableListOf<ScriptLine>()
    private val simplifiedScript = mutableListOf<ScriptLine>()
    
    val availableLocations = listOf("amphitheatre", "lake_memorial", "tosun_terzioglu_garden")
    private val _currentLocation = MutableStateFlow("amphitheatre")
    val currentLocation: StateFlow<String> = _currentLocation.asStateFlow()

    private var currentIndex = 0
    private var maxIndex = 0
    private var currentUtteranceId: String? = null
    
    private val _history = MutableStateFlow<List<String>>(listOf("Press Play on the Debug tab to start tour."))
    val history: StateFlow<List<String>> = _history.asStateFlow()
    
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    
    private val _isDetailed = MutableStateFlow(true)
    val isDetailed: StateFlow<Boolean> = _isDetailed.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main)

    init {
        loadScripts("amphitheatre")
        
        scope.launch {
            ttsManager.onUtteranceCompleted.collect { utteranceId ->
                if (utteranceId == currentUtteranceId) {
                    currentIndex++
                    if (_isPlaying.value) {
                        if (currentIndex < maxIndex) {
                            playCurrentIndex()
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
            ttsManager.stop()
            _isPlaying.value = false
        }
        loadScripts(location)
        _history.value = listOf("Selected ${location.replace('_', ' ')}. Press Play to start.")
    }

    private fun parseScript(assetPath: String): List<ScriptLine> {
        val lines = mutableListOf<ScriptLine>()
        try {
            val inputStream = context.assets.open(assetPath)
            val reader = BufferedReader(InputStreamReader(inputStream))
            reader.forEachLine { line ->
                val match = Regex("\\[(\\d+)\\]\\s*(.*)").find(line)
                if (match != null) {
                    lines.add(ScriptLine(match.groupValues[1].toInt(), match.groupValues[2]))
                }
            }
            reader.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return lines
    }

    fun togglePlayPause() {
        if (_isPlaying.value) {
            // By setting to false and NOT stopping the TTS, 
            // the onUtteranceCompleted block will simply not play the next line.
            _isPlaying.value = false
        } else {
            _isPlaying.value = true
            if (currentIndex < maxIndex) {
                playCurrentIndex()
            } else {
                currentIndex = 0
                playCurrentIndex()
            }
        }
    }

    fun toggleScript() {
        _isDetailed.value = !_isDetailed.value
    }

    private fun playCurrentIndex() {
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
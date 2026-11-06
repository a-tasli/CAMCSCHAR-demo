package org.example

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import kotlin.math.hypot

data class ScriptLine(val index: Int, val text: String, val isKeyFact: Boolean = false)

data class HistoryItem(
    val text: String,
    val isUtterance: Boolean = false,
    val showImage: Boolean? = null,
    val location: String? = null,
    val utteranceIndex: Int? = null
)

class TourManager(private val context: Context, private val ttsManager: TTSManager) {
    private val detailedScript = mutableListOf<ScriptLine>()
    private val simplifiedScript = mutableListOf<ScriptLine>()

    val availableLocations = listOf("guide", "amphitheatre", "lake_memorial", "tosun_terzioglu_garden")

    private val _currentLocation = MutableStateFlow("guide")
    val currentLocation: StateFlow<String> = _currentLocation.asStateFlow()

    private val _currentArea = MutableStateFlow("None")
    val currentArea: StateFlow<String> = _currentArea.asStateFlow()

    // ---- Audio-first pivot (pipeline-spec.md §7) --------------------------------
    // Presentation mode and the derived audio parameters are first-class state so the
    // UI (§7.0), the experiment logger (§8) and the media notification observe them.

    // The app launches in AUDIO_FIRST by default (v2 pivot): eyes-free audio tour
    // with the large map + collapsible researcher transcript. Switchable from Debug.
    private val _presentationMode = MutableStateFlow(PresentationMode.AUDIO_FIRST)
    val presentationMode: StateFlow<PresentationMode> = _presentationMode.asStateFlow()

    private val _audioParams = MutableStateFlow(AudioTourParams.static(PresentationMode.AUDIO_FIRST))
    val audioParams: StateFlow<AudioTourParams> = _audioParams.asStateFlow()

    private val _loadLevel = MutableStateFlow("Waiting...")
    val loadLevel: StateFlow<String> = _loadLevel.asStateFlow()

    private val earconManager = EarconManager(context)

    /** True when running an audio mode (AUDIO_FIRST / AUDIO_ONLY). */
    private val isAudioMode: Boolean
        get() = _presentationMode.value != PresentationMode.MULTIMODAL

    // Checkpoint-repetition and overload-recap bookkeeping (§7.1)
    private var checkpointActive = false
    private var lastCheckpointRepeatedIndex = -1
    private var isRecapUtterance = false
    private var pausedByOverload = false
    private var lastSpokenText: String? = null

    var currentIndex = 0
        private set
    private var maxIndex = 0
    private var currentUtteranceId: String? = null

    private var guideFinished = false

    private val _history = MutableStateFlow<List<HistoryItem>>(listOf(HistoryItem("Welcome to the Campus Tour! Press 'Start Tour' to begin.")))
    val history: StateFlow<List<HistoryItem>> = _history.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isDetailed = MutableStateFlow(true)
    val isDetailed: StateFlow<Boolean> = _isDetailed.asStateFlow()

    private val _isAutoMode = MutableStateFlow(true)
    val isAutoMode: StateFlow<Boolean> = _isAutoMode.asStateFlow()

    private val _isLargeImageMode = MutableStateFlow(false)
    val isLargeImageMode: StateFlow<Boolean> = _isLargeImageMode.asStateFlow()

    private val _isManualGpsMode = MutableStateFlow(false)
    val isManualGpsMode: StateFlow<Boolean> = _isManualGpsMode.asStateFlow()

    private val _manualLocationLat = MutableStateFlow<Double?>(null)
    val manualLocationLat: StateFlow<Double?> = _manualLocationLat.asStateFlow()

    private val _manualLocationLon = MutableStateFlow<Double?>(null)
    val manualLocationLon: StateFlow<Double?> = _manualLocationLon.asStateFlow()

    private val _manualGpsResetTrigger = MutableStateFlow(0)
    val manualGpsResetTrigger: StateFlow<Int> = _manualGpsResetTrigger.asStateFlow()

    private val _stopAndUploadTrigger = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val stopAndUploadTrigger = _stopAndUploadTrigger.asSharedFlow()

    private val _tourFinishedEvent = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val tourFinishedEvent = _tourFinishedEvent.asSharedFlow()

    val requiredPois = listOf("amphitheatre", "lake_memorial", "tosun_terzioglu_garden")
    private val _completedPois = MutableStateFlow<Set<String>>(emptySet())
    val completedPois: StateFlow<Set<String>> = _completedPois.asStateFlow()

    private val _isEndOfTourReached = MutableStateFlow(false)
    val isEndOfTourReached: StateFlow<Boolean> = _isEndOfTourReached.asStateFlow()

    fun setEndOfTourReached(reached: Boolean) {
        _isEndOfTourReached.value = reached
        if (reached) {
            _tourFinishedEvent.tryEmit(Unit)
        }
    }

    fun recordPoiCompleted(location: String) {
        if (location in requiredPois) {
            _completedPois.value = _completedPois.value + location
        }
        checkEndOfTour()
    }

    fun checkEndOfTour() {
        if (_currentLocation.value == "tosun_terzioglu_garden" && _completedPois.value.containsAll(requiredPois)) {
            _isEndOfTourReached.value = true
            _tourFinishedEvent.tryEmit(Unit)
        }
    }

    fun resetCompletedPois() {
        _completedPois.value = emptySet()
        _isEndOfTourReached.value = false
    }

    fun resetAppState() {
        if (_isPlaying.value) {
            delayJob?.cancel()
            ttsManager.stop()
            _isPlaying.value = false
        }
        delayJob?.cancel()
        ttsManager.stop()
        guideFinished = false
        _completedPois.value = emptySet()
        _isEndOfTourReached.value = false
        _currentArea.value = "None"
        _isLargeImageMode.value = false
        _isDetailed.value = true
        checkpointActive = false
        lastCheckpointRepeatedIndex = -1
        isRecapUtterance = false
        pausedByOverload = false
        lastSpokenText = null
        _loadLevel.value = "Waiting..."
        _audioParams.value = AudioTourParams.static(_presentationMode.value)
        loadScripts("guide")
        _history.value = listOf(HistoryItem("Welcome to the Campus Tour! Press 'Start Tour' to begin."))
    }

    fun debugCompleteAllPois() {
        _completedPois.value = requiredPois.toSet()
        if (_currentLocation.value == "tosun_terzioglu_garden") {
            _isEndOfTourReached.value = true
            _tourFinishedEvent.tryEmit(Unit)
        }
    }

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
                    if (wasPlaying) {
                        _isPlaying.value = true
                        if (isAudioMode) {
                            // POI arrival earcon just before the first line of the POI script (§7.2)
                            earconManager.playPoiArrival()
                            playCurrentIndexWithDelay(extraLeadInMs = 900L)
                        } else {
                            playCurrentIndexWithDelay()
                        }
                    }
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
                    if (isRecapUtterance) {
                        // A recap re-spoke the last completed line; continue from where the tour left off
                        isRecapUtterance = false
                        if (_isPlaying.value) {
                            if (currentIndex < maxIndex) {
                                playCurrentIndexWithDelay()
                            } else {
                                endOfScriptReached()
                            }
                        }
                    } else if (_isPlaying.value && checkpointActive && currentLoadLevel != "Overload" &&
                        currentIndex != lastCheckpointRepeatedIndex && isCheckpointLine(currentIndex)
                    ) {
                        // High-load checkpoint (§7.1): re-speak the just-finished key line once
                        lastCheckpointRepeatedIndex = currentIndex
                        speakCurrentLineAsCheckpoint()
                    } else {
                        currentIndex++
                        if (_isPlaying.value) {
                            if (currentIndex < maxIndex) {
                                playCurrentIndexWithDelay()
                            } else {
                                endOfScriptReached()
                            }
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
        lastCheckpointRepeatedIndex = -1
        _currentLocation.value = location
    }

    fun selectLocation(location: String) {
        if (_isPlaying.value) {
            delayJob?.cancel()
            ttsManager.stop()
            _isPlaying.value = false
        }
        loadScripts(location)
        _history.value = listOf(HistoryItem(text = "Selected ${location.replace('_', ' ')}. Press Play to start.", isUtterance = false))
        if (location == "guide") {
            _completedPois.value = emptySet()
        }
        _isEndOfTourReached.value = false
    }

    /**
     * Parses a script asset. A leading `*` marks a key-fact line and is stripped from
     * the spoken text (content conventions, pipeline-spec.md §7.4). Key-fact markers
     * drive checkpoint repetition; when a script has none, checkpoints fall back to
     * repeating the previous line.
     */
    private fun parseScript(assetPath: String): List<ScriptLine> {
        val lines = mutableListOf<ScriptLine>()
        try {
            val inputStream = context.assets.open(assetPath)
            val reader = BufferedReader(InputStreamReader(inputStream))
            var index = 1
            reader.forEachLine { line ->
                if (line.isNotBlank()) {
                    val trimmed = line.trim()
                    val isKeyFact = trimmed.startsWith("*")
                    val text = if (isKeyFact) trimmed.removePrefix("*").trim() else trimmed
                    if (text.isNotBlank()) {
                        lines.add(ScriptLine(index++, text, isKeyFact))
                    }
                }
            }
            reader.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return lines
    }

    private fun currentScript(): List<ScriptLine> =
        if (_isDetailed.value) detailedScript else simplifiedScript

    /** Checkpoint target check (§7.1): key-fact line if the script has markers, else any line. */
    private fun isCheckpointLine(index: Int): Boolean {
        val script = currentScript()
        if (index !in script.indices) return false
        return if (script.any { it.isKeyFact }) script[index].isKeyFact else true
    }

    private fun speakCurrentLineAsCheckpoint() {
        val script = currentScript()
        if (currentIndex !in script.indices) return
        val line = script[currentIndex]
        lastSpokenText = line.text
        val updatedHistory = _history.value.toMutableList()
        updatedHistory.add(
            HistoryItem(
                text = line.text,
                isUtterance = true,
                location = _currentLocation.value,
                utteranceIndex = line.index
            )
        )
        _history.value = updatedHistory
        currentUtteranceId = UUID.randomUUID().toString()
        ttsManager.speak(line.text, currentUtteranceId!!)
    }

    private fun endOfScriptReached() {
        _isPlaying.value = false
        val updatedHistory = _history.value.toMutableList()
        updatedHistory.add(HistoryItem(text = "End of script reached. Waiting for next location...", isUtterance = false))
        _history.value = updatedHistory
        recordPoiCompleted(_currentLocation.value)
        if (isAudioMode) {
            earconManager.playEndOfTour()
        }
    }

    fun toggleAutoMode() {
        setAutoMode(!_isAutoMode.value)
    }

    fun toggleLargeImageMode() {
        // Only meaningful under MULTIMODAL (§7.6); audio modes have no visual tier.
        if (_presentationMode.value == PresentationMode.MULTIMODAL) {
            _isLargeImageMode.value = !_isLargeImageMode.value
        }
    }

    fun setPresentationMode(mode: PresentationMode) {
        _presentationMode.value = mode
        // Re-apply the current configuration under the new mode; ducking (if any) is
        // re-confirmed by the 1 s analysis loop within a tick.
        updateContext(currentLoadLevel, false)
    }

    fun setAutoMode(enabled: Boolean) {
        _isAutoMode.value = enabled
        if (!enabled) {
            pausedByOverload = false
        }
        // Static mode: fixed at what we show to low cognitive load users (small images,
        // fast tts, detailed script). Audio should not duck in static mode.
        updateContext(currentLoadLevel, false)
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

    fun finishTour() {
        delayJob?.cancel()
        ttsManager.stop()
        _isPlaying.value = false
        guideFinished = true

        val updatedHistory = _history.value.toMutableList()
        updatedHistory.add(HistoryItem(text = "Tour finished.", isUtterance = false))
        _history.value = updatedHistory
        recordPoiCompleted(_currentLocation.value)
        _isEndOfTourReached.value = true
        if (isAudioMode) {
            earconManager.playEndOfTour()
        }
        _tourFinishedEvent.tryEmit(Unit)
    }

    fun nextIndex() {
        if (currentIndex < maxIndex - 1) {
            currentIndex++
            if (_isPlaying.value) {
                playCurrentIndexWithDelay()
            } else {
                playCurrentIndex()
            }
        } else {
            recordPoiCompleted(_currentLocation.value)
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

    /**
     * Maps the fused load level to audio parameters per the adaptation matrix
     * (§7.1 for audio modes; §7.6 legacy pins under MULTIMODAL) and applies them.
     */
    fun updateContext(level: String, isSpeaking: Boolean) {
        val previousLevel = currentLoadLevel
        currentLoadLevel = level
        _loadLevel.value = level

        if (_isAutoMode.value) {
            val params = audioParamsForLevel(level)
                .copy(duckWhileSpeaking = true, recapOnResume = isAudioMode)
            _audioParams.value = params

            ttsManager.setDucking(isSpeaking)
            ttsManager.setSpeed(params.speed)
            currentDelay = params.pauseMs
            _isDetailed.value = params.detailed
            checkpointActive = params.checkpoint

            if (_presentationMode.value == PresentationMode.MULTIMODAL) {
                // Legacy v1 visual tier (§7.6)
                when (level) {
                    "Low", "Waiting..." -> _isLargeImageMode.value = false
                    "Medium", "High" -> _isLargeImageMode.value = true
                    "Overload" -> _isLargeImageMode.value = true
                }
            } else {
                _isLargeImageMode.value = false
            }

            // Overload transition (§7.1): attention earcon; remember that WE paused
            // playback so recovery can auto-resume with a recap.
            if (level == "Overload" && previousLevel != "Overload" && _isPlaying.value) {
                pausedByOverload = true
                if (isAudioMode) {
                    earconManager.playOverload()
                }
            }

            // Recovery: resume automatically; in audio modes lead with a one-line recap
            // because there is no visual cue showing where the tour resumed.
            if (previousLevel == "Overload" && level != "Overload" && level != "Waiting..." &&
                !_isPlaying.value && pausedByOverload
            ) {
                pausedByOverload = false
                if (currentIndex < maxIndex) {
                    _isPlaying.value = true
                    val recapText = lastSpokenText
                    if (isAudioMode && recapText != null) {
                        val updatedHistory = _history.value.toMutableList()
                        updatedHistory.add(HistoryItem(text = "(recap) $recapText", isUtterance = false, location = _currentLocation.value))
                        _history.value = updatedHistory
                        isRecapUtterance = true
                        currentUtteranceId = UUID.randomUUID().toString()
                        ttsManager.speak(recapText, currentUtteranceId!!)
                    } else {
                        playCurrentIndexWithDelay()
                    }
                }
            }
        } else {
            // Static mode (§7.5): fixed at the Low configuration, no ducking, no adaptation.
            val staticParams = AudioTourParams.static(_presentationMode.value)
                .copy(recapOnResume = isAudioMode)
            _audioParams.value = staticParams

            ttsManager.setDucking(false)
            ttsManager.setSpeed(staticParams.speed)
            currentDelay = staticParams.pauseMs
            _isDetailed.value = true
            checkpointActive = false
            _isLargeImageMode.value = false
        }
    }

    private fun audioParamsForLevel(level: String): AudioTourParams {
        val audio = isAudioMode
        return when (level) {
            "Low", "Waiting..." -> if (audio) AudioTourParams.AUDIO_LOW else AudioTourParams.MM_LOW
            "Medium" -> if (audio) AudioTourParams.AUDIO_MEDIUM else AudioTourParams.MM_MEDIUM
            "High" -> if (audio) AudioTourParams.AUDIO_HIGH else AudioTourParams.MM_HIGH
            "Overload" -> {
                // Playback is paused by the Overload rule; keep the last pins so the
                // logger records paused=true with the pre-overload configuration.
                val last = _audioParams.value
                val base = if (audio) AudioTourParams.AUDIO_HIGH else AudioTourParams.MM_OVERLOAD
                base.copy(
                    speed = last?.speed ?: base.speed,
                    pauseMs = last?.pauseMs ?: base.pauseMs,
                    detailed = last?.detailed ?: base.detailed,
                    checkpoint = false,
                    paused = true
                )
            }
            else -> if (audio) AudioTourParams.AUDIO_LOW else AudioTourParams.MM_LOW
        }
    }

    private fun playCurrentIndexWithDelay(extraLeadInMs: Long = 0L) {
        delayJob?.cancel()
        delayJob = scope.launch {
            if (extraLeadInMs + currentDelay > 0) {
                delay(extraLeadInMs + currentDelay)
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
        } else {
            // Static mode: always detailed script
            _isDetailed.value = true
        }

        val script = if (_isDetailed.value) detailedScript else simplifiedScript
        if (currentIndex < script.size) {
            val line = script[currentIndex]
            lastSpokenText = line.text

            val updatedHistory = _history.value.toMutableList()
            if (currentIndex == 0) {
                updatedHistory.clear()
            }
            updatedHistory.add(
                HistoryItem(
                    text = line.text,
                    isUtterance = true,
                    location = _currentLocation.value,
                    utteranceIndex = line.index
                )
            )
            _history.value = updatedHistory

            currentUtteranceId = UUID.randomUUID().toString()
            ttsManager.speak(line.text, currentUtteranceId!!)
        } else {
            // In case the scripts have different lengths
            endOfScriptReached()
        }
    }

    fun toggleImageForUtterance(index: Int, currentExpandedState: Boolean) {
        val updatedHistory = _history.value.toMutableList()
        if (index in updatedHistory.indices) {
            val item = updatedHistory[index]
            if (item.isUtterance) {
                updatedHistory[index] = item.copy(showImage = !currentExpandedState)
                _history.value = updatedHistory
            }
        }
    }
}

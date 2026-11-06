package org.example

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fully configurable parameters and policies for the auditory presentation modality.
 * All parameters can be modified at runtime to adapt audio delivery.
 */
data class AuditoryModalityConfig(
    /** Per-tier parameters for adaptive (dynamic / auto) audio mode. */
    val dynamicTiers: MutableMap<String, AudioTourParams> = mutableMapOf(
        "Low" to AudioTourParams(speed = 1.0f, pauseMs = 300L, detailed = true),
        "Waiting..." to AudioTourParams(speed = 1.0f, pauseMs = 300L, detailed = true),
        "Medium" to AudioTourParams(speed = 0.85f, pauseMs = 1500L, detailed = true),
        "High" to AudioTourParams(speed = 0.75f, pauseMs = 2000L, detailed = false, checkpoint = true),
        "Overload" to AudioTourParams(speed = 0.75f, pauseMs = 2000L, detailed = false, paused = true)
    ),

    /** Per-tier parameters for legacy multimodal UI condition. */
    val multimodalTiers: MutableMap<String, AudioTourParams> = mutableMapOf(
        "Low" to AudioTourParams(speed = 1.0f, pauseMs = 0L, detailed = true),
        "Waiting..." to AudioTourParams(speed = 1.0f, pauseMs = 0L, detailed = true),
        "Medium" to AudioTourParams(speed = 0.75f, pauseMs = 1500L, detailed = true),
        "High" to AudioTourParams(speed = 0.75f, pauseMs = 1500L, detailed = false),
        "Overload" to AudioTourParams(speed = 0.75f, pauseMs = 1500L, detailed = false, paused = true)
    ),

    /** Parameters applied when running in static (control) audio mode. */
    var staticAudioParams: AudioTourParams = AudioTourParams(speed = 1.0f, pauseMs = 300L, detailed = true),

    /** Parameters applied when running in static (control) multimodal mode. */
    var staticMultimodalParams: AudioTourParams = AudioTourParams(speed = 1.0f, pauseMs = 0L, detailed = true),

    /** Whether POI arrival earcons are enabled. */
    var poiArrivalEarconEnabled: Boolean = true,

    /** Whether Overload transition earcons are enabled. */
    var overloadEarconEnabled: Boolean = true,

    /** Whether tour completion earcons are enabled. */
    var endOfTourEarconEnabled: Boolean = true,

    /** Delay (ms) between POI arrival earcon and speaking the first script line. */
    var poiArrivalLeadInMs: Long = 900L
)

/**
 * Auditory presentation modality implementation.
 *
 * Implements [PresentationModality] to adapt speech rate, pauses, density, checkpoints,
 * and earcons based on real-time cognitive load.
 *
 * All parameters in [AuditoryModalityConfig] are modifiable at runtime.
 */
class AuditoryModality(
    val ttsManager: TTSManager? = null,
    val earconManager: EarconManager? = null,
    var config: AuditoryModalityConfig = AuditoryModalityConfig()
) : PresentationModality {

    override val modalityId: String = "auditory"
    override var isEnabled: Boolean = true

    private val _currentParams = MutableStateFlow(config.staticAudioParams)
    val currentParams: StateFlow<AudioTourParams> = _currentParams.asStateFlow()

    val currentSpeed: Float
        get() = _currentParams.value.speed

    val currentDelay: Long
        get() = _currentParams.value.pauseMs

    val isDetailed: Boolean
        get() = _currentParams.value.detailed

    val isCheckpointActive: Boolean
        get() = _currentParams.value.checkpoint

    val isPaused: Boolean
        get() = _currentParams.value.paused

    override fun onCognitiveLoadChanged(state: CognitiveLoadState) {
        if (!isEnabled) return

        if (state.isAutoMode) {
            val tierMap = if (state.isAudioMode) config.dynamicTiers else config.multimodalTiers
            val base = tierMap[state.loadLevel]
                ?: (if (state.isAudioMode) AudioTourParams.AUDIO_LOW else AudioTourParams.MM_LOW)

            val params = if (state.loadLevel == "Overload") {
                val last = _currentParams.value
                base.copy(
                    speed = last.speed,
                    pauseMs = last.pauseMs,
                    detailed = last.detailed,
                    checkpoint = false,
                    paused = true
                )
            } else {
                base.copy(duckWhileSpeaking = false, recapOnResume = state.isAudioMode)
            }
            _currentParams.value = params

            ttsManager?.setDucking(false)
            ttsManager?.setSpeed(params.speed)

            // Overload transition attention earcon
            if (state.loadLevel == "Overload" && state.previousLevel != "Overload") {
                if (state.isAudioMode && config.overloadEarconEnabled) {
                    earconManager?.playOverload()
                }
            }
        } else {
            val staticBase = if (state.isAudioMode) config.staticAudioParams else config.staticMultimodalParams
            val staticParams = staticBase.copy(recapOnResume = state.isAudioMode)
            _currentParams.value = staticParams

            ttsManager?.setDucking(false)
            ttsManager?.setSpeed(staticParams.speed)
        }
    }

    override fun reset() {
        ttsManager?.stop()
        _currentParams.value = config.staticAudioParams
    }

    // ---- Parameter Modification APIs ----

    fun setTierParams(level: String, params: AudioTourParams, isMultimodal: Boolean = false) {
        val map = if (isMultimodal) config.multimodalTiers else config.dynamicTiers
        map[level] = params
    }

    fun setSpeed(level: String, speed: Float, isMultimodal: Boolean = false) {
        val map = if (isMultimodal) config.multimodalTiers else config.dynamicTiers
        val existing = map[level] ?: AudioTourParams.AUDIO_LOW
        map[level] = existing.copy(speed = speed)
    }

    fun setPauseMs(level: String, pauseMs: Long, isMultimodal: Boolean = false) {
        val map = if (isMultimodal) config.multimodalTiers else config.dynamicTiers
        val existing = map[level] ?: AudioTourParams.AUDIO_LOW
        map[level] = existing.copy(pauseMs = pauseMs)
    }

    fun setDensity(level: String, detailed: Boolean, isMultimodal: Boolean = false) {
        val map = if (isMultimodal) config.multimodalTiers else config.dynamicTiers
        val existing = map[level] ?: AudioTourParams.AUDIO_LOW
        map[level] = existing.copy(detailed = detailed)
    }

    fun setCheckpoint(level: String, checkpoint: Boolean, isMultimodal: Boolean = false) {
        val map = if (isMultimodal) config.multimodalTiers else config.dynamicTiers
        val existing = map[level] ?: AudioTourParams.AUDIO_LOW
        map[level] = existing.copy(checkpoint = checkpoint)
    }

    fun setStaticSpeed(speed: Float, isMultimodal: Boolean = false) {
        if (isMultimodal) {
            config.staticMultimodalParams = config.staticMultimodalParams.copy(speed = speed)
        } else {
            config.staticAudioParams = config.staticAudioParams.copy(speed = speed)
        }
    }

    fun setStaticPauseMs(pauseMs: Long, isMultimodal: Boolean = false) {
        if (isMultimodal) {
            config.staticMultimodalParams = config.staticMultimodalParams.copy(pauseMs = pauseMs)
        } else {
            config.staticAudioParams = config.staticAudioParams.copy(pauseMs = pauseMs)
        }
    }

    fun setEarconPolicy(poiArrival: Boolean, overload: Boolean, endOfTour: Boolean) {
        config.poiArrivalEarconEnabled = poiArrival
        config.overloadEarconEnabled = overload
        config.endOfTourEarconEnabled = endOfTour
    }

    fun setPoiArrivalLeadInMs(leadInMs: Long) {
        config.poiArrivalLeadInMs = leadInMs
    }

    fun updateConfig(action: (AuditoryModalityConfig) -> Unit) {
        action(config)
    }

    // ---- Audio Actions ----

    fun speak(text: String, utteranceId: String) {
        ttsManager?.speak(text, utteranceId)
    }

    fun stop() {
        ttsManager?.stop()
    }

    fun playPoiArrival() {
        if (isEnabled && config.poiArrivalEarconEnabled) {
            earconManager?.playPoiArrival()
        }
    }

    fun playOverload() {
        if (isEnabled && config.overloadEarconEnabled) {
            earconManager?.playOverload()
        }
    }

    fun playEndOfTour() {
        if (isEnabled && config.endOfTourEarconEnabled) {
            earconManager?.playEndOfTour()
        }
    }
}

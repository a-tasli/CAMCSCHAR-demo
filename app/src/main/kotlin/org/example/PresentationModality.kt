package org.example

/**
 * Snapshot of cognitive load state delivered to all registered presentation modalities.
 *
 * @param loadLevel Current estimated load tier (e.g. "Low", "Medium", "High", "Overload", "Waiting...").
 * @param previousLevel Previous estimated load tier, useful for detecting state transitions.
 * @param combinedLoadIndex Quantitative cognitive load score (typically 0.0 - 100.0+), or null if waiting.
 * @param isSpeaking Whether discrete user voice activity is currently detected.
 * @param isAutoMode Whether the system is operating in adaptive (dynamic) mode vs. static control mode.
 * @param isAudioMode Whether eyes-free audio presentation is active (AUDIO_FIRST or AUDIO_ONLY) vs MULTIMODAL.
 */
data class CognitiveLoadState(
    val loadLevel: String,
    val previousLevel: String = "Waiting...",
    val combinedLoadIndex: Double? = null,
    val isSpeaking: Boolean = false,
    val isAutoMode: Boolean = true,
    val isAudioMode: Boolean = true
)

/**
 * Pluggable interface for information presentation modalities.
 *
 * Implementing this interface allows new modalities (e.g. Visual HUDs, Haptic feedback,
 * ambient cues) to be plugged into the framework without modifying the cognitive load pipeline.
 */
interface PresentationModality {
    /** Unique identifier for this modality (e.g., "auditory", "visual", "haptic"). */
    val modalityId: String

    /** Whether this modality is enabled and actively presenting / adapting information. */
    var isEnabled: Boolean

    /**
     * Called whenever cognitive load state updates. Modalities should adjust their presentation
     * parameters (rate, complexity, pause, salience, etc.) accordingly.
     */
    fun onCognitiveLoadChanged(state: CognitiveLoadState)

    /**
     * Resets or halts active presentation in this modality.
     */
    fun reset()
}

/**
 * Registry and orchestrator for all active presentation modalities.
 *
 * External modules can register new modalities (visual, haptic, etc.) via [register].
 * Updates to cognitive load are broadcast to all enabled modalities via [notifyCognitiveLoadChanged].
 */
class ModalityManager {
    private val _modalities = mutableMapOf<String, PresentationModality>()

    val registeredModalities: Map<String, PresentationModality>
        get() = _modalities.toMap()

    fun register(modality: PresentationModality) {
        _modalities[modality.modalityId] = modality
    }

    fun unregister(modalityId: String): PresentationModality? {
        return _modalities.remove(modalityId)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : PresentationModality> get(modalityId: String): T? {
        return _modalities[modalityId] as? T
    }

    fun notifyCognitiveLoadChanged(state: CognitiveLoadState) {
        for (modality in _modalities.values) {
            if (modality.isEnabled) {
                modality.onCognitiveLoadChanged(state)
            }
        }
    }

    fun resetAll() {
        for (modality in _modalities.values) {
            modality.reset()
        }
    }
}

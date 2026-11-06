package org.example

/**
 * Presentation modes for the audio-first pivot (pipeline-spec.md §7.0).
 *
 * The load pipeline (§3–§6) and the POI scripts are identical in all modes — only
 * presentation differs.
 */
enum class PresentationMode {
    /** Legacy v1 UI: map, transcript, inline/large images (§7.6). */
    MULTIMODAL,

    /** Eyes-free by default: media notification + collapsible transcript, no images/map. */
    AUDIO_FIRST,

    /** No in-tour content UI at all; notification / headset control only. */
    AUDIO_ONLY
}

/**
 * Everything the load level can modulate in the audio channel (pipeline-spec.md §7.0).
 * Produced by `TourManager.updateContext` on every tick and applied live.
 *
 * @param speed            TTS playback rate (×); applied mid-utterance via AudioTrack
 * @param pauseMs          silence inserted between script lines
 * @param detailed         `true` → detailed script, `false` → simplified script
 * @param checkpoint       re-speak the just-spoken key line once before advancing (High)
 * @param recapOnResume    on Overload recovery, re-speak the last completed line first
 * @param duckWhileSpeaking  volume-duck TTS while `isSpeaking` (auto mode only)
 * @param paused           playback paused by Overload rule (§7.1 Overload row)
 */
data class AudioTourParams(
    val speed: Float,
    val pauseMs: Long,
    val detailed: Boolean,
    val checkpoint: Boolean = false,
    val recapOnResume: Boolean = false,
    val duckWhileSpeaking: Boolean = false,
    val paused: Boolean = false
) {
    companion object {
        // v2 audio adaptation matrix (§7.1) — AUDIO_FIRST / AUDIO_ONLY
        val AUDIO_LOW: AudioTourParams = AudioTourParams(1.0f, 300L, detailed = true)
        val AUDIO_MEDIUM: AudioTourParams = AudioTourParams(0.85f, 1500L, detailed = true)
        val AUDIO_HIGH: AudioTourParams = AudioTourParams(0.75f, 2000L, detailed = false, checkpoint = true)

        // v1 legacy pins retained under MULTIMODAL (§7.6)
        val MM_LOW: AudioTourParams = AudioTourParams(1.0f, 0L, detailed = true)
        val MM_MEDIUM: AudioTourParams = AudioTourParams(0.75f, 1500L, detailed = true)
        val MM_HIGH: AudioTourParams = AudioTourParams(0.75f, 1500L, detailed = false)
        val MM_OVERLOAD: AudioTourParams = AudioTourParams(0.75f, 1500L, detailed = false, paused = true)

        /** Static control condition (§7.5): Low-configuration pins, no ducking. */
        fun static(mode: PresentationMode): AudioTourParams =
            if (mode == PresentationMode.MULTIMODAL) MM_LOW else AUDIO_LOW
    }
}

/**
 * Bridges [TourService]'s media notification / MediaSession callbacks to the
 * [TourManager] owned by [MainActivity]. The service cannot reach the activity's
 * object graph directly; MainActivity registers lambdas here on create and
 * resets them on destroy. All callbacks run on the main thread.
 */
object TourControlBridge {
    @Volatile var playPause: (() -> Unit)? = null
    @Volatile var next: (() -> Unit)? = null
    @Volatile var previous: (() -> Unit)? = null
    @Volatile var isPlaying: () -> Boolean = { false }

    fun reset() {
        playPause = null
        next = null
        previous = null
        isPlaying = { false }
    }
}

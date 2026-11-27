package org.example

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Short earcons for the audio-first tour (pipeline-spec.md §7.2). Only played in
 * AUDIO_FIRST / AUDIO_ONLY — in MULTIMODAL the visual UI carries these cues.
 *
 * Sounds are routed through the same headphone/earphone usage as the tour voice
 * (USAGE_ASSISTANCE_NAVIGATION_GUIDANCE / CONTENT_TYPE_SONIFICATION).
 */
class EarconManager(context: Context) {

    private val soundPool: SoundPool? = try {
        SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
    } catch (_: Throwable) {
        null
    }

    private val poiArrivalId = try { soundPool?.load(context, R.raw.earcon_poi_arrival, 1) ?: 0 } catch (_: Throwable) { 0 }
    private val overloadId = try { soundPool?.load(context, R.raw.earcon_overload, 1) ?: 0 } catch (_: Throwable) { 0 }
    private val endOfTourId = try { soundPool?.load(context, R.raw.earcon_end_of_tour, 1) ?: 0 } catch (_: Throwable) { 0 }

    /** POI geofence entry — played just before the first line of the POI script. */
    fun playPoiArrival() {
        soundPool?.play(poiArrivalId, 1f, 1f, 1, 0, 1f)
    }

    /** Transition into Overload — played as playback is paused. */
    fun playOverload() {
        soundPool?.play(overloadId, 1f, 1f, 1, 0, 1f)
    }

    /** End of script / end of tour. */
    fun playEndOfTour() {
        soundPool?.play(endOfTourId, 1f, 1f, 1, 0, 1f)
    }
}

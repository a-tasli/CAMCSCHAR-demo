package org.example

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class TTSManager(private val context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private var isDucked = false
    private var currentSpeed = 1.0f
    private var currentVolume = 1.0f

    private val trackLock = Any()
    private var audioTrack: AudioTrack? = null
    private var bytesPerFrame = 2
    private var totalFramesWritten = 0
    private var currentSynthesisId: String? = null

    private val _onUtteranceCompleted = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val onUtteranceCompleted: SharedFlow<String> = _onUtteranceCompleted.asSharedFlow()

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            // Keep engine at 1.0, we control speed via AudioTrack for instant response
            tts?.setSpeechRate(1.0f)
            isInitialized = true

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    synchronized(trackLock) {
                        if (utteranceId == null || utteranceId != currentSynthesisId) return

                        val track = audioTrack
                        if (totalFramesWritten > 0 && track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                            try {
                                track.setNotificationMarkerPosition(totalFramesWritten)
                            } catch (_: Exception) {
                                _onUtteranceCompleted.tryEmit(utteranceId)
                            }
                        } else {
                            _onUtteranceCompleted.tryEmit(utteranceId)
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {}

                override fun onBeginSynthesis(utteranceId: String?, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                    synchronized(trackLock) {
                        if (utteranceId == null || utteranceId != currentSynthesisId) return

                        try {
                            audioTrack?.stop()
                            audioTrack?.release()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        audioTrack = null

                        bytesPerFrame = channelCount * (if (audioFormat == AudioFormat.ENCODING_PCM_16BIT) 2 else 1)
                        totalFramesWritten = 0

                        val channelConfig = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO

                        try {
                            val newTrack = AudioTrack.Builder()
                                .setAudioAttributes(
                                    AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                        .build()
                                )
                                .setAudioFormat(
                                    AudioFormat.Builder()
                                        .setEncoding(audioFormat)
                                        .setSampleRate(sampleRateInHz)
                                        .setChannelMask(channelConfig)
                                        .build()
                                )
                                .setBufferSizeInBytes(
                                    AudioTrack.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat).coerceAtLeast(1024 * 16)
                                )
                                .setTransferMode(AudioTrack.MODE_STREAM)
                                .build()

                            newTrack.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                                override fun onMarkerReached(track: AudioTrack) {
                                    synchronized(trackLock) {
                                        if (track == audioTrack && currentSynthesisId == utteranceId) {
                                            _onUtteranceCompleted.tryEmit(utteranceId)
                                        }
                                    }
                                }

                                override fun onPeriodicNotification(track: AudioTrack) {}
                            })

                            try {
                                val params = PlaybackParams().setSpeed(currentSpeed)
                                newTrack.playbackParams = params
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }

                            newTrack.setVolume(currentVolume)
                            newTrack.play()
                            audioTrack = newTrack
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                override fun onAudioAvailable(utteranceId: String?, audio: ByteArray) {
                    synchronized(trackLock) {
                        if (utteranceId == null || utteranceId != currentSynthesisId) return
                        val track = audioTrack ?: return
                        if (track.state != AudioTrack.STATE_INITIALIZED || track.playState != AudioTrack.PLAYSTATE_PLAYING) return

                        try {
                            track.write(audio, 0, audio.size)
                            totalFramesWritten += audio.size / bytesPerFrame
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            })
        }
    }

    fun setSpeed(speed: Float) {
        currentSpeed = speed
        synchronized(trackLock) {
            if (isInitialized) {
                audioTrack?.let { track ->
                    try {
                        val params = track.playbackParams
                        params.speed = speed
                        track.playbackParams = params
                    } catch (_: Exception) {
                        // Track might not be in a state to accept params yet
                    }
                }
            }
        }
    }

    fun setDucking(duck: Boolean) {
        // Audio ducking is disabled in this codebase; volume remains at 1.0f
        isDucked = false
        currentVolume = 1.0f
        synchronized(trackLock) {
            audioTrack?.setVolume(1.0f)
        }
    }

    fun speak(text: String, utteranceId: String) {
        if (isInitialized) {
            synchronized(trackLock) {
                currentSynthesisId = utteranceId
                try {
                    audioTrack?.stop()
                    audioTrack?.release()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                audioTrack = null
                totalFramesWritten = 0
            }
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.0f)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        }
    }

    fun stop() {
        synchronized(trackLock) {
            currentSynthesisId = null
            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            audioTrack = null
            totalFramesWritten = 0
        }
        tts?.stop()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}

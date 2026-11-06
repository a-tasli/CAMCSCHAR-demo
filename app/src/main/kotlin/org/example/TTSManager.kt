package org.example

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams

class TTSManager(private val context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    
    private var isDucked = false
    private var currentSpeed = 1.0f
    private var currentVolume = 1.0f
    
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
                    utteranceId?.let { id ->
                        if (totalFramesWritten > 0) {
                            audioTrack?.setNotificationMarkerPosition(totalFramesWritten)
                        } else {
                            _onUtteranceCompleted.tryEmit(id)
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {}

                override fun onBeginSynthesis(utteranceId: String?, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                    audioTrack?.stop()
                    audioTrack?.release()

                    currentSynthesisId = utteranceId
                    bytesPerFrame = channelCount * (if (audioFormat == AudioFormat.ENCODING_PCM_16BIT) 2 else 1)
                    totalFramesWritten = 0

                    val channelConfig = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                    
                    audioTrack = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                        .setAudioFormat(AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRateInHz)
                            .setChannelMask(channelConfig)
                            .build())
                        .setBufferSizeInBytes(AudioTrack.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat).coerceAtLeast(1024 * 16))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                    
                    audioTrack?.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                        override fun onMarkerReached(track: AudioTrack) {
                            currentSynthesisId?.let { id ->
                                _onUtteranceCompleted.tryEmit(id)
                            }
                        }
                        override fun onPeriodicNotification(track: AudioTrack) {}
                    })

                    // Set initial speed and volume
                    try {
                        val params = PlaybackParams().setSpeed(currentSpeed)
                        audioTrack?.playbackParams = params
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    
                    audioTrack?.setVolume(currentVolume)
                    audioTrack?.play()
                }

                override fun onAudioAvailable(utteranceId: String?, audio: ByteArray) {
                    audioTrack?.write(audio, 0, audio.size)
                    totalFramesWritten += audio.size / bytesPerFrame
                }
            })
        }
    }

    fun setSpeed(speed: Float) {
        currentSpeed = speed
        // Update currently playing track speed instantly
        if (isInitialized) {
            audioTrack?.let { track ->
                try {
                    val params = track.playbackParams
                    params.speed = speed
                    track.playbackParams = params
                } catch (e: Exception) {
                    // Track might not be in a state to accept params yet
                }
            }
        }
    }

    fun setDucking(duck: Boolean) {
        // Audio ducking is disabled in this codebase; volume remains at 1.0f
        isDucked = false
        currentVolume = 1.0f
        audioTrack?.setVolume(1.0f)
    }

    fun speak(text: String, utteranceId: String) {
        if (isInitialized) {
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.0f)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        }
    }

    fun stop() {
        tts?.stop()
        audioTrack?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        audioTrack?.stop()
        audioTrack?.release()
    }
}
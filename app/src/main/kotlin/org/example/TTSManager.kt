package org.example

import android.content.Context
import android.media.AudioManager
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
    
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var originalVolume: Int = -1
    private var isDucked = false
    private var currentSpeed = 1.0f
    private var currentVolume = 1.0f
    
    private val _onUtteranceCompleted = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val onUtteranceCompleted: SharedFlow<String> = _onUtteranceCompleted.asSharedFlow()

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setSpeechRate(currentSpeed)
            isInitialized = true
            
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                
                override fun onDone(utteranceId: String?) {
                    utteranceId?.let {
                        _onUtteranceCompleted.tryEmit(it)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {}
            })
        }
    }

    fun setSpeed(speed: Float) {
        currentSpeed = speed
        tts?.setSpeechRate(speed)
    }

    fun setDucking(duck: Boolean) {
        if (duck && !isDucked) {
            originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val duckedVolume = kotlin.math.max(1, (originalVolume * 0.5).toInt())
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, duckedVolume, 0)
            isDucked = true
            currentVolume = 0.5f
        } else if (!duck && isDucked) {
            if (originalVolume != -1) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            }
            isDucked = false
            currentVolume = 1.0f
        }
    }

    fun speak(text: String, utteranceId: String) {
        if (isInitialized) {
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, currentVolume)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        if (isDucked && originalVolume != -1) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
        }
        tts?.stop()
        tts?.shutdown()
    }
}
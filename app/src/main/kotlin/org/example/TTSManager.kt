package org.example

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class TTSManager(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    
    private val _onUtteranceCompleted = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val onUtteranceCompleted: SharedFlow<String> = _onUtteranceCompleted.asSharedFlow()

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setSpeechRate(0.9f)
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

    fun speak(text: String, utteranceId: String) {
        if (isInitialized) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}
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

class TTSManager(private val context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    
    private var isDucked = false
    private var currentSpeed = 1.0f
    private var currentVolume = 1.0f
    
    private var audioTrack: AudioTrack? = null
    
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

                override fun onBeginSynthesis(utteranceId: String?, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                    audioTrack?.stop()
                    audioTrack?.release()

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
                        .setBufferSizeInBytes(AudioTrack.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                    
                    audioTrack?.setVolume(currentVolume)
                    audioTrack?.play()
                }

                override fun onAudioAvailable(utteranceId: String?, audio: ByteArray) {
                    audioTrack?.write(audio, 0, audio.size)
                }
            })
        }
    }

    fun setSpeed(speed: Float) {
        currentSpeed = speed
        tts?.setSpeechRate(speed)
    }

    fun setDucking(duck: Boolean) {
        if (isDucked == duck) return
        isDucked = duck
        currentVolume = if (duck) 0.2f else 1.0f
        audioTrack?.setVolume(currentVolume)
    }

    fun speak(text: String, utteranceId: String) {
        if (isInitialized) {
            // We use synthesizeToFile or speak with a null file to trigger the callbacks
            // speak() with QUEUE_FLUSH will trigger onBeginSynthesis and onAudioAvailable
            val params = Bundle()
            // We must set the volume to 0 here because we are playing it via AudioTrack manually
            // Actually, if we use onAudioAvailable, the TTS engine might still try to play it
            // if we use speak(). To avoid double audio, we should use synthesizeToFile 
            // to a dummy file or use the newer synthesize method if available.
            // However, a simpler way is to use speak() but set the TTS engine volume to 0.
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
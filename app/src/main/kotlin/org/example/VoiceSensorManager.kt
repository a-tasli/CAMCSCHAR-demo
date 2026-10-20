package org.example

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.sqrt

class VoiceSensorManager {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    private val _isCooldown = MutableStateFlow(false)
    val isCooldown: StateFlow<Boolean> = _isCooldown

    private val sampleRate = 8000
    private val bufferSize = 1024 // 128ms chunks

    // Global volume floor (RMS threshold) to ignore distant ambient conversations.
    // 16-bit PCM values range up to 32768. 500 is a reasonable floor for close-proximity speech.
    private val volumeFloor = 400.0 

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRecording) return

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val actualBufferSize = maxOf(bufferSize * 2, minBufferSize)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            actualBufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            try {
                audioRecord?.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            audioRecord = null
            return
        }

        isRecording = true
        try {
            audioRecord?.startRecording()
        } catch (e: Exception) {
            e.printStackTrace()
            isRecording = false
            audioRecord?.release()
            audioRecord = null
            return
        }

        recordingThread = Thread {
            processAudio()
        }
        recordingThread?.start()
    }

    fun stop() {
        isRecording = false
        try {
            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord?.stop()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioRecord = null
        try {
            recordingThread?.join(300)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        recordingThread = null
    }

    private fun processAudio() {
        val buffer = ShortArray(bufferSize)
        val historySize = 10 // Rolling window of the last ~1.2 seconds
        val speechHistory = BooleanArray(historySize)
        var historyIndex = 0
        var lastSpeechTimestamp = 0L

        while (isRecording) {
            val readSize = audioRecord?.read(buffer, 0, bufferSize) ?: 0
            if (readSize > 0) {
                val isSpeech = analyzeChunk(buffer, readSize)
                speechHistory[historyIndex] = isSpeech
                historyIndex = (historyIndex + 1) % historySize

                // Debouncing: If at least 3 out of the last 10 chunks are classified as speech
                val speakingCount = speechHistory.count { it }
                val currentlySpeaking = speakingCount >= 3
                
                if (currentlySpeaking) {
                    lastSpeechTimestamp = System.currentTimeMillis()
                    _isSpeaking.value = true
                    _isCooldown.value = false
                } else {
                    if (_isSpeaking.value) {
                        // Only turn off the speaking state 3 seconds after the last detected speech
                        if (System.currentTimeMillis() - lastSpeechTimestamp > 3000L) {
                            _isSpeaking.value = false
                            _isCooldown.value = false
                        } else {
                            _isCooldown.value = true
                        }
                    }
                }
            }
        }
    }

    private fun analyzeChunk(buffer: ShortArray, readSize: Int): Boolean {
        // 1. Calculate RMS to establish a global volume floor
        var sumSquares = 0.0
        for (i in 0 until readSize) {
            val sample = buffer[i].toDouble()
            sumSquares += sample * sample
        }
        val rms = sqrt(sumSquares / readSize)

        // If it's too quiet (e.g. ambient background noise), discard immediately
        if (rms < volumeFloor) {
            return false 
        }

        // 2. Perform Fast Fourier Transform (FFT)
        // bufferSize must be a power of 2 (1024 is)
        val complexSignal = DoubleArray(bufferSize * 2)
        for (i in 0 until readSize) {
            complexSignal[2 * i] = buffer[i].toDouble() // Real part
            complexSignal[2 * i + 1] = 0.0 // Imaginary part
        }

        fft(complexSignal)

        // 3. Analyze Frequency Bands
        var voiceEnergy = 0.0
        var noiseEnergy = 0.0

        // Frequency resolution: 8000 / 1024 = ~7.81 Hz per bin
        val binRes = sampleRate.toDouble() / bufferSize

        // We only care about positive frequencies (first half of the FFT result)
        for (i in 0 until bufferSize / 2) {
            val real = complexSignal[2 * i]
            val imag = complexSignal[2 * i + 1]
            val magnitude = sqrt(real * real + imag * imag)

            val freq = i * binRes

            // Human voice falls roughly between 300 Hz and 3000 Hz
            if (freq in 300.0..3000.0) {
                voiceEnergy += magnitude
            } else {
                noiseEnergy += magnitude
            }
        }

        // 4. The Heuristic: voice band must dominate the rest of the spectrum
        // A ratio of 1.5 ensures it's actually speech and not broad-spectrum noise (like wind)
        return voiceEnergy > (noiseEnergy * 1.5)
    }

    // In-place Cooley-Tukey FFT algorithm
    private fun fft(data: DoubleArray) {
        val n = data.size / 2
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                var temp = data[2 * i]
                data[2 * i] = data[2 * j]
                data[2 * j] = temp
                temp = data[2 * i + 1]
                data[2 * i + 1] = data[2 * j + 1]
                data[2 * j + 1] = temp
            }
            var k = n / 2
            while (k <= j) {
                j -= k
                k /= 2
            }
            j += k
        }

        var l1 = 1
        while (l1 < n) {
            val l2 = l1 * 2
            val c1 = cos(PI / l1)
            val c2 = -sin(PI / l1)
            var z1 = 1.0
            var z2 = 0.0
            for (j in 0 until l1) {
                var i = j
                while (i < n) {
                    val i1 = i + l1
                    val t1 = z1 * data[2 * i1] - z2 * data[2 * i1 + 1]
                    val t2 = z1 * data[2 * i1 + 1] + z2 * data[2 * i1]
                    data[2 * i1] = data[2 * i] - t1
                    data[2 * i1 + 1] = data[2 * i + 1] - t2
                    data[2 * i] += t1
                    data[2 * i + 1] += t2
                    i += l2
                }
                val temp = z1 * c1 - z2 * c2
                z2 = z1 * c2 + z2 * c1
                z1 = temp
            }
            l1 = l2
        }
    }
}
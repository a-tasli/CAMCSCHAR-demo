package org.example

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Voice-activity channel (§3.4 of pipeline-spec.md).
 *
 * Speech presence is classified with **Silero VAD** (16 kHz ONNX export with
 * explicit state, ~1.2 MB, MIT license) running on ONNX Runtime. Each
 * 512-sample (32 ms) chunk is scored as a speech probability; unlike the old
 * FFT band-energy heuristic this is robust to wind, traffic, footsteps and
 * other broad-spectrum noise.
 *
 * If the model fails to load (e.g. corrupted asset / OOM), the manager falls
 * back to the legacy spectral heuristic so the voice channel degrades instead
 * of dying silently.
 */
class VoiceSensorManager(private val context: Context) {

    companion object {
        private const val TAG = "VoiceSensorManager"
        private const val MODEL_ASSET = "silero_vad_16k.onnx"

        /** Speech probability above which a chunk counts as speech. */
        private const val SPEECH_PROB_THRESHOLD = 0.5f

        /** RMS floor for the legacy heuristic fallback (16-bit PCM scale). */
        private const val LEGACY_VOLUME_FLOOR = 400.0
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    // --- Silero VAD (ONNX Runtime) ---
    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var modelLoaded = false

    /** Recurrent state carried between chunks; [320] zeros starts a fresh stream. */
    private var vadState: FloatArray = FloatArray(320)

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    private val _isCooldown = MutableStateFlow(false)
    val isCooldown: StateFlow<Boolean> = _isCooldown

    private val sampleRate = 16000

    /** Silero v5 16 kHz requires exactly 512-sample (32 ms) chunks. */
    private val chunkSamples = 512

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRecording) return

        loadModel()

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val actualBufferSize = maxOf(chunkSamples * 4, minBufferSize)

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
        // The ORT session is kept across start/stop cycles on purpose: it is
        // expensive to rebuild and the manager lives for the app's lifetime.
    }

    /** Loads the Silero model once; resets the recurrent state per [start]. */
    private fun loadModel() {
        if (modelLoaded) {
            vadState.fill(0f)
            return
        }
        val env = OrtEnvironment.getEnvironment()
        try {
            val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
            }
            ortEnvironment = env
            ortSession = env.createSession(modelBytes, options)
            vadState.fill(0f)
            modelLoaded = true
            Log.i(TAG, "Silero VAD model loaded (${modelBytes.size} bytes)")
        } catch (e: Throwable) { // Error too: OOM / missing native lib must not crash the app
            modelLoaded = false
            Log.e(TAG, "Failed to load Silero VAD model (${MODEL_ASSET}); " +
                    "falling back to spectral heuristic", e)
        }
    }

    private fun processAudio() {
        val buffer = ShortArray(chunkSamples)
        val floatChunk = FloatArray(chunkSamples)

        // 32 ms chunks: last 25 chunks ~ 0.8 s window, ≥ 5 speech chunks ~ 0.16 s
        val historySize = 25
        val minSpeechChunks = 5
        val speechHistory = BooleanArray(historySize)
        var historyIndex = 0
        var lastSpeechTimestamp = 0L

        while (isRecording) {
            val readSize = audioRecord?.read(buffer, 0, chunkSamples) ?: 0
            if (readSize > 0) {
                val isSpeech = analyzeChunk(buffer, readSize, floatChunk)
                speechHistory[historyIndex] = isSpeech
                historyIndex = (historyIndex + 1) % historySize

                // Debouncing: speech only if enough chunks in the rolling window were speech
                val speakingCount = speechHistory.count { it }
                val currentlySpeaking = speakingCount >= minSpeechChunks

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

    private fun analyzeChunk(buffer: ShortArray, readSize: Int, floatChunk: FloatArray): Boolean {
        if (!modelLoaded) return analyzeChunkHeuristic(buffer, readSize)

        for (i in floatChunk.indices) {
            floatChunk[i] = (if (i < readSize) buffer[i] else 0) / 32768.0f
        }
        return try {
            runVad(floatChunk) > SPEECH_PROB_THRESHOLD
        } catch (e: Throwable) { // includes native errors: degrade to the heuristic
            Log.e(TAG, "Silero VAD inference failed; falling back to heuristic", e)
            modelLoaded = false
            analyzeChunkHeuristic(buffer, readSize)
        }
    }

    /** Runs one chunk through the model, updating [vadState]. Returns speech probability. */
    private fun runVad(chunk: FloatArray): Float {
        val env = ortEnvironment ?: error("ORT environment is not initialized")
        val session = ortSession ?: error("ORT session is not initialized")

        val audioTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(chunk), longArrayOf(1, 1, chunk.size.toLong())
        )
        val stateTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(vadState), longArrayOf(1, vadState.size.toLong())
        )

        audioTensor.use {
            stateTensor.use {
                session.run(mapOf("audio_in" to audioTensor, "state_in" to stateTensor)).use { result ->
                    val probTensor = (result.get("probability").orElse(null) ?: result.get(0)) as OnnxTensor
                    val prob = probTensor.floatBuffer.get(0)
                    val newState = (result.get("state_out").orElse(null) ?: result.get(1)) as OnnxTensor
                    val stateBuf = newState.floatBuffer
                    stateBuf.rewind()
                    stateBuf.get(vadState) // carry state into the next chunk
                    return prob
                }
            }
        }
    }

    /**
     * Legacy fallback classifier (pre-Silero §3.4): 16-bit RMS floor, then
     * spectral dominance of the human voice band (300–3000 Hz) vs the rest,
     * restricted to ≤ 4 kHz so the ratio keeps the original 8 kHz tuning.
     */
    private fun analyzeChunkHeuristic(buffer: ShortArray, readSize: Int): Boolean {
        var sumSquares = 0.0
        for (i in 0 until readSize) {
            val sample = buffer[i].toDouble()
            sumSquares += sample * sample
        }
        val rms = sqrt(sumSquares / readSize)

        if (rms < LEGACY_VOLUME_FLOOR) {
            return false
        }

        val n = chunkSamples
        val complexSignal = DoubleArray(n * 2)
        for (i in 0 until readSize) {
            complexSignal[2 * i] = buffer[i].toDouble()
        }

        fft(complexSignal)

        var voiceEnergy = 0.0
        var noiseEnergy = 0.0
        val binRes = sampleRate.toDouble() / n

        for (i in 0 until n / 2) {
            val real = complexSignal[2 * i]
            val imag = complexSignal[2 * i + 1]
            val magnitude = sqrt(real * real + imag * imag)

            val freq = i * binRes

            if (freq > 4000.0) continue // ignore the extension to the 16 kHz band

            if (freq in 300.0..3000.0) {
                voiceEnergy += magnitude
            } else {
                noiseEnergy += magnitude
            }
        }

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

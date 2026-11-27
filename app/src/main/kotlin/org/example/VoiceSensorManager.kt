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
import kotlinx.coroutines.flow.asStateFlow
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

data class AcousticFrameMetrics(
    val rms: Double,
    val dbfs: Double,
    val midbandRatio: Double,
    val voiceToNoiseRatio: Double
)

/**
 * Pure-Kotlin acoustic frame analyzer shared by both the non-speech ambient floor
 * estimator and the legacy spectral heuristic fallback.
 */
object AcousticFrameAnalyzer {
    fun computeMetrics(
        buffer: ShortArray,
        readSize: Int,
        sampleRate: Int = 16000,
        fftSize: Int = 512
    ): AcousticFrameMetrics {
        if (readSize <= 0) {
            return AcousticFrameMetrics(rms = 0.0, dbfs = -120.0, midbandRatio = 0.0, voiceToNoiseRatio = 0.0)
        }

        var sumSquares = 0.0
        for (i in 0 until readSize) {
            val sample = buffer[i].toDouble()
            sumSquares += sample * sample
        }
        val rms = sqrt(sumSquares / readSize)
        val normRms = (rms / 32768.0).coerceAtLeast(1e-6)
        val dbfs = (20.0 * log10(normRms)).coerceIn(-120.0, 0.0)

        val complexSignal = DoubleArray(fftSize * 2)
        val limit = minOf(readSize, fftSize)
        for (i in 0 until limit) {
            complexSignal[2 * i] = buffer[i].toDouble()
        }
        fft(complexSignal)

        var voiceEnergy = 0.0
        var lowHighNoiseEnergy = 0.0
        var voicePower = 0.0
        var totalPower = 0.0
        val binRes = sampleRate.toDouble() / fftSize

        for (i in 1 until fftSize / 2) {
            val real = complexSignal[2 * i]
            val imag = complexSignal[2 * i + 1]
            val power = real * real + imag * imag
            val magnitude = sqrt(power)
            val freq = i * binRes

            totalPower += power
            if (freq in 300.0..3000.0) {
                voicePower += power
            }
            if (freq <= 4000.0) {
                if (freq in 300.0..3000.0) {
                    voiceEnergy += magnitude
                } else {
                    lowHighNoiseEnergy += magnitude
                }
            }
        }

        val midbandRatio = if (totalPower > 0.0) (voicePower / totalPower).coerceIn(0.0, 1.0) else 0.0
        val voiceToNoiseRatio = when {
            lowHighNoiseEnergy > 1e-9 -> voiceEnergy / lowHighNoiseEnergy
            voiceEnergy > 1e-9 -> 999.0
            else -> 0.0
        }

        return AcousticFrameMetrics(
            rms = rms,
            dbfs = dbfs,
            midbandRatio = midbandRatio,
            voiceToNoiseRatio = voiceToNoiseRatio
        )
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

        /** EMA smoothing factor per 32 ms non-speech chunk (~0.4 s time constant). */
        private const val AMBIENT_EMA_ALPHA = 0.08

        /** 312 chunks * 32 ms ≈ 10 seconds rolling speech duty window. */
        private const val DUTY_WINDOW_CHUNKS = 312

        private const val PEAK_WINDOW_MS = 10_000L
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    @Volatile
    private var isTtsActive = false

    // --- Silero VAD (ONNX Runtime) ---
    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var modelLoaded = false

    /** Recurrent state carried between chunks; [320] zeros starts a fresh stream. */
    private var vadState: FloatArray = FloatArray(320)

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isCooldown = MutableStateFlow(false)
    val isCooldown: StateFlow<Boolean> = _isCooldown.asStateFlow()

    private val _ambientNoiseDbfs = MutableStateFlow<Double?>(null)
    val ambientNoiseDbfs: StateFlow<Double?> = _ambientNoiseDbfs.asStateFlow()

    private val _ambientNoisePeakDbfs = MutableStateFlow<Double?>(null)
    val ambientNoisePeakDbfs: StateFlow<Double?> = _ambientNoisePeakDbfs.asStateFlow()

    private val _ambientMidbandRatio = MutableStateFlow<Double?>(null)
    val ambientMidbandRatio: StateFlow<Double?> = _ambientMidbandRatio.asStateFlow()

    private val _speechDutyCycle10s = MutableStateFlow(0f)
    val speechDutyCycle10s: StateFlow<Float> = _speechDutyCycle10s.asStateFlow()

    fun setTtsActive(active: Boolean) {
        isTtsActive = active
    }

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

        // 32 ms chunks: last 25 chunks ~ 0.8 s window, >= 5 speech chunks ~ 0.16 s
        val historySize = 25
        val minSpeechChunks = 5
        val speechHistory = BooleanArray(historySize)
        var historyIndex = 0
        var lastSpeechTimestamp = 0L

        val dutyHistory = BooleanArray(DUTY_WINDOW_CHUNKS)
        var dutyIndex = 0
        var dutyFilled = 0
        var dutySpeechCount = 0

        val peakSamples = ArrayDeque<Pair<Long, Double>>()
        var emaDbfs: Double? = null
        var emaMidband: Double? = null

        while (isRecording) {
            val readSize = audioRecord?.read(buffer, 0, chunkSamples) ?: 0
            if (readSize > 0) {
                val nowMs = System.currentTimeMillis()
                val ttsPlaying = isTtsActive
                val metrics = AcousticFrameAnalyzer.computeMetrics(buffer, readSize, sampleRate, chunkSamples)
                val isSpeech = if (ttsPlaying) false else analyzeChunk(buffer, readSize, floatChunk, metrics)

                speechHistory[historyIndex] = isSpeech
                historyIndex = (historyIndex + 1) % historySize

                // Rolling 10 s speech duty cycle (frozen during own TTS playback)
                if (!ttsPlaying) {
                    if (dutyFilled < DUTY_WINDOW_CHUNKS) {
                        dutyFilled++
                    } else if (dutyHistory[dutyIndex]) {
                        dutySpeechCount--
                    }
                    dutyHistory[dutyIndex] = isSpeech
                    if (isSpeech) dutySpeechCount++
                    dutyIndex = (dutyIndex + 1) % DUTY_WINDOW_CHUNKS
                    _speechDutyCycle10s.value = dutySpeechCount.toFloat() / dutyFilled.toFloat()
                }

                // Non-speech ambient acoustic floor (RAM-only scalars, discarded PCM)
                if (!ttsPlaying && !isSpeech) {
                    emaDbfs = emaDbfs?.let { it + AMBIENT_EMA_ALPHA * (metrics.dbfs - it) } ?: metrics.dbfs
                    emaMidband = emaMidband?.let { it + AMBIENT_EMA_ALPHA * (metrics.midbandRatio - it) } ?: metrics.midbandRatio
                    _ambientNoiseDbfs.value = emaDbfs
                    _ambientMidbandRatio.value = emaMidband

                    peakSamples.addLast(nowMs to metrics.dbfs)
                    while (peakSamples.isNotEmpty() && nowMs - peakSamples.first().first > PEAK_WINDOW_MS) {
                        peakSamples.removeFirst()
                    }
                    _ambientNoisePeakDbfs.value = peakSamples.maxOfOrNull { it.second }
                }

                // Debouncing: speech only if enough chunks in the rolling window were speech
                val speakingCount = speechHistory.count { it }
                val currentlySpeaking = speakingCount >= minSpeechChunks

                if (currentlySpeaking) {
                    lastSpeechTimestamp = nowMs
                    _isSpeaking.value = true
                    _isCooldown.value = false
                } else {
                    if (_isSpeaking.value) {
                        // Only turn off the speaking state 3 seconds after the last detected speech
                        if (nowMs - lastSpeechTimestamp > 3000L) {
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

    private fun analyzeChunk(
        buffer: ShortArray,
        readSize: Int,
        floatChunk: FloatArray,
        metrics: AcousticFrameMetrics
    ): Boolean {
        if (!modelLoaded) return analyzeChunkHeuristic(metrics)

        for (i in floatChunk.indices) {
            floatChunk[i] = (if (i < readSize) buffer[i] else 0) / 32768.0f
        }
        return try {
            runVad(floatChunk) > SPEECH_PROB_THRESHOLD
        } catch (e: Throwable) { // includes native errors: degrade to the heuristic
            Log.e(TAG, "Silero VAD inference failed; falling back to heuristic", e)
            modelLoaded = false
            analyzeChunkHeuristic(metrics)
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
    private fun analyzeChunkHeuristic(metrics: AcousticFrameMetrics): Boolean {
        if (metrics.rms < LEGACY_VOLUME_FLOOR) {
            return false
        }
        return metrics.voiceToNoiseRatio > 1.5
    }
}

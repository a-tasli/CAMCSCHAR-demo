package org.example

import android.annotation.SuppressLint
import android.content.Context
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.CameraSelector
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class BlinkSensorManager(private val context: Context) {
    private val _blinksLast30s = MutableStateFlow(0)
    val blinksLast30s: StateFlow<Int> = _blinksLast30s.asStateFlow()

    private val _leftEyeProb = MutableStateFlow<Float?>(null)
    val leftEyeProb: StateFlow<Float?> = _leftEyeProb.asStateFlow()

    private val _rightEyeProb = MutableStateFlow<Float?>(null)
    val rightEyeProb: StateFlow<Float?> = _rightEyeProb.asStateFlow()

    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    
    // Blink detection state
    private var isEyesClosed = false
    private val blinkTimestamps = mutableListOf<Long>()

    fun start(lifecycleOwner: LifecycleOwner) {
        if (cameraExecutor.isShutdown) {
            cameraExecutor = Executors.newSingleThreadExecutor()
        }
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build()

            val detector = FaceDetection.getClient(options)

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageProxy(detector, imageProxy)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner, cameraSelector, imageAnalyzer
                )
            } catch (exc: Exception) {
                exc.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        cameraExecutor.shutdown()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()
        }, ContextCompat.getMainExecutor(context))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processImageProxy(detector: com.google.mlkit.vision.face.FaceDetector, imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            detector.process(image)
                .addOnSuccessListener { faces ->
                    var faceFound = false
                    for (face in faces) {
                        faceFound = true
                        val leftEyeProb = face.leftEyeOpenProbability
                        val rightEyeProb = face.rightEyeOpenProbability

                        _leftEyeProb.value = leftEyeProb
                        _rightEyeProb.value = rightEyeProb

                        if (leftEyeProb != null && rightEyeProb != null) {
                            val isCurrentlyClosed = leftEyeProb < 0.2f && rightEyeProb < 0.2f
                            
                            if (isCurrentlyClosed && !isEyesClosed) {
                                isEyesClosed = true
                                blinkTimestamps.add(System.currentTimeMillis())
                            } else if (!isCurrentlyClosed && isEyesClosed) {
                                // Eyes opened again
                                if (leftEyeProb > 0.5f && rightEyeProb > 0.5f) {
                                    isEyesClosed = false
                                }
                            }
                        }
                    }
                    if (!faceFound) {
                        _leftEyeProb.value = null
                        _rightEyeProb.value = null
                    }
                    
                    val now = System.currentTimeMillis()
                    val thirtySecondsAgo = now - 30000
                    blinkTimestamps.removeAll { it < thirtySecondsAgo }
                    _blinksLast30s.value = blinkTimestamps.size
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }
}

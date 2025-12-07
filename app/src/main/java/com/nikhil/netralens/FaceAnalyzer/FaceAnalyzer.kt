package com.nikhil.netralens.FaceAnalyzer

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

class FaceAnalyzer (
    private val onFaceDetected: (String) -> Unit
) : ImageAnalysis.Analyzer {


    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL) // <--- CRITICAL
        .build()

    private val detector = FaceDetection.getClient(options)


    private var lastSpeakTime = 0L
    private val SPEAK_DELAY = 4000L

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            detector.process(image)
                .addOnSuccessListener { faces ->

                    val now = System.currentTimeMillis()
                    if (faces.isNotEmpty() && (now - lastSpeakTime > SPEAK_DELAY)) {

                        val count = faces.size
                        val firstFace = faces[0]


                        val smileProb = firstFace.smilingProbability ?: 0f
                        val isSmiling = smileProb > 0.5f

                        val mood = if (isSmiling) "smiling" else "neutral"

                        val message = if (count == 1) {
                            "I see one person. They look $mood."
                        } else {
                            "I see $count people. The closest one looks $mood."
                        }

                        // Send result back to UI
                        onFaceDetected(message)
                        lastSpeakTime = now
                    }
                }
                .addOnFailureListener { e ->

                }
                .addOnCompleteListener {

                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }
}
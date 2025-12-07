package com.nikhil.netralens.mlkit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream

class ObjectAnalyzer(
    context: Context,
    // CHANGED: Added 'String' parameter for direction
    private val onObjectsDetected: (List<String>, Rect, String) -> Unit,
    private val onTextDetected: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private var objectDetector: ObjectDetector? = null
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // NEW: We track the width to calculate "Left/Right"
    private var currentImageWidth = 0

    init {
        setupMediaPipe(context)
    }

    private fun setupMediaPipe(context: Context) {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("efficientdet.tflite")
            .build()

        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setScoreThreshold(0.5f)
            .setMaxResults(1)
            .setResultListener { result, _ ->
                val detections = result.detections()
                if (detections.isNotEmpty()) {
                    val firstDetection = detections[0]
                    val label = firstDetection.categories()[0].categoryName()
                    val box = firstDetection.boundingBox()

                    val rect = Rect(
                        box.left.toInt(),
                        box.top.toInt(),
                        box.right.toInt(),
                        box.bottom.toInt()
                    )

                    // --- NEW: Calculate Direction ---
                    val centerX = box.centerX()
                    val direction = if (currentImageWidth > 0) {
                        when {
                            centerX < currentImageWidth * 0.35 -> "Left"
                            centerX > currentImageWidth * 0.65 -> "Right"
                            else -> "Center"
                        }
                    } else {
                        "Center"
                    }

                    // Pass the direction to the callback
                    onObjectsDetected(listOf(label), rect, direction)
                }
            }
            .setErrorListener { e -> e.printStackTrace() }
            .build()

        try {
            objectDetector = ObjectDetector.createFromOptions(context, options)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val bitmap = imageProxy.toBitmap()
            if (bitmap != null && objectDetector != null) {
                val rotatedBitmap = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees.toFloat())

                // NEW: Update width for calculations
                currentImageWidth = rotatedBitmap.width

                val mpImage = BitmapImageBuilder(rotatedBitmap).build()
                objectDetector?.detectAsync(mpImage, SystemClock.uptimeMillis())
            }

            // Text Recognition logic remains the same...
            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    if (visionText.text.isNotBlank()) {
                        onTextDetected(visionText.text)
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    // --- Helpers (Same as before) ---
    private fun ImageProxy.toBitmap(): Bitmap? {
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = YuvImage(nv21, android.graphics.ImageFormat.NV21, this.width, this.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, this.width, this.height), 100, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Float): Bitmap {
        if (rotationDegrees == 0f) return bitmap
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
package com.nikhil.netralens.mlkit

import android.graphics.Rect
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

// --- ML Kit "Reflex" Analyzer Start ---

/**
 * ### Theory: What This Class Does
 *
 * This class is our "Reflex Brain." It implements the `ImageAnalysis.Analyzer`
 * interface, which means it has one job: to execute the `analyze()` function
 * for *every single frame* the camera produces.
 *
 * - **`init { ... }`**: When this class is created, it immediately
 * builds its tools: the ML Kit Object Detector and Text Recognizer.
 *
 * - **Callbacks**: `onObjectsDetected` and `onTextDetected` are like "phone numbers"
 * we pass in, so this analyzer can report its findings back to our ViewModel.
 *
 * - **`analyze(image: ImageProxy)`**: This is the core function.
 * 1. It's called constantly by CameraX (e.g., 30 times/sec).
 * 2. It converts the camera frame into an `InputImage` that ML Kit understands.
 * 3. It sends that image to *both* detectors to be processed.
 * 4. When a detector succeeds, it uses the callback to send the result (a list
 * of object labels or a block of text) back to the caller.
 * 5. **CRITICAL**: It calls `image.close()` to release the frame,
 * so CameraX can send the next one.
 */
class ObjectAnalyzer(private val onObjectsDetected:(List<String>, Rect)->Unit, private val onTextDetected:(String)->Unit):ImageAnalysis.Analyzer{


        //step 1 is to initialize detectors
        val objectDetector: ObjectDetector
        val textRecognizer: TextRecognizer
        init {
            val objectOptions = ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableClassification()
                .build()
            objectDetector = ObjectDetection.getClient(objectOptions)

            //text detector configuration
            textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
        @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)

        override fun analyze(image: ImageProxy)
        {
            val mediaImage=image.image
            if(mediaImage!=null)
            {
                val inputImage= InputImage.fromMediaImage(
                    mediaImage,
                    image.imageInfo.rotationDegrees
                )
                objectDetector.process(inputImage).addOnSuccessListener { detectedObjects->
                    // Get a simple list of object labels (e.g., "Person", "Door")
                    val labels=detectedObjects.mapNotNull { it.labels.firstOrNull()?.text }
                    // Get the location (bounding box) of the *first* object found
                    val bounds=detectedObjects.firstOrNull()?.boundingBox?:Rect()
                    if(labels.isNotEmpty()){
                        // use the callback to report what we found
                        onObjectsDetected(labels, bounds)
                    }
                }.addOnFailureListener {
                    //log errors
                }

                // process for text
                textRecognizer.process(inputImage)
                    .addOnSuccessListener { visiontext->
                        if(visiontext.text.isNotBlank())
                        {
                            onTextDetected(visiontext.text)
                        }
                    }.addOnFailureListener {
                        // log erros
                    }
                    .addOnCompleteListener{
                        // **CRITICAL**: Always close the imageProxy when all
                        // processing is done, so the next frame can be sent.
                        image.close()

            }
            }else
            {
                image.close()
            }
        }


    }



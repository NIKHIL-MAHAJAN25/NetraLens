package com.nikhil.netralens

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeRect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nikhil.netralens.mlkit.ObjectAnalyzer
import java.nio.ByteBuffer
import java.util.concurrent.Executors

// --- Speech-to-Text Logic Start ---
@Composable
fun rememberstt(
    onResult: (String) -> Unit
): ManagedActivityResultLauncher<Intent, ActivityResult> {
    return rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.getOrNull(0)
            if (!spokenText.isNullOrBlank()) {
                onResult(spokenText)
            }
        }
    }
}

fun createSpeechToTextIntent(): Intent {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Listening...")
    }
    return intent
}
// --- Speech-to-Text Logic End ---

// --- Camera Permission Logic Start ---
@Composable
fun rememberCameraPermissionState(): Boolean {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            hasPermission = isGranted
        }
    )

    LaunchedEffect(key1 = true) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    return hasPermission
}
// --- Camera Permission Logic End ---

// --- Camera Preview Composable Start ---
@Composable
fun CameraPreview(
    // NEW: We added this parameter to turn ML Kit on/off
    enableMlKit: Boolean,
    analyzer: ImageAnalysis.Analyzer,
    imageCapture: ImageCapture,
    modifier: Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Remember the ImageAnalysis use case
    val imageAnalysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }

    AndroidView(
        factory = {
            PreviewView(it)
        },
        update = { previewView ->
            // --- NEW: Toggle the analyzer ---
            if (enableMlKit) {
                // Turn ON
                imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor(), analyzer)
            } else {
                // Turn OFF to save battery
                imageAnalysis.clearAnalyzer()
            }

            val cameraproviderfuture = ProcessCameraProvider.getInstance(context)
            cameraproviderfuture.addListener({
                val cameraprov = cameraproviderfuture.get()

                val preview = androidx.camera.core.Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                try {
                    cameraprov.unbindAll()
                    cameraprov.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis,
                        imageCapture
                    )
                } catch (exc: Exception) {
                    Log.e("CameraPreview", "Use case binding failed", exc)
                }
            }, ContextCompat.getMainExecutor(context))
        },
        modifier = modifier
    )
}
// --- Camera Preview Composable End ---


// --- Main Screen Composable Start ---
@Composable
fun BakingScreen(
    bakingViewModel: BakingViewModel = viewModel(
        factory = BakingViewModelFactory(
            LocalContext.current.applicationContext as Application
        )
    )
) {
    val has = rememberCameraPermissionState()
    val uiState by bakingViewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Pre-warm the TTS engine
    LaunchedEffect(key1 = true) {
        bakingViewModel.onIdle()
    }

    var spokenText by remember { mutableStateOf("Tap anywhere to speak.") }

    val speechLauncher = rememberstt { text ->
        spokenText = text
        bakingViewModel.processUserRequest(text)
    }

    val analyzer = remember {
        ObjectAnalyzer(
           context = context, // Pass context for MediaPipe (if you use it later)
            onObjectsDetected = { labels, bounds ->
                bakingViewModel.onMlKitObjectsDetected(labels, bounds)
                Log.d("ObjectAnalyzer", "Found objects: $labels")
            },
            onTextDetected = { text ->
                bakingViewModel.onMlKitTextDetected(text)
                Log.d("ObjectAnalyzer", "Found text: $text")
            }
        )
    }

    val imageCapture = remember {
        ImageCapture.Builder().build()
    }

    // Expert Brain Trigger
    LaunchedEffect(uiState) {
        if ((uiState as? UiState.Success)?.outputText == "CAPTURE_PHOTO") {
            // Updated function call (capital P)
            takePhoto(context, imageCapture) { bitmap ->
                bakingViewModel.sendGeminiPrompt(bitmap)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable {
                if (uiState !is UiState.Processing) {
                    bakingViewModel.onIdle()
                    speechLauncher.launch(createSpeechToTextIntent())
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (has) {
            CameraPreview(
                // NEW: Only turn on ML Kit when we are looking for something!
                // This saves massive battery.
                enableMlKit = (uiState is UiState.Processing),
                analyzer = analyzer,
                imageCapture = imageCapture,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text("Camera permission is required.", color = Color.White)
        }

        // UI Overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            when (val state = uiState) {
                is UiState.Idle -> {
                    Text(
                        "Tap anywhere to speak.",
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
                is UiState.Listening -> {
                    Text("Listening...", color = Color.White)
                }
                is UiState.Processing -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            state.message,
                            color = Color.White,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                is UiState.Error -> {
                    Text(state.errorMessage, color = MaterialTheme.colorScheme.error)
                }
                is UiState.Success -> {
                    if (state.outputText != "CAPTURE_PHOTO") {
                        Text(
                            text = state.outputText,
                            color = Color.White,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(8.dp)
                                .drawBoundingBox(state.bounds)
                        )
                    }
                }
            }
        }
    }
}

// --- Helper Functions ---

private fun takePhoto(
    context: Context,
    imageCapture: ImageCapture,
    onImageCaptured: (Bitmap) -> Unit
) {
    val executor = Executors.newSingleThreadExecutor()

    imageCapture.takePicture(
        executor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = imageProxyToBitmap(image)
                image.close()
                val rotatedBitmap = rotateBitmap(bitmap, image.imageInfo.rotationDegrees.toFloat())

                // --- CRITICAL FIX: Scale the bitmap before sending! ---
                val scaledBitmap = scaleBitmap(rotatedBitmap, 768)
                // ----------------------------------------------------

                ContextCompat.getMainExecutor(context).execute {
                    onImageCaptured(scaledBitmap) // Send the small, fast bitmap
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e("TakePhoto", "Image capture failed", exception)
            }
        }
    )
}

private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
    val buffer: ByteBuffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Float): Bitmap {
    if (rotationDegrees == 0f) return bitmap
    val matrix = Matrix()
    matrix.postRotate(rotationDegrees)
    return Bitmap.createBitmap(
        bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
    )
}

/**
 * ### Theory: Shrinks a Bitmap
 * This reduces upload time significantly.
 */
private fun scaleBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
    val originalWidth = bitmap.width
    val originalHeight = bitmap.height
    var scaledWidth: Int
    var scaledHeight: Int

    if (originalWidth > originalHeight) {
        scaledWidth = maxDimension
        scaledHeight = (scaledWidth * (originalHeight.toFloat() / originalWidth.toFloat())).toInt()
    } else {
        scaledHeight = maxDimension
        scaledWidth = (scaledHeight * (originalWidth.toFloat() / originalHeight.toFloat())).toInt()
    }

    return Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
}

fun Modifier.drawBoundingBox(box: Rect) = this.then(
    if (box.isEmpty) {
        Modifier
    } else {
        Modifier
            .border(BorderStroke(2.dp, Color.Red))
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val composeRect = box.toComposeRect()
                    placeable.place(
                        x = composeRect.left.toInt(),
                        y = composeRect.top.toInt()
                    )
                }
            }
    }
)

@Preview(showSystemUi = true)
@Composable
fun BakingScreenPreview() {
    BakingScreen()
}
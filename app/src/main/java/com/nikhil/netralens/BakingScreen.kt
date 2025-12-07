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
import android.os.Build
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nikhil.netralens.FaceAnalyzer.FaceAnalyzer
import com.nikhil.netralens.fall.FallDetector
import com.nikhil.netralens.fall.LightDetector
import com.nikhil.netralens.mlkit.ObjectAnalyzer
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi

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

    // The list of all permissions we need
    val permissions = listOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.SEND_SMS
    )

    // Check if we already have them
    var hasPermission by remember {
        mutableStateOf(
            permissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    // The launcher to ask for them
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // result is a Map<String, Boolean>. Check if all values are true.
        hasPermission = result.values.all { it }
    }

    // Launch request on start
    LaunchedEffect(true) {
        if (!hasPermission) {
            launcher.launch(permissions.toTypedArray())
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
@RequiresApi(Build.VERSION_CODES.O)
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
    // --- NEW: Fall Detector Logic ---
    val fallDetector = remember {
        FallDetector(context) {
            bakingViewModel.onFallDetected()
        }
    }
    // 1. Initialize the Light Detector
    val lightDetector = remember { LightDetector(context) }

    // 2. Listen to the ViewModel switch
    // Note: 'isLightModeOn' will be red if you haven't updated BakingViewModel yet!
    LaunchedEffect(bakingViewModel.isLightModeOn) {
        if (bakingViewModel.isLightModeOn) {
            lightDetector.start()
        } else {
            lightDetector.stop()
        }
    }

    // 3. Safety cleanup (Stop beeping if screen closes)
    DisposableEffect(Unit) {
        onDispose { lightDetector.stop() }
    }
    // Start sensor when screen opens, stop when closes
    DisposableEffect(Unit) {
        fallDetector.start()
        onDispose { fallDetector.stop() }
    }
    // -------------------------------
    var spokenText by remember { mutableStateOf("Tap anywhere to speak.") }

    val speechLauncher = rememberstt { text ->
        spokenText = text
        bakingViewModel.processUserRequest(text)
    }
    // 1. Setup Vibrator (Get the phone's motor)
    val vibrator = remember {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vibManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }
    // Timer to prevent "Vibration Spam" (only vibe every 400ms)
    var lastVibrateTime by remember { mutableStateOf(0L) }
    var debugLog by remember { mutableStateOf("Debug: Waiting...") }
    val analyzer = remember {
        ObjectAnalyzer(
            context = context,
            onObjectsDetected = { labels, bounds, direction ->
                bakingViewModel.onMlKitObjectsDetected(labels, bounds, direction)

                // --- DEBUG LOGIC START ---
                // Show us EVERYTHING the camera sees
                val allLabels = labels.joinToString(", ")
                debugLog = "Saw: $allLabels \nDir: $direction"
                // -------------------------

                if (bakingViewModel.mlKitTargetObject != null) {
                    // Check match
                    val isTarget = labels.any {
                        it.contains(bakingViewModel.mlKitTargetObject!!, ignoreCase = true)
                    }

                    // Update debug to show if we matched
                    if (isTarget) debugLog += "\nMATCHED! VIBRATING..."

                    if (isTarget) {
                        val now = System.currentTimeMillis()
                        if (now - lastVibrateTime > 400) {
                            lastVibrateTime = now

                            // Force Vibrate Logic (Old School)
                            when (direction) {
                                "Left" -> {
                                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                                        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 50, 50, 50), -1))
                                    } else {
                                        vibrator.vibrate(longArrayOf(0, 50, 50, 50), -1)
                                    }
                                }
                                "Right" -> {
                                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                                        vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
                                    } else {
                                        vibrator.vibrate(300)
                                    }
                                }
                                "Center" -> {
                                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                                        vibrator.vibrate(VibrationEffect.createOneShot(50, 255))
                                    } else {
                                        vibrator.vibrate(50)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    debugLog += "\n(No Target Set - Say 'Find X')"
                }
            },
            onTextDetected = { text ->
                bakingViewModel.onMlKitTextDetected(text)
            }
        )
    }
    val faceAnalyzer = remember {
        FaceAnalyzer { message ->
            bakingViewModel.onFaceDetected(message)
        }
    }

    // 3. Logic: Pick the correct analyzer
    val activeAnalyzer = if (bakingViewModel.isFaceModeOn) {
        faceAnalyzer
    } else {
        analyzer
    }

    val imageCapture = remember {
        ImageCapture.Builder().build()
    }

    // Expert Brain Trigger
    LaunchedEffect(uiState) {
        if (uiState is UiState.FallDetected) {
            kotlinx.coroutines.delay(3000) // Wait for TTS warning
            speechLauncher.launch(createSpeechToTextIntent())
        }
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
                if (uiState is UiState.FallDetected) {
                    bakingViewModel.onIdle()
                    bakingViewModel.ttsManager.speak("Cancelled")
                }
                else if (uiState is UiState.Processing) {
                    bakingViewModel.cancelSearch() // <--- Calls the new function
                }
                else if (uiState is UiState.Success) {
                    bakingViewModel.ttsManager.stop()
                    bakingViewModel.onIdle()
                }

                // Priority 4: Normal case - Start Listening
                else {
                    speechLauncher.launch(createSpeechToTextIntent())
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (has) {
            CameraPreview(
                // NEW: Only turn on ML Kit when we are looking for something!
                // This saves massive battery.
                enableMlKit = (uiState is UiState.Processing) || bakingViewModel.isFaceModeOn,
                analyzer = activeAnalyzer,
                imageCapture = imageCapture,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text("Camera permission is required.", color = Color.White)
        }
        if (uiState is UiState.FallDetected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Red.copy(alpha = 0.8f))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("FALL DETECTED!", style = MaterialTheme.typography.displayLarge, color = Color.White)
                    Text("Sending SMS in 10s...\nTap or say 'Stop' to cancel", color = Color.White, textAlign = TextAlign.Center)
                }
            }
        }
        // -----------------------------

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
                // --- NEW BRANCH ADDED HERE ---
                is UiState.FallDetected -> {
                    // We handle the main Red Alert screen separately in the code above,
                    // so for this specific overlay box, we can just show a small text
                    // or nothing at all since the big red box covers everything.
                    Text("SOS Mode Active", color = Color.Red)
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

@RequiresApi(Build.VERSION_CODES.O)
@Preview(showSystemUi = true)
@Composable
fun BakingScreenPreview() {
    BakingScreen()
}
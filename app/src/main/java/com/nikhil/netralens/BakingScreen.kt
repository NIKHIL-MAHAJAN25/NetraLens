package com.nikhil.netralens

import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.speech.RecognizerIntent
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeRect
import androidx.compose.ui.layout.layout
import com.nikhil.netralens.mlkit.ObjectAnalyzer
import java.nio.ByteBuffer
import java.util.concurrent.Executors


// --- Speech-to-Text Logic Start ---
/**
 * ### Theory: Manages Camera Permission
 * This composable function does two things:
 * 1. Creates a state variable `hasPermission` that remembers if the user has granted permission.
 * 2. Creates a `permissionLauncher` that knows how to ask for the camera permission.
 * 3. Uses a `LaunchedEffect` to automatically ask for permission when this composable first
 * appears, if we don't already have it.
 * It returns the current permission status.
 */
@Composable
fun rememberstt(
    onResult: (String) -> Unit //callback function
): ManagedActivityResultLauncher<Intent, ActivityResult>{
    //generic so remember
//    | Contract                   | What it Does                                                        |
//    | -------------------------- | ------------------------------------------------------------------- |
//    | `RequestPermission()`      | Asks user for permission                                            |
//    | `TakePicture()`            | Opens camera to take a photo                                        |
//    | `PickContact()`            | Opens contacts picker                                               |
//    | `OpenDocument()`           | Opens file picker                                                   |
//    | `StartActivityForResult()` | Generic — for launching any custom intent (like speech recognition) |

    return rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    )
    //
    {result-> //handling result
        if(result.resultCode== Activity.RESULT_OK) //checking codes
        {
            val spokenText=result.data // getting data from spokentext
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.getOrNull(0)
            if(!spokenText.isNullOrBlank())
            {
                onResult(spokenText)// return the spoken text
            }
        }
    }
}
/**
 * ### Theory: Creates the "Message" for STT
 * This is a simple helper function that builds the `Intent` (the
 * "message") that we send to the Android system to request
 * speech recognition. It tells the system what to show in the pop-up.
 */
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

    // This variable "remembers" the permission status
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    // This is the pop-up launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            hasPermission = isGranted // Update our state when the user decides
        }
    )

    // This runs once when the screen appears
    LaunchedEffect(key1 = true) {
        if (!hasPermission) {
            // If we don't have permission, launch the pop-up
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    return hasPermission
}
// --- Camera Permission Logic End ---

// --- Camera Preview Composable Start ---

/**
 * ### Theory: Displays the Live Camera Feed
 * This composable function is an adapter. It wraps the "old" Android View
 * component `PreviewView` (which shows the camera feed) so it can be
 * used inside our "new" Jetpack Compose UI.
 *
 * 1. `AndroidView(factory = ...)`: This creates the `PreviewView` one time.
 * 2. `AndroidView(update = ...)`: This block runs when the view is ready.
 * This is where we get the `ProcessCameraProvider` (the main camera controller)
 * and "bind" our camera session to the screen's lifecycle.
 */
//Think of it as a recipe with these steps:
//
//Get the oven (the cameraProvider).
//
//Prepare the cupcake (the preview use case).
//
//Put the cupcake on a tray (the preview.setSurfaceProvider).
//
//Set the oven temperature (the cameraSelector).
//
//Put the tray in the oven and turn it on (the bindToLifecycle).
//The Problem: CameraX's PreviewView (the component that shows the camera feed) is an "old" Android View, not a "new" Jetpack Compose function.
//
//The Solution: We use the AndroidView composable. This is an "adapter" that lets us put an old View inside our new Compose UI.
//
//factory block: This runs one time to create the PreviewView.
//
//update block: This block runs when the composable is added to the screen. This is where we "turn on" the camera.
//
//ProcessCameraProvider: This is the main controller for all of CameraX. We get an instance of it.
//
//Preview: This is a CameraX "use case." It's an object that knows how to generate a preview stream. We tell it to send its stream to our previewView.surfaceProvider.
//
//cameraProvider.bindToLifecycle: This is the magic. It binds the camera (using the cameraSelector),
//the preview use case, and the screen's lifecycleOwner all together.
//This automatically handles starting the camera when your app is on-screen and stopping it
//when your app is in the background, preventing crashes and saving battery.
@Composable
fun CameraPreview(
    analyzer: ImageAnalysis.Analyzer,
    imageCapture:ImageCapture,
    modifier: Modifier
){
    val context= LocalContext.current
    val lifecycleOwner= LocalLifecycleOwner.current
    //adapter starts
    AndroidView(
        factory={
            // old view wrapping
            PreviewView(it)
        },
        update={previewView->
            val cameraproviderfuture=ProcessCameraProvider.getInstance(context)
            cameraproviderfuture.addListener({
                val cameraprov=cameraproviderfuture.get()
                //set up preview
                val preview= androidx.camera.core.Preview.Builder().build().also{
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val imageAnalysis=ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        // "Hire" our analyzer and tell it to run on a background thread
                        it.setAnalyzer(Executors.newSingleThreadExecutor(), analyzer)

                    }
                    // Select the back camera
                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    try {
                        // Unbind everything first
                        cameraprov.unbindAll()

                        // Bind the camera, preview, and lifecycle together
                        cameraprov.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis,
                            imageCapture
                            // We'll add more use cases here later
                        )
                    } catch (exc: Exception) {
                        Log.e("CameraPreview", "Use case binding failed", exc)
                    }
                }, ContextCompat.getMainExecutor(context))
                },
                modifier = modifier

    )

}
//Action 3: Update your main BakingScreen to use these new helpers.
//
//Theory: Now we can clean up your main composable. We will:
//
//Remove all the old code (the LazyRow, the TextField, Button, etc.).
//
//Call our rememberCameraPermissionState() helper to check for permission.
//
//Use a simple Box (which lets us stack things).
//
//If we have permission, show the CameraPreview().
//
//If we don't, just show a text message.

//Now we'll put our new "ears" helper to work.
//
//We'll create a new remember state variable called spokenText to hold the result from the STT.
//
//We'll call rememberSpeechToTextLauncher and, in its onResult callback, we'll set our new spokenText variable.
//
//We'll add the .clickable modifier to our main Box. This makes the entire screen tappable.
//
//Inside the clickable block, we'll call speechLauncher.launch() to show the pop-up.
//
//Finally, we'll add a Text composable on top of the camera feed to display the spokenText, just so we can prove it's working.
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
    // -- NEW CODE FOR STEP 3 --
    // A. Create a state variable to "remember" the spoken text
    var spokenText by remember { mutableStateOf("Tap anywhere to speak.") }
    // B. Create the launcher. When it gets a result,
    //    it will update our `spokenText` variable.

    val speechLauncher = rememberstt { text ->
        spokenText = text
        bakingViewModel.processUserRequest(text)
        // LATER: We will send this 'text' to the ViewModel
    }
    val analyzer=remember{
        ObjectAnalyzer(
            onObjectsDetected = {labels, bounds->
                // This is our test callback.
                // We'll just log the results for now.
                bakingViewModel.onMlKitObjectsDetected(labels, bounds)
                Log.d("ObjectAnalyzer", "Found objects: $labels")
            },
            onTextDetected = {text->
                // This is our test callback.
                bakingViewModel.onMlKitTextDetected(text)
                Log.d("ObjectAnalyzer", "Found text: $text")
            }
        )
    }
    val imageCapture=remember{
        ImageCapture.Builder().build()
    }
    // -- END OF NEW CODE --
// --- "Expert Brain" Trigger ---
    // This "listens" to the uiState.
    LaunchedEffect(uiState) {
        // When the ViewModel gives the "CAPTURE_PHOTO" command...
        if ((uiState as? UiState.Success)?.outputText == "CAPTURE_PHOTO") {
            // ...we run our takePhoto function.
            takePhoto(context, imageCapture) { bitmap ->
                // When the photo is ready, we send it to Gemini.
                bakingViewModel.sendGeminiPrompt(bitmap)
            }
        }
    }
//preview starts
    Box(
        modifier = Modifier.fillMaxSize()
            .background(Color.Black)
            .clickable {
                if (uiState !is UiState.Processing) {
                    // ...and we're not busy, reset to Idle and launch the "Ears".
                    bakingViewModel.onIdle()
                    speechLauncher.launch(createSpeechToTextIntent())
                }
            }
            ,contentAlignment = Alignment.Center
    ) {
        if (has) {
            CameraPreview(
                analyzer=analyzer,
                imageCapture=imageCapture,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text("Camera permission is required.", color = Color.White)
        }
        // --- UI Overlay ---
        // This is the "smart" UI that reacts to the ViewModel's state
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            // This 'when' block is the "dimmer light". It changes
            // based on the "dimmer switch" (uiState)
            when (val state = uiState) {
                is UiState.Idle -> {
                    Text(
                        "Tap anywhere to speak.",
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
                is UiState.Listening -> {
                    // This state is set by the speechLauncher
                    Text("Listening...", color = Color.White)
                }
                is UiState.Processing -> {
                    // Show a loading spinner and message
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
                    // Show the error message in red
                    Text(state.errorMessage, color = MaterialTheme.colorScheme.error)
                }
                is UiState.Success -> {
                    // Don't show the "CAPTURE_PHOTO" command to the user
                    if (state.outputText != "CAPTURE_PHOTO") {
                        Text(
                            text = state.outputText,
                            color = Color.White,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(8.dp)
                                // "Wire" the bounding box to our helper
                                .drawBoundingBox(state.bounds)
                        )
                    }
                }
            }
        }
    }
}
// --- "Expert Brain" (Gemini) Photo Taker Start ---

/**
 * ### Theory: Takes a single high-quality photo
 * This function tells the `ImageCapture` tool to take a picture.
 * It runs on a background thread (`executor`).
 * When the picture is ready, `onCaptureSuccess` is called.
 * We then convert the camera's `ImageProxy` object into a
 * standard `Bitmap` that our AI can understand.
 */
private fun takePhoto(context: Context, imageCapture:ImageCapture, onImageCaptured:(Bitmap) -> Unit
){
    val executor = Executors.newSingleThreadExecutor()

    // This is an "in-memory" capture. We get the bitmap directly.
    imageCapture.takePicture(
        executor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                // The camera gives us an "ImageProxy". We need to convert it.
                val bitmap = imageProxyToBitmap(image)
                image.close()
                val rotatedBitmap = rotateBitmap(bitmap, image.imageInfo.rotationDegrees.toFloat())

                // Switch back to the main thread to send the result
                ContextCompat.getMainExecutor(context).execute {
                    onImageCaptured(rotatedBitmap) // <-- This sends the BIG image
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e("TakePhoto", "Image capture failed", exception)
            }
        }
    )
}

/**
 * ### Theory: Helper to convert an ImageProxy to a Bitmap.
 * This is a standard "boilerplate" function. Its only job is to
 * take the raw data from the camera's buffer and turn it into
 * a standard `Bitmap` (a normal image file).
 */
private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
    val buffer: ByteBuffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

/**
 * ### Theory: Helper to rotate the bitmap.
 * A phone's camera sensor is often sideways. This function
 * reads the rotation data from the image and spins the
 * `Bitmap` so it's upright, just as the user sees it.
 */
private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Float): Bitmap {
    if (rotationDegrees == 0f) return bitmap // No rotation needed
    val matrix = Matrix()
    matrix.postRotate(rotationDegrees)
    return Bitmap.createBitmap(
        bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
    )
}

// --- "Expert Brain" (Gemini) Photo Taker End ---
/// --- Helper Modifier Start ---

/**
 * ### Theory: A custom "decorator" (Modifier)
 * This function is a helper that draws a red box on the screen.
 * It takes the `Rect` (rectangle) from our ML Kit result and
 * applies a border to our Text composable, positioned
 * exactly where the object was found.
 *
 * You don't need to memorize this. It's standard boilerplate
 * for drawing ML Kit results in Compose.
 */
fun Modifier.drawBoundingBox(box: Rect) = this.then(
    if (box.isEmpty) {
        Modifier // If no box, do nothing
    } else {
        // If there is a box, draw a red border at its coordinates
        Modifier
            .border(
                BorderStroke(2.dp, Color.Red)
            )
            // This layout logic positions the box correctly
            // based on the coordinates from the ML Kit analyzer
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
/**
 * ### Theory: Shrinks a Bitmap
 * This is our new, crucial optimization. It takes a large Bitmap
 * and scales it down, maintaining its aspect ratio.
 * A smaller Bitmap means a *much* faster upload.
 *
 * @param bitmap The original, large image.
 * @param maxDimension The largest side (width or height) you want the
 * final image to have. 768 is a great, fast size for Gemini.
 * @return A new, smaller Bitmap.
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

// ... (your drawBoundingBox function) ..
// --- Helper Modifier End ---
@Preview(showSystemUi = true)
@Composable
fun BakingScreenPreview() {
    BakingScreen()
}
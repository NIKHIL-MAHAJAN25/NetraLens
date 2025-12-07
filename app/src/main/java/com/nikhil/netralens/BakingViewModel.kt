package com.nikhil.netralens

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.nikhil.netralens.LocationHelper.LocationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BakingViewModel(application: Application) : AndroidViewModel(application) {
    val ttsManager = TTSmanager(application)
    // Light Mode State
    var isFaceModeOn by mutableStateOf(false)
        private set
    var isLightModeOn by mutableStateOf(false)
        private set
    private val locationHelper = LocationHelper(application.applicationContext)
    private val _uiState: MutableStateFlow<UiState> =
        MutableStateFlow(UiState.Idle)
    val uiState: StateFlow<UiState> =
        _uiState.asStateFlow()
    // --- State for the "Reflex" (ML Kit) Brain ---
    // This holds the simple string we are looking for, e.g., "door"
    var mlKitTargetObject by mutableStateOf<String?>(null)
    var mlKitTargetText by mutableStateOf(false)
    private var lastGeminiPrompt: String = "Describe the scene for me."
    private val generativeModel = GenerativeModel(
        modelName = "gemini-2.5-flash",
        apiKey = BuildConfig.apiKey
    )
// --- ViewModel Logic Functions Start ---

    /**
     * ### Theory: This is the "Conductor"
     * This is the main entry point from the UI. It receives the
     * spoken text and decides which "brain" to use.
     */
    fun processUserRequest(spokenText: String) {
        val lowerText = spokenText.lowercase()
        // --- NEW: Face Mode Command ---
        // Triggers: "Who is this", "Find people", "Look for person"
        if (lowerText.contains("who") || lowerText.contains("people") || lowerText.contains("person")) {
            isFaceModeOn = true

            // IMPORTANT: Turn off other modes to prevent talking over each other
            // isLightModeOn = false (Uncomment if you have light mode)

            ttsManager.speak("Looking for people...")
            return
        }
        if (lowerText.contains("stop") && isFaceModeOn) {
            isFaceModeOn = false
            ttsManager.speak("Stopped looking.")
            return
        }
        // --- NEW: Light Mode Command ---
        if (lowerText.contains("light") && (lowerText.contains("mode") || lowerText.contains("sensor"))) {
            if (lowerText.contains("on") || lowerText.contains("start")) {
                isLightModeOn = true
                ttsManager.speak("Light guidance on.")
            } else if (lowerText.contains("off") || lowerText.contains("stop")) {
                isLightModeOn = false
                ttsManager.speak("Light guidance off.")
            }
            return
        }
        _uiState.value = UiState.Processing("Thinking...")
        if (_uiState.value is UiState.FallDetected) {
            if (spokenText.contains("stop", ignoreCase = true) ||
                spokenText.contains("ok", ignoreCase = true) ||
                spokenText.contains("cancel", ignoreCase = true)) {

                _uiState.value = UiState.Idle
                ttsManager.speak("SOS Cancelled.")
                return
            }
        }
        // The "Wake Word" for the "Expert" brain
        if (spokenText.startsWith("Gemini", ignoreCase = true) ||
            spokenText.startsWith("describe", ignoreCase = true)
        ) {
            // This is a complex, paid task.
            mlKitTargetObject = null // Stop ML Kit tasks
            mlKitTargetText = false

            // --- NEW: Save the user's actual question ---
            // We strip the wake word to get the real prompt
            var prompt = spokenText.removePrefix("gemini").trim()
            if (prompt.isBlank()) {
                prompt = spokenText.removePrefix("describe").trim()
            }
            if (prompt.isBlank()) {
                prompt = "Describe the scene for me." // A safe fallback
            }
            lastGeminiPrompt = prompt // Remember this question
            _uiState.value = UiState.Success("CAPTURE_PHOTO", Rect())

        } else if (spokenText.startsWith("find", ignoreCase = true)) {
            // This is a free, "reflex" task.
            val target = spokenText.removePrefix("find").trim()
            mlKitTargetObject = target // Give the "reflex brain" its order
            mlKitTargetText = false
            val processingMessage = "Looking for $target..."
            _uiState.value = UiState.Processing(processingMessage)
            ttsManager.speak(processingMessage) // --- ADDED THIS LINE ---

        } else if (spokenText.startsWith("read", ignoreCase = true)) {
            // This is a free, "reflex" task.
            mlKitTargetObject = null
            mlKitTargetText = true // Give the "reflex brain" its order
            val processingMessage = "Looking for text..."
            _uiState.value = UiState.Processing(processingMessage)
            ttsManager.speak(processingMessage) // --- ADDED THIS LINE ---
        } else {
            val errorMessage = "Sorry, I didn't understand. Try 'find [object]', 'read text', or 'Gemini, describe'."
            _uiState.value = UiState.Error(errorMessage)
            ttsManager.speak(errorMessage) // --- ADDED THIS LINE ---
        }
    }

    /**
     * ### Theory: The "Expert" Brain Task
     * This function is called by the UI *after* a photo has been
     * successfully captured. It runs the expensive, cloud-based
     * Gemini API call in a background thread.
     */
    fun sendGeminiPrompt(bitmap: Bitmap) {
        val processingMessage = "Analyzing with AI..."
        _uiState.value = UiState.Processing(processingMessage)
        ttsManager.speak(processingMessage)

        // --- THIS IS THE CRITICAL FIX ---
        // We create a "persona" for Gemini and combine it
        // with the user's question.
        val systemInstruction = """
            You are an assistant for a visually impaired person.
            Your goal is to provide clear, concise, and safe instructions.
            Be direct and use simple language.
            gemini is a wakeup call for the tts engine so dont add it in prompt if its the first word of the prompt.
            prioritize navigating safely always tell and describe obstructions and objects nearby if any.
            if any object is directly in front then warn the user
            Give precise directions and approximate number of steps needed to reach the object, destination or whatever the user asked for
            Do not use descriptive or flowery language. 
            Focus on navigation, obstacles, and safety.
            
            Based on the image, answer this user's question: "$lastGeminiPrompt"
        """.trimIndent()
        // --- END OF FIX ---

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = generativeModel.generateContent(
                    content {
                        image(bitmap)
                        text(systemInstruction) // Use the new, combined prompt
                    }
                )
                response.text?.let { outputContent ->
                    _uiState.value = UiState.Success(outputContent)
                    ttsManager.speak(outputContent)
                }
            } catch (e: Exception) {
                val errorMessage = "API Error: ${e.localizedMessage}"
                _uiState.value = UiState.Error(errorMessage)
                ttsManager.speak(errorMessage)
            }
        }
    }

    /**
     * ### Theory: The "Reflex" Brain Callback (for Objects)
     * This function is called by our `ObjectAnalyzer` *constantly*
     * (e.g., 30 times per second). It's very fast.
     * It checks if the objects it found match the `mlKitTargetObject`
     * (our "order").
     */
    fun onMlKitObjectsDetected(labels: List<String>, bounds: Rect,direction: String) {
        // Only act if we are actively looking for an object
        if (mlKitTargetObject == null) return

        // Simple matching logic
        val foundObject = labels.firstOrNull { label ->
            label.contains(mlKitTargetObject!!, ignoreCase = true)
        }

        if (foundObject != null) {
            // NEW: Include direction in the speech
            val successMessage = "Found $foundObject $direction."

            _uiState.value = UiState.Success(successMessage, bounds)
            ttsManager.speak(successMessage) // e.g. "Found Laptop to your right."

        }
    }

    /**
     * ### Theory: The "Reflex" Brain Callback (for Text)
     * This function is called by our `ObjectAnalyzer` *constantly*.
     * It checks if we are in "read text" mode.
     */
    fun onMlKitTextDetected(text: String) {
        // Only act if we are actively looking for text
        if (!mlKitTargetText) return

        val successMessage = "Found text: $text"
        _uiState.value = UiState.Success(successMessage)
        ttsManager.speak(successMessage) // C. Speak the final ML Kit result!
        mlKitTargetText = false
    }

    /**
     * ### Theory: Resets the "Conductor"
     * A simple function to reset the UI back to its "waiting" state.
     */
    fun onIdle() {
        mlKitTargetObject = null
        mlKitTargetText = false
        _uiState.value = UiState.Idle
    }
    /**
     * ### Theory: The "Cleanup" Crew
     * This function is called automatically by Android when the
     * ViewModel is destroyed. This is the PERFECT place to
     * call our ttsManager.shutdown() to prevent memory leaks.
     */
    override fun onCleared() {
        super.onCleared()
        ttsManager.shutdown() // D. Clean up the "Mouth"
    }
    // ... inside BakingViewModel class ...

    // Emergency Contact (Hardcode for demo, or use a settings input later)
    private val EMERGENCY_PHONE = "7529073222" // REPLACE WITH A REAL NUMBER FOR TESTING

    fun onFallDetected() {
        if (_uiState.value !is UiState.FallDetected) {
            _uiState.value = UiState.FallDetected
            ttsManager.speak("Fall detected. Sending SOS in 10 seconds. Say Stop to cancel.")

            // Start a 10-second countdown
            viewModelScope.launch {
                delay(10000) // 10 seconds
                // Check if user is STILL in FallDetected state (didn't cancel)
                if (_uiState.value is UiState.FallDetected) {
                    sendSOS()
                }
            }
        }
    }

    private fun sendSOS() {
        // Step A: Get Location first
        locationHelper.getCurrentLocation { locationLink ->

            // Step B: Send SMS once we have the link
            try {
                val smsManager = android.telephony.SmsManager.getDefault()
                val message = "SOS! Fall detected. Help me here: $locationLink"

                // Replace with your real phone number for testing
                smsManager.sendTextMessage(EMERGENCY_PHONE, null, message, null, null)

                _uiState.value = UiState.Success("SOS Sent", android.graphics.Rect())
                ttsManager.speak("SOS sent with location.")
            } catch (e: Exception) {
                _uiState.value = UiState.Error("SMS Failed")
                ttsManager.speak("Could not send S O S.")
            }
        }
    }
    fun cancelSearch() {
        // 1. Stop any current TTS (like "Looking for laptop...")
        ttsManager.stop()

        // 2. Force the UI back to "Idle" (Tap to speak)
        // This breaks the "Processing/Searching" lock.
        _uiState.value = UiState.Idle

        // Optional: If you have a variable tracking the specific object (e.g. var targetObject: String?),
        // set it to null here.
        // targetObject = null
    }
    fun onFaceDetected(message: String) {
        // Double-check the mode is ON before speaking
        if (isFaceModeOn) {
            ttsManager.speak(message)
        }
    }
// --- ViewModel Logic Functions End ---
}



/**
 * ### Theory: The "ViewModel Factory"
 * Because our ViewModel now needs an `Application` in its
 * constructor, we can't just call `viewModel()` anymore.
 * We need this small "factory" class to tell Compose
 * *how* to build our ViewModel.
 */
class BakingViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BakingViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return BakingViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
// --- ViewModel "Conductor" Logic End ---
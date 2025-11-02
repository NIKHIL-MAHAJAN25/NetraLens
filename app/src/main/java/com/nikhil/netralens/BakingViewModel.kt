package com.nikhil.netralens

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BakingViewModel(application: Application) : AndroidViewModel(application) {
    private val ttsManager = TTSmanager(application)
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
        _uiState.value = UiState.Processing("Thinking...")

        // The "Wake Word" for the "Expert" brain
        if (spokenText.startsWith("Netra", ignoreCase = true) ||
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
    fun onMlKitObjectsDetected(labels: List<String>, bounds: Rect) {
        // Only act if we are actively looking for an object
        if (mlKitTargetObject == null) return

        val foundObject = labels.firstOrNull { label ->
            label.equals(mlKitTargetObject, ignoreCase = true)
        }

        if (foundObject != null) {
            val successMessage = "Found $foundObject!"
            _uiState.value = UiState.Success(successMessage, bounds)
            ttsManager.speak(successMessage) // C. Speak the final ML Kit result!
            mlKitTargetObject = null
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
package com.nikhil.netralens

import android.graphics.Rect

// --- UI State Definition Start ---

/**
 * ### Theory: What This Is
 *
 * This is a "sealed interface" which defines all possible states
 * our UI can be in. It's like a set of traffic light colors.
 * The UI's only job is to look at this state and show the correct thing.
 *
 * - **Idle**: The app is waiting for a command.
 * - **Listening**: The STT pop-up is active (we'll add this state).
 * - **Processing**: A brain is working (ML Kit or Gemini).
 * - **Success**: We have a result to show. We include a `Rect` (rectangle)
 * so the ML Kit brain can tell the UI *where* it found the object.
 * - **Error**: Something went wrong.
 */
sealed interface UiState {
    object Idle : UiState
    object Listening : UiState
    object FallDetected : UiState
    data class Processing(val message: String) : UiState
    data class Success(val outputText: String, val bounds: Rect = Rect()) : UiState
    data class Error(val errorMessage: String) : UiState
}

// --- UI State Definition End ---
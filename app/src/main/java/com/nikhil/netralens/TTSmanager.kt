package com.nikhil.netralens

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

// --- "Mouth" (Text-to-Speech) Manager Start ---

/**
 * ### Theory: Manages the Text-to-Speech Engine
 * This class is a "helper" that handles the complex lifecycle
 * of the Android Text-to-Speech (TTS) engine.
 *
 * 1. `init`: When this class is created, it immediately starts
 * initializing the TTS engine.
 * 2. `onInit`: This is a callback that runs when the engine is
 * ready. We set the language here.
 * 3. `speak`: This is the function our ViewModel will call. It
 * uses `QUEUE_FLUSH` to interrupt any previous text and
 * speak the new text immediately.
 * 4. `shutdown`: This is the most important cleanup function.
 * We MUST call this to release the TTS resources and
 * prevent memory leaks.
 */
class TTSmanager (
    context: Context
) : TextToSpeech.OnInitListener {

    private val tts: TextToSpeech = TextToSpeech(context, this)
    private var isReady = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Set the language
            val result = tts.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "The Language specified is not supported!")
            } else {
                isReady = true
            }
        } else {
            Log.e("TTS", "Initialization failed!")
        }
    }
    fun stop() {
        if (isReady) {
            tts.stop()
        }
    }
    fun speak(text: String) {
        if (isReady) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        } else {
            Log.e("TTS", "TTS is not ready.")
        }
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
// --- "Mouth" (Text-to-Speech) Manager End ---
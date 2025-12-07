package com.nikhil.netralens.fall

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LightDetector(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

    // Generates system beeps without needing MP3 files
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)

    private var currentLux = 0f
    private var beepingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    fun start() {
        // 1. Start listening to light
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        // 2. Start the "Beeping Loop"
        startBeepingLoop()
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        beepingJob?.cancel()
    }

    private fun startBeepingLoop() {
        beepingJob?.cancel()
        beepingJob = scope.launch {
            while (isActive) {
                // Logic: Brighter light = Shorter delay (Faster beeps)
                // Map lux (0 to 1000) to delay (1000ms to 50ms)

                if (currentLux < 10) {
                    // Too dark? Don't beep, just wait
                    delay(500)
                } else {
                    // Calculate delay: Higher Lux -> Lower Delay
                    val delayTime = (2000 / (currentLux / 10 + 1)).toLong().coerceIn(50, 1000)

                    // Play a short "Pip" sound
                    toneGenerator.startTone(ToneGenerator.TONE_CDMA_PIP, 150)
                    delay(delayTime)
                }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LIGHT) {
            currentLux = event.values[0]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
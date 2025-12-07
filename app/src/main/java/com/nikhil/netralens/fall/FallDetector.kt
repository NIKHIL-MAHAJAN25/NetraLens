package com.nikhil.netralens.fall

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class FallDetector(context: Context, private val onFallDetected: () -> Unit) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)


    private val IMPACT_THRESHOLD = 40f
    private var lastFallTime: Long = 0

    fun start() {
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // Calculate total G-Force
        val gForce = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        if (gForce > IMPACT_THRESHOLD) {
            val now = System.currentTimeMillis()
            // Prevent double-triggering (wait 10 seconds between falls)
            if (lastFallTime + 10000 < now) {
                lastFallTime = now
                onFallDetected()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
package com.slukhayka.audiobooks.player

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlin.math.sqrt

/**
 * Spec-22 T5: pure shake-detection math. Returns true when the net
 * acceleration (excluding standard gravity) of the accelerometer reading
 * exceeds [threshold] m/s². Extracted from [ShakeDetector.onSensorChanged]
 * so the boundary is unit-testable without faking a [SensorEvent].
 */
internal fun shakeExceedsThreshold(
    x: Float,
    y: Float,
    z: Float,
    threshold: Float = 13.5f
): Boolean {
    val gX = x / SensorManager.GRAVITY_EARTH
    val gY = y / SensorManager.GRAVITY_EARTH
    val gZ = z / SensorManager.GRAVITY_EARTH
    val gForce = sqrt(gX * gX + gY * gY + gZ * gZ)
    return gForce > (threshold / SensorManager.GRAVITY_EARTH)
}

/**
 * Shake detector for the sleep timer's fade-out window (spec-22 T5).
 *
 * Listens for a physical shake gesture while the sleep timer is fading the
 * volume down; the callback (extend by +15 min) restores volume to 1.0 and a
 * haptic double-click tick confirms the extension. The threshold is expressed
 * in m/s² of net acceleration above gravity (~13.5 m/s² ≈ 1.4 g).
 */
class ShakeDetector(
    private val context: Context,
    private val threshold: Float = 13.5f,
    private val minShakeIntervalMs: Long = 1200L,
    private val onShake: () -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var lastShakeTime = 0L
    private var isListening = false

    fun startListening() {
        if (isListening || sensorManager == null || accelerometer == null) return
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        isListening = true
    }

    fun stopListening() {
        if (!isListening || sensorManager == null) return
        sensorManager.unregisterListener(this)
        isListening = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // Shake exceeds ~1.4x gravity (≈ threshold m/s²).
        if (shakeExceedsThreshold(x, y, z, threshold)) {
            val now = System.currentTimeMillis()
            if (now - lastShakeTime >= minShakeIntervalMs) {
                lastShakeTime = now
                triggerHapticFeedback()
                onShake()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    private fun triggerHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager =
                    context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(
                        VibrationEffect.createOneShot(100L, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(100L)
                }
            }
        } catch (e: Exception) {
            Log.w("ShakeDetector", "Failed to trigger haptics", e)
        }
    }
}

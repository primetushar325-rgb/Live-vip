package com.livevip.wallpaper.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.livevip.wallpaper.render.Motion
import kotlin.math.atan2

/**
 * Converts device orientation into a normalized tilt (-1..1 on each axis).
 *
 * Preferred source: TYPE_ROTATION_VECTOR, which Android fuses from the gyroscope, accelerometer and
 * magnetometer. Fallback: raw accelerometer gravity with a low-pass filter. If neither sensor exists,
 * [start] returns false and the caller uses touch input instead.
 */
class TiltSensor(context: Context, private val onTilt: (x: Float, y: Float) -> Unit) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var active: Sensor? = null
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var lowX = 0f
    private var lowY = 0f
    private var lowZ = 9.81f

    val isAvailable: Boolean get() = rotationSensor != null || accelerometer != null
    val isRunning: Boolean get() = active != null

    /** Human-readable description of the sensor in use, for the UI. */
    val sourceName: String
        get() = when (active?.type) {
            Sensor.TYPE_ROTATION_VECTOR -> "Rotation sensor (gyroscope + accelerometer)"
            Sensor.TYPE_ACCELEROMETER -> "Accelerometer (fallback)"
            else -> "No motion sensor"
        }

    fun start(): Boolean {
        if (active != null) return true
        val sensor = rotationSensor ?: accelerometer ?: return false
        val ok = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        if (ok) active = sensor
        return ok
    }

    fun stop() {
        if (active != null) sensorManager.unregisterListener(this)
        active = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                val pitch = orientation[1]
                val roll = orientation[2]
                onTilt(Motion.angleToNormalized(roll), Motion.angleToNormalized(pitch))
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val v = event.values
                lowX += (v[0] - lowX) * 0.15f
                lowY += (v[1] - lowY) * 0.15f
                lowZ += (v[2] - lowZ) * 0.15f
                val roll = atan2(lowX, lowZ)
                val pitch = atan2(lowY, lowZ)
                onTilt(Motion.angleToNormalized(roll), Motion.angleToNormalized(pitch))
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

package com.intercom.segluky

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager

/**
 * Rumbo magnetico del propio movil para la pantalla principal.
 *
 * Usa el vector de rotacion (o acelerometro + magnetometro si no existe) y
 * devuelve el rumbo de la parte de arriba de la pantalla, teniendo en cuenta si
 * el movil esta en horizontal. Suaviza el azimut para que no baile.
 *
 * Es solo un dato informativo: no interviene en el detector ni en el SOS.
 */
class PhoneHeading(
    context: Context,
    private val onHeading: (Float) -> Unit
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private val rotationSensor =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val rotationMatrix = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)
    private var lastAccel: FloatArray? = null
    private var lastMag: FloatArray? = null

    private var azimuth = Float.NaN
    private var started = false

    fun available(): Boolean =
        rotationSensor != null || (accelerometer != null && magnetometer != null)

    fun start() {
        if (started) return
        val manager = sensorManager ?: return
        val sensor = rotationSensor ?: accelerometer ?: return
        manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        if (rotationSensor == null && magnetometer != null) {
            manager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI)
        }
        started = true
    }

    fun stop() {
        if (!started) return
        sensorManager?.unregisterListener(this)
        started = false
        lastAccel = null
        lastMag = null
        azimuth = Float.NaN
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR,
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                publish()
            }

            Sensor.TYPE_ACCELEROMETER -> {
                lastAccel = event.values.copyOf()
                publishFallback()
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                lastMag = event.values.copyOf()
                publishFallback()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun publishFallback() {
        if (rotationSensor != null) return
        val accel = lastAccel ?: return
        val mag = lastMag ?: return
        if (!SensorManager.getRotationMatrix(rotationMatrix, null, accel, mag)) return
        publish()
    }

    private fun publish() {
        val (axisX, axisY) = when (displayRotation()) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        if (!SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remapped)) return
        SensorManager.getOrientation(remapped, orientation)
        val raw = normalize(Math.toDegrees(orientation[0].toDouble()).toFloat())
        azimuth = if (azimuth.isNaN()) raw else smooth(azimuth, raw)
        onHeading(azimuth)
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = try {
        windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
    } catch (_: Exception) {
        Surface.ROTATION_0
    }

    private fun smooth(previous: Float, next: Float, alpha: Float = 0.20f): Float {
        var delta = next - previous
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        return normalize(previous + delta * alpha)
    }

    private fun normalize(degrees: Float): Float {
        var value = degrees % 360f
        if (value < 0f) value += 360f
        return value
    }
}

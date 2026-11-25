package com.intercom.segluky

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Condicion de vuelco del sensor externo.
 *
 * Solo usa la gravedad de X e Y respecto a una referencia aprendida con la
 * moto vertical. El rumbo (Z) no interviene. La referencia se aprende durante
 * [calibrationMs] exigiendo que la moto este quieta: si la muestra se mueve o
 * el sensor no esta con Z hacia arriba, la calibracion se reinicia.
 */
class Wt9011TiltWatch(
    thresholdDegrees: Float = Wt9011Settings.DEFAULT_THRESHOLD_DEGREES,
    private val sustainedMs: Long = SUSTAINED_TILT_MS,
    private val calibrationMs: Long = CALIBRATION_MS,
    private val rearmMs: Long = REARM_MS,
    private val hysteresisDegrees: Float = HYSTERESIS_DEGREES
) {
    data class Reading(
        val calibrated: Boolean,
        val tiltDegrees: Float?,
        val axisXDegrees: Float?,
        val axisYDegrees: Float?,
        /** La condicion esta activa (inclinacion sostenida). */
        val sustained: Boolean,
        /** Flanco: se ha alcanzado la condicion y aun no se ha rearmado. */
        val triggered: Boolean
    )

    private var threshold = thresholdDegrees.coerceIn(
        Wt9011Settings.MIN_THRESHOLD_DEGREES,
        Wt9011Settings.MAX_THRESHOLD_DEGREES
    )

    private var filteredX = 0f
    private var filteredY = 0f
    private var filteredZ = 0f
    private var filteredValid = false
    private var lastTimestamp: Long? = null

    private var calibrationStartedAt: Long? = null
    private var calibrationSamples = 0
    private var sumX = 0f
    private var sumY = 0f
    private var sumZ = 0f
    private var sumSqX = 0f
    private var sumSqY = 0f
    private var sumSqZ = 0f

    private var baselineX = 0f
    private var baselineY = 0f
    private var baselineZ = 0f
    private var calibrated = false
    private var relaxedCalibration = false

    private var tiltedSince: Long? = null
    private var triggered = false
    private var rearmSince: Long? = null

    private var lastTilt: Float? = null
    private var lastAxisX: Float? = null
    private var lastAxisY: Float? = null

    val thresholdDegrees: Float get() = threshold

    fun setThreshold(degrees: Float) {
        threshold = degrees.coerceIn(
            Wt9011Settings.MIN_THRESHOLD_DEGREES,
            Wt9011Settings.MAX_THRESHOLD_DEGREES
        )
    }

    /**
     * Vuelve a aprender la referencia y borra la condicion en curso.
     * [force] lo usa el boton CALIBRAR: acepta cualquier montaje porque el
     * usuario confirma que la moto esta vertical. La calibracion automatica
     * sigue exigiendo estar a menos de 10 grados para no aprender la pata de
     * cabra como si fuera la vertical.
     */
    fun calibrate(force: Boolean = false) {
        reset()
        relaxedCalibration = force
    }

    fun reset() {
        relaxedCalibration = false
        filteredValid = false
        filteredX = 0f
        filteredY = 0f
        filteredZ = 0f
        lastTimestamp = null
        calibrationStartedAt = null
        calibrationSamples = 0
        sumX = 0f
        sumY = 0f
        sumZ = 0f
        sumSqX = 0f
        sumSqY = 0f
        sumSqZ = 0f
        calibrated = false
        baselineX = 0f
        baselineY = 0f
        baselineZ = 0f
        tiltedSince = null
        triggered = false
        rearmSince = null
        lastTilt = null
        lastAxisX = null
        lastAxisY = null
    }

    fun feed(
        ax: Float,
        ay: Float,
        az: Float,
        gx: Float,
        gy: Float,
        gz: Float,
        timestamp: Long
    ): Reading {
        if (!ax.isFinite() || !ay.isFinite() || !az.isFinite() || timestamp < 0L) {
            return currentReading()
        }
        // Pasa-bajos sobre el vector de gravedad: la vibracion del motor y los
        // baches son ruido de alta frecuencia; una inclinacion real es lenta.
        val previous = lastTimestamp
        val dt = if (previous == null) 0L else (timestamp - previous).coerceIn(0L, MAX_FILTER_DT_MS)
        lastTimestamp = timestamp
        if (!filteredValid) {
            filteredX = ax
            filteredY = ay
            filteredZ = az
            filteredValid = true
        } else if (dt > 0L) {
            val alpha = (1.0 - exp(-dt.toDouble() / FILTER_TAU_MS)).toFloat()
            filteredX += alpha * (ax - filteredX)
            filteredY += alpha * (ay - filteredY)
            filteredZ += alpha * (az - filteredZ)
        }

        val magnitude = sqrt(filteredX * filteredX + filteredY * filteredY + filteredZ * filteredZ)
        if (!magnitude.isFinite() || magnitude < MIN_GRAVITY_G || magnitude > MAX_GRAVITY_G) {
            return currentReading()
        }
        val unitX = filteredX / magnitude
        val unitY = filteredY / magnitude
        val unitZ = filteredZ / magnitude

        if (!calibrated) {
            // Con el sensor tumbado no se puede aprender la vertical: Z tiene
            // que estar razonablemente arriba. El giro descarta un movimiento
            // franco; la estabilidad de la media descarta una moto en marcha.
            val gyro = sqrt(gx * gx + gy * gy + gz * gz)
            val minimumUnitZ = if (relaxedCalibration) {
                RELAXED_MIN_CALIBRATION_UNIT_Z
            } else {
                MIN_CALIBRATION_UNIT_Z
            }
            if (abs(unitZ) < minimumUnitZ || gyro > MAX_CALIBRATION_GYRO_DPS) {
                restartCalibration()
                return currentReading()
            }
            val startedAt = calibrationStartedAt
            if (startedAt == null || timestamp < startedAt) {
                calibrationStartedAt = timestamp
                compileCalibrationSample(unitX, unitY, unitZ)
                return currentReading()
            }
            compileCalibrationSample(unitX, unitY, unitZ)
            if (timestamp - startedAt < calibrationMs || calibrationSamples < MIN_CALIBRATION_SAMPLES) {
                return currentReading()
            }
            val averageX = sumX / calibrationSamples
            val averageY = sumY / calibrationSamples
            val averageZ = sumZ / calibrationSamples
            val varianceX = (sumSqX / calibrationSamples - averageX * averageX).coerceAtLeast(0f)
            val varianceY = (sumSqY / calibrationSamples - averageY * averageY).coerceAtLeast(0f)
            val varianceZ = (sumSqZ / calibrationSamples - averageZ * averageZ).coerceAtLeast(0f)
            val deviation = sqrt(varianceX + varianceY + varianceZ)
            if (deviation > MAX_CALIBRATION_DEVIATION) {
                // La moto se movio durante la ventana: se vuelve a empezar.
                restartCalibration()
                return currentReading()
            }
            val length = sqrt(averageX * averageX + averageY * averageY + averageZ * averageZ)
            if (!length.isFinite() || length <= 0f) {
                restartCalibration()
                return currentReading()
            }
            baselineX = averageX / length
            baselineY = averageY / length
            baselineZ = averageZ / length
            calibrated = true
            relaxedCalibration = false
            lastTilt = 0f
            lastAxisX = 0f
            lastAxisY = 0f
            return Reading(
                calibrated = true,
                tiltDegrees = 0f,
                axisXDegrees = 0f,
                axisYDegrees = 0f,
                sustained = false,
                triggered = false
            )
        }

        val dot = (baselineX * unitX + baselineY * unitY + baselineZ * unitZ).coerceIn(-1f, 1f)
        val tilt = Math.toDegrees(acos(dot.toDouble())).toFloat()
        val axisX = Math.toDegrees(asin(unitX.coerceIn(-1f, 1f).toDouble())).toFloat()
        val axisY = Math.toDegrees(asin(unitY.coerceIn(-1f, 1f).toDouble())).toFloat()

        lastTilt = tilt
        lastAxisX = axisX
        lastAxisY = axisY

        if (tilt >= threshold) {
            rearmSince = null
            val since = tiltedSince ?: timestamp.also { tiltedSince = it }
            if (!triggered && timestamp >= since && timestamp - since >= sustainedMs) {
                triggered = true
            }
        } else if (tilt < threshold - hysteresisDegrees) {
            tiltedSince = null
            if (triggered) {
                val since = rearmSince ?: timestamp.also { rearmSince = it }
                if (timestamp >= since && timestamp - since >= rearmMs) {
                    triggered = false
                    rearmSince = null
                }
            }
        }

        return Reading(
            calibrated = true,
            tiltDegrees = tilt,
            axisXDegrees = axisX,
            axisYDegrees = axisY,
            sustained = triggered,
            triggered = triggered
        )
    }

    private fun compileCalibrationSample(unitX: Float, unitY: Float, unitZ: Float) {
        sumX += unitX
        sumY += unitY
        sumZ += unitZ
        sumSqX += unitX * unitX
        sumSqY += unitY * unitY
        sumSqZ += unitZ * unitZ
        calibrationSamples++
    }

    private fun restartCalibration() {
        calibrationStartedAt = null
        calibrationSamples = 0
        sumX = 0f
        sumY = 0f
        sumZ = 0f
        sumSqX = 0f
        sumSqY = 0f
        sumSqZ = 0f
    }

    private fun currentReading(): Reading = Reading(
        calibrated = calibrated,
        tiltDegrees = lastTilt,
        axisXDegrees = lastAxisX,
        axisYDegrees = lastAxisY,
        sustained = triggered,
        triggered = triggered
    )

    companion object {
        const val SUSTAINED_TILT_MS = 5_000L
        const val CALIBRATION_MS = 3_000L
        const val REARM_MS = 3_000L
        const val HYSTERESIS_DEGREES = 5f

        private const val MIN_GRAVITY_G = 0.6f
        private const val MAX_GRAVITY_G = 1.4f
        // No se calibra con la moto en la pata de cabra: solo se acepta una
        // vertical a menos de 10 grados. Si esta ladeada, la app espera a que
        // se enderece y la mantenga quieta.
        private const val MIN_CALIBRATION_UNIT_Z = 0.985f
        private const val RELAXED_MIN_CALIBRATION_UNIT_Z = 0.5f
        private const val MAX_CALIBRATION_GYRO_DPS = 180f
        private const val MAX_CALIBRATION_DEVIATION = 0.10f
        private const val MIN_CALIBRATION_SAMPLES = 20
        private const val MAX_FILTER_DT_MS = 1_000L

        /** Constante de tiempo del pasa-bajos de gravedad, en milisegundos. */
        private const val FILTER_TAU_MS = 350.0
    }
}

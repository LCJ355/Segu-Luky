package com.intercom.segluky

import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.sqrt

/** Tracks the device normal, so heading changes do not count as a fall. */
internal class FallOrientationDetector(
    private val tiltThresholdDegrees: Float = TILT_THRESHOLD_DEGREES,
    private val sustainedDurationMs: Long = SUSTAINED_TILT_DURATION_MS,
    private val calibrationDurationMs: Long = CALIBRATION_DURATION_MS
) {
    data class Reading(
        val calibrated: Boolean,
        val tiltDegrees: Float?,
        val tiltedSince: Long?,
        val sustainedFall: Boolean
    )

    private var calibrationStartedAt: Long? = null
    private var calibrationSumX = 0f
    private var calibrationSumY = 0f
    private var calibrationSumZ = 0f
    private var calibrationSamples = 0
    private var baselineX: Float? = null
    private var baselineY = 0f
    private var baselineZ = 0f
    private var tiltedSince: Long? = null

    // Gravity-based attitude tracking (works with TYPE_GRAVITY or low-pass accelerometer).
    private var gravityBaselineX: Float? = null
    private var gravityBaselineY = 0f
    private var gravityBaselineZ = 0f
    private var gravityBaselineUpdatedAt: Long? = null
    private var gravityWarmupEndsAt: Long? = null
    private var gravityTiltedSince: Long? = null

    fun reset() {
        calibrationStartedAt = null
        calibrationSumX = 0f
        calibrationSumY = 0f
        calibrationSumZ = 0f
        calibrationSamples = 0
        baselineX = null
        baselineY = 0f
        baselineZ = 0f
        tiltedSince = null
        gravityBaselineX = null
        gravityBaselineY = 0f
        gravityBaselineZ = 0f
        gravityBaselineUpdatedAt = null
        gravityWarmupEndsAt = null
        gravityTiltedSince = null
    }

    /**
     * Feeds a gravity vector (device coordinates). The baseline is the riding
     * attitude: it is learned during a warm-up and kept slow-tracking while the
     * device is roughly upright, so handlebar mounts and curves do not drift it.
     * A crash tilt (>= threshold for the sustained window) yields sustainedFall.
     */
    fun onGravity(x: Float, y: Float, z: Float, timestamp: Long): Reading {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite() || timestamp < 0L) return currentGravityReading()
        val magnitude = sqrt(x * x + y * y + z * z)
        if (!magnitude.isFinite() || magnitude <= 0f) return currentGravityReading()
        val unit = Vector(x / magnitude, y / magnitude, z / magnitude)
        if (magnitude / STANDARD_GRAVITY < MIN_GRAVITY_G || magnitude / STANDARD_GRAVITY > MAX_GRAVITY_G) {
            return currentGravityReading()
        }

        var baseX = gravityBaselineX
        if (baseX == null) {
            gravityBaselineX = unit.x
            gravityBaselineY = unit.y
            gravityBaselineZ = unit.z
            gravityBaselineUpdatedAt = timestamp
            gravityWarmupEndsAt = timestamp + calibrationDurationMs
            gravityTiltedSince = null
            return Reading(calibrated = false, tiltDegrees = null, tiltedSince = null, sustainedFall = false)
        }

        val warmupEnd = gravityWarmupEndsAt
        if (warmupEnd != null && timestamp < warmupEnd) {
            // Warm-up: keep the baseline as a running average.
            val n = ((timestamp - calibrationStartedAt(gravityBaselineUpdatedAt)) / SAMPLE_STEP_MS).toInt().coerceAtLeast(1)
            val alpha = 1f / (n + 1)
            gravityBaselineX = alpha * unit.x + (1f - alpha) * baseX
            gravityBaselineY = alpha * unit.y + (1f - alpha) * gravityBaselineY
            gravityBaselineZ = alpha * unit.z + (1f - alpha) * gravityBaselineZ
            return Reading(calibrated = false, tiltDegrees = null, tiltedSince = null, sustainedFall = false)
        }

        val dot = (baseX * unit.x + gravityBaselineY * unit.y + gravityBaselineZ * unit.z).coerceIn(-1f, 1f)
        val tilt = Math.toDegrees(acos(dot.toDouble())).toFloat()

        // Slow-track the baseline only while essentially upright (small tilt).
        if (tilt <= BASELINE_LEARN_TILT_DEGREES) {
            val updatedAt = gravityBaselineUpdatedAt ?: timestamp
            if (timestamp > updatedAt) {
                val elapsed = (timestamp - updatedAt).coerceAtMost(MAX_LEARN_INTERVAL_MS)
                val alpha = exp(-elapsed.toDouble() / BASELINE_TIME_CONSTANT_MS).toFloat()
                gravityBaselineX = alpha * baseX + (1f - alpha) * unit.x
                gravityBaselineY = alpha * gravityBaselineY + (1f - alpha) * unit.y
                gravityBaselineZ = alpha * gravityBaselineZ + (1f - alpha) * unit.z
                gravityBaselineUpdatedAt = timestamp
            }
        }

        if (tilt < tiltThresholdDegrees) {
            gravityTiltedSince = null
            return Reading(calibrated = true, tiltDegrees = tilt, tiltedSince = null, sustainedFall = false)
        }
        val since = gravityTiltedSince ?: timestamp.also { gravityTiltedSince = it }
        val sustained = timestamp >= since && timestamp - since >= sustainedDurationMs
        return Reading(calibrated = true, tiltDegrees = tilt, tiltedSince = since, sustainedFall = sustained)
    }

    private fun calibrationStartedAt(firstAt: Long?): Long = firstAt ?: 0L

    private fun currentGravityReading(): Reading = Reading(
        calibrated = gravityBaselineX != null,
        tiltDegrees = null,
        tiltedSince = gravityTiltedSince,
        sustainedFall = false
    )

    fun onRotationVector(values: FloatArray, timestamp: Long): Reading {
        val normal = deviceNormal(values) ?: return currentReading()
        val baseX = baselineX
        if (baseX == null) return calibrate(normal, timestamp)

        val dot = (baseX * normal.x + baselineY * normal.y + baselineZ * normal.z)
            .coerceIn(-1f, 1f)
        val tilt = Math.toDegrees(acos(dot.toDouble())).toFloat()
        if (tilt < tiltThresholdDegrees) {
            tiltedSince = null
            return Reading(calibrated = true, tiltDegrees = tilt, tiltedSince = null, sustainedFall = false)
        }

        val since = tiltedSince ?: timestamp.also { tiltedSince = it }
        val sustained = timestamp >= since && timestamp - since >= sustainedDurationMs
        return Reading(calibrated = true, tiltDegrees = tilt, tiltedSince = since, sustainedFall = sustained)
    }

    private fun calibrate(normal: Vector, timestamp: Long): Reading {
        val startedAt = calibrationStartedAt
        if (startedAt == null || timestamp < startedAt) {
            calibrationStartedAt = timestamp
            calibrationSumX = normal.x
            calibrationSumY = normal.y
            calibrationSumZ = normal.z
            calibrationSamples = 1
            return Reading(calibrated = false, tiltDegrees = null, tiltedSince = null, sustainedFall = false)
        }

        calibrationSumX += normal.x
        calibrationSumY += normal.y
        calibrationSumZ += normal.z
        calibrationSamples++
        if (timestamp - startedAt < calibrationDurationMs) {
            return Reading(calibrated = false, tiltDegrees = null, tiltedSince = null, sustainedFall = false)
        }

        val average = normalize(
            calibrationSumX / calibrationSamples,
            calibrationSumY / calibrationSamples,
            calibrationSumZ / calibrationSamples
        ) ?: return Reading(calibrated = false, tiltDegrees = null, tiltedSince = null, sustainedFall = false)
        baselineX = average.x
        baselineY = average.y
        baselineZ = average.z
        return Reading(calibrated = true, tiltDegrees = 0f, tiltedSince = null, sustainedFall = false)
    }

    private fun currentReading(): Reading = Reading(
        calibrated = baselineX != null,
        tiltDegrees = null,
        tiltedSince = tiltedSince,
        sustainedFall = false
    )

    private fun deviceNormal(values: FloatArray): Vector? {
        if (values.size < 3) return null
        var x = values[0]
        var y = values[1]
        var z = values[2]
        var w = if (values.size >= 4) values[3] else {
            sqrt((1f - x * x - y * y - z * z).coerceAtLeast(0f))
        }
        val quaternionLength = sqrt(x * x + y * y + z * z + w * w)
        if (!quaternionLength.isFinite() || quaternionLength <= 0f) return null
        x /= quaternionLength
        y /= quaternionLength
        z /= quaternionLength
        w /= quaternionLength

        return normalize(
            x = 2f * (x * z + w * y),
            y = 2f * (y * z - w * x),
            z = 1f - 2f * (x * x + y * y)
        )
    }

    private fun normalize(x: Float, y: Float, z: Float): Vector? {
        val length = sqrt(x * x + y * y + z * z)
        if (!length.isFinite() || length <= 0f) return null
        return Vector(x / length, y / length, z / length)
    }

    private data class Vector(val x: Float, val y: Float, val z: Float)

    companion object {
        const val TILT_THRESHOLD_DEGREES = 60f
        const val SUSTAINED_TILT_DURATION_MS = 5_000L
        const val CALIBRATION_DURATION_MS = 3_000L

        private const val STANDARD_GRAVITY = 9.80665f
        private const val MIN_GRAVITY_G = 0.6f
        private const val MAX_GRAVITY_G = 1.4f
        private const val BASELINE_LEARN_TILT_DEGREES = 30f
        private const val BASELINE_TIME_CONSTANT_MS = 8_000.0
        private const val MAX_LEARN_INTERVAL_MS = 2_000L
        private const val SAMPLE_STEP_MS = 100L
    }
}

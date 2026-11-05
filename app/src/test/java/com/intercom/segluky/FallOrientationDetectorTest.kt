package com.intercom.segluky

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallOrientationDetectorTest {
    companion object {
        private const val G = 9.80665f
    }

    @Test
    fun requiresCalibrationAndSustainedTilt() {
        val detector = FallOrientationDetector()

        assertFalse(detector.onRotationVector(rotationAroundX(0f), 0L).calibrated)
        assertTrue(detector.onRotationVector(rotationAroundX(0f), 3_000L).calibrated)

        assertFalse(detector.onRotationVector(rotationAroundX(70f), 3_100L).sustainedFall)
        assertFalse(detector.onRotationVector(rotationAroundX(70f), 8_099L).sustainedFall)
        assertTrue(detector.onRotationVector(rotationAroundX(70f), 8_100L).sustainedFall)
    }

    @Test
    fun headingChangeDoesNotCountAsTilt() {
        val detector = FallOrientationDetector()
        detector.onRotationVector(rotationAroundZ(0f), 0L)
        detector.onRotationVector(rotationAroundZ(0f), 3_000L)

        val reading = detector.onRotationVector(rotationAroundZ(120f), 9_000L)

        assertFalse(reading.sustainedFall)
        assertTrue((reading.tiltDegrees ?: Float.MAX_VALUE) < 1f)
    }

    @Test
    fun gravityCalibratesAndConfirmsSustainedTilt() {
        val detector = FallOrientationDetector(
            tiltThresholdDegrees = 55f,
            sustainedDurationMs = 4_000L,
            calibrationDurationMs = 2_000L
        )

        // Warm-up upright (calibration 2 s).
        repeat(10) { index ->
            detector.onGravity(0f, 0f, G, 0L + index * 200L)
        }
        var reading = detector.onGravity(0f, 0f, G, 2_000L)
        assertTrue(reading.calibrated)
        assertTrue((reading.tiltDegrees ?: 90f) < 5f)

        // Bike falls to its side: gravity now points along device X (90 deg tilt).
        reading = detector.onGravity(G, 0f, 0f, 2_200L)
        assertFalse(reading.sustainedFall)
        reading = detector.onGravity(G, 0f, 0f, 6_199L)
        assertFalse(reading.sustainedFall)
        reading = detector.onGravity(G, 0f, 0f, 6_200L)
        assertTrue(reading.sustainedFall)
    }

    @Test
    fun gravityRecoveryToUprightResetsTilt() {
        val detector = FallOrientationDetector(
            tiltThresholdDegrees = 55f,
            sustainedDurationMs = 4_000L,
            calibrationDurationMs = 2_000L
        )
        repeat(10) { index ->
            detector.onGravity(0f, 0f, G, 0L + index * 200L)
        }
        detector.onGravity(G, 0f, 0f, 2_200L)
        val reading = detector.onGravity(0f, 0f, G, 2_400L)
        assertFalse(reading.sustainedFall)
        assertTrue((reading.tiltDegrees ?: 90f) < 5f)
    }

    private fun rotationAroundX(degrees: Float): FloatArray {
        val halfAngle = Math.toRadians(degrees.toDouble()).toFloat() / 2f
        return floatArrayOf(sin(halfAngle), 0f, 0f, cos(halfAngle))
    }

    private fun rotationAroundZ(degrees: Float): FloatArray {
        val halfAngle = Math.toRadians(degrees.toDouble()).toFloat() / 2f
        return floatArrayOf(0f, 0f, sin(halfAngle), cos(halfAngle))
    }
}

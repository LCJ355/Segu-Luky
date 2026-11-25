package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class Wt9011TiltWatchTest {

    private fun feed(
        watch: Wt9011TiltWatch,
        degrees: Float,
        startMs: Long,
        durationMs: Long,
        stepMs: Long = 20L
    ): Wt9011TiltWatch.Reading {
        val radians = (degrees * PI / 180.0).toFloat()
        var last = watch.feed(sin(radians), 0f, cos(radians), 0f, 0f, 0f, startMs)
        var time = startMs + stepMs
        val end = startMs + durationMs
        while (time <= end) {
            last = watch.feed(sin(radians), 0f, cos(radians), 0f, 0f, 0f, time)
            time += stepMs
        }
        return last
    }

    private fun calibratedWatch(threshold: Float = 55f): Wt9011TiltWatch {
        val watch = Wt9011TiltWatch(thresholdDegrees = threshold)
        val reading = feed(watch, 0f, 0L, 3_100L)
        assertTrue(reading.calibrated)
        return watch
    }

    @Test
    fun defaultThresholdIsFiftyFiveAndStaysInRange() {
        val watch = Wt9011TiltWatch()
        assertEquals(55f, watch.thresholdDegrees, 0.001f)
        watch.setThreshold(80f)
        assertEquals(60f, watch.thresholdDegrees, 0.001f)
        watch.setThreshold(10f)
        assertEquals(50f, watch.thresholdDegrees, 0.001f)
    }

    @Test
    fun learnsUprightReferenceBeforeTriggering() {
        val watch = Wt9011TiltWatch()
        val uncalibrated = feed(watch, 0f, 0L, 1_000L)
        assertFalse(uncalibrated.calibrated)
        val calibrated = feed(watch, 0f, 1_020L, 2_100L)
        assertTrue(calibrated.calibrated)
        assertEquals(0f, calibrated.tiltDegrees ?: -1f, 0.01f)
    }

    @Test
    fun doesNotCalibrateWithSensorOnItsSide() {
        val watch = Wt9011TiltWatch()
        var last = watch.feed(1f, 0f, 0f, 0f, 0f, 0f, 0L)
        var time = 0L
        while (time <= 4_000L) {
            last = watch.feed(1f, 0f, 0f, 0f, 0f, 0f, time)
            time += 20L
        }
        assertFalse(last.calibrated)
        assertEquals(null, last.tiltDegrees)
    }

    @Test
    fun triggersAfterFiveSecondsAboveThreshold() {
        val watch = calibratedWatch()
        var firstAboveAt: Long? = null
        var triggeredAt: Long? = null
        var time = 3_200L
        while (time <= 20_000L) {
            val reading = feed(watch, 56f, time, 0L)
            val tilt = reading.tiltDegrees ?: 0f
            if (firstAboveAt == null && tilt >= 55f) firstAboveAt = time
            if (reading.triggered) {
                triggeredAt = time
                break
            }
            time += 20L
        }
        assertNotNull("debe superar el umbral", firstAboveAt)
        assertNotNull("debe disparar", triggeredAt)
        assertTrue(triggeredAt!! - firstAboveAt!! >= 4_980L)
    }

    @Test
    fun doesNotTriggerBelowThresholdMinusHysteresis() {
        val watch = calibratedWatch()
        val reading = feed(watch, 54f, 3_200L, 10_000L)
        assertFalse(reading.triggered)
    }

    @Test
    fun rearmRequiresUprightForThreeSeconds() {
        val watch = calibratedWatch()
        var triggeredAt: Long? = null
        var time = 3_200L
        while (time <= 20_000L) {
            if (feed(watch, 56f, time, 0L).triggered) {
                triggeredAt = time
                break
            }
            time += 20L
        }
        assertNotNull(triggeredAt)

        val upright = feed(watch, 30f, time + 100L, 2_800L)
        assertTrue("debe seguir disparado mientras no pase el rearme", upright.triggered)
        val rearmed = feed(watch, 30f, time + 3_000L, 400L)
        assertFalse(rearmed.triggered)

        val second = feed(watch, 56f, time + 3_600L, 8_000L)
        assertTrue(second.triggered)
    }

    @Test
    fun thresholdChangeAppliesToTheSameCalibration() {
        val watch = calibratedWatch(threshold = 55f)
        watch.setThreshold(50f)
        val reading = feed(watch, 52f, 3_200L, 10_000L)
        assertTrue(reading.triggered)
    }

    @Test
    fun reportsSeparateXAndYComponents() {
        val watch = calibratedWatch()
        val reading = feed(watch, 20f, 3_200L, 2_000L)
        assertEquals(20f, reading.axisXDegrees ?: -1f, 0.5f)
        assertEquals(0f, reading.axisYDegrees ?: -1f, 0.5f)
    }

    @Test
    fun ignoresSamplesOutsideGravityRange() {
        val watch = calibratedWatch()
        val reading = watch.feed(0f, 0f, 3f, 0f, 0f, 0f, 5_000L)
        assertTrue(reading.calibrated)
        assertEquals(0f, reading.tiltDegrees ?: -1f, 0.01f)
    }
}

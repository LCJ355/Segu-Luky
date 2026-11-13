package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalibrationRatePolicyTest {
    @Test
    fun speedBandsAreProgressiveFromTwentyToHundred() {
        assertEquals(20, CalibrationRatePolicy.targetHz(0f))
        assertEquals(20, CalibrationRatePolicy.targetHz(12f))
        assertEquals(40, CalibrationRatePolicy.targetHz(12.5f))
        assertEquals(40, CalibrationRatePolicy.targetHz(24f))
        assertEquals(60, CalibrationRatePolicy.targetHz(25f))
        assertEquals(80, CalibrationRatePolicy.targetHz(37.5f))
        assertEquals(80, CalibrationRatePolicy.targetHz(49f))
        assertEquals(100, CalibrationRatePolicy.targetHz(50f))
        assertEquals(100, CalibrationRatePolicy.targetHz(120f))
    }

    @Test
    fun missingSpeedKeepsTheMinimumRate() {
        assertEquals(20, CalibrationRatePolicy.targetHz(null))
        assertEquals(20, CalibrationRatePolicy.targetHz(Float.NaN))
    }

    @Test
    fun controllerWaitsForStabilityAndMinimumRebindInterval() {
        val controller = CalibrationRateController(stableMs = 10_000L, minRebindMs = 15_000L)
        controller.reset(now = 0L, initialHz = 20)

        assertNull(controller.update(55f, now = 1_000L))
        assertNull(controller.update(55f, now = 10_999L))
        assertEquals(100, controller.update(55f, now = 11_000L))

        assertNull(controller.update(0f, now = 12_000L))
        assertNull(controller.update(0f, now = 22_000L))
        assertEquals(20, controller.update(0f, now = 26_000L))
    }

    @Test
    fun resetCanStartAtAFreshLastKnownSpeed() {
        val controller = CalibrationRateController()
        controller.reset(now = 5_000L, initialHz = 100)

        assertEquals(100, controller.appliedHz)
        assertNull(controller.update(55f, now = 6_000L))
    }

    @Test
    fun medianIgnoresASingleGpsSpike() {
        val median = MedianSpeed()
        assertEquals(40f, median.add(40f))
        median.add(90f)
        assertEquals(42f, median.add(42f))
        assertEquals(43f, median.add(43f))
    }
}

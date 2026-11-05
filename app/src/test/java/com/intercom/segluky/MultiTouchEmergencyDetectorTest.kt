package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiTouchEmergencyDetectorTest {
    @Test
    fun twoFingersNeedFiveSeconds() {
        val detector = MultiTouchEmergencyDetector()

        assertFalse(detector.update(pointerCount = 2, timestamp = 1_000L))
        assertEquals(6_000L, detector.nextDeadline())
        assertFalse(detector.update(pointerCount = 2, timestamp = 5_999L))
        assertTrue(detector.update(pointerCount = 2, timestamp = 6_000L))
        assertNull(detector.nextDeadline())
    }

    @Test
    fun liftingBelowTwoResetsTheHold() {
        val detector = MultiTouchEmergencyDetector()

        detector.update(pointerCount = 2, timestamp = 1_000L)
        assertFalse(detector.update(pointerCount = 1, timestamp = 4_000L))
        assertFalse(detector.update(pointerCount = 2, timestamp = 4_001L))
        assertTrue(detector.update(pointerCount = 2, timestamp = 9_001L))
    }

    @Test
    fun briefMultiTouchDoesNotTrigger() {
        val detector = MultiTouchEmergencyDetector()

        assertFalse(detector.update(pointerCount = 2, timestamp = 1_000L))
        detector.reset()
        assertFalse(detector.update(pointerCount = 2, timestamp = 1_000L))
        assertFalse(detector.update(pointerCount = 1, timestamp = 1_100L))
    }
}
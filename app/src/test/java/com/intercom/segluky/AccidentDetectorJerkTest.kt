package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccidentDetectorJerkTest {
    @Test
    fun sharpSubThresholdHitAtFiftyKmhCounts() {
        val events = RecordingListener()
        val detector = AccidentDetector(events)
        detector.setGolpeSensitivity(3.5f)
        detector.setFallOrientationGateEnabled(false)

        detector.onLocation(50f, timestamp = 1_000L)
        detector.onDynamicAcceleration(g(0.1f), 0f, 0f, timestamp = 1_980L)
        detector.onDynamicAcceleration(g(3.2f), 0f, 0f, timestamp = 2_000L)

        assertEquals(SafetyPhase.COUNTDOWN, detector.runtimeState().phase)
        assertEquals(listOf("golpe fuerte detectado"), events.manualReasons)
    }

    @Test
    fun theSameSharpHitBelowTwentyKmhIsIgnored() {
        val events = RecordingListener()
        val detector = AccidentDetector(events)
        detector.setGolpeSensitivity(3.5f)
        detector.setFallOrientationGateEnabled(false)

        detector.onLocation(10f, timestamp = 1_000L)
        detector.onDynamicAcceleration(g(0.1f), 0f, 0f, timestamp = 1_980L)
        detector.onDynamicAcceleration(g(3.2f), 0f, 0f, timestamp = 2_000L)

        assertEquals(SafetyPhase.MONITORING, detector.runtimeState().phase)
        assertTrue(events.manualReasons.isEmpty())
    }

    @Test
    fun withoutSpeedThereIsNoJerkAssist() {
        val events = RecordingListener()
        val detector = AccidentDetector(events)
        detector.setGolpeSensitivity(3.5f)
        detector.setFallOrientationGateEnabled(false)

        detector.onDynamicAcceleration(g(0.1f), 0f, 0f, timestamp = 1_980L)
        detector.onDynamicAcceleration(g(3.2f), 0f, 0f, timestamp = 2_000L)

        assertEquals(SafetyPhase.MONITORING, detector.runtimeState().phase)
        assertTrue(events.manualReasons.isEmpty())
    }

    @Test
    fun aStaleSpeedDoesNotAssistTheJerk() {
        val events = RecordingListener()
        val detector = AccidentDetector(events)
        detector.setGolpeSensitivity(3.5f)
        detector.setFallOrientationGateEnabled(false)

        detector.onLocation(50f, timestamp = 1_000L)
        detector.onDynamicAcceleration(g(0.1f), 0f, 0f, timestamp = 20_980L)
        detector.onDynamicAcceleration(g(3.2f), 0f, 0f, timestamp = 21_000L)

        assertEquals(SafetyPhase.MONITORING, detector.runtimeState().phase)
        assertTrue(events.manualReasons.isEmpty())
    }

    @Test
    fun aGradualHitAtFiftyKmhIsStillBelowTheThreshold() {
        val events = RecordingListener()
        val detector = AccidentDetector(events)
        detector.setGolpeSensitivity(3.5f)
        detector.setFallOrientationGateEnabled(false)

        detector.onLocation(50f, timestamp = 1_000L)
        detector.onDynamicAcceleration(g(3.0f), 0f, 0f, timestamp = 1_980L)
        detector.onDynamicAcceleration(g(3.2f), 0f, 0f, timestamp = 2_080L)

        assertEquals(SafetyPhase.MONITORING, detector.runtimeState().phase)
        assertTrue(events.manualReasons.isEmpty())
    }

    @Test
    fun aRealThresholdHitStillCountsWithOrWithoutSpeed() {
        val events = RecordingListener()
        val detector = AccidentDetector(events)
        detector.setGolpeSensitivity(3.5f)
        detector.setFallOrientationGateEnabled(false)

        detector.onDynamicAcceleration(g(3.6f), 0f, 0f, timestamp = 2_000L)

        assertEquals(SafetyPhase.COUNTDOWN, detector.runtimeState().phase)
        assertEquals(listOf("golpe fuerte detectado"), events.manualReasons)
    }

    private fun g(value: Float): Float = value * AccidentDetector.STANDARD_GRAVITY

    private class RecordingListener : AccidentDetector.Listener {
        val manualReasons = mutableListOf<String>()

        override fun onValidationStarted(startedAt: Long) = Unit
        override fun onValidationExpired() = Unit
        override fun onAutomaticEmergency() = Unit
        override fun onManualEmergency(reason: String) {
            manualReasons += reason
        }

        override fun onVibrationDetected() = Unit
        override fun onMotionDetected() = Unit
    }
}

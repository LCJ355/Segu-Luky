package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccidentDetectorTest {
    @Test
    fun configuredHitStartsImmediateCountdownRegardlessOfSpeed() {
        val events = RecordingListener()
        val detector = AccidentDetector(events)
        detector.setGolpeSensitivity(3f)

        detector.onLocation(35f, timestamp = 1_000L)
        detector.onDynamicAcceleration(g(7.2f), 0f, 0f, timestamp = 2_000L)

        assertEquals(0, events.validations)
        assertEquals(listOf("golpe fuerte detectado"), events.manualReasons)
        assertEquals(SafetyPhase.COUNTDOWN, detector.runtimeState().phase)
    }

    @Test
    fun configuredHitStartsImmediateCountdownWithoutSpeed() {
        val events = RecordingListener()
        val detector = AccidentDetector(events)
        detector.setGolpeSensitivity(3f)

        detector.onDynamicAcceleration(g(7.2f), 0f, 0f, timestamp = 2_000L)

        assertEquals(0, events.validations)
        assertEquals(listOf("golpe fuerte detectado"), events.manualReasons)
        assertEquals(SafetyPhase.COUNTDOWN, detector.runtimeState().phase)
    }

    @Test
    fun subSevenGLinearMovementDoesNotStartAutomaticValidation() {
        val events = RecordingListener()
        val detector = AccidentDetector(events)

        detector.onLocation(35f, timestamp = 1_000L)
        detector.onLinearAcceleration(g(0.9f), 0f, 0f, timestamp = 2_000L)

        assertEquals(0, events.validations)
    }

    @Test
    fun stationaryValidationRequestsAutomaticEmergencyAfterWindow() {
        val events = RecordingListener()
        val detector = AccidentDetector(events, validationDurationMs = 300L)

        detector.simulateImpact(timestamp = 1_000L)
        detector.tick(timestamp = 1_299L)
        assertEquals(0, events.automaticEmergencies)
        detector.tick(timestamp = 1_300L)

        assertEquals(1, events.automaticEmergencies)
        assertEquals(SafetyPhase.COUNTDOWN, detector.runtimeState().phase)
    }

    @Test
    fun movementRequiresANewContinuousImmobilityWindow() {
        val events = RecordingListener()
        val detector = AccidentDetector(events, validationDurationMs = 300L)

        detector.simulateImpact(timestamp = 1_000L)
        detector.onDynamicAcceleration(g(0.3f), 0f, 0f, timestamp = 1_100L)
        detector.onDynamicAcceleration(0f, 0f, 0f, timestamp = 1_200L)
        detector.tick(timestamp = 1_499L)
        assertEquals(0, events.automaticEmergencies)
        detector.tick(timestamp = 1_500L)

        assertEquals(1, events.automaticEmergencies)
        assertTrue(events.motionDetected)
    }

    @Test
    fun validationCandidateExpiresInsteadOfRemainingArmedForever() {
        val events = RecordingListener()
        val detector = AccidentDetector(
            listener = events,
            validationDurationMs = 300L,
            validationMaxDurationMs = 600L
        )

        detector.simulateImpact(timestamp = 1_000L)
        detector.onDynamicAcceleration(g(0.3f), 0f, 0f, timestamp = 1_100L)
        detector.tick(timestamp = 1_600L)

        assertEquals(1, events.expiredValidations)
        assertEquals(SafetyPhase.MONITORING, detector.runtimeState().phase)
    }

    @Test
    fun manualConfirmationCanBeRearmedForASecondEmergency() {
        val events = RecordingListener()
        val detector = AccidentDetector(events)

        assertTrue(detector.confirm("primer SOS"))
        detector.resumeMonitoring()
        assertTrue(detector.confirm("segundo SOS"))

        assertEquals(listOf("primer SOS", "segundo SOS"), events.manualReasons)
    }

    @Test
    fun strongDynamicMovementWakesScreenWithCooldown() {
        val events = RecordingListener()
        val detector = AccidentDetector(events)

        detector.onDynamicAcceleration(g(2.5f), 0f, 0f, timestamp = 1_000L)
        detector.onDynamicAcceleration(g(2.5f), 0f, 0f, timestamp = 5_000L)
        detector.onDynamicAcceleration(0f, 0f, 0f, timestamp = 5_100L)
        detector.onDynamicAcceleration(g(2.5f), 0f, 0f, timestamp = 11_001L)

        assertEquals(2, events.vibrations)
    }

    @Test
    fun restingGravityNeverTriggersLowestSensitivityFallback() {
        val events = RecordingListener()
        val detector = AccidentDetector(events)
        detector.setGolpeSensitivity(0.5f)

        repeat(20) { index ->
            detector.onAccelerometer(0f, 0f, AccidentDetector.STANDARD_GRAVITY, 1_000L + index * 20L)
        }

        assertTrue(events.manualReasons.isEmpty())
        assertEquals(0, events.vibrations)
    }

    @Test
    fun singleStrongDynamicMoveStartsImmediateCountdown() {
        val events = RecordingListener()
        val detector = AccidentDetector(events)
        detector.setGolpeSensitivity(3f)

        detector.onDynamicAcceleration(g(3.1f), 0f, 0f, timestamp = 1_000L)

        assertEquals(listOf("golpe fuerte detectado"), events.manualReasons)
        assertEquals(SafetyPhase.COUNTDOWN, detector.runtimeState().phase)
    }

    @Test
    fun movementBelowSensitivityDoesNotStartCountdown() {
        val events = RecordingListener()
        val detector = AccidentDetector(events)
        detector.setGolpeSensitivity(5f)

        detector.onDynamicAcceleration(g(3.5f), 0f, 0f, timestamp = 1_000L)

        assertTrue(events.manualReasons.isEmpty())
    }

    @Test
    fun strongHitIgnoresSosConditions() {
        val events = RecordingListener()
        val detector = AccidentDetector(events)
        detector.setGolpeSensitivity(3f)

        detector.onDynamicAcceleration(g(3.2f), 0f, 0f, timestamp = 1_000L)

        assertEquals(listOf("golpe fuerte detectado"), events.manualReasons)
        assertEquals(SafetyPhase.COUNTDOWN, detector.runtimeState().phase)
    }

    @Test
    fun strongHitIgnoresFallOrientation() {
        val events = RecordingListener()
        val detector = AccidentDetector(events)
        detector.setGolpeSensitivity(3f)

        detector.onDynamicAcceleration(g(3.2f), 0f, 0f, timestamp = 1_000L)

        assertEquals(listOf("golpe fuerte detectado"), events.manualReasons)
        assertEquals(SafetyPhase.COUNTDOWN, detector.runtimeState().phase)
    }

    @Test
    fun repeatedStrongSamplesDuringCountdownDoNotTriggerTwice() {
        val events = RecordingListener()
        val detector = AccidentDetector(events)
        detector.setGolpeSensitivity(3f)

        detector.onDynamicAcceleration(g(4f), 0f, 0f, timestamp = 1_000L)
        detector.onDynamicAcceleration(g(4.2f), 0f, 0f, timestamp = 1_050L)
        detector.onDynamicAcceleration(g(4.1f), 0f, 0f, timestamp = 1_100L)

        assertEquals(listOf("golpe fuerte detectado"), events.manualReasons)
        assertEquals(SafetyPhase.COUNTDOWN, detector.runtimeState().phase)
    }

    @Test
    fun exactThresholdHitStartsCountdown() {
        val events = RecordingListener()
        val detector = AccidentDetector(events)
        detector.setGolpeSensitivity(3f)

        detector.onDynamicAcceleration(g(3.0f), 0f, 0f, timestamp = 1_000L)

        assertEquals(listOf("golpe fuerte detectado"), events.manualReasons)
        assertEquals(SafetyPhase.COUNTDOWN, detector.runtimeState().phase)
    }

    @Test
    fun hitDuringValidationPromotesToCountdown() {
        val events = RecordingListener()
        val detector = AccidentDetector(events)
        detector.setGolpeSensitivity(2f)

        detector.simulateImpact(timestamp = 500L)
        assertEquals(SafetyPhase.VALIDATING, detector.runtimeState().phase)

        detector.onDynamicAcceleration(g(3f), 0f, 0f, timestamp = 1_000L)

        assertEquals(listOf("golpe fuerte detectado"), events.manualReasons)
        assertEquals(SafetyPhase.COUNTDOWN, detector.runtimeState().phase)
    }

    @Test
    fun gravitySensorInputCalibratesWithoutFirstSampleDiscard() {
        val events = RecordingListener()
        val detector = AccidentDetector(events)
        detector.setGolpeSensitivity(0.5f)

        // Simulate gravity sensor reporting tilted orientation
        detector.onGravitySensor(0f, 7f, 7f, timestamp = 100L)

        // Now send a moderate impact - should be measured against gravity sensor baseline
        val result = detector.onAccelerometer(0f, 10f, 10f, timestamp = 200L)
        assertTrue(result != null && result > 0f)
    }

    @Test
    fun outOfOrderTimestampsAreRejected() {
        val events = RecordingListener()
        val detector = AccidentDetector(events)
        detector.setGolpeSensitivity(3f)

        detector.onDynamicAcceleration(g(5f), 0f, 0f, timestamp = 2_000L)
        detector.onDynamicAcceleration(g(5f), 0f, 0f, timestamp = 1_000L) // older

        assertEquals(1, events.manualReasons.size)
    }

    @Test
    fun nanInputIsRejected() {
        val events = RecordingListener()
        val detector = AccidentDetector(events)
        detector.setGolpeSensitivity(3f)

        detector.onDynamicAcceleration(Float.NaN, 0f, 0f, timestamp = 1_000L)

        assertTrue(events.manualReasons.isEmpty())
    }

    @Test
    fun runtimeValidationCanBeRestored() {
        val events = RecordingListener()
        val detector = AccidentDetector(events, validationDurationMs = 300L)

        detector.restoreRuntime(
            AccidentDetector.RuntimeState(
                phase = SafetyPhase.VALIDATING,
                validationStartedAt = 1_000L,
                immobileSince = 1_100L
            )
        )
        detector.tick(1_400L)

        assertEquals(1, events.automaticEmergencies)
        assertNull(detector.runtimeState().validationStartedAt)
    }

    @Test
    fun singleHitWithConfirmationArmsCandidateWithoutCountdown() {
        val events = RecordingListener()
        val detector = AccidentDetector(events)
        detector.setGolpeSensitivity(3f)
        detector.setFallOrientationGateEnabled(true)

        detector.onDynamicAcceleration(g(4f), 0f, 0f, timestamp = 1_000L)

        assertEquals(SafetyPhase.FALL_CANDIDATE, detector.runtimeState().phase)
        assertTrue(events.manualReasons.isEmpty())
    }

    @Test
    fun sustainedTiltConfirmsCandidateIntoCountdown() {
        val events = RecordingListener()
        val detector = AccidentDetector(events)
        detector.setGolpeSensitivity(3f)
        detector.setFallOrientationGateEnabled(true)

        detector.onDynamicAcceleration(g(4f), 0f, 0f, timestamp = 1_000L)
        assertTrue(detector.onFallTilt(sustained = true, timestamp = 5_000L))

        assertEquals(SafetyPhase.COUNTDOWN, detector.runtimeState().phase)
        assertEquals(listOf("caida de moto confirmada"), events.manualReasons)
    }

    @Test
    fun candidateExpiresWithoutSosWhenNoTilt() {
        val events = RecordingListener()
        val detector = AccidentDetector(events)
        detector.setGolpeSensitivity(3f)
        detector.setFallOrientationGateEnabled(true)

        detector.onDynamicAcceleration(g(4f), 0f, 0f, timestamp = 1_000L)
        detector.tick(timestamp = 16_000L)

        assertEquals(SafetyPhase.MONITORING, detector.runtimeState().phase)
        assertTrue(events.manualReasons.isEmpty())
    }

    @Test
    fun deliberateShakeTwoHitsIsImmediateSos() {
        val events = RecordingListener()
        val detector = AccidentDetector(events)
        detector.setGolpeSensitivity(3f)
        detector.setFallOrientationGateEnabled(true)

        detector.onDynamicAcceleration(g(4f), 0f, 0f, timestamp = 1_000L)
        assertEquals(SafetyPhase.FALL_CANDIDATE, detector.runtimeState().phase)
        detector.onDynamicAcceleration(g(4f), 0f, 0f, timestamp = 1_400L)

        assertEquals(SafetyPhase.COUNTDOWN, detector.runtimeState().phase)
        assertEquals(listOf("sacudida de emergencia"), events.manualReasons)
    }

    @Test
    fun uprightMovementAbortsCandidate() {
        val events = RecordingListener()
        val detector = AccidentDetector(events)
        detector.setGolpeSensitivity(3f)
        detector.setFallOrientationGateEnabled(true)

        detector.onDynamicAcceleration(g(4f), 0f, 0f, timestamp = 1_000L)
        detector.abortFallCandidate()

        assertEquals(SafetyPhase.MONITORING, detector.runtimeState().phase)
        assertTrue(events.manualReasons.isEmpty())
        assertTrue(events.candidateAborted)
    }

    @Test
    fun ridingSpeedAbortsCandidate() {
        val events = RecordingListener()
        val detector = AccidentDetector(events)
        detector.setGolpeSensitivity(3f)
        detector.setFallOrientationGateEnabled(true)

        detector.onDynamicAcceleration(g(4f), 0f, 0f, timestamp = 1_000L)
        detector.onLocation(45f, timestamp = 2_000L)

        assertEquals(SafetyPhase.MONITORING, detector.runtimeState().phase)
    }

    @Test
    fun featureOffKeepsSingleHitImmediate() {
        val events = RecordingListener()
        val detector = AccidentDetector(events)
        detector.setGolpeSensitivity(3f)
        detector.setFallOrientationGateEnabled(false)

        detector.onDynamicAcceleration(g(4f), 0f, 0f, timestamp = 1_000L)

        assertEquals(SafetyPhase.COUNTDOWN, detector.runtimeState().phase)
        assertEquals(listOf("golpe fuerte detectado"), events.manualReasons)
    }

    @Test
    fun tiltOutsideCandidateWindowIsIgnored() {
        val events = RecordingListener()
        val detector = AccidentDetector(events)
        detector.setGolpeSensitivity(3f)
        detector.setFallOrientationGateEnabled(true)

        assertFalse(detector.onFallTilt(sustained = true, timestamp = 5_000L))
        assertEquals(SafetyPhase.MONITORING, detector.runtimeState().phase)
        assertTrue(events.manualReasons.isEmpty())
    }

    private fun g(value: Float): Float = value * AccidentDetector.STANDARD_GRAVITY

    private class RecordingListener : AccidentDetector.Listener {
        var validations = 0
        var expiredValidations = 0
        var automaticEmergencies = 0
        val manualReasons = mutableListOf<String>()
        var vibrations = 0
        var motionDetected = false
        var candidateAborted = false

        override fun onValidationStarted(startedAt: Long) {
            validations++
        }

        override fun onValidationExpired() {
            expiredValidations++
        }

        override fun onAutomaticEmergency() {
            automaticEmergencies++
        }

        override fun onManualEmergency(reason: String) {
            manualReasons += reason
        }

        override fun onVibrationDetected() {
            vibrations++
        }

        override fun onMotionDetected() {
            motionDetected = true
        }

        override fun onFallCandidateAborted() {
            candidateAborted = true
        }
    }
}

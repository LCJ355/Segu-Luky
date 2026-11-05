package com.intercom.segluky

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the write-amplification fix: the monitor streams sensors at ~50 Hz and
 * used to commit SharedPreferences on every sample. Only changes that matter for
 * crash recovery may request another write.
 */
class PersistedSafetyRuntimeTest {
    @Test
    fun identicalStateNeedsNoWrite() {
        val state = runtime()

        assertTrue(state.matchesMeaningfully(state))
        assertTrue(state.matchesMeaningfully(runtime()))
    }

    @Test
    fun gpsNoiseBelowOneMeterNeedsNoWrite() {
        val previous = runtime(latitude = 40.416775, longitude = -3.703790, accuracy = 8.0f)
        val jittered = runtime(
            latitude = 40.4167752,
            longitude = -3.7037898,
            accuracy = 8.2f
        )

        assertTrue(previous.matchesMeaningfully(jittered))
    }

    @Test
    fun realMovementForcesAWrite() {
        val previous = runtime(latitude = 40.416775, longitude = -3.703790)
        val moved = runtime(latitude = 40.417000, longitude = -3.703790)

        assertFalse(previous.matchesMeaningfully(moved))
    }

    @Test
    fun refreshedFixTimestampForcesAWrite() {
        val previous = runtime(latitude = 40.416775, longitude = -3.703790)
        val refreshed = previous.copy(locationWallTime = previous.locationWallTime!! + 10_000L)

        assertFalse(previous.matchesMeaningfully(refreshed))
    }

    @Test
    fun accuracyJumpForcesAWrite() {
        val previous = runtime(accuracy = 8.0f)
        val degraded = runtime(accuracy = 45.0f)

        assertFalse(previous.matchesMeaningfully(degraded))
    }

    @Test
    fun appearingAndDisappearingFixForceAWrite() {
        val withoutFix = runtime()
        val withFix = runtime(latitude = 40.416775, longitude = -3.703790)

        assertFalse(withoutFix.matchesMeaningfully(withFix))
        assertFalse(withFix.matchesMeaningfully(withoutFix))
    }

    @Test
    fun detectorPhaseAndTimersAlwaysForceAWrite() {
        val monitoring = runtime()

        assertFalse("phase", monitoring.matchesMeaningfully(monitoring.copy(phase = SafetyPhase.VALIDATING)))
        assertFalse("validationStartedAt", monitoring.matchesMeaningfully(monitoring.copy(validationStartedAt = 1_000L)))
        assertFalse("immobileSince", monitoring.matchesMeaningfully(monitoring.copy(immobileSince = 1_000L)))
        assertFalse("monitoringEnabled", monitoring.matchesMeaningfully(monitoring.copy(monitoringEnabled = false)))
    }

    @Test
    fun emergencyIdentityAlwaysForcesAWrite() {
        val idle = runtime()

        assertFalse(idle.matchesMeaningfully(idle.copy(countdownEndsAt = 5_000L)))
        assertFalse(idle.matchesMeaningfully(idle.copy(countdownEndsAtWallTime = 5_000L)))
        assertFalse(idle.matchesMeaningfully(idle.copy(countdownReason = "golpe fuerte detectado")))
        assertFalse(idle.matchesMeaningfully(idle.copy(eventId = "event-1")))
    }

    private fun runtime(
        latitude: Double? = null,
        longitude: Double? = null,
        accuracy: Float? = null
    ) = PersistedSafetyRuntime(
        monitoringEnabled = true,
        phase = SafetyPhase.MONITORING,
        latitude = latitude,
        longitude = longitude,
        locationAccuracyMeters = accuracy,
        locationWallTime = 1_700_000_000_000L
    )
}

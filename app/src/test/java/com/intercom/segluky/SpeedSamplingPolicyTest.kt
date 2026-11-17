package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedSamplingPolicyTest {
    @Test
    fun `sin velocidad solo permite hasta 50 Hz`() {
        val policy = SpeedSamplingPolicy()
        policy.update(null, 0L)

        assertEquals(SamplingTier.CRUISE, policy.cap(SamplingTier.DEMANDING, SafetyPhase.MONITORING))
    }

    @Test
    fun `a 10 kmh o menos limita a 50 Hz tras cinco segundos`() {
        val policy = SpeedSamplingPolicy()
        policy.update(8f, 0L)
        assertEquals(SpeedBand.UNKNOWN, policy.band)

        policy.update(8f, 5_000L)
        assertEquals(SpeedBand.SLOW, policy.band)
        assertEquals(SamplingTier.CRUISE, policy.cap(SamplingTier.DEMANDING, SafetyPhase.MONITORING))
    }

    @Test
    fun `a 15 kmh o mas permite 100 Hz tras cinco segundos`() {
        val policy = SpeedSamplingPolicy()
        policy.update(20f, 0L)
        assertEquals(SpeedBand.UNKNOWN, policy.band)

        policy.update(20f, 5_000L)
        assertEquals(SpeedBand.MOVING, policy.band)
        assertEquals(SamplingTier.DEMANDING, policy.cap(SamplingTier.DEMANDING, SafetyPhase.MONITORING))
    }

    @Test
    fun `la banda intermedia 10-15 kmh conserva el estado anterior`() {
        val policy = SpeedSamplingPolicy()
        policy.update(20f, 0L)
        policy.update(20f, 5_000L)
        assertEquals(SpeedBand.MOVING, policy.band)

        policy.update(12f, 6_000L)
        assertEquals(SpeedBand.MOVING, policy.band)
    }

    @Test
    fun `durante candidato o validacion no se limita el muestreo`() {
        val policy = SpeedSamplingPolicy()
        policy.update(8f, 0L)
        policy.update(8f, 5_000L)

        assertEquals(
            SamplingTier.DEMANDING,
            policy.cap(SamplingTier.DEMANDING, SafetyPhase.VALIDATING)
        )
    }
}

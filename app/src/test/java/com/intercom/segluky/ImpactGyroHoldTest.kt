package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Test

class ImpactGyroHoldTest {

    @Test
    fun `sin lectura el giro es cero`() {
        assertEquals(0f, ImpactGyroHold().valueAt(1_000L), 0.001f)
    }

    @Test
    fun `una lectura reciente acompana a la muestra siguiente`() {
        val hold = ImpactGyroHold()
        hold.update(1_000L, 0f, 0f, 120f)
        assertEquals(120f, hold.valueAt(1_020L), 0.5f)
    }

    @Test
    fun `una lectura caducada no se atribuye al golpe siguiente`() {
        val hold = ImpactGyroHold()
        hold.update(1_000L, 0f, 0f, 120f)
        assertEquals(0f, hold.valueAt(1_000L + ImpactGyroHold.MAX_AGE_MS + 1L), 0.001f)
    }

    @Test
    fun `la magnitud combina los tres ejes`() {
        val hold = ImpactGyroHold()
        assertEquals(5f, hold.update(1_000L, 3f, 4f, 0f), 0.001f)
    }

    @Test
    fun `reset descarta la lectura pendiente`() {
        val hold = ImpactGyroHold()
        hold.update(1_000L, 0f, 0f, 120f)
        hold.reset()
        assertEquals(0f, hold.valueAt(1_001L), 0.001f)
    }

    @Test
    fun `la confianza de orientacion solo es plena con actitud calibrada y fiable`() {
        assertEquals(0.2f, ImpactSequence.orientationConfidence(calibrated = false), 0.001f)
        assertEquals(1f, ImpactSequence.orientationConfidence(calibrated = true), 0.001f)
        assertEquals(
            0.4f,
            ImpactSequence.orientationConfidence(calibrated = true, accuracyReliable = false),
            0.001f
        )
    }
}

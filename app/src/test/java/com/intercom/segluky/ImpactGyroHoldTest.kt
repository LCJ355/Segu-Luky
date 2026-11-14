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

    @Test
    fun `la calidad del giro empieza en uno con lectura fresca`() {
        val hold = ImpactGyroHold()
        hold.update(1_000L, 0f, 0f, 120f)
        assertEquals(1f, hold.qualityAt(1_000L), 0.001f)
        assertEquals(1f, hold.qualityAt(1_000L + ImpactGyroHold.FRESH_MS), 0.001f)
    }

    @Test
    fun `la calidad decae entre fresca y caducada`() {
        val hold = ImpactGyroHold()
        hold.update(1_000L, 0f, 0f, 120f)
        val media = hold.qualityAt(1_000L + (ImpactGyroHold.FRESH_MS + ImpactGyroHold.MAX_AGE_MS) / 2)
        assertEquals(0.5f, media, 0.05f)
    }

    @Test
    fun `sin lectura vigente la calidad es cero`() {
        val hold = ImpactGyroHold()
        assertEquals(0f, hold.qualityAt(1_000L), 0.001f)
        hold.update(1_000L, 0f, 0f, 120f)
        assertEquals(0f, hold.qualityAt(1_000L + ImpactGyroHold.MAX_AGE_MS + 1L), 0.001f)
    }

    @Test
    fun `un hueco de entrega baja la calidad a la mitad`() {
        val hold = ImpactGyroHold()
        hold.update(1_000L, 0f, 0f, 120f)
        hold.update(1_000L + ImpactGyroHold.GAP_MS + 50L, 0f, 0f, 120f)
        assertEquals(0.5f, hold.qualityAt(1_000L + ImpactGyroHold.GAP_MS + 50L), 0.01f)
    }

    @Test
    fun `reset deja la calidad a cero`() {
        val hold = ImpactGyroHold()
        hold.update(1_000L, 0f, 0f, 120f)
        hold.reset()
        assertEquals(0f, hold.qualityAt(1_001L), 0.001f)
    }
}

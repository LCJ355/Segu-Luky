package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpactWidthMeterTest {

    @Test
    fun `un pico corto mide pocos milisegundos`() {
        val meter = ImpactWidthMeter(enterG = 3.5f)
        assertNull(meter.add(1_000L, 6f))
        assertNull(meter.add(1_010L, 1f))
        assertNull(meter.add(1_020L, 1f))
        val width = meter.add(1_030L, 1f)
        // un unico pico por encima del umbral: el ancho es 0 ms, no inventa duracion
        assertEquals(0L, width!!.widthMs)
        assertEquals(1_030L, width.closedAtMs)
        assertFalse(width.saturated)
    }

    @Test
    fun `un impacto sostenido mide su ancho completo`() {
        val meter = ImpactWidthMeter(enterG = 3.5f)
        assertNull(meter.add(1_000L, 6f))
        for (t in 1_010L..1_200L step 10L) assertNull(meter.add(t, 6f))
        val width = meter.add(1_230L, 1f)
        assertEquals(200L, width!!.widthMs)
    }

    @Test
    fun `un valle breve no parte el impacto`() {
        val meter = ImpactWidthMeter(enterG = 3.5f)
        assertNull(meter.add(1_000L, 6f))
        assertNull(meter.add(1_010L, 1f))
        assertNull(meter.add(1_020L, 6f))
        assertNull(meter.add(1_030L, 6f))
        val width = meter.add(1_100L, 1f)
        assertEquals(30L, width!!.widthMs)
        assertEquals(2, width.peaks)
    }

    @Test
    fun `detecta la saturacion del sensor`() {
        val meter = ImpactWidthMeter(enterG = 3.5f)
        assertNull(meter.add(1_000L, 16f))
        assertNull(meter.add(1_010L, 16f))
        val width = meter.add(1_050L, 1f)
        assertTrue(width!!.saturated)
    }

    @Test
    fun `reset deja el medidor listo para otro golpe`() {
        val meter = ImpactWidthMeter(enterG = 3.5f)
        assertNull(meter.add(1_000L, 6f))
        assertEquals(1_040L, meter.add(1_040L, 1f)!!.closedAtMs)
        meter.reset()
        assertNull(meter.add(2_000L, 1f))
        assertNull(meter.add(2_010L, 6f))
        val width = meter.add(2_040L, 1f)
        assertEquals(2_040L, width!!.closedAtMs)
        assertEquals(0L, width.widthMs)
    }
}

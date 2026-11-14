package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdParserTest {

    @Test
    fun `velocidad con espacios y sin ellos`() {
        assertEquals(50f, ObdParser.speedKmh("41 0D 32\r\r>")!!, 0.001f)
        assertEquals(50f, ObdParser.speedKmh("410D32")!!, 0.001f)
        assertEquals(0f, ObdParser.speedKmh("41 0D 00")!!, 0.001f)
        assertEquals(255f, ObdParser.speedKmh("41 0D FF")!!, 0.001f)
    }

    @Test
    fun `velocidad con eco del comando delante`() {
        // Con ATE0 no hay eco, pero un clon que lo ignore no debe romper el parser.
        assertEquals(80f, ObdParser.speedKmh("010D\r41 0D 50\r\r>")!!, 0.001f)
    }

    @Test
    fun `sin datos no devuelve velocidad`() {
        assertNull(ObdParser.speedKmh("NO DATA\r\r>"))
        assertNull(ObdParser.speedKmh("SEARCHING...\rNO DATA\r\r>"))
        assertNull(ObdParser.speedKmh("?"))
        assertNull(ObdParser.speedKmh("UNABLE TO CONNECT"))
        assertNull(ObdParser.speedKmh(""))
    }

    @Test
    fun `rpm divide entre cuatro`() {
        // 1AF8 = 6904; 6904/4 = 1726 rpm
        assertEquals(1726, ObdParser.rpm("41 0C 1A F8"))
        assertEquals(0, ObdParser.rpm("410C0000"))
        assertNull(ObdParser.rpm("41 0C 1A"))
    }

    @Test
    fun `acelerador y refrigerante`() {
        assertEquals(0, ObdParser.throttlePercent("41 11 00"))
        assertEquals(100, ObdParser.throttlePercent("41 11 FF"))
        assertEquals(50, ObdParser.throttlePercent("41 11 80"))
        assertEquals(90, ObdParser.coolantC("41 05 82"))
        assertNull(ObdParser.coolantC("NO DATA"))
    }

    @Test
    fun `codigos de averia del byte de monitores`() {
        assertEquals(0, ObdParser.dtcCount("41 01 00 07 65 00"))
        assertEquals(2, ObdParser.dtcCount("41 01 82 07 65 00"))
        assertNull(ObdParser.dtcCount("41 01"))
    }

    @Test
    fun `protocolo negociado`() {
        assertEquals("ISO 15765-4 CAN 11/500", ObdParser.protocol("A6\r\r>"))
        assertEquals("ISO 15765-4 CAN 11/500", ObdParser.protocol("6"))
        assertEquals("ISO 15765-4 CAN 29/500", ObdParser.protocol("A7"))
        assertEquals("ISO 15765-4 CAN 11/250", ObdParser.protocol("A8"))
        assertEquals("SAE J1850 VPW", ObdParser.protocol("2"))
        assertEquals("ISO 9141-2", ObdParser.protocol("3"))
        assertEquals("AUTO", ObdParser.protocol("A0"))
        assertNull(ObdParser.protocol("UNABLE TO CONNECT"))
    }

    @Test
    fun `tension de la bateria`() {
        assertEquals(12.6f, ObdParser.voltage("12.6V\r\r>")!!, 0.001f)
        assertEquals(11.9f, ObdParser.voltage("11,9 V")!!, 0.001f)
        assertNull(ObdParser.voltage("NO DATA"))
    }

    @Test
    fun `secuencia de arranque del ELM327`() {
        assertEquals(ObdPids.init, listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATSP0"))
        assertTrue(ObdPids.init.first() == "ATZ")
        assertTrue(ObdPids.init.contains("ATSP0"))
    }

    @Test
    fun `el estado solo da velocidad reciente`() {
        val fresh = ObdState(speedKmh = 60f, updatedAtMs = 1_000L)
        assertEquals(60f, fresh.freshSpeed(2_500L)!!, 0.001f)
        assertNull(fresh.freshSpeed(1_000L + ObdState.SPEED_MAX_AGE_MS + 1))
        assertNull(ObdState().freshSpeed(5_000L))
    }
}

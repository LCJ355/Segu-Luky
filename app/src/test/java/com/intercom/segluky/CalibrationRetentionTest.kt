package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationRetentionTest {

    private fun stats(
        rows: Long = 300_000,
        speedRows: Long = 300_000,
        bands: Int = 5,
        hz: Float = 98f,
        target: Float = 100f,
        rateChanges: Int = 40,
        jerk: Int = 80
    ) = CalibrationSegmentStats(rows, speedRows, bands, hz, target, rateChanges, jerk)

    @Test
    fun `un bloque completo y variado puntua alto`() {
        assertTrue(CalibrationRetention.score(stats()) >= 95)
    }

    @Test
    fun `un bloque sin velocidad vale mucho menos`() {
        val completo = CalibrationRetention.score(stats())
        val sinVelocidad = CalibrationRetention.score(stats(speedRows = 0, bands = 0, jerk = 0, rateChanges = 0))
        assertTrue(completo > sinVelocidad + 40)
    }

    @Test
    fun `un bloque vacio no puntua`() {
        assertEquals(0, CalibrationRetention.score(CalibrationSegmentStats()))
        assertEquals(0, CalibrationRetention.score(stats(rows = 0)))
    }

    @Test
    fun `solo sustituye si mejora`() {
        val nuevo = stats()
        assertFalse(CalibrationRetention.replaces(nuevo, 80, 80))
        assertFalse(CalibrationRetention.replaces(nuevo, 79, 80))
        assertTrue(CalibrationRetention.replaces(nuevo, 81, 80))
        assertFalse(CalibrationRetention.replaces(stats(rows = 0), 99, 10))
    }

    @Test
    fun `las bandas de velocidad`() {
        assertEquals(0, CalibrationRetention.band(0f))
        assertEquals(0, CalibrationRetention.band(12f))
        assertEquals(1, CalibrationRetention.band(12.5f))
        assertEquals(1, CalibrationRetention.band(24f))
        assertEquals(2, CalibrationRetention.band(25f))
        assertEquals(3, CalibrationRetention.band(40f))
        assertEquals(4, CalibrationRetention.band(50f))
        assertEquals(4, CalibrationRetention.band(140f))
    }

    @Test
    fun `los topes de seguridad`() {
        assertEquals(15 * 60 * 1000L, CalibrationRetention.SEGMENT_MS)
        assertEquals(4 * 60 * 60 * 1000L, CalibrationRetention.RECORDING_MAX_MS)
    }
}

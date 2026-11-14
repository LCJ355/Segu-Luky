package com.intercom.segluky

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImpactSampleRecorderTest {

    @Before
    fun limpiarAntes() = ImpactSampleRecorder.clear()

    @After
    fun limpiarDespues() = ImpactSampleRecorder.clear()

    private fun llenar(hz: Int, segundos: Int, hastaMs: Long = 0L): Long {
        val total = hz * segundos
        val paso = 1_000.0 / hz
        var atMs = hastaMs
        for (i in 0 until total) {
            atMs = (i * paso).toLong() + hastaMs
            ImpactSampleRecorder.record(atMs, 1f)
        }
        return atMs
    }

    @Test
    fun `a 148 Hz se conservan los 15 s de la ventana de recuperacion`() {
        llenar(hz = 148, segundos = 20)
        assertTrue(
            "retenido ${ImpactSampleRecorder.retainedMs()} ms",
            ImpactSampleRecorder.retainedMs() >= 15_000L
        )
    }

    @Test
    fun `a 50 y 100 Hz tambien se conserva la ventana completa`() {
        for (hz in listOf(50, 100)) {
            ImpactSampleRecorder.clear()
            llenar(hz = hz, segundos = 30)
            assertTrue(
                "a $hz Hz retenido ${ImpactSampleRecorder.retainedMs()} ms",
                ImpactSampleRecorder.retainedMs() >= 15_000L
            )
        }
    }

    @Test
    fun `el buffer nunca pasa de 20 s aunque haya muchas muestras`() {
        llenar(hz = 200, segundos = 40)
        assertTrue(ImpactSampleRecorder.retainedMs() <= ImpactSampleRecorder.WINDOW_MS)
        assertTrue(ImpactSampleRecorder.retainedMs() >= 19_000L)
    }

    @Test
    fun `las muestras guardadas son las mas recientes`() {
        llenar(hz = 100, segundos = 30)
        val ultima = ImpactSampleRecorder.lastAtMs()
        val ventana = ImpactSampleRecorder.snapshot()
        assertTrue(ventana.isNotEmpty())
        assertEquals(ultima, ventana.last().atMs)
        assertTrue(ventana.first().atMs >= ultima - ImpactSampleRecorder.WINDOW_MS)
    }

    @Test
    fun `sin muestras no hay ventana`() {
        assertEquals(0, ImpactSampleRecorder.count())
        assertEquals(0L, ImpactSampleRecorder.retainedMs())
        assertEquals(0L, ImpactSampleRecorder.lastAtMs())
        assertTrue(ImpactSampleRecorder.snapshot().isEmpty())
    }
}

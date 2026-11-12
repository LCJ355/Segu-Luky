package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensitivityAdvisorTest {
    @Test
    fun redondeaAPasosDeMedioGConMargen() {
        // 3,0 x 1,20 = 3,6 -> 3,5
        val suggestion = SensitivityAdvisor.suggest(
            peakG = 3.0f,
            currentG = 3.5f,
            rideMs = 5 * 60_000L
        )

        assertEquals(3.5f, suggestion!!.recommendedG, 0.001f)
        assertEquals(SensitivityAdvisor.Verdict.CORRECT, suggestion.verdict)
    }

    @Test
    fun nuncaRecomiendaMenosDeMedioYMedio() {
        val suggestion = SensitivityAdvisor.suggest(
            peakG = 0.6f,
            currentG = 1.5f,
            rideMs = 5 * 60_000L
        )

        assertEquals(1.5f, suggestion!!.recommendedG, 0.001f)
    }

    @Test
    fun nuncaSuperaLaSensibilidadMaxima() {
        val suggestion = SensitivityAdvisor.suggest(
            peakG = 8f,
            currentG = 3.5f,
            rideMs = 5 * 60_000L
        )

        assertEquals(AccidentDetector.MAX_SENSITIVITY_G, suggestion!!.recommendedG, 0.001f)
        assertEquals(SensitivityAdvisor.Verdict.RAISE, suggestion.verdict)
    }

    @Test
    fun sinPicoOSinTiempoNoHaySugerencia() {
        assertNull(
            SensitivityAdvisor.suggest(peakG = null, currentG = 3.5f, rideMs = 5 * 60_000L)
        )
        assertNull(
            SensitivityAdvisor.suggest(peakG = 0.2f, currentG = 3.5f, rideMs = 5 * 60_000L)
        )
        assertNull(
            SensitivityAdvisor.suggest(peakG = 4f, currentG = 3.5f, rideMs = 30_000L)
        )
    }

    @Test
    fun laCompensacionPorFrecuenciaSubeElRecomendado() {
        // Un movil que entrega 49 Hz mide el pico mas bajo: 3,0 x 1,20 / 0,70 = 5,14 -> 5,0
        val suggestion = SensitivityAdvisor.suggest(
            peakG = 3.0f,
            rateFactor = 0.70f,
            currentG = 3.5f,
            rideMs = 5 * 60_000L
        )

        assertEquals(5.0f, suggestion!!.recommendedG, 0.001f)
    }

    @Test
    fun elPerfilAprendidoPuedeSubirLaSugerencia() {
        val suggestion = SensitivityAdvisor.suggest(
            peakG = 1.0f,
            profileG = 2.3f,
            currentG = 1.5f,
            rideMs = 5 * 60_000L
        )

        assertEquals(2.5f, suggestion!!.recommendedG, 0.001f)
        assertEquals(SensitivityAdvisor.Verdict.RAISE, suggestion.verdict)
    }

    @Test
    fun elVeredictoBajaCuandoElAjusteEsAlto() {
        val suggestion = SensitivityAdvisor.suggest(
            peakG = 1.5f,
            currentG = 5.0f,
            rideMs = 5 * 60_000L
        )

        assertEquals(SensitivityAdvisor.Verdict.LOWER, suggestion!!.verdict)
    }
}

package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveSensitivityTest {
    @Test
    fun sinDatosElUmbralEfectivoEsElAjuste() {
        val adaptive = AdaptiveSensitivity(mode = AdaptationWindowMode.ONE)
        adaptive.setUserSensitivity(3.5f)

        assertEquals(3.5f, adaptive.effectiveThresholdG, 0.001f)
        assertEquals(0f, adaptive.snapshot().floorG, 0.001f)
    }

    @Test
    fun unMovilRuidosoSubeElSueloPorEncimaDelAjuste() {
        val adaptive = AdaptiveSensitivity(mode = AdaptationWindowMode.ONE)
        adaptive.setUserSensitivity(1.5f)

        feed(adaptive, fromMs = 0L, toMs = 40_000L, stepMs = 10L, g = 0.45f)

        // 0,45 g de ruido x 5 = 2,25 g de suelo; el ajuste de 1,5 g ya no manda.
        assertEquals(2.25f, adaptive.effectiveThresholdG, 0.15f)
    }

    @Test
    fun dosMovilesDistintosDanEfectivosDistintosConElMismoAjuste() {
        val ruidoso = AdaptiveSensitivity(mode = AdaptationWindowMode.ONE)
        val limpio = AdaptiveSensitivity(mode = AdaptationWindowMode.ONE)
        ruidoso.setUserSensitivity(1.5f)
        limpio.setUserSensitivity(1.5f)

        feed(ruidoso, 0L, 40_000L, 10L, 0.45f)
        feed(limpio, 0L, 40_000L, 10L, 0.02f)

        assertTrue(ruidoso.effectiveThresholdG > limpio.effectiveThresholdG + 0.5f)
        assertEquals(1.5f, limpio.effectiveThresholdG, 0.05f)
    }

    @Test
    fun elRuidoNuncaBajaElAjusteDelUsuario() {
        val adaptive = AdaptiveSensitivity(mode = AdaptationWindowMode.ONE)
        adaptive.setUserSensitivity(3.5f)

        feed(adaptive, 0L, 40_000L, 10L, 0.02f)

        assertEquals(3.5f, adaptive.effectiveThresholdG, 0.01f)
    }

    @Test
    fun laCompensacionPorFrecuenciaBajaElUmbral() {
        val adaptive = AdaptiveSensitivity(mode = AdaptationWindowMode.ONE)
        adaptive.setUserSensitivity(3.5f)

        // 50 Hz reales: los picos se miden mas bajos, asi que el umbral se compensa.
        feed(adaptive, 0L, 40_000L, 20L, 0.3f)

        assertTrue(adaptive.effectiveThresholdG < 3.5f)
        assertTrue(adaptive.effectiveThresholdG >= 3.5f * 0.70f - 0.01f)
    }

    @Test
    fun losGolpesNoEntranEnElPromedio() {
        val adaptive = AdaptiveSensitivity(mode = AdaptationWindowMode.ONE)
        adaptive.setUserSensitivity(3.5f)

        feed(adaptive, 0L, 20_000L, 10L, 0.05f)
        adaptive.record(9f, 20_100L)
        feed(adaptive, 20_200L, 40_000L, 10L, 0.05f)

        assertTrue(adaptive.snapshot().p99G < 0.5f)
        assertEquals(3.5f, adaptive.effectiveThresholdG, 0.01f)
    }

    @Test
    fun elPerfilAprendidoSoloMandaTrasCincoMinutosDeUso() {
        val perfil = DeviceProfile(noiseG = 1.0f, p99G = 0.2f, deliveredHz = 100f, usageMs = 4 * 60_000L)
        val adaptive = AdaptiveSensitivity(mode = AdaptationWindowMode.ONE, initialProfile = perfil)
        adaptive.setUserSensitivity(3.5f)

        assertEquals(3.5f, adaptive.effectiveThresholdG, 0.01f)

        val maduro = AdaptiveSensitivity(
            mode = AdaptationWindowMode.ONE,
            initialProfile = perfil.copy(usageMs = 6 * 60_000L)
        )
        maduro.setUserSensitivity(3.5f)

        assertEquals(5.0f, maduro.effectiveThresholdG, 0.05f)
    }

    @Test
    fun losTopesEvitanQueUnPerfilMaloEnsalgaElMovil() {
        val perfil = DeviceProfile(noiseG = 2.0f, p99G = 3.0f, deliveredHz = 100f, usageMs = 60 * 60_000L)
        val adaptive = AdaptiveSensitivity(mode = AdaptationWindowMode.ONE, initialProfile = perfil)
        adaptive.setUserSensitivity(1.5f)

        // Suelo de 10 g, pero el tope es 1,6 x 1,5 = 2,4 g.
        assertEquals(2.4f, adaptive.effectiveThresholdG, 0.05f)
    }

    @Test
    fun laVentanaSeFundeEnElPerfilAprendido() {
        val adaptive = AdaptiveSensitivity(mode = AdaptationWindowMode.ONE)
        adaptive.setUserSensitivity(2f)
        feed(adaptive, 0L, 40_000L, 10L, 0.20f)

        val perfil = adaptive.mergeIntoProfile()

        assertTrue(perfil.usageMs >= 30_000L)
        assertTrue(perfil.noiseG > 0.05f)
        assertEquals(100f, perfil.deliveredHz, 15f)
    }

    @Test
    fun reiniciarElAprendizajeVuelveAlAjuste() {
        val perfil = DeviceProfile(noiseG = 1.0f, p99G = 0.5f, deliveredHz = 100f, usageMs = 60 * 60_000L)
        val adaptive = AdaptiveSensitivity(mode = AdaptationWindowMode.ONE, initialProfile = perfil)
        adaptive.setUserSensitivity(2f)
        assertTrue(adaptive.effectiveThresholdG > 2f)

        adaptive.resetProfile(1_000L)

        assertEquals(2f, adaptive.effectiveThresholdG, 0.01f)
        assertEquals(0L, adaptive.snapshot().usageMs)
    }

    @Test
    fun laVentanaAutoSeAcortaCuandoElCaminoCambia() {
        val adaptive = AdaptiveSensitivity(mode = AdaptationWindowMode.AUTO)
        adaptive.setUserSensitivity(3.5f)

        feed(adaptive, 0L, 60_000L, 10L, 1.0f)
        feed(adaptive, 60_010L, 121_000L, 10L, 3.0f)

        assertEquals(1, adaptive.activeMinutes)
        assertTrue(adaptive.snapshot().auto)
    }

    @Test
    fun laVentanaAutoSeAlargaTrasDosVentanasEstables() {
        val adaptive = AdaptiveSensitivity(mode = AdaptationWindowMode.AUTO)
        adaptive.setUserSensitivity(3.5f)

        feed(adaptive, 0L, 200_000L, 10L, 1.0f)

        assertEquals(3, adaptive.activeMinutes)
    }

    @Test
    fun elModoManualNoCambiaSolo() {
        val adaptive = AdaptiveSensitivity(mode = AdaptationWindowMode.ONE)
        adaptive.setUserSensitivity(3.5f)

        feed(adaptive, 0L, 60_000L, 10L, 1.0f)
        feed(adaptive, 60_010L, 130_000L, 10L, 3.0f)

        assertEquals(1, adaptive.activeMinutes)
        assertFalse(adaptive.snapshot().auto)
    }

    @Test
    fun unGolpeSubeLaFrecuenciaAlInstante() {
        val adaptive = AdaptiveSensitivity(mode = AdaptationWindowMode.ONE)
        feed(adaptive, 0L, 5_000L, 10L, 0.05f)

        adaptive.onEvent(6_000L)

        assertTrue(adaptive.shouldRebind(6_100L))
        assertEquals(SamplingTier.DEMANDING, adaptive.desiredTier(6_100L))
        adaptive.onTierApplied(6_100L)
        assertEquals(SamplingTier.DEMANDING, adaptive.currentTier)
    }

    @Test
    fun elMovilQuietoBajaLaFrecuencia() {
        val adaptive = AdaptiveSensitivity(mode = AdaptationWindowMode.ONE)
        feed(adaptive, 0L, 45_000L, 10L, 0.02f)

        assertTrue(adaptive.shouldRebind(46_000L))
        assertEquals(SamplingTier.QUIET, adaptive.desiredTier(46_000L))
    }

    @Test
    fun laPermanenciaMinimaEvitaCambiosSeguidos() {
        val adaptive = AdaptiveSensitivity(mode = AdaptationWindowMode.ONE)
        feed(adaptive, 0L, 45_000L, 10L, 0.02f)
        adaptive.onTierApplied(46_000L)

        adaptive.onEvent(47_000L)

        assertFalse(adaptive.shouldRebind(47_100L))
    }

    private fun feed(
        adaptive: AdaptiveSensitivity,
        fromMs: Long,
        toMs: Long,
        stepMs: Long,
        g: Float
    ) {
        var timestamp = fromMs
        while (timestamp <= toMs) {
            adaptive.record(g, timestamp)
            timestamp += stepMs
        }
    }

    @Test
    fun elResumenNoInventaFrecuenciaSinMuestras() {
        val adaptive = AdaptiveSensitivity(mode = AdaptationWindowMode.ONE)
        adaptive.setUserSensitivity(3f)

        val snapshot = adaptive.snapshot()

        assertEquals(0f, snapshot.deliveredHz, 0.001f)
        assertEquals(3f, snapshot.effectiveThresholdG, 0.01f)
        assertEquals(0L, snapshot.usageMs)
    }
}

package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La espera creciente tiene que caber dentro de la ventana de 10 min que ya
 * vigila el despacho: si los reintentos la superan, el ultimo no llega a
 * intentarse nunca.
 */
class TelegramBotRetryTest {
    @Test
    fun laEsperaCreceConCadaFallo() {
        assertEquals(0L, TelegramBotRetry.delayAfter(0))
        assertEquals(20_000L, TelegramBotRetry.delayAfter(1))
        assertEquals(60_000L, TelegramBotRetry.delayAfter(2))
        assertEquals(120_000L, TelegramBotRetry.delayAfter(3))
        assertEquals(300_000L, TelegramBotRetry.delayAfter(4))
        assertNull(TelegramBotRetry.delayAfter(5))
        assertNull(TelegramBotRetry.delayAfter(9))
    }

    @Test
    fun losReintentosCabenEnLaVentanaDelDespacho() {
        val ventana = 10 * 60_000L
        val total = (1 until TelegramBotRetry.MAX_ATTEMPTS)
            .sumOf { TelegramBotRetry.delayAfter(it) ?: 0L }
        assertTrue("la suma de esperas ($total ms) debe caber en la ventana", total < ventana)
    }

    @Test
    fun dejaDeReintentarAlAgotarLosIntentos() {
        assertTrue(TelegramBotRetry.canRetry(1))
        assertTrue(TelegramBotRetry.canRetry(4))
        assertFalse(TelegramBotRetry.canRetry(5))
        assertFalse(TelegramBotRetry.canRetry(6))
    }

    @Test
    fun elEstadoSoloTerminaSinPendientesNiAgotados() {
        assertTrue(TelegramBotStatus(total = 3, delivered = 3, pending = 0, exhausted = 0, failures = "").finished)
        assertFalse(TelegramBotStatus(total = 3, delivered = 1, pending = 2, exhausted = 0, failures = "").finished)
        assertFalse(TelegramBotStatus(total = 3, delivered = 2, pending = 0, exhausted = 1, failures = "1 (HTTP 502)").finished)
    }

    @Test
    fun distingueFallosDefinitivosDeLosQueSeReintentan() {
        // Token revocado, chat inexistente o el contacto no inicio el bot: esperar
        // no arregla nada y gastaria la ventana del despacho.
        assertTrue(TelegramBotRetry.isPermanent("HTTP 403"))
        assertTrue(TelegramBotRetry.isPermanent("Unauthorized"))
        assertTrue(TelegramBotRetry.isPermanent("chat not found"))
        assertTrue(TelegramBotRetry.isPermanent("token del bot no valido"))
        // Fallos de red o del servidor: si merece la pena reintentar.
        assertFalse(TelegramBotRetry.isPermanent("HTTP 502"))
        assertFalse(TelegramBotRetry.isPermanent("timeout"))
        assertFalse(TelegramBotRetry.isPermanent("sin respuesta"))
        assertFalse(TelegramBotRetry.isPermanent(""))
    }
}

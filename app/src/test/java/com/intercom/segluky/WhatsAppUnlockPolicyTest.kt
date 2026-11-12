package com.intercom.segluky

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El trampoline del desbloqueo no puede abrir WhatsApp cuando el usuario cancela:
 * seria hacer justo lo que acaba de rechazar y dejaria el mensaje de emergencia
 * encima de la pantalla de bloqueo.
 */
class WhatsAppUnlockPolicyTest {
    @Test
    fun conElBloqueoDesbloqueadoSeAbreElChat() {
        assertTrue(WhatsAppUnlockPolicy.launchTarget(KeyguardOutcome.DISMISSED))
    }

    @Test
    fun unFalloDelSistemaNoEsUnaDecisionDelUsuario() {
        assertTrue(WhatsAppUnlockPolicy.launchTarget(KeyguardOutcome.FAILED))
    }

    @Test
    fun cancelarNoAbreElChat() {
        assertFalse(WhatsAppUnlockPolicy.launchTarget(KeyguardOutcome.CANCELLED))
    }

    @Test
    fun laCancelacionDejaMotivoEnElRegistro() {
        assertTrue(WhatsAppUnlockPolicy.CANCELLED_DETAIL.isNotBlank())
    }
}

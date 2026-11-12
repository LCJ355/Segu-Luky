package com.intercom.segluky

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EscalationPolicyTest {
    @Test
    fun shortOffhookCallIsNotAnswered() {
        assertFalse(EscalationPolicy.isAnswered(offhookAtElapsed = 1_000L, idleAtElapsed = 2_500L))
    }

    @Test
    fun connectedCallIsAnsweredAfterMinimumDuration() {
        assertTrue(EscalationPolicy.isAnswered(offhookAtElapsed = 1_000L, idleAtElapsed = 4_000L))
    }

    @Test
    fun callWithoutOffhookIsNeverAnswered() {
        assertFalse(EscalationPolicy.isAnswered(offhookAtElapsed = null, idleAtElapsed = 90_000L))
    }

    @Test
    fun activeCallIsProtectedUntilTheWaitBudgetExpires() {
        val offhookAt = 1_000L
        assertTrue(EscalationPolicy.shouldWaitForCallEnd(offhookAt, offhookAt + 60_000L))
        assertFalse(
            EscalationPolicy.shouldWaitForCallEnd(
                offhookAt,
                offhookAt + EscalationPolicy.MAX_ACTIVE_CALL_MS
            )
        )
    }

    @Test
    fun longConnectedCallClosesTheRound() {
        val offhookAt = 1_000L

        assertFalse(EscalationPolicy.answeredByLongCall(offhookAt, offhookAt + 60_000L))
        assertTrue(
            EscalationPolicy.answeredByLongCall(
                offhookAt,
                offhookAt + EscalationPolicy.MAX_ACTIVE_CALL_MS
            )
        )
    }

    @Test
    fun callWithoutOffhookIsNeverALongCall() {
        assertFalse(EscalationPolicy.answeredByLongCall(null, 10 * 60_000L))
    }

    @Test
    fun shortConnectedCallDoesNotCloseTheRound() {
        // Un timbrado de 20 s y un buzon superan ANSWERED_MIN_CALL_MS: la ronda
        // debe continuar con el siguiente contacto, sin terminar el protocolo.
        val offhookAt = 5_000L
        val idleAt = offhookAt + 20_000L

        assertTrue(EscalationPolicy.isAnswered(offhookAt, idleAt))
        assertTrue(EscalationPolicy.shouldWaitForCallEnd(offhookAt, idleAt))
        assertFalse(EscalationPolicy.answeredByLongCall(offhookAt, idleAt))
    }

    @Test
    fun staleIdleRightAfterDialingDoesNotSkipTheContact() {
        // El IDLE de la llamada anterior puede llegar justo despues de marcar el
        // siguiente contacto: tratarlo como fallo saltaria ese contacto.
        val placedAt = 10_000L

        assertTrue(EscalationPolicy.idleArrivedWhileDialing(placedAt, placedAt + 500L))
        assertFalse(
            EscalationPolicy.idleArrivedWhileDialing(
                placedAt,
                placedAt + EscalationPolicy.CALL_DIAL_SETTLE_MS
            )
        )
    }

    @Test
    fun idleWithoutAnyDialIsNotADialInProgress() {
        assertFalse(EscalationPolicy.idleArrivedWhileDialing(null, 10_000L))
    }
}

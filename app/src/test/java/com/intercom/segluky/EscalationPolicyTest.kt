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
}

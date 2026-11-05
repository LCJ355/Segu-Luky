package com.intercom.segluky

/**
 * Pure decisions for the ordered call escalation. Android does not expose a
 * reliable "remote party answered" signal through PhoneStateListener for
 * outgoing calls (OFFHOOK also covers dialing and ringing), so an answered
 * call is inferred from a connected call that lasts at least
 * [ANSWERED_MIN_CALL_MS]. A long-running call is never interrupted; the next
 * contact is only attempted once the current call ends or the absolute wait
 * budget is exhausted.
 */
internal object EscalationPolicy {
    const val ANSWERED_MIN_CALL_MS = 3_000L
    const val MAX_ACTIVE_CALL_MS = 5 * 60_000L

    fun isAnswered(offhookAtElapsed: Long?, idleAtElapsed: Long): Boolean =
        offhookAtElapsed != null && idleAtElapsed >= offhookAtElapsed + ANSWERED_MIN_CALL_MS

    /** True while an off-hook call is too young to be force-ended. */
    fun shouldWaitForCallEnd(offhookAtElapsed: Long?, nowElapsed: Long): Boolean =
        offhookAtElapsed != null && nowElapsed - offhookAtElapsed < MAX_ACTIVE_CALL_MS
}

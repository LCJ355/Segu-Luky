package com.intercom.segluky

/**
 * Pure decisions for the ordered call escalation.
 *
 * Android does not expose a reliable "remote party answered" signal for outgoing
 * calls: `CALL_STATE_OFFHOOK` also covers dialling and ringing, and a voicemail
 * pickup looks exactly like an answer. The off-hook duration is therefore only
 * *evidence* of a possible answer and no longer closes the round. Two rules keep
 * the escalation safe:
 *
 * - A call that is still connected after [MAX_ACTIVE_CALL_MS] is treated as
 *   answered and closes the round, because calling the next contact while
 *   someone is talking (or recording a message) is worse than stopping.
 * - Any call that ends earlier advances to the next contact, whether it was
 *   answered, unanswered or a voicemail.
 *
 * The app never ends a call itself, so it does not depend on
 * `ANSWER_PHONE_CALLS` being granted.
 */
internal object EscalationPolicy {
    /** Off-hook time above which the log records a *possible* answer. */
    const val ANSWERED_MIN_CALL_MS = 3_000L
    /** Absolute wait budget for a connected call before it closes the round. */
    const val MAX_ACTIVE_CALL_MS = 5 * 60_000L

    /** Evidence of a possible answer: a connected call of at least the minimum. */
    fun isAnswered(offhookAtElapsed: Long?, idleAtElapsed: Long): Boolean =
        offhookAtElapsed != null && idleAtElapsed >= offhookAtElapsed + ANSWERED_MIN_CALL_MS

    /** True while an off-hook call is still inside the wait budget. */
    fun shouldWaitForCallEnd(offhookAtElapsed: Long?, nowElapsed: Long): Boolean =
        offhookAtElapsed != null && nowElapsed - offhookAtElapsed < MAX_ACTIVE_CALL_MS

    /** A connected call that outlasted the wait budget counts as answered. */
    fun answeredByLongCall(offhookAtElapsed: Long?, nowElapsed: Long): Boolean =
        offhookAtElapsed != null && nowElapsed - offhookAtElapsed >= MAX_ACTIVE_CALL_MS
}

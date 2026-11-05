package com.intercom.segluky

/** Returns the requested beep interval for the remaining countdown time. */
internal fun countdownBeepIntervalMs(remainingMs: Long): Long? = when {
    remainingMs in 10_001L..20_000L -> 2_000L
    remainingMs in 3_001L..10_000L -> 1_000L
    remainingMs in 1L..3_000L -> 500L
    else -> null
}

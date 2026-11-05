package com.intercom.segluky

internal class MultiTouchEmergencyDetector(
    private val twoFingerHoldMs: Long = 5_000L
) {
    private var twoFingerStartedAt: Long? = null
    private var fired = false

    fun update(pointerCount: Int, timestamp: Long): Boolean {
        if (fired) return false
        return when {
            pointerCount >= 2 -> {
                if (twoFingerStartedAt == null) twoFingerStartedAt = timestamp
                if (timestamp - twoFingerStartedAt!! >= twoFingerHoldMs) {
                    fired = true
                    true
                } else false
            }
            else -> {
                twoFingerStartedAt = null
                false
            }
        }
    }

    fun nextDeadline(): Long? = when {
        fired -> null
        twoFingerStartedAt != null -> twoFingerStartedAt!! + twoFingerHoldMs
        else -> null
    }

    fun reset() {
        twoFingerStartedAt = null
        fired = false
    }
}
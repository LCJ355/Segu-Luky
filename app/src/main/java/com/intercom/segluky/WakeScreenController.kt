package com.intercom.segluky

import android.content.Context
import android.os.PowerManager

class WakeScreenController(context: Context) {
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private var wakeLock: PowerManager.WakeLock? = null

    @Suppress("DEPRECATION")
    fun wakeFor(durationMs: Long = DEFAULT_WAKE_DURATION_MS) {
        val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = powerManager.newWakeLock(flags, "Segu-Luky:VibrationWake").apply {
            setReferenceCounted(false)
            acquire(durationMs)
        }
    }

    fun release() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private companion object {
        const val DEFAULT_WAKE_DURATION_MS = 15_000L
    }
}

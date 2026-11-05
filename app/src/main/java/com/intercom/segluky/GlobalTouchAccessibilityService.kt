package com.intercom.segluky

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.provider.Settings
import android.view.MotionEvent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi

/** Optional global SOS gesture. Android requires the user to enable this service explicitly. */
class GlobalTouchAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val detector = MultiTouchEmergencyDetector()
    private var pointerCount = 0
    private var twoFingerHoldStartedAt: Long? = null

    private val twoFingerHold = Runnable {
        val startedAt = twoFingerHoldStartedAt ?: return@Runnable
        if (pointerCount >= 2 && System.currentTimeMillis() - startedAt >= TWO_FINGER_HOLD_MS) {
            confirmEmergency()
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onMotionEvent(event: MotionEvent) {
        val action = event.actionMasked
        val newPointerCount = when (action) {
            MotionEvent.ACTION_POINTER_UP -> (event.pointerCount - 1).coerceAtLeast(0)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 0
            else -> event.pointerCount
        }
        val previousPointerCount = pointerCount
        pointerCount = newPointerCount

        if (detector.update(newPointerCount, event.eventTime)) confirmEmergency()

        if (newPointerCount >= 2 && previousPointerCount < 2) {
            twoFingerHoldStartedAt = System.currentTimeMillis()
            handler.removeCallbacks(twoFingerHold)
            handler.postDelayed(twoFingerHold, TWO_FINGER_HOLD_MS)
        } else if (newPointerCount < 2) {
            twoFingerHoldStartedAt = null
            handler.removeCallbacks(twoFingerHold)
        }

        if (newPointerCount == 0) {
            detector.reset()
            twoFingerHoldStartedAt = null
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or AccessibilityServiceInfo.FLAG_SEND_MOTION_EVENTS
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) = Unit

    override fun onInterrupt() {
        pointerCount = 0
        detector.reset()
        twoFingerHoldStartedAt = null
        handler.removeCallbacks(twoFingerHold)
    }

    private fun confirmEmergency() {
        if (pointerCount >= 2) {
            twoFingerHoldStartedAt = null
            handler.removeCallbacks(twoFingerHold)
            SafetyMonitorService.confirm(this)
        }
    }

    companion object {
        private const val TWO_FINGER_HOLD_MS = 5_000L

        fun isEnabled(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
            val manager = context.getSystemService(AccessibilityManager::class.java)
                ?: return false
            val expected = ComponentName(context, GlobalTouchAccessibilityService::class.java)
            return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                    ComponentName(serviceInfo.packageName, serviceInfo.name) == expected
                }
        }

        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        fun openSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

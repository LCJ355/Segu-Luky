package com.intercom.segluky

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val restoredGesture = runCatching {
            GlobalTouchAccessibilityService.restoreAfterBoot(context)
        }.onFailure {
            Log.w("SeguLuky", "No se pudo comprobar el gesto global", it)
        }.getOrDefault(false)
        if (restoredGesture) SafetyStateStore.log("Gesto global 2 dedos restaurado tras el reinicio")
        if (SafetyRuntimeRepository(context).wasMonitoringRequested()) {
            SafetyMonitorService.start(context)
        }
    }
}
package com.intercom.segluky

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class SosAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val serviceAction = when (action) {
            ACTION_COMPLETE_SOS -> SafetyMonitorService.ACTION_ALARM_COMPLETE
            ACTION_ESCALATE_CALL -> SafetyMonitorService.ACTION_ESCALATE
            ACTION_DISPATCH_WATCHDOG -> SafetyMonitorService.ACTION_DISPATCH_WATCHDOG
            else -> return
        }
        val serviceIntent = Intent(context, SafetyMonitorService::class.java)
            .setAction(serviceAction)
        runCatching { ContextCompat.startForegroundService(context, serviceIntent) }
    }

    companion object {
        const val ACTION_COMPLETE_SOS = "com.intercom.segluky.COMPLETE_SOS_ALARM"
        const val ACTION_ESCALATE_CALL = "com.intercom.segluky.ESCALATE_CALL_ALARM"
        const val ACTION_DISPATCH_WATCHDOG = "com.intercom.segluky.DISPATCH_WATCHDOG_ALARM"
    }
}

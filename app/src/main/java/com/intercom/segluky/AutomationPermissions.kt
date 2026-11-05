package com.intercom.segluky

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Comprueba los permisos que hacen posible el envio AUTOMATICO de WhatsApp
 * (sin confirmacion del usuario):
 *  - Servicio de accesibilidad "Envio SOS WhatsApp" activado (pulsa Enviar).
 *  - Permiso "Aparecer encima de otras apps" (SYSTEM_ALERT_WINDOW): en Android
 *    moderno es lo que permite ABRIR WhatsApp desde segundo plano.
 */
object AutomationPermissions {

    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, WhatsAppAutoSendService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        if (enabled.isEmpty()) return false
        return enabled.split(':').any { entry ->
            val flat = entry.trim()
            flat.equals(expected.flattenToString(), ignoreCase = true) ||
                flat.contains(WhatsAppAutoSendService::class.java.name)
        }
    }

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun canScheduleExactAlarms(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false)

    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun openAccessibilitySettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun openOverlaySettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

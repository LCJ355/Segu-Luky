package com.intercom.segluky

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.InputDevice
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.annotation.RequiresApi

/** Optional global SOS gesture. Android requires the user to enable this service explicitly. */
class GlobalTouchAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val detector = MultiTouchEmergencyDetector()
    private var pointerCount = 0
    private var twoFingerHoldStartedAt: Long? = null
    private var lastFireAt = 0L
    private val vibrator by lazy { getSystemService(Vibrator::class.java) }

    private val twoFingerHold = Runnable {
        val startedAt = twoFingerHoldStartedAt ?: return@Runnable
        if (pointerCount >= 2 && System.currentTimeMillis() - startedAt >= TWO_FINGER_HOLD_MS) {
            confirmEmergency()
        }
    }

    /** Short pulses so whoever holds both fingers knows the gesture is being recognised. */
    private val tick = object : Runnable {
        override fun run() {
            if (pointerCount < 2 || twoFingerHoldStartedAt == null) return
            haptic(TICK_VIBRATION_MS)
            handler.postDelayed(this, TICK_INTERVAL_MS)
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
        if (newPointerCount != previousPointerCount) {
            Log.d(LOG_TAG, "dedos en pantalla: $newPointerCount")
        }

        if (detector.update(newPointerCount, event.eventTime)) confirmEmergency()

        if (newPointerCount >= 2 && previousPointerCount < 2) {
            Log.i(LOG_TAG, "dos dedos detectados: cuenta atras de 5 s")
            twoFingerHoldStartedAt = System.currentTimeMillis()
            handler.removeCallbacks(twoFingerHold)
            handler.postDelayed(twoFingerHold, TWO_FINGER_HOLD_MS)
            handler.removeCallbacks(tick)
            handler.post(tick)
        } else if (newPointerCount < 2) {
            stopHoldTracking()
        }

        if (newPointerCount == 0) {
            detector.reset()
            stopHoldTracking()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or AccessibilityServiceInfo.FLAG_SEND_MOTION_EVENTS
            }
            // Android 14+: sin declarar la fuente de entrada no llega ningun MotionEvent.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                motionEventSources = InputDevice.SOURCE_TOUCHSCREEN
            }
        }
        Log.i(LOG_TAG, "servicio conectado sdk=${Build.VERSION.SDK_INT} flags=0x${Integer.toHexString(serviceInfo.flags)} fuentes=${serviceInfo.motionEventSources}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) = Unit

    override fun onInterrupt() {
        pointerCount = 0
        detector.reset()
        stopHoldTracking()
    }

    private fun stopHoldTracking() {
        twoFingerHoldStartedAt = null
        handler.removeCallbacks(twoFingerHold)
        handler.removeCallbacks(tick)
    }

    private fun haptic(durationMs: Long, amplitude: Int = 140) {
        runCatching { vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, amplitude)) }
    }

    private fun confirmEmergency() {
        val now = System.currentTimeMillis()
        if (now - lastFireAt < FIRE_COOLDOWN_MS) return
        if (pointerCount < 2) return
        lastFireAt = now
        stopHoldTracking()
        Log.i(LOG_TAG, "gesto confirmado: lanzando SOS")
        haptic(FIRE_VIBRATION_MS, 255)
        SafetyMonitorService.confirm(this)
    }

    companion object {
        private const val LOG_TAG = "SeguLukyGesto"
        private const val TWO_FINGER_HOLD_MS = 5_000L
        private const val TICK_INTERVAL_MS = 1_000L
        private const val TICK_VIBRATION_MS = 45L
        private const val FIRE_VIBRATION_MS = 220L
        private const val FIRE_COOLDOWN_MS = 1_500L
        private const val PREFS = "segluky_global_gesture"
        private const val KEY_AUTO_ENABLE = "auto_enable"

        private fun component(context: Context): ComponentName =
            ComponentName(context, GlobalTouchAccessibilityService::class.java)

        fun isEnabled(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
            val manager = context.getSystemService(AccessibilityManager::class.java)
                ?: return false
            val expected = component(context)
            return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                    ComponentName(serviceInfo.packageName, serviceInfo.name) == expected
                }
        }

        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        /**
         * True once the one-off
         * `adb shell pm grant com.intercom.segluky android.permission.WRITE_SECURE_SETTINGS`
         * has been applied, which lets the app toggle its own accessibility service.
         */
        fun canEnableByItself(context: Context): Boolean = runCatching {
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        fun wasAutoEnableRequested(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_ENABLE, false)

        private fun rememberAutoEnable(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO_ENABLE, true).apply()
        }

        /** Enables the service silently. Needs WRITE_SECURE_SETTINGS; returns false otherwise. */
        fun enableByItself(context: Context): Boolean {
            if (!isSupported() || !canEnableByItself(context)) return false
            val resolver = context.contentResolver
            val expected = component(context)
            val enabled = runCatching {
                val current = Settings.Secure.getString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ).orEmpty()
                val entries = current.split(':').map { it.trim() }.filter { it.isNotEmpty() }
                val alreadyThere = entries.any {
                    ComponentName.unflattenFromString(it) == expected
                }
                val updated = if (alreadyThere) {
                    current
                } else {
                    (entries + expected.flattenToString()).joinToString(":")
                }
                Settings.Secure.putString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    updated
                )
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                true
            }.getOrDefault(false)
            if (enabled) rememberAutoEnable(context)
            return enabled
        }

        /**
         * El servicio filtra todo el flujo tactil, y en algunas ROM OPPO/ColorOS eso deja la
         * pantalla sin respuesta. Por eso no se reactiva solo tras reiniciar: el usuario tiene
         * que volver a activarlo desde Ajustes > Accesibilidad o con tools\activar-gesto-global.cmd.
         */
        fun restoreAfterBoot(context: Context): Boolean {
            if (!isSupported() || isEnabled(context)) return false
            if (wasAutoEnableRequested(context)) {
                Log.w(
                    LOG_TAG,
                    "Gesto global sin reactivar: se activa a mano para no bloquear el tacto."
                )
            }
            return false
        }

        /** Opens the exact toggle for this service when the OEM exposes it, else the full list. */
        fun openSettings(context: Context) {
            val expected = component(context)
            val flags = Intent.FLAG_ACTIVITY_NEW_TASK
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra(Intent.EXTRA_COMPONENT_NAME, expected)
                    .addFlags(flags)
                if (runCatching { context.startActivity(details) }.isSuccess) return
            }
            val list = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .putExtra(Intent.EXTRA_COMPONENT_NAME, expected)
                .addFlags(flags)
            if (runCatching { context.startActivity(list) }.isSuccess) return
            runCatching {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(flags))
            }
        }
    }
}

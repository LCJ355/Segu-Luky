package com.intercom.segluky

import android.content.Context

/** Ajustes propios del sensor de vuelco; no tocan los del movil. */
object Wt9011Settings {

    const val PREFERENCES = "wt9011_prefs"

    private const val KEY_MAC = "mac"
    private const val KEY_NAME = "name"
    private const val KEY_THRESHOLD = "threshold"
    private const val KEY_ENABLED = "enabled"

    const val DEFAULT_THRESHOLD_DEGREES = 55f
    const val MIN_THRESHOLD_DEGREES = 50f
    const val MAX_THRESHOLD_DEGREES = 60f

    fun loadThreshold(context: Context): Float =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD_DEGREES)
            .coerceIn(MIN_THRESHOLD_DEGREES, MAX_THRESHOLD_DEGREES)

    fun saveThreshold(context: Context, degrees: Float) {
        val value = degrees.coerceIn(MIN_THRESHOLD_DEGREES, MAX_THRESHOLD_DEGREES)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_THRESHOLD, value).apply()
    }

    fun loadEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun saveEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun loadDevice(context: Context): Wt9011Device {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return Wt9011Device(
            name = preferences.getString(KEY_NAME, "").orEmpty(),
            address = preferences.getString(KEY_MAC, "").orEmpty(),
            rssi = 0
        )
    }

    fun saveDevice(context: Context, device: Wt9011Device) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MAC, device.address)
            .putString(KEY_NAME, device.name)
            .apply()
    }
}

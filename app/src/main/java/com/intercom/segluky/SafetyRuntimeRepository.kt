package com.intercom.segluky

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings

data class PersistedSafetyRuntime(
    val monitoringEnabled: Boolean = false,
    val phase: SafetyPhase = SafetyPhase.STOPPED,
    val validationStartedAt: Long? = null,
    val immobileSince: Long? = null,
    val countdownEndsAt: Long? = null,
    val countdownEndsAtWallTime: Long? = null,
    val countdownReason: String = "",
    val eventId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracyMeters: Float? = null,
    val locationWallTime: Long? = null
)

/**
 * True when [other] is close enough to this state that persisting it again would
 * only cost a flash write.
 *
 * The live coordinates and the reported GPS accuracy change on every fix, so a
 * plain equality check is never satisfied while the monitor runs. Sensor
 * callbacks used to trigger a committed SharedPreferences write each time, which
 * is what this tolerance removes. Any change that matters for crash recovery
 * (phase, event, timers) still forces a write immediately.
 */
internal fun PersistedSafetyRuntime?.matchesMeaningfully(other: PersistedSafetyRuntime): Boolean {
    val previous = this ?: return false
    if (previous.monitoringEnabled != other.monitoringEnabled) return false
    if (previous.phase != other.phase) return false
    if (previous.validationStartedAt != other.validationStartedAt) return false
    if (previous.immobileSince != other.immobileSince) return false
    if (previous.countdownEndsAt != other.countdownEndsAt) return false
    if (previous.countdownEndsAtWallTime != other.countdownEndsAtWallTime) return false
    if (previous.countdownReason != other.countdownReason) return false
    if (previous.eventId != other.eventId) return false
    if (!previous.latitude.sameCoordinate(other.latitude)) return false
    if (!previous.longitude.sameCoordinate(other.longitude)) return false
    if (!previous.locationAccuracyMeters.sameAccuracy(other.locationAccuracyMeters)) return false
    // The wall clock is what decides whether a restored fix is still fresh, so a
    // refreshed fix must be written even when the phone has not moved.
    if (previous.locationWallTime != other.locationWallTime) return false
    return true
}

/** ~1 m of latitude: anything smaller is GPS noise, not a move worth persisting. */
private fun Double?.sameCoordinate(other: Double?): Boolean {
    if (this == null || other == null) return this == other
    return kotlin.math.abs(this - other) < COORDINATE_COMMIT_EPSILON
}

/** A slightly better fix is not a reason to write the whole state again. */
private fun Float?.sameAccuracy(other: Float?): Boolean {
    if (this == null || other == null) return this == other
    return kotlin.math.abs(this - other) < ACCURACY_COMMIT_EPSILON_METERS
}

private const val COORDINATE_COMMIT_EPSILON = 0.00001
private const val ACCURACY_COMMIT_EPSILON_METERS = 0.5f

internal class SafetyRuntimeRepository(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): PersistedSafetyRuntime {
        val savedBootCount = preferences.getInt(KEY_BOOT_COUNT, Int.MIN_VALUE)
        if (savedBootCount != currentBootCount()) {
            clearActiveState()
            return PersistedSafetyRuntime()
        }

        val phase = runCatching {
            SafetyPhase.valueOf(preferences.getString(KEY_PHASE, SafetyPhase.STOPPED.name).orEmpty())
        }.getOrDefault(SafetyPhase.STOPPED)
        return PersistedSafetyRuntime(
            monitoringEnabled = preferences.getBoolean(KEY_MONITORING_ENABLED, false),
            phase = phase,
            validationStartedAt = preferences.getOptionalLong(KEY_VALIDATION_STARTED_AT),
            immobileSince = preferences.getOptionalLong(KEY_IMMOBILE_SINCE),
            countdownEndsAt = preferences.getOptionalLong(KEY_COUNTDOWN_ENDS_AT),
            countdownEndsAtWallTime = preferences.getOptionalLong(KEY_COUNTDOWN_ENDS_AT_WALL),
            countdownReason = preferences.getString(KEY_COUNTDOWN_REASON, "").orEmpty(),
            eventId = preferences.getString(KEY_EVENT_ID, null),
            latitude = preferences.getOptionalDouble(KEY_LATITUDE),
            longitude = preferences.getOptionalDouble(KEY_LONGITUDE),
            locationAccuracyMeters = preferences.getOptionalFloat(KEY_LOCATION_ACCURACY),
            locationWallTime = preferences.getOptionalLong(KEY_LOCATION_WALL_TIME)
        )
    }

    fun save(runtime: PersistedSafetyRuntime): Boolean =
        preferences.edit()
            .putInt(KEY_BOOT_COUNT, currentBootCount())
            .putBoolean(KEY_MONITORING_ENABLED, runtime.monitoringEnabled)
            .putString(KEY_PHASE, runtime.phase.name)
            .putOptionalLong(KEY_VALIDATION_STARTED_AT, runtime.validationStartedAt)
            .putOptionalLong(KEY_IMMOBILE_SINCE, runtime.immobileSince)
            .putOptionalLong(KEY_COUNTDOWN_ENDS_AT, runtime.countdownEndsAt)
            .putOptionalLong(KEY_COUNTDOWN_ENDS_AT_WALL, runtime.countdownEndsAtWallTime)
            .putString(KEY_COUNTDOWN_REASON, runtime.countdownReason)
            .putString(KEY_EVENT_ID, runtime.eventId)
            .putOptionalDouble(KEY_LATITUDE, runtime.latitude)
            .putOptionalDouble(KEY_LONGITUDE, runtime.longitude)
            .putOptionalFloat(KEY_LOCATION_ACCURACY, runtime.locationAccuracyMeters)
            .putOptionalLong(KEY_LOCATION_WALL_TIME, runtime.locationWallTime)
            .commit()

    fun wasEventCompleted(eventId: String?): Boolean =
        eventId != null && preferences.getString(KEY_LAST_COMPLETED_EVENT_ID, null) == eventId

    fun markEventCompleted(eventId: String?): Boolean {
        if (eventId == null) return false
        return preferences.edit().putString(KEY_LAST_COMPLETED_EVENT_ID, eventId).commit()
    }

    fun setMonitoringRequested(requested: Boolean): Boolean =
        preferences.edit().putBoolean(KEY_MONITORING_REQUESTED, requested).commit()

    fun wasMonitoringRequested(): Boolean = preferences.getBoolean(KEY_MONITORING_REQUESTED, false)

    @SuppressLint("ApplySharedPref")
    fun clearActiveState() {
        preferences.edit()
            .remove(KEY_MONITORING_ENABLED)
            .remove(KEY_PHASE)
            .remove(KEY_VALIDATION_STARTED_AT)
            .remove(KEY_IMMOBILE_SINCE)
            .remove(KEY_COUNTDOWN_ENDS_AT)
            .remove(KEY_COUNTDOWN_ENDS_AT_WALL)
            .remove(KEY_COUNTDOWN_REASON)
            .remove(KEY_EVENT_ID)
            .remove(KEY_LATITUDE)
            .remove(KEY_LONGITUDE)
            .remove(KEY_LOCATION_ACCURACY)
            .remove(KEY_LOCATION_WALL_TIME)
            .remove(KEY_LEGACY_SIMULATED_EVENT)
            .putInt(KEY_BOOT_COUNT, currentBootCount())
            .commit()
    }

    private fun currentBootCount(): Int = runCatching {
        Settings.Global.getInt(applicationContext.contentResolver, Settings.Global.BOOT_COUNT)
    }.getOrDefault(0)

    private fun android.content.SharedPreferences.getOptionalLong(key: String): Long? =
        if (contains(key)) getLong(key, 0L) else null

    private fun android.content.SharedPreferences.getOptionalDouble(key: String): Double? =
        if (contains(key)) Double.fromBits(getLong(key, 0L)) else null

    private fun android.content.SharedPreferences.getOptionalFloat(key: String): Float? =
        if (contains(key)) getFloat(key, 0f) else null

    private fun android.content.SharedPreferences.Editor.putOptionalLong(
        key: String,
        value: Long?
    ): android.content.SharedPreferences.Editor = if (value == null) remove(key) else putLong(key, value)

    private fun android.content.SharedPreferences.Editor.putOptionalDouble(
        key: String,
        value: Double?
    ): android.content.SharedPreferences.Editor = if (value == null) remove(key) else putLong(key, value.toBits())

    private fun android.content.SharedPreferences.Editor.putOptionalFloat(
        key: String,
        value: Float?
    ): android.content.SharedPreferences.Editor = if (value == null) remove(key) else putFloat(key, value)

    private companion object {
        const val PREFERENCES = "segu_prefs"
        const val KEY_BOOT_COUNT = "runtime_boot_count"
        const val KEY_MONITORING_ENABLED = "runtime_monitoring_enabled"
        const val KEY_PHASE = "runtime_phase"
        const val KEY_VALIDATION_STARTED_AT = "runtime_validation_started_at"
        const val KEY_IMMOBILE_SINCE = "runtime_immobile_since"
        const val KEY_COUNTDOWN_ENDS_AT = "runtime_countdown_ends_at"
        const val KEY_COUNTDOWN_ENDS_AT_WALL = "runtime_countdown_ends_at_wall"
        const val KEY_COUNTDOWN_REASON = "runtime_countdown_reason"
        const val KEY_EVENT_ID = "runtime_event_id"
        const val KEY_LATITUDE = "runtime_latitude"
        const val KEY_LONGITUDE = "runtime_longitude"
        const val KEY_LOCATION_ACCURACY = "runtime_location_accuracy"
        const val KEY_LOCATION_WALL_TIME = "runtime_location_wall_time"
        const val KEY_LEGACY_SIMULATED_EVENT = "runtime_simulated_event"
        const val KEY_LAST_COMPLETED_EVENT_ID = "runtime_last_completed_event_id"
        const val KEY_MONITORING_REQUESTED = "runtime_monitoring_requested"
    }
}

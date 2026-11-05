package com.intercom.segluky

import android.content.Context
import org.json.JSONArray

internal enum class DispatchStep {
    WHATSAPP,
    CALLS,
    DONE
}

internal data class PendingEmergencyDispatch(
    val eventId: String,
    val callPhones: List<String>,
    val callIndex: Int,
    val answered: Boolean,
    val step: DispatchStep = DispatchStep.CALLS
)

/**
 * Persisted state for the SOS delivery pipeline. A dispatch intentionally
 * survives having zero call recipients: it still has to drain the WhatsApp
 * queue and then mark the event complete.
 */
internal class EmergencyDispatchRepository(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("segu_emergency_dispatch", Context.MODE_PRIVATE)

    fun load(): PendingEmergencyDispatch? {
        val eventId = preferences.getString(KEY_EVENT_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val raw = preferences.getString(KEY_CALL_PHONES, null)
        val phones = if (raw == null) {
            emptyList()
        } else {
            runCatching {
                val array = JSONArray(raw)
                buildList {
                    for (index in 0 until array.length()) {
                        array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }.getOrNull() ?: return null
        }
        val step = runCatching {
            DispatchStep.valueOf(preferences.getString(KEY_STEP, DispatchStep.CALLS.name).orEmpty())
        }.getOrDefault(DispatchStep.CALLS)
        val callIndex = if (phones.isEmpty()) {
            0
        } else {
            preferences.getInt(KEY_CALL_INDEX, 0).coerceIn(0, phones.lastIndex)
        }
        val answered = preferences.getBoolean(KEY_ANSWERED, false)
        return PendingEmergencyDispatch(eventId, phones, callIndex, answered, step)
    }

    fun save(dispatch: PendingEmergencyDispatch): Boolean {
        val array = JSONArray()
        dispatch.callPhones.forEach { array.put(it) }
        return preferences.edit()
            .putString(KEY_EVENT_ID, dispatch.eventId)
            .putString(KEY_CALL_PHONES, array.toString())
            .putInt(KEY_CALL_INDEX, dispatch.callIndex)
            .putBoolean(KEY_ANSWERED, dispatch.answered)
            .putString(KEY_STEP, dispatch.step.name)
            .commit()
    }

    fun clear(): Boolean = preferences.edit().clear().commit()

    private companion object {
        const val KEY_EVENT_ID = "event_id"
        const val KEY_CALL_PHONES = "call_phones"
        const val KEY_CALL_INDEX = "call_index"
        const val KEY_ANSWERED = "answered"
        const val KEY_STEP = "step"
    }
}

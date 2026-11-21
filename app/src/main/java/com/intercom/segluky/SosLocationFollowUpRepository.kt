package com.intercom.segluky

import android.content.Context
import org.json.JSONObject

/**
 * Estado persistido del seguimiento de ubicacion ([SosLocationFollowUp]).
 *
 * Se guarda en disco porque el aviso y el reenvio pueden caer en procesos
 * distintos: Android puede matar el servicio entre el SOS y el momento en que el
 * movil recupera cobertura o posicion, y sin este dato el reenvio se perderia.
 */
internal class SosLocationFollowUpRepository(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("segu_sos_follow_up", Context.MODE_PRIVATE)

    fun load(): SosLocationFollowUpState? {
        if (!preferences.contains(KEY_STATE)) return null
        val raw = preferences.getString(KEY_STATE, null)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val eventId = json.optString(KEY_EVENT_ID)
            if (eventId.isBlank()) return@runCatching null
            SosLocationFollowUpState(
                eventId = eventId,
                armedAtWall = json.optLong(KEY_ARMED_AT),
                initialLinkSent = json.optBoolean(KEY_INITIAL_LINK_SENT),
                sentCount = json.optInt(KEY_SENT_COUNT).coerceAtLeast(0),
                manualOffered = json.optBoolean(KEY_MANUAL_OFFERED),
                lastSentAtWall = json.optLong(KEY_LAST_SENT_AT).takeIf {
                    json.has(KEY_LAST_SENT_AT)
                },
                lastLatitude = json.optDouble(KEY_LATITUDE).takeIf {
                    json.has(KEY_LATITUDE) && it.isFinite()
                },
                lastLongitude = json.optDouble(KEY_LONGITUDE).takeIf {
                    json.has(KEY_LONGITUDE) && it.isFinite()
                }
            )
        }.getOrNull()
    }

    // commit() y no apply(): si el proceso muere justo despues del aviso, apply()
    // puede no haber escrito nada y el reenvio se pierde en silencio.
    @android.annotation.SuppressLint("ApplySharedPref")
    fun save(state: SosLocationFollowUpState): Boolean {
        val json = JSONObject().apply {
            put(KEY_EVENT_ID, state.eventId)
            put(KEY_ARMED_AT, state.armedAtWall)
            put(KEY_INITIAL_LINK_SENT, state.initialLinkSent)
            put(KEY_SENT_COUNT, state.sentCount)
            put(KEY_MANUAL_OFFERED, state.manualOffered)
            state.lastSentAtWall?.let { put(KEY_LAST_SENT_AT, it) }
            state.lastLatitude?.let { put(KEY_LATITUDE, it) }
            state.lastLongitude?.let { put(KEY_LONGITUDE, it) }
        }
        return preferences.edit().putString(KEY_STATE, json.toString()).commit()
    }

    @android.annotation.SuppressLint("ApplySharedPref")
    fun clear() {
        preferences.edit().remove(KEY_STATE).commit()
    }

    private companion object {
        const val KEY_STATE = "state"
        const val KEY_EVENT_ID = "eventId"
        const val KEY_ARMED_AT = "armedAtWall"
        const val KEY_INITIAL_LINK_SENT = "initialLinkSent"
        const val KEY_SENT_COUNT = "sentCount"
        const val KEY_MANUAL_OFFERED = "manualOffered"
        const val KEY_LAST_SENT_AT = "lastSentAtWall"
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
    }
}

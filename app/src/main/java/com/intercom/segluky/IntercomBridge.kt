package com.intercom.segluky

import android.content.Context
import android.net.Uri
import android.os.SystemClock

/**
 * Puente con InterCOM-Luky. InterCOM expone un ContentProvider de solo lectura
 * con su estado (activo, grupo, motoristas) y una llamada "notice" que difunde
 * un aviso de voz a todos los motoristas del grupo o del INTERCOM WiFi.
 *
 * Si la version instalada de InterCOM no trae el puente, [status] devuelve null
 * y [sendNotice] devuelve false: Segu-Luky sigue funcionando sin el aviso.
 */
object IntercomBridge {
    const val AUTHORITY = "com.intercom.lukymoto.bridge"
    private const val METHOD_NOTICE = "notice"
    private const val BRIDGE_RETRY_MILLIS = 30_000L
    private val STATUS_URI: Uri = Uri.parse("content://$AUTHORITY/status")
    private var unavailableUntil = 0L

    data class Status(
        val active: Boolean,
        val group: String = "",
        val peers: Int = 0,
        val version: String = ""
    )

    fun status(context: Context): Status? {
        if (SystemClock.elapsedRealtime() < unavailableUntil) return null
        val result = runCatching {
            context.contentResolver.query(STATUS_URI, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    null
                } else {
                    Status(
                        active = cursor.getInt(cursor.columnIndex("active")) == 1,
                        group = cursor.getString(cursor.columnIndex("group")) ?: "",
                        peers = cursor.getInt(cursor.columnIndex("peers")),
                        version = cursor.getString(cursor.columnIndex("version")) ?: ""
                    )
                }
            }
        }.getOrNull()
        // Sin puente (InterCOM antiguo o sin instalar): no reintentar en cada sondeo.
        unavailableUntil = if (result == null) {
            SystemClock.elapsedRealtime() + BRIDGE_RETRY_MILLIS
        } else {
            0L
        }
        return result
    }

    /**
     * true solo cuando InterCOM confirma que ha difundido el aviso al grupo. Si
     * InterCOM esta instalado pero sin grupo, puede reproducir el aviso en este
     * movil y aun asi devolver false.
     */
    fun sendNotice(context: Context, text: String): Boolean = runCatching {
        val reply = context.contentResolver.call(STATUS_URI, METHOD_NOTICE, text, null)
        reply?.getBoolean("sent", false) ?: false
    }.getOrDefault(false)

    private fun android.database.Cursor.columnIndex(name: String): Int =
        getColumnIndex(name).takeIf { it >= 0 } ?: error("columna $name ausente en el puente INTERCOM")
}

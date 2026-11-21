package com.intercom.segluky

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Seguimiento de la ubicacion despues del SOS.
 *
 * El aviso original solo lleva el enlace del mapa si en ese mismo momento hay una
 * posicion de menos de cinco minutos. En un tunel, en un parking o con el GPS
 * apagado el contacto recibe "ubicacion GPS no disponible" y ya no sabe nunca
 * donde esta el motorista, aunque el movil recupere la posicion veinte segundos
 * despues. Este seguimiento reenvia la posicion en cuanto aparece.
 *
 * Las reglas son deliberadamente conservadoras: quien recibe el aviso puede estar
 * conduciendo hacia el accidente y no debe recibir un mensaje cada veinte
 * segundos.
 *
 * - La ventana dura [WINDOW_MS] desde el aviso original.
 * - Si el SOS salio sin mapa, el primer reenvio sale con la primera posicion.
 * - Los siguientes exigen [MIN_INTERVAL_MS] y [MIN_DISTANCE_METERS] de distancia.
 * - Nunca mas de [MAX_UPDATES] reenvios por evento.
 */
internal data class SosLocationFollowUpState(
    val eventId: String,
    /** Reloj de pared del aviso original: sobrevive al reinicio del proceso. */
    val armedAtWall: Long,
    /** El SOS ya llevaba enlace de mapa cuando salio. */
    val initialLinkSent: Boolean,
    val sentCount: Int = 0,
    /** Aviso manual ya ofrecido en la notificacion (WhatsApp y enlace t.me). */
    val manualOffered: Boolean = false,
    val lastSentAtWall: Long? = null,
    val lastLatitude: Double? = null,
    val lastLongitude: Double? = null
)

internal object SosLocationFollowUp {
    /** Tiempo que el seguimiento sigue vivo despues del aviso original. */
    const val WINDOW_MS = 15 * 60_000L
    /** Separacion minima entre dos reenvios mientras el motorista se mueve. */
    const val MIN_INTERVAL_MS = 60_000L
    /** Movimiento minimo para que un reenvio aporte algo nuevo. */
    const val MIN_DISTANCE_METERS = 50.0
    const val MAX_UPDATES = 5

    /**
     * True cuando ya no tiene sentido seguir reenviando: se cerro la ventana,
     * se agotaron los avisos o el reloj retrocedio (un salto de hora no puede
     * alargar la ventana).
     */
    fun isExpired(state: SosLocationFollowUpState, nowWall: Long): Boolean {
        val elapsed = nowWall - state.armedAtWall
        return elapsed < 0L ||
            elapsed > WINDOW_MS ||
            state.sentCount >= MAX_UPDATES
    }

    /** True cuando esta posicion merece un aviso nuevo. */
    fun shouldSend(
        state: SosLocationFollowUpState?,
        nowWall: Long,
        latitude: Double,
        longitude: Double
    ): Boolean {
        val current = state ?: return false
        if (isExpired(current, nowWall)) return false
        // El SOS salio sin mapa: la primera posicion siempre aporta.
        if (!current.initialLinkSent && current.sentCount == 0) return true
        val lastAt = current.lastSentAtWall ?: return true
        if (nowWall - lastAt < MIN_INTERVAL_MS) return false
        val lastLatitude = current.lastLatitude ?: return true
        val lastLongitude = current.lastLongitude ?: return true
        return distanceMeters(lastLatitude, lastLongitude, latitude, longitude) >= MIN_DISTANCE_METERS
    }

    fun recordSent(
        state: SosLocationFollowUpState,
        nowWall: Long,
        latitude: Double,
        longitude: Double,
        manualOffered: Boolean = false
    ): SosLocationFollowUpState = state.copy(
        sentCount = state.sentCount + 1,
        manualOffered = state.manualOffered || manualOffered,
        lastSentAtWall = nowWall,
        lastLatitude = latitude,
        lastLongitude = longitude
    )

    /**
     * Id propio para cada reenvio. Las dos colas (WhatsApp y Telegram) deduplican
     * por evento, asi que un aviso nuevo necesita su propia identidad o se
     * descartaria como repetido.
     */
    fun jobEventId(eventId: String, sentCount: Int): String = "$eventId#ubi${sentCount + 1}"

    /** Todos los ids que puede llegar a usar un evento, para poder limpiarlos. */
    fun jobEventIds(eventId: String): List<String> =
        (0 until MAX_UPDATES).map { jobEventId(eventId, it) }

    /** Distancia en metros entre dos coordenadas (haversine, sin Android). */
    fun distanceMeters(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double
    ): Double {
        val earthRadiusMeters = 6_371_000.0
        val deltaLat = Math.toRadians(toLatitude - fromLatitude)
        val deltaLon = Math.toRadians(toLongitude - fromLongitude)
        val fromLat = Math.toRadians(fromLatitude)
        val toLat = Math.toRadians(toLatitude)
        val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(fromLat) * cos(toLat) * sin(deltaLon / 2) * sin(deltaLon / 2)
        return 2 * earthRadiusMeters * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}

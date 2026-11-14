package com.intercom.segluky

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Retiene la ultima lectura del giroscopio hasta que caduca.
 *
 * El giro y la aceleracion no llegan sincronizados: el giroscopio se pide a otra
 * frecuencia y, ademas, solo se registra mientras dura la ventana de validacion.
 * Cada muestra de movimiento se queda por tanto con el giro mas reciente, y si ese
 * giro ya tiene mas de [maxAgeMs] se descarta: atribuir a un golpe una rotacion
 * vieja convertiria un bache en un accidente, que es peor que no puntuar el giro.
 */
class ImpactGyroHold(private val maxAgeMs: Long = MAX_AGE_MS) {
    private var atMs = UNSET
    private var magnitudeDegPerSec = 0f

    /** Guarda la lectura y devuelve su magnitud en grados por segundo. */
    fun update(atMs: Long, x: Float, y: Float, z: Float): Float {
        magnitudeDegPerSec = sqrt(x * x + y * y + z * z)
        this.atMs = atMs
        return magnitudeDegPerSec
    }

    /** Giro vigente en [atMs]; 0 si no hay lectura o ya esta caducada. */
    fun valueAt(atMs: Long): Float {
        if (this.atMs == UNSET) return 0f
        return if (abs(atMs - this.atMs) <= maxAgeMs) magnitudeDegPerSec else 0f
    }

    fun reset() {
        atMs = UNSET
        magnitudeDegPerSec = 0f
    }

    companion object {
        /**
         * El giroscopio se pide a 50 Hz (20 ms por muestra): 200 ms es ya diez
         * periodos de retraso, suficiente para no confundir un giro con el del
         * impacto anterior y corto como para no perder el del golpe actual.
         */
        const val MAX_AGE_MS = 200L

        private const val UNSET = Long.MIN_VALUE
    }
}

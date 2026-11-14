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
 *
 * La **calidad** del giro se mide aqui sola (frescura y huecos de entrega) y no
 * depende de la orientacion del movil: la magnitud en grados/s no necesita saber
 * como esta colocado el telefono para ser valida.
 */
class ImpactGyroHold(
    private val maxAgeMs: Long = MAX_AGE_MS,
    private val freshMs: Long = FRESH_MS,
    private val gapMs: Long = GAP_MS
) {
    private var atMs = UNSET
    private var magnitudeDegPerSec = 0f
    private var lastGapMs = UNSET

    /** Guarda la lectura y devuelve su magnitud en grados por segundo. */
    fun update(atMs: Long, x: Float, y: Float, z: Float): Float {
        if (this.atMs != UNSET) lastGapMs = atMs - this.atMs
        magnitudeDegPerSec = if (x.isFinite() && y.isFinite() && z.isFinite()) {
            sqrt(x * x + y * y + z * z)
        } else {
            0f
        }
        this.atMs = atMs
        return magnitudeDegPerSec
    }

    /** Giro vigente en [atMs]; 0 si no hay lectura o ya esta caducada. */
    fun valueAt(atMs: Long): Float {
        if (this.atMs == UNSET) return 0f
        return if (abs(atMs - this.atMs) <= maxAgeMs) magnitudeDegPerSec else 0f
    }

    /**
     * Calidad del giro entre 0 y 1: 1 mientras la lectura sea reciente
     * (menos de [freshMs]), decae en linea hasta 0 al caducar y se queda a la
     * mitad si la entrega tiene huecos (ultimo intervalo mayor que [gapMs]).
     */
    fun qualityAt(atMs: Long): Float {
        if (this.atMs == UNSET) return 0f
        val age = abs(atMs - this.atMs)
        if (age > maxAgeMs) return 0f
        val freshness = if (age <= freshMs || maxAgeMs <= freshMs) {
            1f
        } else {
            1f - (age - freshMs).toFloat() / (maxAgeMs - freshMs).toFloat()
        }
        val gapPenalty = if (lastGapMs != UNSET && lastGapMs > gapMs) 0.5f else 1f
        return (freshness * gapPenalty).coerceIn(0f, 1f)
    }

    fun reset() {
        atMs = UNSET
        magnitudeDegPerSec = 0f
        lastGapMs = UNSET
    }

    companion object {
        /**
         * El giroscopio se pide a 50 Hz (20 ms por muestra): 200 ms es ya diez
         * periodos de retraso, suficiente para no confundir un giro con el del
         * impacto anterior y corto como para no perder el del golpe actual.
         */
        const val MAX_AGE_MS = 200L
        /** Lecturas de menos de 100 ms se consideran frescas (5 periodos a 50 Hz). */
        const val FRESH_MS = 100L
        /** Un intervalo de entrega mayor que esto ya es un hueco. */
        const val GAP_MS = 150L

        private const val UNSET = Long.MIN_VALUE
    }
}

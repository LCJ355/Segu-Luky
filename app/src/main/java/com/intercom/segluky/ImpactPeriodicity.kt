package com.intercom.segluky

import kotlin.math.sqrt

/**
 * Memoria corta de impactos para separar un tramo de badenes de una colision.
 *
 * Un mismo baden repetido (o una calzada rota) produce golpes parecidos y
 * espaciados de forma regular; un choque llega una vez y sin ritmo. Se guardan
 * los ultimos [capacity] impactos, se miden los intervalos entre ellos y se
 * calcula su coeficiente de variacion (desviacion tipica / media).
 *
 * La regla es asimetrica a proposito: un patron repetido baja la sospecha de un
 * golpe duro, pero nunca desmonta un choque ya declarado. Preferimos un aviso de
 * mas por un baden antes que callar una colision.
 */
class ImpactPeriodicity(
    private val capacity: Int = MAX_HITS,
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val maxIntervalMs: Long = MAX_INTERVAL_MS,
    private val maxVariation: Float = MAX_COEFFICIENT,
    private val minHits: Int = MIN_HITS
) {
    private val hits = ArrayDeque<Long>()

    /** Guarda el instante del ultimo golpe contado; ignora repeticiones y desorden. */
    fun record(atMs: Long) {
        if (hits.isNotEmpty() && atMs <= hits.last()) return
        hits.addLast(atMs)
        while (hits.size > capacity) hits.removeFirst()
    }

    fun count(): Int = hits.size

    fun intervalsMs(): List<Long> {
        if (hits.size < 2) return emptyList()
        val intervals = ArrayList<Long>(hits.size - 1)
        var previous = hits.first()
        for (index in 1 until hits.size) {
            val current = hits.elementAt(index)
            intervals.add(current - previous)
            previous = current
        }
        return intervals
    }

    /** Coeficiente de variacion de los intervalos; [Float.MAX_VALUE] si no hay dos. */
    fun coefficientOfVariation(): Float {
        val intervals = intervalsMs()
        if (intervals.size < 2) return Float.MAX_VALUE
        val mean = intervals.sum().toDouble() / intervals.size
        if (mean <= 0.0) return Float.MAX_VALUE
        val variance = intervals.sumOf { (it - mean) * (it - mean) } / intervals.size
        return (sqrt(variance) / mean).toFloat()
    }

    /** True si el ritmo de los ultimos golpes parece badenes y no un accidente. */
    fun isRepetitivePattern(): Boolean {
        if (count() < minHits) return false
        val intervals = intervalsMs()
        if (intervals.size < minHits - 1) return false
        if (intervals.any { it < minIntervalMs || it > maxIntervalMs }) return false
        return coefficientOfVariation() <= maxVariation
    }

    /**
     * Ajusta el veredicto de la secuencia cuando el ritmo es repetitivo: resta
     * [PENALTY] puntos (el nivel se recalcula) y lo deja escrito en los motivos.
     * Un CHOQUE no se toca: se anota el patron y se mantiene el aviso.
     */
    fun adjust(verdict: ImpactSequenceVerdict): ImpactSequenceVerdict {
        if (!isRepetitivePattern()) return verdict
        val motivo = "patron repetitivo (${description()})"
        if (verdict.level == ImpactVerdict.CHOQUE) {
            return verdict.copy(reasons = verdict.reasons + motivo)
        }
        val score = (verdict.score - PENALTY).coerceAtLeast(0)
        val level = when {
            score >= GOLPE_DURO_MIN -> ImpactVerdict.GOLPE_DURO
            score >= BACHE_MIN -> ImpactVerdict.BACHE
            else -> ImpactVerdict.RUIDO
        }
        return verdict.copy(score = score, level = level, reasons = verdict.reasons + motivo)
    }

    fun description(): String {
        val variation = coefficientOfVariation()
        val ritmo = if (variation == Float.MAX_VALUE) {
            "sin intervalos"
        } else {
            String.format(java.util.Locale.US, "CV %.2f", variation)
        }
        return "${count()} impactos, $ritmo"
    }

    fun reset() {
        hits.clear()
    }

    companion object {
        /** Diez golpes son suficientes para ver un ritmo sin arrastrar el viaje entero. */
        const val MAX_HITS = 10
        /** Con menos de cuatro golpes no hay ritmo que valga. */
        const val MIN_HITS = 4
        const val PENALTY = 8
        const val GOLPE_DURO_MIN = 40
        const val BACHE_MIN = 18
        /** Dos golpes en el mismo segundo son el mismo baden contado dos veces. */
        const val MIN_INTERVAL_MS = 1_000L
        /** Mas de minuto y medio entre golpes ya no es un tramo de badenes. */
        const val MAX_INTERVAL_MS = 90_000L
        /** Un ritmo regular de verdad se queda muy por debajo del 25 % de variacion. */
        const val MAX_COEFFICIENT = 0.25f
    }
}

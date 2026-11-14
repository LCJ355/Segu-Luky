package com.intercom.segluky

/**
 * Memoria corta de impactos para separar un tramo de badenes de una colision.
 *
 * Un mismo baden repetido (o una calzada rota) produce golpes parecidos y
 * espaciados de forma regular; un choque llega una vez y sin ritmo. Se guardan
 * los ultimos [capacity] impactos, se miden los intervalos entre ellos y se
 * describe su dispersion con la **mediana** y la **desviacion absoluta mediana**
 * (MAD), que aguantan mejor un intervalo raro que una media y una desviacion
 * tipica.
 *
 * El ritmo **no resta puntos por si solo**: hace falta ademas un contexto benigno
 * (el vehiculo siguio circulando). Y aunque lo haya, un CHOQUE no se desmonta
 * nunca: se anota el patron y se mantiene el aviso.
 */
class ImpactPeriodicity(
    private val capacity: Int = MAX_HITS,
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val maxIntervalMs: Long = MAX_INTERVAL_MS,
    private val maxDispersion: Float = MAX_DISPERSION,
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

    /** Intervalo tipico entre golpes (mediana), en ms; 0 si no hay intervalos. */
    fun medianIntervalMs(): Long {
        val intervals = intervalsMs()
        if (intervals.isEmpty()) return 0L
        return median(intervals)
    }

    /** Dispersion robusta: mediana de |intervalo - mediana|, en ms. */
    fun medianAbsoluteDeviationMs(): Long {
        val intervals = intervalsMs()
        if (intervals.size < 2) return 0L
        val centre = medianIntervalMs()
        return median(intervals.map { kotlin.math.abs(it - centre) })
    }

    /** Desviacion absoluta mediana relativa; [Float.MAX_VALUE] si no hay dos intervalos. */
    fun dispersion(): Float {
        val centre = medianIntervalMs()
        if (intervalsMs().size < 2 || centre <= 0L) return Float.MAX_VALUE
        return medianAbsoluteDeviationMs().toFloat() / centre.toFloat()
    }

    /** True si el ritmo de los ultimos golpes parece badenes y no un accidente. */
    fun isRepetitivePattern(): Boolean {
        if (count() < minHits) return false
        val intervals = intervalsMs()
        if (intervals.size < minHits - 1) return false
        if (intervals.any { it < minIntervalMs || it > maxIntervalMs }) return false
        return dispersion() <= maxDispersion
    }

    /**
     * Ajusta el veredicto cuando el ritmo es repetitivo **y** el vehiculo seguia
     * circulando con normalidad ([benign]). Sin contexto benigno no se resta nada:
     * varios golpes seguidos de un accidente tambien pueden ser regulares.
     *
     * Con contexto benigno resta [PENALTY] puntos (el nivel se recalcula). Un
     * CHOQUE nunca se toca: solo se anota el patron en los motivos.
     */
    fun adjust(verdict: ImpactSequenceVerdict, benign: Boolean): ImpactSequenceVerdict {
        if (!isRepetitivePattern() || !benign) return verdict
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
        val dispersion = dispersion()
        val ritmo = if (dispersion == Float.MAX_VALUE) {
            "sin intervalos"
        } else {
            String.format(
                java.util.Locale.US,
                "mediana %.1f s, MAD %.2f",
                medianIntervalMs() / 1000f,
                dispersion
            )
        }
        return "${count()} impactos, $ritmo"
    }

    fun reset() {
        hits.clear()
    }

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2
        }
    }

    companion object {
        /** Diez golpes son suficientes para ver un ritmo sin arrastrar el viaje entero. */
        const val MAX_HITS = 10
        /** Con menos de seis golpes (cinco intervalos) el ritmo no es concluyente. */
        const val MIN_HITS = 6
        const val PENALTY = 8
        const val GOLPE_DURO_MIN = 40
        const val BACHE_MIN = 18
        /** Dos golpes en el mismo segundo son el mismo baden contado dos veces. */
        const val MIN_INTERVAL_MS = 1_000L
        /** Mas de minuto y medio entre golpes ya no es un tramo de badenes. */
        const val MAX_INTERVAL_MS = 90_000L
        /** Un ritmo regular de verdad se queda muy por debajo del 25 % de dispersion. */
        const val MAX_DISPERSION = 0.25f
    }
}

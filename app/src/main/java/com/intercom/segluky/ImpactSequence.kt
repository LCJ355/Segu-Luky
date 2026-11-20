package com.intercom.segluky

import kotlin.math.sqrt

/** Una muestra de la secuencia: aceleracion en los tres ejes y giro. */
data class ImpactSample(
    val atMs: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gyroDegPerSec: Float = 0f
)

data class ImpactSequenceVerdict(
    val score: Int,
    val level: ImpactVerdict,
    val phase: String,
    val reasons: List<String>
)

/**
 * Medida del ancho con la resolucion con la que se ha tomado. [reliable] en falso
 * significa que el ancho no se debe usar para puntuar (pocas muestras o menos de
 * 50 Hz), solo como diagnostico.
 */
data class ImpactWidthQuality(
    val widthMs: Long,
    val samplesAbove: Int,
    val sampleIntervalMs: Float,
    val toleranceMs: Long,
    val reliable: Boolean
)

/**
 * Analiza la secuencia alrededor de un golpe en vez de un unico pico.
 *
 * Fases: forma del impacto (0-0,5 s), giro (0-2 s), desaceleracion (0-5 s) y
 * recuperacion (0-15 s). La severidad sube en cuanto hay evidencia critica: no
 * espera a completar la ventana si la velocidad se ha caido o el motor se calo.
 *
 * Solo mide: no dispara nada. El giro puntua por la calidad de su propia lectura
 * (frescura y huecos), no por la calibracion de la orientacion: la magnitud en
 * grados/s no necesita saber como esta colocado el telefono.
 */
object ImpactSequence {
    const val TICK_MS = 150L
    const val IMPACT_WINDOW_MS = 500L
    const val ROTATION_WINDOW_MS = 2_000L
    const val DECEL_WINDOW_MS = 5_000L
    const val RECOVERY_WINDOW_MS = 15_000L
    const val ROTATION_MIN_DEG = 90f
    const val MIN_ORIENTATION_CONFIDENCE = 0.5f
    const val MIN_GYRO_CONFIDENCE = 0.5f
    const val SATURATION_G = 15.9f
    /** Tolerancia minima del ancho: por debajo se cerraria con un solo hueco. */
    const val MIN_WIDTH_TOLERANCE_MS = 20L
    /** Un intervalo de muestra mayor que esto (menos de 50 Hz) no mide el ancho. */
    const val MAX_RELIABLE_INTERVAL_MS = 20f
    /** Menos de tres muestras por encima del umbral no describen un impacto. */
    const val MIN_SAMPLES_ABOVE = 3
    /**
     * Umbral de salida (60 % del de entrada). La misma histeresis vale para el
     * ancho y para contar subidas: si no, una senal que respira alrededor del
     * umbral se contaba como varios golpes y regalaba los 10 puntos de rebote.
     */
    const val EXIT_RATIO = 0.6f

    /**
     * Ancho del impacto medido con histeresis sobre una lista de muestras: entra
     * con el umbral de golpe, se mantiene con el 60 % y no cierra hasta que pasan
     * [toleranceMs] por debajo. La tolerancia debe crecer con el intervalo real de
     * muestreo: con 20 ms a 20 Hz (una muestra cada 50 ms) basta un hueco para
     * cerrar el impacto.
     */
    fun widthMs(
        samples: List<ImpactSample>,
        thresholdG: Float,
        hitAtMs: Long,
        toleranceMs: Long = MIN_WIDTH_TOLERANCE_MS
    ): Long {
        val enter = thresholdG.coerceAtLeast(0.1f)
        val exit = (enter * EXIT_RATIO).coerceAtLeast(0.1f)
        var startedAt = -1L
        var lastAbove = -1L
        var closed = false
        val limit = hitAtMs + IMPACT_WINDOW_MS
        for (sample in samples) {
            if (sample.atMs < hitAtMs || sample.atMs > limit) continue
            if (closed) break
            val g = magnitude(sample)
            if (startedAt < 0L) {
                if (g >= enter) { startedAt = sample.atMs; lastAbove = sample.atMs }
                continue
            }
            if (g >= exit) {
                lastAbove = sample.atMs
            } else if (sample.atMs - lastAbove > toleranceMs) {
                // Impacto cerrado: lo que venga despues es otro, no ensancha este.
                closed = true
            }
        }
        if (startedAt < 0L || lastAbove < 0L) return 0L
        return (lastAbove - startedAt).coerceAtLeast(0L)
    }

    fun magnitude(sample: ImpactSample): Float =
        sqrt(sample.ax * sample.ax + sample.ay * sample.ay + sample.az * sample.az)

    /**
     * Ancho mas la resolucion con la que se ha medido: intervalo real de muestreo,
     * tolerancia aplicada, muestras por encima del umbral y si el resultado es
     * fiable (>= 50 Hz y al menos tres muestras). Por debajo de eso el ancho sirve
     * de diagnostico, no para puntuar.
     */
    fun widthQuality(samples: List<ImpactSample>, thresholdG: Float, hitAtMs: Long): ImpactWidthQuality {
        val enter = thresholdG.coerceAtLeast(0.1f)
        val window = samples.filter { it.atMs in hitAtMs..(hitAtMs + IMPACT_WINDOW_MS) }
        if (window.size < 2) {
            return ImpactWidthQuality(0L, window.size, 0f, MIN_WIDTH_TOLERANCE_MS, false)
        }
        val interval = medianIntervalMs(window)
        val tolerance = maxOf(MIN_WIDTH_TOLERANCE_MS, (interval * 1.5f).toLong())
        val width = widthMs(window, thresholdG, hitAtMs, tolerance)
        val above = window.count { magnitude(it) >= enter }
        val reliable = width > 0L &&
            above >= MIN_SAMPLES_ABOVE &&
            interval > 0f &&
            interval <= MAX_RELIABLE_INTERVAL_MS
        return ImpactWidthQuality(width, above, interval, tolerance, reliable)
    }

    /** Intervalo mediano entre muestras consecutivas, en ms. */
    fun medianIntervalMs(samples: List<ImpactSample>): Float {
        if (samples.size < 2) return 0f
        val intervals = ArrayList<Float>(samples.size - 1)
        for (index in 1 until samples.size) {
            intervals.add((samples[index].atMs - samples[index - 1].atMs).toFloat())
        }
        intervals.sort()
        val middle = intervals.size / 2
        return if (intervals.size % 2 == 1) {
            intervals[middle]
        } else {
            (intervals[middle - 1] + intervals[middle]) / 2f
        }
    }

    /**
     * Cuanto de la ventana [windowMs] llega a cubrir la muestra mas reciente.
     *
     * Sirve para no llamar "ventana de 15 s" a una lectura que solo tiene 2 s: los
     * hitos diferidos (5 s y 15 s) registran esta cobertura junto al veredicto.
     */
    fun coveredMs(samples: List<ImpactSample>, hitAtMs: Long, windowMs: Long): Long {
        val last = samples.lastOrNull()?.atMs ?: return 0L
        return (last - hitAtMs).coerceIn(0L, windowMs)
    }

    /**
     * Confianza en la orientacion del movil, que decide si el giro puede puntuar.
     *
     * Sin calibrar, la actitud de conduccion es una suposicion (0,2). Con la
     * gravedad calibrada y precisa, el giro es fiable (1,0). Si la gravedad llega
     * marcada como no fiable se queda por debajo del umbral (0,4): se prefiere no
     * sumar rotacion antes que sumarla mal.
     */
    fun orientationConfidence(calibrated: Boolean, accuracyReliable: Boolean = true): Float =
        when {
            !calibrated -> 0.2f
            accuracyReliable -> 1f
            else -> 0.4f
        }

    fun evaluate(
        samples: List<ImpactSample>,
        hitAtMs: Long,
        thresholdG: Float,
        speedBeforeKmh: Float?,
        speedAfterKmh: Float?,
        rpmAfter: Int?,
        orientationConfidence: Float = 1f,
        gyroConfidence: Float = 1f
    ): ImpactSequenceVerdict {
        val reasons = ArrayList<String>()
        val threshold = thresholdG.coerceAtLeast(0.1f)
        val impact = samples.filter { it.atMs in hitAtMs..(hitAtMs + IMPACT_WINDOW_MS) }
        if (impact.isEmpty()) {
            return ImpactSequenceVerdict(0, ImpactVerdict.RUIDO, "sin muestras", listOf("sin datos"))
        }
        val peak = impact.maxOf { magnitude(it) }
        val rms = sqrt(impact.sumOf { (magnitude(it) * magnitude(it)).toDouble() } / impact.size).toFloat()
        // Un "cruce" es una subida real por encima del umbral, no una muestra por
        // encima: a 100 Hz contar muestras daba 50 y regalaba siempre los 10 puntos
        // de rebote. Con subidas, un golpe limpio suma 1 y un rebote suma 2 o mas.
        val crossings = countUpCrossings(impact, threshold)
        val saturated = peak >= SATURATION_G
        val rotation = samples
            .filter { it.atMs in hitAtMs..(hitAtMs + ROTATION_WINDOW_MS) }
            .maxOfOrNull { kotlin.math.abs(it.gyroDegPerSec) } ?: 0f

        var score = 0
        val ratio = peak / threshold
        when {
            ratio >= 1.5f -> { score += 35; reasons.add("pico ${fmt(peak)} g (${fmt(ratio)}x)") }
            ratio >= 1.2f -> { score += 22; reasons.add("pico ${fmt(peak)} g") }
            else -> { score += 12; reasons.add("pico justo ${fmt(peak)} g") }
        }
        if (crossings >= 2) { score += 10; reasons.add("$crossings cruces: rebote") }
        if (rms > threshold) { score += 5; reasons.add("sacudida sostenida") }
        if (saturated) { score += 8; reasons.add("sensor saturado") }
        if (rotation >= ROTATION_MIN_DEG && gyroConfidence >= MIN_GYRO_CONFIDENCE) {
            score += 10
            reasons.add("giro ${rotation.toInt()} grados/s")
            // La orientacion no valida la magnitud del giro; solo matiza si se
            // puede hablar de cambio de postura.
            if (orientationConfidence < MIN_ORIENTATION_CONFIDENCE) {
                reasons.add("giro sin orientacion calibrada")
            }
        } else if (rotation >= ROTATION_MIN_DEG) {
            reasons.add("giro ${rotation.toInt()} grados/s sin calidad")
        }

        val drop = if (speedBeforeKmh != null && speedAfterKmh != null) {
            (speedBeforeKmh - speedAfterKmh).coerceAtLeast(0f)
        } else null
        val stopped = (speedAfterKmh ?: Float.MAX_VALUE) <= 10f
        if (drop != null) {
            when {
                drop >= 30f && stopped -> { score += 40; reasons.add("velocidad ${fmt(drop)} km/h abajo, parado") }
                drop >= 30f -> { score += 25; reasons.add("velocidad ${fmt(drop)} km/h abajo") }
                drop >= 10f -> { score += 10; reasons.add("velocidad ${fmt(drop)} km/h abajo") }
                else -> { score -= 6; reasons.add("velocidad intacta") }
            }
        }
        val stalled = rpmAfter != null && rpmAfter == 0 && (speedBeforeKmh ?: 0f) >= 20f
        if (stalled) { score += 20; reasons.add("motor calado") }

        val total = score.coerceIn(0, 100)
        val phase = when {
            rotation >= ROTATION_MIN_DEG && gyroConfidence >= MIN_GYRO_CONFIDENCE -> "giro"
            drop != null -> "desaceleracion"
            else -> "impacto"
        }
        val level = when {
            stalled && total >= 45 -> ImpactVerdict.CHOQUE
            drop != null && drop >= 30f && stopped && total >= 45 -> ImpactVerdict.CHOQUE
            total >= 40 -> ImpactVerdict.GOLPE_DURO
            total >= 18 -> ImpactVerdict.BACHE
            else -> ImpactVerdict.RUIDO
        }
        return ImpactSequenceVerdict(total, level, phase, reasons)
    }

    /**
     * Numero de subidas por encima del umbral dentro de la ventana, en orden
     * temporal. Cada subida es un golpe; varios golpes seguidos son un rebote.
     */
    private fun countUpCrossings(impact: List<ImpactSample>, threshold: Float): Int {
        val enter = threshold.coerceAtLeast(0.1f)
        val exit = (enter * EXIT_RATIO).coerceAtLeast(0.1f)
        var crossings = 0
        var above = false
        for (sample in impact.sortedBy { it.atMs }) {
            val g = magnitude(sample)
            if (!above && g >= enter) {
                crossings++
                above = true
            } else if (above && g < exit) {
                above = false
            }
        }
        return crossings
    }

    private fun fmt(value: Float): String =
        String.format(java.util.Locale.US, "%.1f", value)
}

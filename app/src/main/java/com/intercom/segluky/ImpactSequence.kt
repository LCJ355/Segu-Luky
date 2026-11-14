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
 * Analiza la secuencia alrededor de un golpe en vez de un unico pico.
 *
 * Fases: forma del impacto (0-0,5 s), giro (0-2 s), desaceleracion (0-5 s) y
 * recuperacion (0-15 s). La severidad sube en cuanto hay evidencia critica: no
 * espera a completar la ventana si la velocidad se ha caido o el motor se calo.
 *
 * Solo mide: no dispara nada. El giro no cuenta si la orientacion no es fiable.
 */
object ImpactSequence {
    const val TICK_MS = 150L
    const val IMPACT_WINDOW_MS = 500L
    const val ROTATION_WINDOW_MS = 2_000L
    const val DECEL_WINDOW_MS = 5_000L
    const val RECOVERY_WINDOW_MS = 15_000L
    const val ROTATION_MIN_DEG = 90f
    const val MIN_ORIENTATION_CONFIDENCE = 0.5f
    const val SATURATION_G = 15.9f

    fun magnitude(sample: ImpactSample): Float =
        sqrt(sample.ax * sample.ax + sample.ay * sample.ay + sample.az * sample.az)

    fun evaluate(
        samples: List<ImpactSample>,
        hitAtMs: Long,
        thresholdG: Float,
        speedBeforeKmh: Float?,
        speedAfterKmh: Float?,
        rpmAfter: Int?,
        orientationConfidence: Float = 1f
    ): ImpactSequenceVerdict {
        val reasons = ArrayList<String>()
        val threshold = thresholdG.coerceAtLeast(0.1f)
        val impact = samples.filter { it.atMs in hitAtMs..(hitAtMs + IMPACT_WINDOW_MS) }
        if (impact.isEmpty()) {
            return ImpactSequenceVerdict(0, ImpactVerdict.RUIDO, "sin muestras", listOf("sin datos"))
        }
        val peak = impact.maxOf { magnitude(it) }
        val rms = sqrt(impact.sumOf { (magnitude(it) * magnitude(it)).toDouble() } / impact.size).toFloat()
        val crossings = impact.count { magnitude(it) >= threshold }
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
        if (rotation >= ROTATION_MIN_DEG && orientationConfidence >= MIN_ORIENTATION_CONFIDENCE) {
            score += 10
            reasons.add("giro ${rotation.toInt()} grados/s")
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
            hitAtMs + ROTATION_WINDOW_MS > hitAtMs + IMPACT_WINDOW_MS && rotation >= ROTATION_MIN_DEG -> "giro"
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

    private fun fmt(value: Float): String =
        String.format(java.util.Locale.US, "%.1f", value)
}

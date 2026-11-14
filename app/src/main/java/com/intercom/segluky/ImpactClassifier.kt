package com.intercom.segluky

/** Que parece el golpe, con los datos que haya en ese momento. */
enum class ImpactVerdict(val label: String) {
    RUIDO("RUIDO"),
    BACHE("BACHE"),
    GOLPE_DURO("GOLPE DURO"),
    CHOQUE("CHOQUE")
}

/**
 * Lo que se sabe de un golpe cuando se cuenta. Todo es opcional menos el pico y
 * el nivel: si falta la velocidad o el motor, el veredicto se queda mas corto
 * (nunca inventa un choque).
 */
data class ImpactSignals(
    val peakG: Float,
    val thresholdG: Float,
    val jerkGPerSecond: Float = 0f,
    val widthMs: Long = 0L,
    val speedBeforeKmh: Float? = null,
    val speedAfterKmh: Float? = null,
    val rpmAfter: Int? = null,
    val attitudeChangeDegrees: Float? = null,
    val hasVehicleLink: Boolean = false
) {
    val speedDropKmh: Float?
        get() {
            val before = speedBeforeKmh ?: return null
            val after = speedAfterKmh ?: return null
            return (before - after).coerceAtLeast(0f)
        }
}

data class ImpactAssessment(
    val verdict: ImpactVerdict,
    val score: Int,
    val reasons: List<String>
) {
    val summary: String
        get() = "${verdict.label} ($score): " + reasons.joinToString(", ")
}

/**
 * Distingue un bache grande de un accidente con las senales que ya mide la app.
 *
 * Un bache es un pico alto pero **corto y simetrico**, con la velocidad y la
 * actitud intactas. Un accidente es un pico mas ancho, con **caida de velocidad**
 * (o motor calado) y, muchas veces, el movil cambiando de postura.
 *
 * En esta version solo se mide: el veredicto va al registro y no cambia cuando
 * salta el SOS. El disparo se conectara cuando haya datos reales del coche.
 */
object ImpactClassifier {

    const val WIDE_IMPACT_MS = 60L
    const val SHORT_SPIKE_MS = 25L
    const val SEVERE_SPEED_DROP_KMH = 30f
    const val STOPPED_KMH = 10f
    const val STALL_SPEED_KMH = 20f
    const val ATTITUDE_CHANGE_DEGREES = 15f

    fun assess(signals: ImpactSignals): ImpactAssessment {
        val reasons = ArrayList<String>()
        var score = 0
        val threshold = signals.thresholdG.coerceAtLeast(0.1f)
        val ratio = signals.peakG / threshold

        when {
            ratio >= 1.5f -> {
                score += 35
                reasons.add("pico ${format(signals.peakG)} g (${format(ratio)}x el nivel)")
            }
            ratio >= 1.2f -> {
                score += 22
                reasons.add("pico ${format(signals.peakG)} g")
            }
            else -> {
                score += 12
                reasons.add("pico justo ${format(signals.peakG)} g")
            }
        }

        if (signals.widthMs >= WIDE_IMPACT_MS) {
            score += 15
            reasons.add("impacto ancho ${signals.widthMs} ms")
        } else if (signals.widthMs in 1 until SHORT_SPIKE_MS) {
            score -= 6
            reasons.add("pico corto ${signals.widthMs} ms")
        }

        val drop = signals.speedDropKmh
        if (drop != null) {
            when {
                drop >= SEVERE_SPEED_DROP_KMH &&
                    (signals.speedAfterKmh ?: Float.MAX_VALUE) <= STOPPED_KMH -> {
                    score += 40
                    reasons.add("velocidad ${format(drop)} km/h abajo, casi parado")
                }
                drop >= SEVERE_SPEED_DROP_KMH -> {
                    score += 25
                    reasons.add("velocidad ${format(drop)} km/h abajo")
                }
                drop >= 10f -> {
                    score += 10
                    reasons.add("velocidad ${format(drop)} km/h abajo")
                }
                else -> {
                    score -= 6
                    reasons.add("velocidad intacta")
                }
            }
        } else if (signals.hasVehicleLink) {
            reasons.add("sin dato de velocidad del coche")
        }

        // El jerk es otra medida de la misma sacudida: solo aporta cuando no hay
        // velocidad (unica evidencia) y nunca se suma a la caida de velocidad,
        // para no inflar la puntuacion por senales correlacionadas.
        if (signals.speedDropKmh == null && signals.jerkGPerSecond >= 60f) {
            score += 10
            reasons.add("jerk alto sin dato de velocidad")
        }

        val stalled = signals.rpmAfter != null &&
            signals.rpmAfter == 0 &&
            (signals.speedBeforeKmh ?: 0f) >= STALL_SPEED_KMH
        if (stalled) {
            score += 20
            reasons.add("motor calado")
        }

        signals.attitudeChangeDegrees?.let { degrees ->
            if (degrees >= ATTITUDE_CHANGE_DEGREES) {
                score += 15
                reasons.add("postura cambiada ${format(degrees)} grados")
            }
        }

        val scoreClamped = score.coerceIn(0, 100)
        val verdict = when {
            (drop != null && drop >= SEVERE_SPEED_DROP_KMH &&
                (signals.speedAfterKmh ?: Float.MAX_VALUE) <= STOPPED_KMH &&
                scoreClamped >= 45) -> ImpactVerdict.CHOQUE
            stalled && scoreClamped >= 45 -> ImpactVerdict.CHOQUE
            scoreClamped >= 40 -> ImpactVerdict.GOLPE_DURO
            scoreClamped >= 18 -> ImpactVerdict.BACHE
            else -> ImpactVerdict.RUIDO
        }
        return ImpactAssessment(verdict, scoreClamped, reasons)
    }

    private fun format(value: Float): String = String.format(java.util.Locale.US, "%.1f", value)
}

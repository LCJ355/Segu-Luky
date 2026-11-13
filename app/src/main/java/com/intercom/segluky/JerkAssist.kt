package com.intercom.segluky

import kotlin.math.abs

/**
 * El jerk (cambio de aceleracion por unidad de tiempo) es una senal incremental:
 * nunca dispara solo, solo suma un pequeno margen a un golpe que ya esta cerca
 * del umbral. Solo se considera a partir de 20 km/h; por debajo vale cero.
 *
 * La escala es conservadora y se puede ajustar con datos de calibracion:
 * - a 20 km/h aporta el 20 % del maximo;
 * - a 50 km/h o mas aporta el 100 %;
 * - un jerk de [REFERENCE_JERK_G_PER_S] satura el aporte.
 */
internal object JerkAssist {
    const val MIN_SPEED_KMH = 20f
    const val FULL_SPEED_KMH = 50f
    const val MIN_SPEED_FACTOR = 0.20f
    const val SPEED_MAX_AGE_MS = 10_000L
    const val MAX_BONUS_G = 0.35f
    const val REFERENCE_JERK_G_PER_S = 120f
    const val MIN_DT_MS = 5L
    const val MAX_DT_MS = 120L

    fun jerkGPerSecond(previousG: Float, currentG: Float, dtMs: Long): Float {
        if (!previousG.isFinite() || !currentG.isFinite()) return 0f
        if (dtMs !in MIN_DT_MS..MAX_DT_MS) return 0f
        return abs(currentG - previousG) * 1_000f / dtMs
    }

    fun bonusG(speedKmh: Float?, jerkGPerS: Float): Float {
        if (speedKmh == null || !speedKmh.isFinite() || speedKmh < MIN_SPEED_KMH) return 0f
        if (!jerkGPerS.isFinite() || jerkGPerS <= 0f) return 0f
        val speedFactor = (
            MIN_SPEED_FACTOR +
                (1f - MIN_SPEED_FACTOR) *
                ((speedKmh - MIN_SPEED_KMH) / (FULL_SPEED_KMH - MIN_SPEED_KMH))
            ).coerceIn(0f, 1f)
        val jerkFactor = (jerkGPerS / REFERENCE_JERK_G_PER_S).coerceIn(0f, 1f)
        return MAX_BONUS_G * speedFactor * jerkFactor
    }

    fun assistedDynamicG(dynamicG: Float, speedKmh: Float?, jerkGPerS: Float): Float =
        dynamicG + bonusG(speedKmh, jerkGPerS)
}

package com.intercom.segluky

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Traduce el pico medido en un viaje a una sensibilidad recomendada.
 *
 * La formula es la misma que usa `Instrucciones/analizar_calibracion.py`, para que la
 * app y el analisis del PC no den dos respuestas distintas: pico x 1,20 (margen), en
 * pasos de 0,5 g y con suelo de 1,5 g. La compensacion por frecuencia corrige a los
 * moviles que miden los picos mas bajos.
 */
internal object SensitivityAdvisor {
    const val MIN_RIDE_MS = 60_000L
    const val MIN_PEAK_G = 0.5f
    const val MARGIN = 1.20f
    const val STEP_G = 0.5f
    const val MIN_RECOMMENDED_G = 1.5f

    enum class Verdict { CORRECT, RAISE, LOWER }

    data class Suggestion(
        val recommendedG: Float,
        val verdict: Verdict
    )

    /**
     * @param peakG pico del viaje
     * @param rateFactor compensacion por frecuencia (0,70-1,00)
     * @param profileG piso aprendido del movil, si ya hay datos suficientes
     * @param currentG ajuste actual del usuario
     * @param rideMs tiempo de monitor del viaje
     */
    fun suggest(
        peakG: Float?,
        rateFactor: Float = 1f,
        profileG: Float = 0f,
        currentG: Float,
        rideMs: Long
    ): Suggestion? {
        if (peakG == null || !peakG.isFinite() || peakG < MIN_PEAK_G) return null
        if (rideMs < MIN_RIDE_MS) return null
        val corrected = peakG * MARGIN / rateFactor.coerceIn(0.5f, 1.5f)
        val recommended = roundToStep(maxOf(corrected, profileG.coerceAtLeast(0f)))
        return Suggestion(recommended, verdictOf(recommended, currentG))
    }

    fun formatG(g: Float): String = String.format(Locale.US, "%.1f", g)

    private fun roundToStep(g: Float): Float {
        val stepped = (g / STEP_G).roundToInt() * STEP_G
        return stepped.coerceIn(MIN_RECOMMENDED_G, AccidentDetector.MAX_SENSITIVITY_G)
    }

    private fun verdictOf(recommendedG: Float, currentG: Float): Verdict = when {
        recommendedG > currentG + STEP_G / 2 -> Verdict.RAISE
        recommendedG < currentG - STEP_G / 2 -> Verdict.LOWER
        else -> Verdict.CORRECT
    }
}

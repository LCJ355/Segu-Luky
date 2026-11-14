package com.intercom.segluky

/** Lo que ha dado de si un bloque de grabacion de 15 minutos. */
data class CalibrationSegmentStats(
    val rows: Long = 0L,
    val speedRows: Long = 0L,
    val bandsCovered: Int = 0,
    val achievedHz: Float = 0f,
    val targetHz: Float = 0f,
    val rateChanges: Int = 0,
    val jerkPeaks: Int = 0
)

/**
 * Decide que bloque de calibracion se queda. Solo hay uno: el mas significativo.
 *
 * Un bloque vale mas cuanto mas cubre los regimenes de velocidad (que es lo que
 * afina el muestreo y el umbral), cuanto mas dato tiene con velocidad valida,
 * cuanto mas se acerca a la frecuencia pedida y cuanta mas actividad recoge
 * (cambios de tasa y picos de jerk).
 */
object CalibrationRetention {

    const val SEGMENT_MS = 15 * 60 * 1000L
    const val RECORDING_MAX_MS = 4 * 60 * 60 * 1000L
    const val SPEED_BANDS = 5
    const val JERK_PEAK_G_PER_S = 1.5f

    fun score(stats: CalibrationSegmentStats): Int {
        if (stats.rows <= 0L) return 0
        val bands = stats.bandsCovered.coerceIn(0, SPEED_BANDS).toFloat() / SPEED_BANDS
        val speedShare = (stats.speedRows.toFloat() / stats.rows).coerceIn(0f, 1f)
        val hz = if (stats.targetHz > 0f) (stats.achievedHz / stats.targetHz).coerceIn(0f, 1f) else 0f
        val activity = ((stats.rateChanges / 20f) + (stats.jerkPeaks / 40f)).coerceIn(0f, 1f)
        val value = 40f * bands + 25f * speedShare + 20f * hz + 15f * activity
        return value.coerceIn(0f, 100f).toInt()
    }

    /** true si el bloque nuevo debe sustituir al guardado. */
    fun replaces(newStats: CalibrationSegmentStats, newScore: Int, currentScore: Int): Boolean {
        if (newStats.rows <= 0L) return false
        return newScore > currentScore
    }

    /** Banda de velocidad (0..4) para contar cuantos regimenes cubre el bloque. */
    fun band(speedKmh: Float): Int = when {
        speedKmh < 12.5f -> 0
        speedKmh < 25f -> 1
        speedKmh < 40f -> 2
        speedKmh < 50f -> 3
        else -> 4
    }
}

package com.intercom.segluky



/**
 * Tasa de muestreo de la grabacion de calibracion. Es independiente del monitor:
 * solo se usa para capturar datos con mas resolucion cuando la moto corre.
 *
 * De 0 a 50 km/h la tasa sube de 20 a 100 Hz. Para minimizar rebindings se
 * cuantiza en pasos de 20 Hz, de forma que solo hay cinco escalones.
 */
internal object CalibrationRatePolicy {
    const val MIN_HZ = 20
    const val MAX_HZ = 100
    const val FULL_SPEED_KMH = 50f
    const val STEP_HZ = 20
    const val STABLE_MS = 10_000L
    const val MIN_REBIND_MS = 15_000L
    const val SPEED_MEDIAN_SAMPLES = 3
    const val SPEED_MAX_AGE_MS = 10_000L

    /** 0 km/h -> 20 Hz, 50 km/h o mas -> 100 Hz, en escalones de 20 Hz. */
    fun targetHz(speedKmh: Float?): Int {
        if (speedKmh == null || !speedKmh.isFinite()) return MIN_HZ
        val speed = speedKmh.coerceIn(0f, FULL_SPEED_KMH)
        val raw = MIN_HZ + (MAX_HZ - MIN_HZ) * (speed / FULL_SPEED_KMH)
        // Truncar, no redondear: asi los tramos quedan 0-12,5, 12,5-25...
        val steps = (raw / STEP_HZ).toInt()
        return (steps * STEP_HZ).coerceIn(MIN_HZ, MAX_HZ)
    }
}

/**
 * Evita cambiar la frecuencia del sensor en cada fix GPS. Un cambio debe ser
 * estable durante [CalibrationRatePolicy.STABLE_MS] y dejar pasar
 * [CalibrationRatePolicy.MIN_REBIND_MS] desde el anterior.
 */
internal class CalibrationRateController(
    private val stableMs: Long = CalibrationRatePolicy.STABLE_MS,
    private val minRebindMs: Long = CalibrationRatePolicy.MIN_REBIND_MS
) {
    var appliedHz: Int = CalibrationRatePolicy.MIN_HZ
        private set
    private var pendingHz: Int = CalibrationRatePolicy.MIN_HZ
    private var pendingSince: Long = 0L
    private var lastRebindAt: Long = 0L

    fun reset(now: Long, initialHz: Int = CalibrationRatePolicy.MIN_HZ) {
        appliedHz = initialHz.coerceIn(CalibrationRatePolicy.MIN_HZ, CalibrationRatePolicy.MAX_HZ)
        pendingHz = appliedHz
        pendingSince = 0L
        lastRebindAt = now - minRebindMs
    }

    /**
     * Devuelve la nueva frecuencia si toca re-registrar el sensor, o null si
     * hay que mantener la actual.
     */
    fun update(speedKmh: Float?, now: Long): Int? {
        if (now < 0L) return null
        val target = CalibrationRatePolicy.targetHz(speedKmh)
        if (target == appliedHz) {
            pendingHz = target
            pendingSince = 0L
            return null
        }
        if (pendingSince == 0L || target != pendingHz) {
            pendingHz = target
            pendingSince = now
            return null
        }
        if (now - pendingSince < stableMs) return null
        if (lastRebindAt != 0L && now - lastRebindAt < minRebindMs) return null
        appliedHz = target
        lastRebindAt = now
        pendingSince = 0L
        return target
    }
}

/** Mediana de las ultimas velocidades para no reaccionar a un pico de GPS. */
internal class MedianSpeed(
    private val size: Int = CalibrationRatePolicy.SPEED_MEDIAN_SAMPLES
) {
    private val values = ArrayDeque<Float>()

    fun add(speedKmh: Float): Float {
        values.addLast(speedKmh)
        while (values.size > size) values.removeFirst()
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    fun reset() = values.clear()
}

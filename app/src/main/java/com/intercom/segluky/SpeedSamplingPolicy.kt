package com.intercom.segluky

internal enum class SpeedBand {
    UNKNOWN,
    SLOW,
    MOVING
}

/**
 * La velocidad no apaga la deteccion: solo limita el muestreo de maxima frecuencia
 * cuando no hay datos o el vehiculo va despacio. El acelerometro y la deteccion de
 * impactos siguen activos; el giroscopio ya se enciende solo en candidato/validacion.
 */
internal class SpeedSamplingPolicy(
    private val movingKmh: Float = 15f,
    private val slowKmh: Float = 10f,
    private val dwellMs: Long = 5_000L
) {
    var band: SpeedBand = SpeedBand.UNKNOWN
        private set

    private var pending: SpeedBand? = null
    private var pendingSince = 0L

    fun update(speedKmh: Float?, timestamp: Long): SpeedBand {
        val desired = when {
            speedKmh == null || !speedKmh.isFinite() || speedKmh < 0f -> SpeedBand.UNKNOWN
            speedKmh >= movingKmh -> SpeedBand.MOVING
            speedKmh <= slowKmh -> SpeedBand.SLOW
            else -> null
        }
        if (desired == null) {
            pending = null
            return band
        }
        if (desired == band) {
            pending = null
            return band
        }
        if (pending != desired) {
            pending = desired
            pendingSince = timestamp
            return band
        }
        if (timestamp - pendingSince >= dwellMs) {
            band = desired
            pending = null
        }
        return band
    }

    /** En marcha se permite el nivel que pida el aprendizaje; parado o sin dato, tope 50 Hz. */
    fun cap(desired: SamplingTier, phase: SafetyPhase): SamplingTier {
        if (phase != SafetyPhase.MONITORING) return desired
        if (band == SpeedBand.MOVING) return desired
        return if (desired.ordinal > SamplingTier.CRUISE.ordinal) SamplingTier.CRUISE else desired
    }
}

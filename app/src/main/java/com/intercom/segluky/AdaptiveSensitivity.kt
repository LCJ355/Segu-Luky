package com.intercom.segluky

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Modo de la ventana de aprendizaje.
 *
 * [AUTO] deja que la app elija entre 1 y 3 minutos segun como cambie el camino; los
 * valores fijos son la opcion manual para quien prefiera control total.
 */
internal enum class AdaptationWindowMode(val id: String, val minutes: Int) {
    AUTO("auto", 0),
    ONE("1", 1),
    TWO("2", 2),
    THREE("3", 3);

    val isAuto: Boolean get() = this == AUTO

    companion object {
        val fixed: List<AdaptationWindowMode> = listOf(ONE, TWO, THREE)

        fun fromId(id: String?): AdaptationWindowMode =
            entries.firstOrNull { it.id == id?.trim() } ?: AUTO
    }
}

/**
 * Perfil aprendido de un movil concreto: cuanto ruido tiene su sensor, que nivel de
 * aceleracion es "normal" en marcha y cuantas muestras por segundo entrega de verdad.
 *
 * Existe porque el mismo ajuste en g no significa lo mismo en dos telefonos: uno con
 * un sensor ruidoso dispara solo y otro que suaviza los picos mide el golpe mas bajo.
 */
internal data class DeviceProfile(
    val noiseG: Float = 0f,
    val p99G: Float = 0f,
    val deliveredHz: Float = 0f,
    val usageMs: Long = 0L
) {
    val floorG: Float get() = SensitivityMath.floor(noiseG, p99G)
}

/** Formula del umbral efectivo y todas sus constantes, en un sitio y sin Android. */
internal object SensitivityMath {
    /** Un movil se considera "afinado" a partir de este uso acumulado. */
    const val PROFILE_MIN_USAGE_MS = 5 * 60_000L

    /** Margen sobre el ruido en reposo para que un sensor sucio no dispare solo. */
    const val NOISE_MARGIN = 5f
    const val NOISE_MIN_G = 0.30f

    /** Margen sobre el traqueteo normal de marcha (p99). */
    const val AMBIENT_FACTOR = 1.30f

    /** Frecuencia de referencia: por debajo de ella los picos se miden mas bajos. */
    const val REFERENCE_HZ = 100f
    const val RATE_FACTOR_MIN = 0.70f
    const val RATE_FACTOR_MAX = 1.00f

    /** Topes al alza: un perfil mal aprendido cuesta sensibilidad, nunca la anula. */
    const val PROFILE_MAX_RATIO = 1.60f
    const val MAX_LIFT_G = 2.0f

    const val MIN_SENSITIVITY_G = AccidentDetector.MIN_SENSITIVITY_G
    const val MAX_SENSITIVITY_G = AccidentDetector.MAX_SENSITIVITY_G

    /** Datos minimos para que la ventana pueda mover el umbral. */
    const val MIN_DATA_MS = 10_000L
    const val MIN_DATA_SAMPLES = 100

    /** Tramos quietos: donde se mide el ruido propio del sensor. */
    const val QUIET_P50_MAX_G = 0.15f
    const val QUIET_TIER_P50_G = 0.08f
    const val QUIET_TIER_P99_G = 0.60f
    const val DEMANDING_TIER_P99_G = 1.60f

    const val DWELL_MIN_MS = 20_000L
    const val PROFILE_ALPHA_MAX = 0.35f

    /** La ventana AUTO acorta si el p99 cambia mucho y alarga si esta estable. */
    const val AUTO_DRIFT_SHORTEN = 0.30f
    const val AUTO_DRIFT_LENGTHEN = 0.10f
    const val AUTO_STABLE_WINDOWS = 2

    /** Suelo del denominador del drift: evita divisiones locas con niveles casi nulos. */
    const val MIN_DRIFT_BASE_G = 0.30f

    fun floor(noiseG: Float, p99G: Float): Float = maxOf(
        NOISE_MIN_G,
        if (noiseG.isFinite() && noiseG > 0f) noiseG * NOISE_MARGIN else 0f,
        if (p99G.isFinite() && p99G > 0f) p99G * AMBIENT_FACTOR else 0f
    )

    /**
     * Compensacion por densidad de muestreo: pedir 100 Hz no garantiza 100 Hz, y un
     * movil que entrega 50 Hz ve picos mas bajos que uno que entrega 200 Hz.
     */
    fun rateFactor(hz: Float): Float {
        if (!hz.isFinite() || hz <= 0f) return RATE_FACTOR_MAX
        return sqrt(hz / REFERENCE_HZ).coerceIn(RATE_FACTOR_MIN, RATE_FACTOR_MAX)
    }

    fun effective(userG: Float, floorG: Float, rateFactor: Float): Float {
        val user = userG.coerceIn(MIN_SENSITIVITY_G, MAX_SENSITIVITY_G)
        val base = maxOf(user, floorG)
        val low = user * RATE_FACTOR_MIN
        val high = minOf(user * PROFILE_MAX_RATIO, user + MAX_LIFT_G, MAX_SENSITIVITY_G)
        return (base * rateFactor).coerceIn(low, high)
    }
}

/** Histograma de dos mitades con rotacion: percentiles en O(1) y sin listas. */
private class Buckets {
    private var current = IntArray(BUCKET_COUNT)
    private var previous = IntArray(BUCKET_COUNT)
    private var currentCount = 0
    private var previousCount = 0

    val samples: Int get() = currentCount + previousCount
    val currentSamples: Int get() = currentCount
    val previousSamples: Int get() = previousCount

    fun add(index: Int) {
        current[index]++
        currentCount++
    }

    fun rotate() {
        previous = current
        current = IntArray(BUCKET_COUNT)
        previousCount = currentCount
        currentCount = 0
    }

    fun clear() {
        current = IntArray(BUCKET_COUNT)
        previous = IntArray(BUCKET_COUNT)
        currentCount = 0
        previousCount = 0
    }

    /** Percentil de la ventana; [previousWeight] da menos peso a la mitad antigua. */
    fun percentile(p: Float, previousWeight: Float): Float {
        val weight = if (previousWeight.isFinite()) previousWeight.coerceIn(0f, 1f) else 0f
        val total = currentCount + previousCount * weight
        if (total <= 0f) return 0f
        val target = total * p.coerceIn(0f, 1f)
        var accumulated = 0f
        for (index in 0 until BUCKET_COUNT) {
            accumulated += current[index] + previous[index] * weight
            if (accumulated >= target) return bucketValue(index)
        }
        return bucketValue(BUCKET_COUNT - 1)
    }

    fun currentPercentile(p: Float): Float = single(current, currentCount, p)

    fun previousPercentile(p: Float): Float = single(previous, previousCount, p)

    private fun single(values: IntArray, count: Int, p: Float): Float {
        if (count <= 0) return 0f
        val target = count * p.coerceIn(0f, 1f)
        var accumulated = 0f
        for (index in 0 until BUCKET_COUNT) {
            accumulated += values[index]
            if (accumulated >= target) return bucketValue(index)
        }
        return bucketValue(BUCKET_COUNT - 1)
    }

    private fun bucketValue(index: Int): Float =
        index.toFloat() / BUCKETS_PER_G + 0.5f / BUCKETS_PER_G

    companion object {
        const val BUCKETS_PER_G = 10f
        const val BUCKET_COUNT = 81
    }
}

/**
 * Ventana deslizante de aceleracion dinamica.
 *
 * Solo se le pasan las muestras por debajo del umbral vigente: un golpe no debe subir
 * el umbral que lo detecta. La mitad antigua pesa la mitad, asi que el p99 reacciona
 * en una ventana y no en dos.
 */
internal class MotionWindow(initialWindowMs: Long = DEFAULT_WINDOW_MS) {
    private val ambient = Buckets()
    private val quiet = Buckets()
    private var windowMs = initialWindowMs.coerceAtLeast(MIN_WINDOW_MS)
    private var halfStartedAt = UNSET_TIME
    private var firstAt = UNSET_TIME
    private var lastAt = UNSET_TIME
    private var pendingDrift: Float? = null

    val samples: Int get() = ambient.samples
    val spanMs: Long get() = if (firstAt == UNSET_TIME || lastAt == UNSET_TIME) 0L else lastAt - firstAt
    val lastSampleAt: Long get() = lastAt
    val currentSamples: Int get() = ambient.currentSamples
    val previousSamples: Int get() = ambient.previousSamples
    val activeWindowMs: Long get() = windowMs

    fun resize(newWindowMs: Long) {
        windowMs = newWindowMs.coerceAtLeast(MIN_WINDOW_MS)
        halfStartedAt = lastAt
    }

    /** Media de toda la ventana, con la mitad antigua pesando la mitad. */
    fun percentile(p: Float): Float = ambient.percentile(p, PREVIOUS_WEIGHT)

    /** Ruido del sensor, medido solo en los tramos en los que el movil esta quieto. */
    fun quietNoiseG(): Float = quiet.percentile(0.20f, PREVIOUS_WEIGHT)

    fun isQuietNow(): Boolean = percentile(0.50f) < SensitivityMath.QUIET_P50_MAX_G

    fun hasEnoughData(): Boolean =
        samples >= SensitivityMath.MIN_DATA_SAMPLES && spanMs >= SensitivityMath.MIN_DATA_MS

    fun currentP99(): Float = ambient.currentPercentile(0.99f)

    fun previousP99(): Float = ambient.previousPercentile(0.99f)

    /** Registra una muestra. Devuelve true cuando toca evaluar la ventana AUTO. */
    fun record(dynamicG: Float, timestamp: Long, quietSample: Boolean): Boolean {
        if (!dynamicG.isFinite() || dynamicG < 0f || timestamp < 0L) return false
        val index = (dynamicG * Buckets.BUCKETS_PER_G).toInt().coerceIn(0, Buckets.BUCKET_COUNT - 1)
        ambient.add(index)
        if (quietSample) quiet.add(index)
        return advanceTime(timestamp)
    }

    /** Avanza el reloj sin registrar muestra (los golpes quedan fuera del promedio). */
    fun advance(timestamp: Long): Boolean {
        if (timestamp < 0L) return false
        return advanceTime(timestamp)
    }

    /**
     * Diferencia entre las dos ultimas mitades, calculada justo antes de rotar.
     *
     * Se lee con [consumeDrift]: sirve para que la ventana AUTO acorte cuando el
     * camino cambia de verdad y alargue cuando lleva rato estable.
     */
    fun consumeDrift(): Float? = pendingDrift.also { pendingDrift = null }

    private fun advanceTime(timestamp: Long): Boolean {
        if (firstAt == UNSET_TIME) firstAt = timestamp
        if (halfStartedAt == UNSET_TIME) halfStartedAt = timestamp
        if (lastAt != UNSET_TIME && timestamp < lastAt) return false
        lastAt = timestamp
        if (timestamp - halfStartedAt < windowMs / 2) return false
        pendingDrift = if (ambient.previousSamples > 0) {
            val current = ambient.currentPercentile(0.99f)
            val previous = ambient.previousPercentile(0.99f)
            abs(current - previous) / maxOf(current, previous, SensitivityMath.MIN_DRIFT_BASE_G)
        } else {
            null
        }
        ambient.rotate()
        quiet.rotate()
        halfStartedAt = timestamp
        return true
    }

    fun reset() {
        ambient.clear()
        quiet.clear()
        halfStartedAt = UNSET_TIME
        firstAt = UNSET_TIME
        lastAt = UNSET_TIME
        pendingDrift = null
    }

    private companion object {
        const val DEFAULT_WINDOW_MS = 120_000L
        const val MIN_WINDOW_MS = 60_000L
        const val PREVIOUS_WEIGHT = 0.5f
        const val UNSET_TIME = Long.MIN_VALUE
    }
}

/** Niveles de muestreo: mas Hz = picos mejor medidos, mas bateria. */
internal enum class SamplingTier(val periodUs: Int, val hz: Int, val label: String) {
    QUIET(40_000, 25, "quieto"),
    CRUISE(20_000, 50, "crucero"),
    DEMANDING(10_000, 100, "demanda")
}

/** Resumen de la adaptacion para la interfaz y los registros. */
internal data class AdaptiveSnapshot(
    val effectiveThresholdG: Float,
    val floorG: Float,
    val noiseG: Float,
    val p99G: Float,
    val deliveredHz: Float,
    val usageMs: Long,
    val activeMinutes: Int,
    val auto: Boolean,
    val tier: SamplingTier
)

/**
 * Une todo lo anterior: aprende el perfil del movil, decide el umbral efectivo y elige
 * la frecuencia de muestreo. Es Kotlin puro para poder probarlo sin Android.
 */
internal class AdaptiveSensitivity(
    mode: AdaptationWindowMode = AdaptationWindowMode.AUTO,
    initialProfile: DeviceProfile = DeviceProfile(),
    private val autoStartMinutes: Int = DEFAULT_AUTO_MINUTES
) {
    private var modeValue = mode
    private var autoMinutes = autoStartMinutes.coerceIn(1, 3)
    private var profile = initialProfile
    private var userG = DEFAULT_USER_G

    private val window = MotionWindow(windowMs(modeValue, autoMinutes))

    private var effectiveCache = DEFAULT_USER_G
    private var floorCache = 0f
    private var rateHz = 0f
    private var rateSamples = 0
    private var rateStartedAt = UNSET_TIME

    private var stableWindows = 0
    private var lastAutoChangeAt = UNSET_TIME
    private var mergedAt = UNSET_TIME
    private var demandUntil = UNSET_TIME

    private var tier = SamplingTier.CRUISE
    private var tierSince = UNSET_TIME
    private var pendingTier: SamplingTier = SamplingTier.CRUISE
    private var pendingSince = UNSET_TIME

    val effectiveThresholdG: Float get() = effectiveCache
    val activeMinutes: Int get() = if (modeValue.isAuto) autoMinutes else modeValue.minutes
    val mode: AdaptationWindowMode get() = modeValue
    val currentTier: SamplingTier get() = tier

    fun setUserSensitivity(g: Float) {
        if (!g.isFinite()) return
        userG = g.coerceIn(SensitivityMath.MIN_SENSITIVITY_G, SensitivityMath.MAX_SENSITIVITY_G)
        recomputeEffective()
    }

    /**
     * Alimenta una muestra de aceleracion dinamica.
     *
     * [dynamicG] por encima del umbral vigente se considera golpe: no entra en el
     * promedio (si entrara, el propio golpe subiria el umbral que lo detecta).
     */
    fun record(dynamicG: Float, timestamp: Long) {
        if (!dynamicG.isFinite() || dynamicG < 0f || timestamp < 0L) return
        observeRate(timestamp)
        val quiet = window.isQuietNow()
        val rotated = if (dynamicG < effectiveCache) {
            window.record(dynamicG, timestamp, quiet)
        } else {
            window.advance(timestamp)
        }
        if (rotated) evaluateAutoWindow(timestamp)
        refreshPendingTier(timestamp)
        recomputeEffective()
    }

    /** Avisa de un golpe o candidato: sube la frecuencia una ventana completa. */
    fun onEvent(timestamp: Long) {
        if (timestamp < 0L) return
        demandUntil = timestamp + window.activeWindowMs
        pendingTier = SamplingTier.DEMANDING
        pendingSince = timestamp - holdUpMs()
    }

    fun desiredTier(timestamp: Long): SamplingTier = rawTier(timestamp)

    /**
     * true cuando toca cambiar de frecuencia. Solo el servicio decide si puede
     * hacerlo (fase MONITORING y sensor registrado).
     */
    fun shouldRebind(timestamp: Long): Boolean {
        if (pendingTier == tier) return false
        if (pendingSince == UNSET_TIME) return false
        val hold = if (pendingTier == SamplingTier.DEMANDING) holdUpMs() else holdDownMs()
        if (timestamp - pendingSince < hold) return false
        if (tierSince != UNSET_TIME && timestamp - tierSince < SensitivityMath.DWELL_MIN_MS) return false
        return true
    }

    fun onTierApplied(timestamp: Long) {
        tier = pendingTier
        tierSince = timestamp
    }

    fun setMode(mode: AdaptationWindowMode, timestamp: Long) {
        modeValue = mode
        stableWindows = 0
        lastAutoChangeAt = timestamp
        window.resize(windowMs(modeValue, autoMinutes))
    }

    /** Nueva salida: olvida lo aprendido en esta ventana, conserva el perfil. */
    fun resetWindow(timestamp: Long) {
        window.reset()
        mergedAt = UNSET_TIME
        stableWindows = 0
        pendingTier = tier
        pendingSince = timestamp
        demandUntil = UNSET_TIME
        recomputeEffective()
    }

    /** Olvida el perfil aprendido: vuelve a empezar como un movil nuevo. */
    fun resetProfile(timestamp: Long) {
        profile = DeviceProfile()
        window.reset()
        mergedAt = UNSET_TIME
        pendingSince = timestamp
        recomputeEffective()
    }

    /**
     * Funde la ventana actual en el perfil. Se llama cada minuto y al detener el
     * monitor.
     */
    fun mergeIntoProfile(): DeviceProfile {
        if (!window.hasEnoughData()) return profile
        val last = window.lastSampleAt
        if (last == UNSET_TIME) return profile
        val since = if (mergedAt == UNSET_TIME) last - window.spanMs else mergedAt
        val delta = (last - since).coerceAtLeast(0L)
        if (delta <= 0L) return profile
        val alpha = minOf(
            SensitivityMath.PROFILE_ALPHA_MAX,
            delta.toFloat() / (profile.usageMs + delta).toFloat()
        )
        val noise = window.quietNoiseG()
        val p99 = window.percentile(0.99f)
        val hz = if (rateHz > 0f) rateHz else profile.deliveredHz
        profile = DeviceProfile(
            noiseG = blend(profile.noiseG, noise, alpha),
            p99G = blend(profile.p99G, p99, alpha),
            deliveredHz = blend(profile.deliveredHz, hz, alpha),
            usageMs = profile.usageMs + delta
        )
        mergedAt = last
        recomputeEffective()
        return profile
    }

    fun snapshot(): AdaptiveSnapshot = AdaptiveSnapshot(
        effectiveThresholdG = effectiveCache,
        floorG = floorCache,
        noiseG = if (window.hasEnoughData()) window.quietNoiseG() else profile.noiseG,
        p99G = if (window.hasEnoughData()) window.percentile(0.99f) else profile.p99G,
        deliveredHz = rateHz,
        usageMs = profile.usageMs,
        activeMinutes = activeMinutes,
        auto = modeValue.isAuto,
        tier = tier
    )

    fun rateFactor(): Float = SensitivityMath.rateFactor(rateHz)

    private fun recomputeEffective() {
        val windowFloor = if (window.hasEnoughData()) {
            SensitivityMath.floor(window.quietNoiseG(), window.percentile(0.99f))
        } else {
            0f
        }
        val profileFloor = if (profile.usageMs >= SensitivityMath.PROFILE_MIN_USAGE_MS) {
            profile.floorG
        } else {
            0f
        }
        floorCache = maxOf(windowFloor, profileFloor)
        effectiveCache = SensitivityMath.effective(userG, floorCache, rateFactor())
    }

    private fun observeRate(timestamp: Long) {
        if (rateStartedAt == UNSET_TIME) {
            rateStartedAt = timestamp
            rateSamples = 0
            return
        }
        val elapsed = timestamp - rateStartedAt
        if (elapsed < 0L) {
            rateStartedAt = timestamp
            rateSamples = 0
            return
        }
        rateSamples++
        if (elapsed >= RATE_WINDOW_MS) {
            rateHz = rateSamples * 1000f / elapsed
            rateStartedAt = timestamp
            rateSamples = 0
        }
    }

    private fun refreshPendingTier(timestamp: Long) {
        val raw = rawTier(timestamp)
        if (raw != pendingTier) {
            pendingTier = raw
            pendingSince = timestamp
        }
    }

    private fun rawTier(timestamp: Long): SamplingTier {
        if (timestamp < demandUntil) return SamplingTier.DEMANDING
        if (!window.hasEnoughData()) return tier
        val p99 = window.percentile(0.99f)
        if (p99 >= SensitivityMath.DEMANDING_TIER_P99_G) return SamplingTier.DEMANDING
        val quiet = window.percentile(0.50f) < SensitivityMath.QUIET_TIER_P50_G &&
            p99 < SensitivityMath.QUIET_TIER_P99_G
        return if (quiet) SamplingTier.QUIET else SamplingTier.CRUISE
    }

    private fun evaluateAutoWindow(timestamp: Long) {
        if (!modeValue.isAuto) return
        val drift = window.consumeDrift() ?: return
        when {
            drift >= SensitivityMath.AUTO_DRIFT_SHORTEN -> {
                stableWindows = 0
                stepAutoWindow(timestamp, -1)
            }
            drift <= SensitivityMath.AUTO_DRIFT_LENGTHEN -> {
                stableWindows++
                if (stableWindows >= SensitivityMath.AUTO_STABLE_WINDOWS) {
                    stableWindows = 0
                    stepAutoWindow(timestamp, 1)
                }
            }
            else -> stableWindows = 0
        }
    }

    private fun stepAutoWindow(timestamp: Long, delta: Int) {
        if (lastAutoChangeAt != UNSET_TIME && timestamp - lastAutoChangeAt < holdDownMs()) return
        val next = (autoMinutes + delta).coerceIn(1, 3)
        if (next == autoMinutes) return
        autoMinutes = next
        lastAutoChangeAt = timestamp
        window.resize(windowMs(modeValue, autoMinutes))
    }

    private fun holdUpMs(): Long =
        maxOf(SensitivityMath.DWELL_MIN_MS / 2, window.activeWindowMs / 8)

    private fun holdDownMs(): Long =
        maxOf(SensitivityMath.DWELL_MIN_MS, window.activeWindowMs / 2)

    private fun blend(old: Float, new: Float, alpha: Float): Float {
        if (!new.isFinite() || new <= 0f) return old
        if (!old.isFinite() || old <= 0f) return new
        return old * (1f - alpha) + new * alpha
    }

    private companion object {
        const val DEFAULT_AUTO_MINUTES = 2
        const val DEFAULT_USER_G = 3.5f
        const val RATE_WINDOW_MS = 5_000L
        const val UNSET_TIME = Long.MIN_VALUE

        fun windowMs(mode: AdaptationWindowMode, autoMinutes: Int): Long =
            (if (mode.isAuto) autoMinutes else mode.minutes).coerceIn(1, 3) * 60_000L
    }
}

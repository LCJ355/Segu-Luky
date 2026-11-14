package com.intercom.segluky

/** Medida robusta de un impacto: ancho real, picos y saturacion. */
data class ImpactWidth(
    val widthMs: Long,
    val peaks: Int,
    val samples: Int,
    val saturated: Boolean,
    val closedAtMs: Long
)

/**
 * Mide cuanto dura un impacto con histeresis: entra con un umbral alto y no
 * cierra hasta que la senal lleva un rato por debajo del umbral de salida. Asi
 * un solo golpe no se parte en dos por un valle de pocos milisegundos, que es
 * justo el error que no permite comparar un bache con una colision.
 */
class ImpactWidthMeter(
    private val enterG: Float,
    exitRatio: Float = 0.6f,
    private val toleranceMs: Long = 20L
) {
    private val exitG = (enterG * exitRatio).coerceAtLeast(0.1f)
    private var active = false
    private var startedAtMs = 0L
    private var lastAboveAtMs = 0L
    private var previousAbove = false
    private var peaks = 0
    private var samples = 0
    private var saturated = false

    fun reset() {
        active = false
        startedAtMs = 0L
        lastAboveAtMs = 0L
        previousAbove = false
        peaks = 0
        samples = 0
        saturated = false
    }

    /** Devuelve la medida cuando el impacto se cierra; null mientras siga abierto. */
    fun add(atMs: Long, g: Float): ImpactWidth? {
        samples++
        if (g >= SATURATION_G) saturated = true
        val above = g >= exitG
        if (!active) {
            if (g >= enterG) {
                active = true
                startedAtMs = atMs
                lastAboveAtMs = atMs
                peaks = 1
                previousAbove = true
            }
            return null
        }
        if (above) {
            if (!previousAbove) peaks++
            lastAboveAtMs = atMs
            previousAbove = true
            return null
        }
        previousAbove = false
        if (atMs - lastAboveAtMs <= toleranceMs) return null
        val width = (lastAboveAtMs - startedAtMs).coerceAtLeast(0L)
        val result = ImpactWidth(width, peaks, samples, saturated, atMs)
        reset()
        return result
    }

    companion object {
        const val SATURATION_G = 15.9f
    }
}

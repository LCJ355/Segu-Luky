package com.intercom.segluky

/**
 * Memoria corta de muestras para analizar la secuencia alrededor de un golpe.
 * Guarda los ultimos 20 s en un buffer circular pequeno (a 100 Hz son 2000
 * muestras: unos pocos KB) y nunca crece mas.
 */
object ImpactSampleRecorder {
    private const val CAPACITY = 2_000
    private const val WINDOW_MS = 20_000L

    private val samples = arrayOfNulls<ImpactSample>(CAPACITY)
    private var next = 0
    private var count = 0

    @Synchronized
    fun record(atMs: Long, dynamicG: Float, gyroDegPerSec: Float = 0f) {
        // Se guarda la magnitud en el eje X y ceros en el resto: el detector ya
        // entrega aceleracion dinamica agregada, que es lo que analiza la secuencia.
        samples[next] = ImpactSample(atMs, dynamicG, 0f, 0f, gyroDegPerSec)
        next = (next + 1) % CAPACITY
        if (count < CAPACITY) count++
    }

    @Synchronized
    fun lastAtMs(): Long = if (count == 0) 0L else samples[(next - 1 + CAPACITY) % CAPACITY]?.atMs ?: 0L

    @Synchronized
    fun snapshot(): List<ImpactSample> {
        if (count == 0) return emptyList()
        val result = ArrayList<ImpactSample>(count)
        val start = (next - count + CAPACITY) % CAPACITY
        for (i in 0 until count) {
            samples[(start + i) % CAPACITY]?.let { result.add(it) }
        }
        val cutoff = lastAtMs() - WINDOW_MS
        return result.filter { it.atMs >= cutoff }
    }

    @Synchronized
    fun clear() {
        samples.fill(null)
        next = 0
        count = 0
    }
}

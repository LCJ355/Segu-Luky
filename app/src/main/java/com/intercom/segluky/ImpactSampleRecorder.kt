package com.intercom.segluky

/**
 * Memoria corta de muestras para analizar la secuencia alrededor de un golpe.
 *
 * La retencion la manda **el tiempo, no el numero de muestras**: el buffer tiene
 * huecos para 20 s a 200 Hz y, al llenarse, descarta lo mas viejo. Asi un movil
 * que entrega mas Hz de los pedidos (se han medido hasta 148 Hz pidiendo 100) no
 * recorta la ventana: con 2.000 muestras solo se cubrian 13,5 s a 148 Hz y la
 * ventana de recuperacion (15 s) no cabia.
 *
 * La muestra guarda la aceleracion dinamica agregada en el eje X y ceros en Y y
 * Z: **no es un vector de tres ejes**, solo una magnitud, asi que no se debe
 * inferir de aqui la direccion del golpe.
 */
object ImpactSampleRecorder {
    /** 20 s a 200 Hz: el doble del techo medido (148 Hz), con margen. */
    private const val CAPACITY = 4_000
    const val WINDOW_MS = 20_000L

    private val samples = arrayOfNulls<ImpactSample>(CAPACITY)
    private var next = 0
    private var count = 0

    @Synchronized
    fun record(atMs: Long, dynamicG: Float, gyroDegPerSec: Float = 0f) {
        samples[next] = ImpactSample(atMs, dynamicG, 0f, 0f, gyroDegPerSec)
        next = (next + 1) % CAPACITY
        if (count < CAPACITY) count++
    }

    @Synchronized
    fun lastAtMs(): Long = if (count == 0) 0L else samples[(next - 1 + CAPACITY) % CAPACITY]?.atMs ?: 0L

    @Synchronized
    fun count(): Int = count

    /** Ventana que cubren de verdad las muestras guardadas, como mucho [WINDOW_MS]. */
    @Synchronized
    fun retainedMs(): Long {
        if (count < 2) return 0L
        val oldest = samples[oldestIndex()]?.atMs ?: return 0L
        return (lastAtMs() - oldest).coerceIn(0L, WINDOW_MS)
    }

    @Synchronized
    fun snapshot(): List<ImpactSample> {
        if (count == 0) return emptyList()
        val start = oldestIndex()
        val cutoff = lastAtMs() - WINDOW_MS
        val result = ArrayList<ImpactSample>(count)
        for (i in 0 until count) {
            val sample = samples[(start + i) % CAPACITY] ?: continue
            if (sample.atMs >= cutoff) result.add(sample)
        }
        return result
    }

    @Synchronized
    fun clear() {
        samples.fill(null)
        next = 0
        count = 0
    }

    private fun oldestIndex(): Int = (next - count + CAPACITY) % CAPACITY
}

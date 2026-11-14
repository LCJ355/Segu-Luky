package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Test

class ImpactSequenceTest {

    private fun samples(peak: Float, crossings: Int, gyro: Float, count: Int = 6): List<ImpactSample> {
        val list = ArrayList<ImpactSample>()
        for (i in 0 until count) {
            val factor = if (crossings >= 2 && i % 2 == 0) 1f else 0.6f
            list.add(ImpactSample(1_000L + i * 50L, peak * factor, 0.1f, 0.1f, if (i == 1) gyro else 8f))
        }
        return list
    }

    @Test
    fun `un bache corto sin caida de velocidad se queda en bache`() {
        val verdict = ImpactSequence.evaluate(
            samples = samples(peak = 6f, crossings = 1, gyro = 20f),
            hitAtMs = 1_000L, thresholdG = 3.5f,
            speedBeforeKmh = 60f, speedAfterKmh = 60f, rpmAfter = 2_000
        )
        assertEquals(ImpactVerdict.BACHE, verdict.level)
    }

    @Test
    fun `choque con caida de velocidad y motor calado es choque`() {
        val verdict = ImpactSequence.evaluate(
            samples = samples(peak = 8f, crossings = 2, gyro = 140f),
            hitAtMs = 1_000L, thresholdG = 3.5f,
            speedBeforeKmh = 90f, speedAfterKmh = 0f, rpmAfter = 0
        )
        assertEquals(ImpactVerdict.CHOQUE, verdict.level)
    }

    @Test
    fun `sin velocidad no se inventa un choque`() {
        val verdict = ImpactSequence.evaluate(
            samples = samples(peak = 8f, crossings = 2, gyro = 140f),
            hitAtMs = 1_000L, thresholdG = 3.5f,
            speedBeforeKmh = null, speedAfterKmh = null, rpmAfter = null
        )
        assertEquals(ImpactVerdict.GOLPE_DURO, verdict.level)
    }

    @Test
    fun `un giro fuerte sin caida de velocidad no llega a choque`() {
        val verdict = ImpactSequence.evaluate(
            samples = samples(peak = 8f, crossings = 2, gyro = 200f),
            hitAtMs = 1_000L, thresholdG = 3.5f,
            speedBeforeKmh = 50f, speedAfterKmh = 50f, rpmAfter = 1_500
        )
        assertEquals(ImpactVerdict.GOLPE_DURO, verdict.level)
    }

    @Test
    fun `sin muestras no hay veredicto`() {
        val verdict = ImpactSequence.evaluate(
            samples = emptyList(), hitAtMs = 1_000L, thresholdG = 3.5f,
            speedBeforeKmh = 50f, speedAfterKmh = 0f, rpmAfter = 0
        )
        assertEquals(ImpactVerdict.RUIDO, verdict.level)
    }
}

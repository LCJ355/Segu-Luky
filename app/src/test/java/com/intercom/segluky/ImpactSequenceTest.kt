package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `el giro real sube la puntuacion cuando la actitud es fiable`() {
        val conGiro = ImpactSequence.evaluate(
            samples = samples(peak = 6f, crossings = 1, gyro = 140f),
            hitAtMs = 1_000L, thresholdG = 3.5f,
            speedBeforeKmh = 60f, speedAfterKmh = 60f, rpmAfter = 2_000,
            orientationConfidence = 1f
        )
        val sinGiro = ImpactSequence.evaluate(
            samples = samples(peak = 6f, crossings = 1, gyro = 0f),
            hitAtMs = 1_000L, thresholdG = 3.5f,
            speedBeforeKmh = 60f, speedAfterKmh = 60f, rpmAfter = 2_000,
            orientationConfidence = 1f
        )
        assertEquals(10, conGiro.score - sinGiro.score)
        assertTrue(conGiro.reasons.any { it.contains("giro") })
        assertFalse(sinGiro.reasons.any { it.contains("giro") })
    }

    @Test
    fun `sin confianza en la orientacion el giro no puntua`() {
        val conGiro = ImpactSequence.evaluate(
            samples = samples(peak = 6f, crossings = 1, gyro = 140f),
            hitAtMs = 1_000L, thresholdG = 3.5f,
            speedBeforeKmh = 60f, speedAfterKmh = 60f, rpmAfter = 2_000,
            orientationConfidence = 0.2f
        )
        val sinGiro = ImpactSequence.evaluate(
            samples = samples(peak = 6f, crossings = 1, gyro = 0f),
            hitAtMs = 1_000L, thresholdG = 3.5f,
            speedBeforeKmh = 60f, speedAfterKmh = 60f, rpmAfter = 2_000,
            orientationConfidence = 0.2f
        )
        assertEquals(sinGiro.score, conGiro.score)
        assertFalse(conGiro.reasons.any { it.contains("giro") })
    }

    @Test
    fun `un movil sin giroscopio da el mismo veredicto`() {
        val conLecturaBaja = ImpactSequence.evaluate(
            samples = samples(peak = 6f, crossings = 1, gyro = 8f),
            hitAtMs = 1_000L, thresholdG = 3.5f,
            speedBeforeKmh = 60f, speedAfterKmh = 60f, rpmAfter = 2_000,
            orientationConfidence = 1f
        )
        val sinGiroscopio = ImpactSequence.evaluate(
            samples = samples(peak = 6f, crossings = 1, gyro = 0f),
            hitAtMs = 1_000L, thresholdG = 3.5f,
            speedBeforeKmh = 60f, speedAfterKmh = 60f, rpmAfter = 2_000,
            orientationConfidence = 1f
        )
        assertEquals(sinGiroscopio.score, conLecturaBaja.score)
        assertEquals(sinGiroscopio.level, conLecturaBaja.level)
    }

    @Test
    fun `la cobertura de la ventana se mide con la muestra mas reciente`() {
        val muestras = listOf(
            ImpactSample(1_000L, 1f, 0f, 0f),
            ImpactSample(3_000L, 1f, 0f, 0f)
        )
        // 2 s de datos: la ventana de 15 s esta incompleta.
        assertEquals(2_000L, ImpactSequence.coveredMs(muestras, 1_000L, 15_000L))
        // Con mas historia por detras, la cobertura se corta en el tamano de la ventana.
        assertEquals(15_000L, ImpactSequence.coveredMs(muestras, -12_000L, 15_000L))
        // Sin muestras o con muestras anteriores al golpe no hay cobertura.
        assertEquals(0L, ImpactSequence.coveredMs(emptyList(), 1_000L, 15_000L))
        assertEquals(0L, ImpactSequence.coveredMs(muestras, 5_000L, 15_000L))
    }
}

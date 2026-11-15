package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpactSequenceTest {

    private fun samples(peak: Float, crossings: Int, gyro: Float, count: Int = 6): List<ImpactSample> {
        val list = ArrayList<ImpactSample>()
        var hits = 0
        for (i in 0 until count) {
            val isHit = hits < crossings && i % 2 == 0
            if (isHit) hits++
            val level = if (isHit) peak else 1f
            list.add(ImpactSample(1_000L + i * 50L, level, 0.1f, 0.1f, if (i == 0) gyro else 8f))
        }
        return list
    }

    @Test
    fun `un solo golpe no regala el bonus de rebote`() {
        // Un pico unico sube el umbral una vez. Contar muestras por encima daba
        // "muchos cruces" a cualquier golpe grabado a 50-100 Hz.
        val verdict = ImpactSequence.evaluate(
            samples = samples(peak = 6f, crossings = 1, gyro = 20f),
            hitAtMs = 1_000L, thresholdG = 3.5f,
            speedBeforeKmh = 60f, speedAfterKmh = 60f, rpmAfter = 2_000
        )
        assertFalse(verdict.reasons.any { it.contains("cruces") })
    }

    @Test
    fun `dos subidas por encima del umbral son rebote`() {
        val verdict = ImpactSequence.evaluate(
            samples = samples(peak = 6f, crossings = 2, gyro = 20f),
            hitAtMs = 1_000L, thresholdG = 3.5f,
            speedBeforeKmh = 60f, speedAfterKmh = 60f, rpmAfter = 2_000
        )
        assertTrue(verdict.reasons.any { it.contains("2 cruces") })
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
        // B - revision externa: la orientacion ya no es la puerta del giro; solo
        // matiza si se puede hablar de cambio de postura.
        assertEquals(sinGiro.score + 10, conGiro.score)
        assertTrue(conGiro.reasons.any { it.contains("sin orientacion calibrada") })
    }

    @Test
    fun `el giro sin calidad no puntua aunque haya rotacion`() {
        val sinCalidad = ImpactSequence.evaluate(
            samples = samples(peak = 6f, crossings = 1, gyro = 140f),
            hitAtMs = 1_000L, thresholdG = 3.5f,
            speedBeforeKmh = 60f, speedAfterKmh = 60f, rpmAfter = 2_000,
            orientationConfidence = 1f,
            gyroConfidence = 0.2f
        )
        val sinGiro = ImpactSequence.evaluate(
            samples = samples(peak = 6f, crossings = 1, gyro = 0f),
            hitAtMs = 1_000L, thresholdG = 3.5f,
            speedBeforeKmh = 60f, speedAfterKmh = 60f, rpmAfter = 2_000,
            orientationConfidence = 1f,
            gyroConfidence = 0.2f
        )
        assertEquals(sinGiro.score, sinCalidad.score)
        assertTrue(sinCalidad.reasons.any { it.contains("sin calidad") })
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
    fun `la fase de giro exige rotacion y calidad de giroscopio`() {
        val conCalidad = ImpactSequence.evaluate(
            samples = samples(peak = 6f, crossings = 1, gyro = 140f),
            hitAtMs = 1_000L, thresholdG = 3.5f,
            speedBeforeKmh = 60f, speedAfterKmh = 40f, rpmAfter = 2_000
        )
        assertEquals("giro", conCalidad.phase)

        val sinCalidad = ImpactSequence.evaluate(
            samples = samples(peak = 6f, crossings = 1, gyro = 140f),
            hitAtMs = 1_000L, thresholdG = 3.5f,
            speedBeforeKmh = 60f, speedAfterKmh = 40f, rpmAfter = 2_000,
            gyroConfidence = 0.2f
        )
        assertEquals("desaceleracion", sinCalidad.phase)
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

    @Test
    fun `el ancho a 100 Hz se puede usar y a 20 Hz no`() {
        val rapido = tren(spacingMs = 10L, count = 20, g = 6f)
        val calidadRapida = ImpactSequence.widthQuality(rapido, 3.5f, 1_000L)
        assertTrue(calidadRapida.reliable)
        assertTrue(calidadRapida.widthMs >= 150L)
        assertEquals(20, calidadRapida.samplesAbove)

        val lento = tren(spacingMs = 50L, count = 10, g = 6f)
        val calidadLenta = ImpactSequence.widthQuality(lento, 3.5f, 1_000L)
        assertFalse("a 20 Hz el ancho no es resoluble", calidadLenta.reliable)
    }

    @Test
    fun `la tolerancia del ancho crece con el intervalo de muestreo`() {
        // A 20 Hz el hueco de una muestra dura 50 ms: con 20 ms cerraria el golpe.
        val lento = tren(spacingMs = 50L, count = 10, g = 6f, dipAt = 4, dipG = 1f)
        val conToleranciaFija = ImpactSequence.widthMs(lento, 3.5f, 1_000L)
        val conToleranciaReal = ImpactSequence.widthQuality(lento, 3.5f, 1_000L)
        assertTrue(conToleranciaReal.toleranceMs > ImpactSequence.MIN_WIDTH_TOLERANCE_MS)
        assertTrue(
            "fija $conToleranciaFija ms, real ${conToleranciaReal.widthMs} ms",
            conToleranciaReal.widthMs > conToleranciaFija
        )
    }

    @Test
    fun `menos de tres muestras por encima del umbral no son fiables`() {
        val pocas = listOf(
            ImpactSample(1_000L, 6f, 0f, 0f),
            ImpactSample(1_010L, 1f, 0f, 0f),
            ImpactSample(1_020L, 6f, 0f, 0f)
        )
        assertFalse(ImpactSequence.widthQuality(pocas, 3.5f, 1_000L).reliable)
    }

    private fun tren(spacingMs: Long, count: Int, g: Float, dipAt: Int = -1, dipG: Float = 1f):
        List<ImpactSample> {
        val list = ArrayList<ImpactSample>(count)
        for (i in 0 until count) {
            val value = if (i == dipAt) dipG else g
            list.add(ImpactSample(1_000L + i * spacingMs, value, 0f, 0f))
        }
        return list
    }
}

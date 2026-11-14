package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpactPeriodicityTest {

    private fun verdict(score: Int, level: ImpactVerdict) =
        ImpactSequenceVerdict(score, level, "impacto", emptyList())

    private fun regular(hits: Int = ImpactPeriodicity.MIN_HITS, intervalMs: Long = 20_000L):
        ImpactPeriodicity {
        val periodicity = ImpactPeriodicity()
        var atMs = 0L
        repeat(hits) {
            periodicity.record(atMs)
            atMs += intervalMs
        }
        return periodicity
    }

    @Test
    fun `seis impactos regulares forman un patron de badenes`() {
        val periodicity = regular()
        assertEquals(ImpactPeriodicity.MIN_HITS, periodicity.count())
        assertEquals(5, periodicity.intervalsMs().size)
        assertTrue(periodicity.isRepetitivePattern())
        assertEquals(20_000L, periodicity.medianIntervalMs())
        assertTrue(periodicity.dispersion() < 0.01f)
    }

    @Test
    fun `con cinco impactos el ritmo aun no es concluyente`() {
        val periodicity = regular(hits = ImpactPeriodicity.MIN_HITS - 1)
        assertFalse(periodicity.isRepetitivePattern())
        val ajustada = periodicity.adjust(verdict(50, ImpactVerdict.GOLPE_DURO), benign = true)
        assertEquals(50, ajustada.score)
        assertEquals(ImpactVerdict.GOLPE_DURO, ajustada.level)
    }

    @Test
    fun `sin contexto benigno el patron no resta puntos`() {
        val periodicity = regular()
        assertTrue(periodicity.isRepetitivePattern())
        val ajustada = periodicity.adjust(verdict(45, ImpactVerdict.GOLPE_DURO), benign = false)
        assertEquals(45, ajustada.score)
        assertEquals(ImpactVerdict.GOLPE_DURO, ajustada.level)
        assertTrue(ajustada.reasons.isEmpty())
    }

    @Test
    fun `con contexto benigno el patron repetido baja un golpe duro a bache`() {
        val periodicity = regular()
        val ajustada = periodicity.adjust(verdict(45, ImpactVerdict.GOLPE_DURO), benign = true)
        assertEquals(45 - ImpactPeriodicity.PENALTY, ajustada.score)
        assertEquals(ImpactVerdict.BACHE, ajustada.level)
        assertTrue(ajustada.reasons.any { it.contains("patron") })
    }

    @Test
    fun `un choque dentro del patron de badenes sigue siendo choque`() {
        val periodicity = regular()
        val ajustada = periodicity.adjust(verdict(75, ImpactVerdict.CHOQUE), benign = true)
        assertEquals(ImpactVerdict.CHOQUE, ajustada.level)
        assertEquals(75, ajustada.score)
        assertTrue(ajustada.reasons.any { it.contains("patron") })
    }

    @Test
    fun `intervalos irregulares no son patron`() {
        val periodicity = ImpactPeriodicity()
        var atMs = 0L
        for (interval in listOf(5_000L, 40_000L, 7_000L, 55_000L, 10_000L)) {
            periodicity.record(atMs)
            atMs += interval
        }
        periodicity.record(atMs)
        assertEquals(ImpactPeriodicity.MIN_HITS, periodicity.count())
        assertFalse(periodicity.isRepetitivePattern())
        assertTrue(periodicity.dispersion() > ImpactPeriodicity.MAX_DISPERSION)
    }

    @Test
    fun `un intervalo raro suelto no rompe el patron`() {
        val periodicity = ImpactPeriodicity()
        var atMs = 0L
        for (interval in listOf(20_000L, 20_000L, 20_000L, 20_000L, 26_000L)) {
            periodicity.record(atMs)
            atMs += interval
        }
        periodicity.record(atMs)
        // La mediana y la MAD aguantan un intervalo raro mejor que media y tipica.
        assertTrue(periodicity.isRepetitivePattern())
    }

    @Test
    fun `impactos muy espaciados no son patron`() {
        val periodicity = regular(intervalMs = 600_000L)
        assertFalse(periodicity.isRepetitivePattern())
    }

    @Test
    fun `dos golpes en el mismo instante cuentan como uno`() {
        val periodicity = ImpactPeriodicity()
        periodicity.record(1_000L)
        periodicity.record(1_000L)
        periodicity.record(900L)
        assertEquals(1, periodicity.count())
    }

    @Test
    fun `la memoria se queda con los ultimos impactos`() {
        val periodicity = regular(hits = ImpactPeriodicity.MAX_HITS + 5)
        assertEquals(ImpactPeriodicity.MAX_HITS, periodicity.count())
    }

    @Test
    fun `reset olvida el ritmo anterior`() {
        val periodicity = regular()
        assertTrue(periodicity.isRepetitivePattern())
        periodicity.reset()
        assertEquals(0, periodicity.count())
        assertFalse(periodicity.isRepetitivePattern())
    }

    @Test
    fun `sin impactos no hay patron ni ajuste`() {
        val periodicity = ImpactPeriodicity()
        assertFalse(periodicity.isRepetitivePattern())
        assertTrue(periodicity.intervalsMs().isEmpty())
        assertEquals(0L, periodicity.medianIntervalMs())
        val ajustada = periodicity.adjust(verdict(30, ImpactVerdict.BACHE), benign = true)
        assertEquals(30, ajustada.score)
    }
}

package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpactPeriodicityTest {

    private fun verdict(score: Int, level: ImpactVerdict) =
        ImpactSequenceVerdict(score, level, "impacto", emptyList())

    private fun regular(hits: Int, intervalMs: Long = 20_000L): ImpactPeriodicity {
        val periodicity = ImpactPeriodicity()
        var atMs = 0L
        repeat(hits) {
            periodicity.record(atMs)
            atMs += intervalMs
        }
        return periodicity
    }

    @Test
    fun `cinco impactos regulares forman un patron de badenes`() {
        val periodicity = regular(hits = 5)
        assertEquals(5, periodicity.count())
        assertEquals(4, periodicity.intervalsMs().size)
        assertTrue(periodicity.isRepetitivePattern())
        assertTrue(periodicity.coefficientOfVariation() < 0.01f)
    }

    @Test
    fun `menos de cuatro impactos no bajan la sospecha`() {
        val periodicity = regular(hits = 3)
        assertFalse(periodicity.isRepetitivePattern())
        val adjusted = periodicity.adjust(verdict(50, ImpactVerdict.GOLPE_DURO))
        assertEquals(50, adjusted.score)
        assertEquals(ImpactVerdict.GOLPE_DURO, adjusted.level)
    }

    @Test
    fun `un choque dentro del patron de badenes sigue siendo choque`() {
        val periodicity = regular(hits = 5)
        val adjusted = periodicity.adjust(verdict(75, ImpactVerdict.CHOQUE))
        assertEquals(ImpactVerdict.CHOQUE, adjusted.level)
        assertEquals(75, adjusted.score)
        assertTrue(adjusted.reasons.any { it.contains("patron") })
    }

    @Test
    fun `el patron repetido baja un golpe duro a bache`() {
        val periodicity = regular(hits = 5)
        val adjusted = periodicity.adjust(verdict(45, ImpactVerdict.GOLPE_DURO))
        assertEquals(45 - ImpactPeriodicity.PENALTY, adjusted.score)
        assertEquals(ImpactVerdict.BACHE, adjusted.level)
        assertTrue(adjusted.reasons.any { it.contains("patron") })
    }

    @Test
    fun `intervalos irregulares no son patron`() {
        val periodicity = ImpactPeriodicity()
        var atMs = 0L
        for (interval in listOf(5_000L, 40_000L, 7_000L, 55_000L)) {
            periodicity.record(atMs)
            atMs += interval
        }
        periodicity.record(atMs)
        assertFalse(periodicity.isRepetitivePattern())
    }

    @Test
    fun `impactos muy espaciados no son patron`() {
        val periodicity = regular(hits = 5, intervalMs = 600_000L)
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
        val periodicity = regular(hits = 5)
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
        val adjusted = periodicity.adjust(verdict(30, ImpactVerdict.BACHE))
        assertEquals(30, adjusted.score)
    }
}

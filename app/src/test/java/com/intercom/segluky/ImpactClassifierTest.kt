package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpactClassifierTest {

    private fun signals(
        peakG: Float = 4f,
        thresholdG: Float = 3.5f,
        widthMs: Long = 80L,
        speedBefore: Float? = 80f,
        speedAfter: Float? = 80f,
        rpm: Int? = 2000,
        attitude: Float? = 2f,
        link: Boolean = true
    ) = ImpactSignals(
        peakG = peakG,
        thresholdG = thresholdG,
        jerkGPerSecond = 40f,
        widthMs = widthMs,
        speedBeforeKmh = speedBefore,
        speedAfterKmh = speedAfter,
        rpmAfter = rpm,
        attitudeChangeDegrees = attitude,
        hasVehicleLink = link
    )

    @Test
    fun `un bache grande no es un accidente`() {
        val assessment = ImpactClassifier.assess(signals(peakG = 5.5f, widthMs = 15L))
        assertEquals(ImpactVerdict.BACHE, assessment.verdict)
        assertTrue(assessment.score < 40)
    }

    @Test
    fun `un pico flojo es ruido`() {
        val assessment = ImpactClassifier.assess(
            signals(peakG = 2f, widthMs = 12L, speedBefore = 30f, speedAfter = 30f)
        )
        assertEquals(ImpactVerdict.RUIDO, assessment.verdict)
    }

    @Test
    fun `golpe ancho sin caida de velocidad es golpe duro`() {
        val assessment = ImpactClassifier.assess(signals(peakG = 6f, widthMs = 120L))
        assertEquals(ImpactVerdict.GOLPE_DURO, assessment.verdict)
    }

    @Test
    fun `caida de velocidad y parado es choque`() {
        val assessment = ImpactClassifier.assess(
            signals(peakG = 6f, widthMs = 120L, speedBefore = 90f, speedAfter = 0f)
        )
        assertEquals(ImpactVerdict.CHOQUE, assessment.verdict)
        assertTrue(assessment.reasons.any { it.contains("velocidad") })
    }

    @Test
    fun `motor calado tambien confirma choque`() {
        val assessment = ImpactClassifier.assess(
            signals(peakG = 6f, widthMs = 120L, speedBefore = 60f, speedAfter = 50f, rpm = 0)
        )
        assertEquals(ImpactVerdict.CHOQUE, assessment.verdict)
        assertTrue(assessment.reasons.any { it.contains("calado") })
    }

    @Test
    fun `sin velocidad no se inventa un choque`() {
        val assessment = ImpactClassifier.assess(
            signals(peakG = 7f, widthMs = 130L, speedBefore = null, speedAfter = null, rpm = null)
        )
        assertEquals(ImpactVerdict.GOLPE_DURO, assessment.verdict)
    }

    @Test
    fun `postura cambiada suma pero no basta sola`() {
        val withAttitude = ImpactClassifier.assess(
            signals(peakG = 6f, widthMs = 120L, attitude = 40f)
        )
        val without = ImpactClassifier.assess(
            signals(peakG = 6f, widthMs = 120L, attitude = 2f)
        )
        assertTrue(withAttitude.score > without.score)
    }

    @Test
    fun `la puntuacion nunca sale de cero a cien`() {
        val best = ImpactClassifier.assess(
            signals(peakG = 8f, widthMs = 200L, speedBefore = 120f, speedAfter = 0f, rpm = 0, attitude = 60f)
        )
        assertEquals(100, best.score)
        val worst = ImpactClassifier.assess(
            ImpactSignals(peakG = 0.1f, thresholdG = 3.5f, widthMs = 5L, speedBeforeKmh = 20f, speedAfterKmh = 20f)
        )
        assertEquals(0, worst.score)
        assertEquals(ImpactVerdict.RUIDO, worst.verdict)
    }
    @Test
    fun `el jerk solo aporta cuando no hay velocidad`() {
        val base = signals(peakG = 5f, widthMs = 40L, speedBefore = null, speedAfter = null, rpm = null)
        val sinJerk = ImpactClassifier.assess(base.copy(jerkGPerSecond = 5f))
        val conJerk = ImpactClassifier.assess(base.copy(jerkGPerSecond = 120f))
        assertTrue(conJerk.score > sinJerk.score)
        val conVelocidad = signals(peakG = 5f, widthMs = 40L)
        val a = ImpactClassifier.assess(conVelocidad.copy(jerkGPerSecond = 5f))
        val b = ImpactClassifier.assess(conVelocidad.copy(jerkGPerSecond = 120f))
        assertEquals(a.score, b.score)
    }
}
package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountdownBeepPatternTest {
    @Test
    fun beepsEveryTwoSecondsFromTwentyToTen() {
        assertEquals(2_000L, countdownBeepIntervalMs(20_000L))
        assertEquals(2_000L, countdownBeepIntervalMs(10_001L))
    }

    @Test
    fun beepsEverySecondFromTenToThree() {
        assertEquals(1_000L, countdownBeepIntervalMs(10_000L))
        assertEquals(1_000L, countdownBeepIntervalMs(3_001L))
    }

    @Test
    fun beepsTwicePerSecondDuringLastThreeSeconds() {
        assertEquals(500L, countdownBeepIntervalMs(3_000L))
        assertEquals(500L, countdownBeepIntervalMs(1L))
    }

    @Test
    fun noBeepBeforeTwentyOrAtZero() {
        assertNull(countdownBeepIntervalMs(20_001L))
        assertNull(countdownBeepIntervalMs(0L))
    }
}

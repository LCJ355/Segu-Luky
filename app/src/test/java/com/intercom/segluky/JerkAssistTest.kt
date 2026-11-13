package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Test

class JerkAssistTest {
    @Test
    fun jerkIsTheChangeOfAccelerationOverTime() {
        assertEquals(155f, JerkAssist.jerkGPerSecond(0.1f, 3.2f, 20L), 0.001f)
        assertEquals(0f, JerkAssist.jerkGPerSecond(0.1f, 3.2f, 1L), 0.001f)
        assertEquals(0f, JerkAssist.jerkGPerSecond(0.1f, 3.2f, 500L), 0.001f)
    }

    @Test
    fun belowTwentyKmhThereIsNoBonus() {
        assertEquals(0f, JerkAssist.bonusG(null, 200f), 0.001f)
        assertEquals(0f, JerkAssist.bonusG(19.9f, 200f), 0.001f)
    }

    @Test
    fun bonusStartsAtTwentyAndGrowsWithSpeed() {
        assertEquals(0.07f, JerkAssist.bonusG(20f, 200f), 0.001f)
        assertEquals(0.21f, JerkAssist.bonusG(35f, 200f), 0.001f)
        assertEquals(0.35f, JerkAssist.bonusG(50f, 200f), 0.001f)
    }

    @Test
    fun bonusIsBoundedByTheJerkScale() {
        assertEquals(0.175f, JerkAssist.bonusG(50f, 60f), 0.001f)
        assertEquals(0f, JerkAssist.bonusG(50f, 0f), 0.001f)
    }

    @Test
    fun assistedValueIsJustADynamicPlusBonus() {
        assertEquals(3.55f, JerkAssist.assistedDynamicG(3.2f, 50f, 200f), 0.001f)
    }
}

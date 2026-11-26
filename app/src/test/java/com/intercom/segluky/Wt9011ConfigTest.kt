package com.intercom.segluky

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Wt9011ConfigTest {

    @Test
    fun planUsesNineAxesFiftyHertzAndFastContent() {
        val plan = Wt9011Config.plan()
        assertEquals(5, plan.size)
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x69, 0x88.toByte(), 0xB5.toByte()),
            plan[0]
        )
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x24, 0x00, 0x00),
            plan[1]
        )
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x03, 0x08, 0x00),
            plan[2]
        )
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x02, 0x07, 0x00),
            plan[3]
        )
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x00, 0x00, 0x00),
            plan[4]
        )
    }

    @Test
    fun commandsAreSpacedEnoughForThisFirmware() {
        assertEquals(130L, Wt9011Config.COMMAND_GAP_MS)
        assertEquals(50, Wt9011Config.RATE_HZ)
        assertEquals(0x07, Wt9011Config.CONTENT_MASK)
    }

    @Test
    fun hexFormattingIsStable() {
        assertEquals("FF AA 69 88 B5", Wt9011Config.hex(Wt9011Config.UNLOCK))
        assertEquals("FF AA 00 00 00", Wt9011Config.hex(Wt9011Config.SAVE))
    }
}

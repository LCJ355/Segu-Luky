package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Wt9011ProtocolTest {

    private fun fastFrame(
        ax: Int,
        ay: Int,
        az: Int,
        gx: Int = 0,
        gy: Int = 0,
        gz: Int = 0,
        angleX: Int = 0,
        angleY: Int = 0,
        angleZ: Int = 0
    ): ByteArray {
        val out = ByteArray(20)
        out[0] = 0x55
        out[1] = 0x61.toByte()
        put16(out, 2, ax)
        put16(out, 4, ay)
        put16(out, 6, az)
        put16(out, 8, gx)
        put16(out, 10, gy)
        put16(out, 12, gz)
        put16(out, 14, angleX)
        put16(out, 16, angleY)
        put16(out, 18, angleZ)
        return out
    }

    private fun classicFrame(flag: Int, d0: Int, d1: Int, d2: Int, d3: Int): ByteArray {
        val out = ByteArray(11)
        out[0] = 0x55
        out[1] = flag.toByte()
        put16(out, 2, d0)
        put16(out, 4, d1)
        put16(out, 6, d2)
        put16(out, 8, d3)
        var sum = 0
        for (index in 0 until 10) sum += out[index].toInt() and 0xFF
        out[10] = (sum and 0xFF).toByte()
        return out
    }

    private fun put16(target: ByteArray, index: Int, value: Int) {
        target[index] = (value and 0xFF).toByte()
        target[index + 1] = ((value shr 8) and 0xFF).toByte()
    }

    @Test
    fun decodesFastFrameWithConfirmedScales() {
        val samples = mutableListOf<Wt9011Sample>()
        val parser = Wt9011Parser { 123L }
        parser.feed(fastFrame(ax = 0x1000, ay = 0, az = 0x0800, gx = 0x1000, angleX = 0x0800)) {
            samples += it
        }

        assertEquals(1, samples.size)
        val sample = samples.first()
        assertEquals(123L, sample.timestampNanos)
        assertEquals(2.0f, sample.ax, 0.0005f)
        assertEquals(1.0f, sample.az, 0.0005f)
        assertEquals(250.0f, sample.gx, 0.01f)
        assertEquals(11.25f, sample.angleX, 0.01f)
        assertTrue(sample.fastFrame)
        assertEquals(1L, parser.fastFrames)
    }

    @Test
    fun toleratesFragmentedNotificationsAndGarbage() {
        val frame = fastFrame(ax = 0, ay = 0, az = 0x4000)
        val samples = mutableListOf<Wt9011Sample>()
        val parser = Wt9011Parser()
        parser.feed(byteArrayOf(0x01, 0x02, 0x03) + frame.copyOfRange(0, 7)) { samples += it }
        assertEquals(0, samples.size)
        parser.feed(frame.copyOfRange(7, frame.size)) { samples += it }

        assertEquals(1, samples.size)
        assertEquals(8.0f, samples.first().az, 0.001f)
        assertEquals(3L, parser.droppedBytes)
    }

    @Test
    fun rejectsClassicFrameWithBadChecksum() {
        val frame = classicFrame(0x52, 0x1000, 0, 0x0800, 0)
        frame[10] = (frame[10] + 1).toByte()
        val samples = mutableListOf<Wt9011Sample>()
        val parser = Wt9011Parser()
        parser.feed(frame) { samples += it }

        assertTrue(samples.isEmpty())
        assertEquals(1L, parser.badChecksum)
    }

    @Test
    fun classicFramesAreAssembledIntoOneSample() {
        val samples = mutableListOf<Wt9011Sample>()
        val parser = Wt9011Parser { 456L }
        parser.feed(classicFrame(0x53, 0x1000, 0, 0, 0)) { samples += it }
        parser.feed(classicFrame(0x54, 0x0800, 0x0400, 0, 0)) { samples += it }
        assertEquals(0, samples.size)
        parser.feed(classicFrame(0x52, 0x2000, 0, 0x4000, 0x0100)) { samples += it }

        assertEquals(1, samples.size)
        val sample = samples.first()
        assertEquals(4.0f, sample.ax, 0.0005f)
        assertEquals(8.0f, sample.az, 0.0005f)
        assertEquals(250.0f, sample.gx, 0.01f)
        assertEquals(11.25f, sample.angleX, 0.01f)
        assertEquals(5.625f, sample.angleY, 0.01f)
        assertTrue(!sample.fastFrame)
    }

    @Test
    fun decodesTemperatureRegisterResponse() {
        val frame = ByteArray(20)
        frame[0] = 0x55
        frame[1] = 0x71.toByte()
        frame[2] = 0x40
        frame[3] = 0x00
        // 25,80 C = 2580 = 0x0A14, little-endian
        frame[4] = 0x14
        frame[5] = 0x0A
        val registers = mutableListOf<Wt9011Register>()
        val parser = Wt9011Parser()
        parser.registerListener = { registers += it }
        parser.feed(frame) { }

        assertEquals(1, registers.size)
        assertEquals(0x40, registers.first().address)
        assertEquals(2580, registers.first().values.first())
        assertEquals(1L, parser.registerFrames)
    }

    @Test
    fun headingIncreasesClockwise() {
        // El sensor entrega el angulo Z en sentido antihorario: +90 es girar a
        // la izquierda (oeste), que en una brujula es 270.
        assertEquals(270f, Wt9011Parser.headingFromYaw(90f), 0.01f)
        assertEquals(90f, Wt9011Parser.headingFromYaw(-90f), 0.01f)
        assertEquals(0f, Wt9011Parser.headingFromYaw(0f), 0.01f)
        assertEquals(180f, Wt9011Parser.headingFromYaw(180f), 0.01f)
        assertEquals(1f, Wt9011Parser.headingFromYaw(359f), 0.01f)
        assertEquals(359f, Wt9011Parser.headingFromYaw(-359f), 0.01f)
    }

    @Test
    fun resetClearsCountersAndPendingBytes() {
        val parser = Wt9011Parser()
        parser.feed(byteArrayOf(0x55)) {}
        parser.reset()
        assertEquals(0L, parser.droppedBytes)
        assertEquals(0L, parser.frames)
        parser.feed(fastFrame(0, 0, 0x4000)) { }
        assertEquals(1L, parser.frames)
    }
}

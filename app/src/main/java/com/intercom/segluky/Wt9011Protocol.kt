package com.intercom.segluky

import kotlin.math.sqrt

/**
 * Muestra del WT9011DCL-BT50.
 *
 * La trama de alta velocidad `55 61` trae aceleracion, giro y angulos en una
 * sola notificacion de 20 bytes. La variante clasica de 11 bytes (`0x52`,
 * `0x53`, `0x54`) se sigue aceptando por si el sensor queda con la
 * configuracion de fabrica: entonces la muestra se emite al llegar la
 * aceleracion, con el ultimo giro y angulo conocidos.
 */
data class Wt9011Sample(
    val timestampNanos: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gx: Float,
    val gy: Float,
    val gz: Float,
    val angleX: Float,
    val angleY: Float,
    val angleZ: Float,
    val fastFrame: Boolean
) {
    val accModuleG: Float get() = sqrt(ax * ax + ay * ay + az * az)

    val gyroModuleDps: Float get() = sqrt(gx * gx + gy * gy + gz * gz)
}

/** Respuesta `55 71` a una lectura de registro: direccion y 8 valores int16. */
data class Wt9011Register(
    val address: Int,
    val values: IntArray
)

/**
 * Parser incremental: tolera notificaciones partidas, bytes de basura y
 * checksums malos sin perder el flujo. Todo lo que entrega es inmutable.
 */
class Wt9011Parser(
    private val clockNanos: () -> Long = { System.nanoTime() }
) {
    private var buffer = ByteArray(256)
    private var length = 0

    private var lastGx = 0f
    private var lastGy = 0f
    private var lastGz = 0f
    private var lastAngleX = 0f
    private var lastAngleY = 0f
    private var lastAngleZ = 0f

    var droppedBytes = 0L
        private set
    var badChecksum = 0L
        private set
    var frames = 0L
        private set
    var fastFrames = 0L
        private set
    var registerFrames = 0L
        private set

    /** Se invoca al llegar una respuesta `55 71` (lectura de registro). */
    var registerListener: ((Wt9011Register) -> Unit)? = null

    fun reset() {
        length = 0
        lastGx = 0f
        lastGy = 0f
        lastGz = 0f
        lastAngleX = 0f
        lastAngleY = 0f
        lastAngleZ = 0f
        droppedBytes = 0L
        badChecksum = 0L
        frames = 0L
        fastFrames = 0L
        registerFrames = 0L
    }

    fun feed(data: ByteArray, count: Int = data.size, onSample: (Wt9011Sample) -> Unit) {
        if (count <= 0) return
        ensureCapacity(length + count)
        System.arraycopy(data, 0, buffer, length, count)
        length += count
        parse(onSample)
    }

    private fun ensureCapacity(required: Int) {
        if (required <= buffer.size) return
        var size = buffer.size
        while (size < required) size = size shl 1
        buffer = buffer.copyOf(size)
    }

    private fun parse(onSample: (Wt9011Sample) -> Unit) {
        var index = 0
        while (true) {
            while (index < length && (buffer[index].toInt() and 0xFF) != HEADER) {
                index++
                droppedBytes++
            }
            if (length - index < 2) break

            val flag = buffer[index + 1].toInt() and 0xFF
            val frameLength = when {
                flag == FLAG_FAST -> FAST_FRAME_LENGTH
                flag == FLAG_REGISTER -> REGISTER_FRAME_LENGTH
                flag in CLASSIC_FLAG_MIN..CLASSIC_FLAG_MAX -> CLASSIC_FRAME_LENGTH
                else -> 0
            }
            if (frameLength == 0) {
                index++
                droppedBytes++
                continue
            }
            if (length - index < frameLength) break

            if (flag != FLAG_FAST && flag != FLAG_REGISTER) {
                var sum = 0
                for (offset in 0 until frameLength - 1) {
                    sum += buffer[index + offset].toInt() and 0xFF
                }
                val expected = buffer[index + frameLength - 1].toInt() and 0xFF
                if ((sum and 0xFF) != expected) {
                    badChecksum++
                    index++
                    continue
                }
            }

            val timestamp = clockNanos()
            if (flag == FLAG_FAST) {
                val ax = i16f(buffer, index + 2) * ACC_SCALE
                val ay = i16f(buffer, index + 4) * ACC_SCALE
                val az = i16f(buffer, index + 6) * ACC_SCALE
                lastGx = i16f(buffer, index + 8) * GYRO_SCALE
                lastGy = i16f(buffer, index + 10) * GYRO_SCALE
                lastGz = i16f(buffer, index + 12) * GYRO_SCALE
                lastAngleX = i16f(buffer, index + 14) * ANGLE_SCALE
                lastAngleY = i16f(buffer, index + 16) * ANGLE_SCALE
                lastAngleZ = i16f(buffer, index + 18) * ANGLE_SCALE
                fastFrames++
                frames++
                onSample(
                    Wt9011Sample(
                        timestampNanos = timestamp,
                        ax = ax,
                        ay = ay,
                        az = az,
                        gx = lastGx,
                        gy = lastGy,
                        gz = lastGz,
                        angleX = lastAngleX,
                        angleY = lastAngleY,
                        angleZ = lastAngleZ,
                        fastFrame = true
                    )
                )
            } else if (flag == FLAG_REGISTER) {
                val address = (buffer[index + 2].toInt() and 0xFF) or
                    ((buffer[index + 3].toInt() and 0xFF) shl 8)
                val values = IntArray(8) { slot ->
                    i16(buffer, index + 4 + slot * 2)
                }
                registerFrames++
                registerListener?.invoke(Wt9011Register(address, values))
            } else {
                when (flag) {
                    FLAG_ACC -> {
                        val ax = i16f(buffer, index + 2) * ACC_SCALE
                        val ay = i16f(buffer, index + 4) * ACC_SCALE
                        val az = i16f(buffer, index + 6) * ACC_SCALE
                        frames++
                        onSample(
                            Wt9011Sample(
                                timestampNanos = timestamp,
                                ax = ax,
                                ay = ay,
                                az = az,
                                gx = lastGx,
                                gy = lastGy,
                                gz = lastGz,
                                angleX = lastAngleX,
                                angleY = lastAngleY,
                                angleZ = lastAngleZ,
                                fastFrame = false
                            )
                        )
                    }

                    FLAG_GYRO -> {
                        lastGx = i16f(buffer, index + 2) * GYRO_SCALE
                        lastGy = i16f(buffer, index + 4) * GYRO_SCALE
                        lastGz = i16f(buffer, index + 6) * GYRO_SCALE
                    }

                    FLAG_ANGLE -> {
                        lastAngleX = i16f(buffer, index + 2) * ANGLE_SCALE
                        lastAngleY = i16f(buffer, index + 4) * ANGLE_SCALE
                        lastAngleZ = i16f(buffer, index + 6) * ANGLE_SCALE
                    }
                }
            }

            index += frameLength
        }

        if (index > 0) {
            if (index < length) {
                System.arraycopy(buffer, index, buffer, 0, length - index)
            }
            length -= index
        }
    }

    companion object {
        const val HEADER = 0x55
        const val FLAG_FAST = 0x61
        const val FAST_FRAME_LENGTH = 20

        const val FLAG_REGISTER = 0x71
        const val REGISTER_FRAME_LENGTH = 20

        const val FLAG_ACC = 0x52
        const val FLAG_GYRO = 0x53
        const val FLAG_ANGLE = 0x54

        private const val CLASSIC_FLAG_MIN = 0x51
        private const val CLASSIC_FLAG_MAX = 0x5F
        private const val CLASSIC_FRAME_LENGTH = 11

        /** 16 g sobre 32768 cuentas. */
        const val ACC_SCALE = 16f / 32768f

        /** 2000 grados/s sobre 32768 cuentas. */
        const val GYRO_SCALE = 2000f / 32768f

        /** 180 grados sobre 32768 cuentas. */
        const val ANGLE_SCALE = 180f / 32768f

        fun i16(payload: ByteArray, index: Int): Int {
            val low = payload[index].toInt() and 0xFF
            val high = payload[index + 1].toInt() and 0xFF
            return ((high shl 8) or low).toShort().toInt()
        }

        fun i16f(payload: ByteArray, index: Int): Float = i16(payload, index).toFloat()
    }
}

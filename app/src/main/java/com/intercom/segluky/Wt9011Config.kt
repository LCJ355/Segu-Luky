package com.intercom.segluky

/**
 * Configuracion del WT9011DCL-BT50.
 *
 * El sensor viene de fabrica a 9,9 Hz y en 9 ejes. Para el vuelco no hace falta
 * el magnetometro, pero el rumbo del sensor (angulo Z) si: se deja en 9 ejes y
 * a 50 Hz. La inclinacion se sigue calculando con la gravedad (X/Y), asi que el
 * magnetometro no altera el detector.
 *
 * Las ordenes se escriben con [COMMAND_GAP_MS] entre ellas: si se mandan de
 * golpe, el firmware solo aplica la primera.
 */
object Wt9011Config {

    const val RATE_HZ = 50
    const val CONTENT_MASK = 0x07 // aceleracion + giro + angulo
    const val COMMAND_GAP_MS = 130L

    private const val REG_CONTENT = 0x02
    private const val REG_RATE = 0x03
    private const val REG_AXIS6 = 0x24

    private const val AXIS_6 = 0x01
    private const val AXIS_9 = 0x00

    private const val RATE_CODE_50_HZ = 0x08

    val UNLOCK = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x69, 0x88.toByte(), 0xB5.toByte())
    val SAVE = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x00, 0x00, 0x00)

    /** 6 ejes: solo acelerometro + giroscopio. El rumbo queda relativo. */
    val AXIS_6_COMMAND: ByteArray = registerCommand(REG_AXIS6, AXIS_6)

    /** 9 ejes: activa el magnetometro y da el rumbo magnetico del sensor. */
    val AXIS_9_COMMAND: ByteArray = registerCommand(REG_AXIS6, AXIS_9)

    val RATE_50_HZ_COMMAND: ByteArray = registerCommand(REG_RATE, RATE_CODE_50_HZ)

    val CONTENT_COMMAND: ByteArray = registerCommand(REG_CONTENT, CONTENT_MASK)

    /**
     * Lectura del registro 0x40 (temperatura interna). Es solo lectura: no
     * necesita desbloquear y se puede enviar mientras llegan las tramas 0x61.
     */
    val TEMPERATURE_READ: ByteArray = byteArrayOf(
        0xFF.toByte(),
        0xAA.toByte(),
        0x27,
        0x40,
        0x00
    )

    /** Secuencia completa de conexion. */
    fun plan(): List<ByteArray> = listOf(
        UNLOCK,
        AXIS_9_COMMAND,
        RATE_50_HZ_COMMAND,
        CONTENT_COMMAND,
        SAVE
    )

    fun registerCommand(register: Int, value: Int): ByteArray = byteArrayOf(
        0xFF.toByte(),
        0xAA.toByte(),
        register.toByte(),
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte()
    )

    fun hex(bytes: ByteArray): String = buildString(bytes.size * 3) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            if (value < 16) append('0')
            append(value.toString(16).uppercase())
            append(' ')
        }
    }.trim()
}

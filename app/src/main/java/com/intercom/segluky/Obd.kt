package com.intercom.segluky

/**
 * Datos de conexion con un adaptador ELM327 sacados del APK de referencia
 * `docs/referencia/OBDII/Torque_Pro v1.8.16.apk` (analisis estatico del DEX):
 *
 * - transportes: Bluetooth clasico con `createRfcommSocketToServiceRecord` y UUID
 *   `00001101-0000-1000-8000-00805F9B34FB`, y WiFi por el puerto `35000`;
 * - comandos de inicializacion: `ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATH0/ATH1`,
 *   `ATSP0`, `ATDP`, `ATDPN`, `ATRV`, `ATMA`;
 * - protocolos: ISO 15765-4 CAN (11/250, 11/500, 29/250, 29/500), J1850, ISO 9141,
 *   KWP;
 * - PIDs usados: `0100`, `0101`, `0105`, `010C`, `010D`, `010F`, `0111`, `011C`,
 *   `012F`, `0140`.
 *
 * Aqui no se copia codigo de Torque: solo se usan esos datos publicos de
 * protocolo para hablar con el adaptador.
 */
enum class ObdStatus {
    OFF,
    NO_PERMISSION,
    NO_DEVICE,
    CONNECTING,
    READY,
    NO_DATA,
    ERROR
}

/** Estado del enlace con el coche, tal y como lo pinta la tarjeta. */
data class ObdState(
    val status: ObdStatus = ObdStatus.OFF,
    val detail: String = "",
    val protocol: String = "",
    val speedKmh: Float? = null,
    val rpm: Int? = null,
    val throttlePercent: Int? = null,
    val coolantC: Int? = null,
    val voltage: Float? = null,
    val dtcCount: Int? = null,
    val readingsPerSecond: Int = 0,
    val updatedAtMs: Long = 0L,
    val deviceName: String = "",
    val deviceAddress: String = ""
) {
    val isConnected: Boolean get() = status == ObdStatus.READY || status == ObdStatus.NO_DATA

    /** Velocidad del coche utilizable ahora mismo (2 s de antiguedad como mucho). */
    fun freshSpeed(nowMs: Long): Float? =
        speedKmh?.takeIf { updatedAtMs > 0L && nowMs - updatedAtMs <= SPEED_MAX_AGE_MS }

    companion object {
        const val SPEED_MAX_AGE_MS = 2_000L
    }
}

/** Un adaptador emparejado que el usuario puede elegir. */
data class ObdDevice(val name: String, val address: String) {
    val label: String get() = if (name.isBlank()) address else "$name ($address)"
}

/** PIDs y comandos; los valores salen de la tabla estandar OBD-II. */
object ObdPids {
    const val SPEED = "010D"
    const val RPM = "010C"
    const val THROTTLE = "0111"
    const val COOLANT = "0105"
    const val MONITORS = "0101"
    const val PROTOCOL = "ATDPN"
    const val VOLTAGE = "ATRV"

    /** Secuencia de arranque del ELM327 (la misma familia de comandos que usa Torque). */
    val init: List<String> = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATSP0")

    const val INIT_TIMEOUT_MS = 3_000L
    const val COMMAND_TIMEOUT_MS = 900L
}

/**
 * Parser de respuestas del ELM327. Todo lo que entra es texto; lo que no se
 * entiende devuelve null y la tarjeta lo muestra como "—".
 */
object ObdParser {

    private val ERROR_MARKERS = listOf(
        "NO DATA",
        "UNABLE TO CONNECT",
        "BUS INIT",
        "CAN ERROR",
        "STOPPED",
        "ERROR",
        "?"
    )

    /** Quita CR, LF y espacios sobrantes (ATS0 ya quita los del medio). */
    fun compact(raw: String): String =
        raw.replace("\r", "").replace("\n", "").replace(" ", "").trim().uppercase()

    fun isError(raw: String): Boolean = ERROR_MARKERS.any { raw.uppercase().contains(it) }

    /**
     * Bytes de la respuesta a un PID. Busca "41" + PID y devuelve los bytes que
     * siguen. Acepta respuestas separadas por lineas y con o sin espacios.
     */
    fun payloadBytes(raw: String, pid: String): List<Int>? {
        if (isError(raw)) return null
        val hex = compact(raw).filter { it.isDigit() || it in 'A'..'F' }
        val needle = "41" + pid.uppercase()
        val at = hex.indexOf(needle)
        if (at < 0) return null
        val rest = hex.substring(at + needle.length)
        if (rest.length < 2) return null
        val bytes = ArrayList<Int>()
        var i = 0
        while (i + 1 < rest.length && bytes.size < 8) {
            val value = rest.substring(i, i + 2).toIntOrNull(16) ?: break
            bytes.add(value)
            i += 2
        }
        return bytes.ifEmpty { null }
    }

    /** `010D`: un byte, km/h directo. */
    fun speedKmh(raw: String): Float? =
        payloadBytes(raw, "0D")?.firstOrNull()?.toFloat()

    /** `010C`: dos bytes, rpm = ((A*256)+B)/4. */
    fun rpm(raw: String): Int? {
        val bytes = payloadBytes(raw, "0C") ?: return null
        if (bytes.size < 2) return null
        return (bytes[0] * 256 + bytes[1]) / 4
    }

    /** `0111`: un byte, % = A*100/255. */
    fun throttlePercent(raw: String): Int? =
        payloadBytes(raw, "11")?.firstOrNull()?.let { it * 100 / 255 }

    /** `0105`: un byte menos 40, en grados. */
    fun coolantC(raw: String): Int? =
        payloadBytes(raw, "05")?.firstOrNull()?.minus(40)

    /** `0101`: los siete bits bajos del primer byte son el numero de codigos. */
    fun dtcCount(raw: String): Int? =
        payloadBytes(raw, "01")?.firstOrNull()?.and(0x7F)

    /** `ATDPN`: "A6" o "6" segun la version del adaptador. */
    fun protocol(raw: String): String? {
        val text = raw.replace("\r", " ").replace("\n", " ").trim()
        if (text.isEmpty() || isError(text)) return null
        val token = text.split(" ").firstOrNull { it.any { c -> c.isLetterOrDigit() } } ?: return null
        val code = token.trim().uppercase().removePrefix("A")
        return when (code) {
            "0" -> "AUTO"
            "1" -> "SAE J1850 PWM"
            "2" -> "SAE J1850 VPW"
            "3" -> "ISO 9141-2"
            "4" -> "ISO 14230-4 (KWP 5 baud)"
            "5" -> "ISO 14230-4 (KWP rapido)"
            "6" -> "ISO 15765-4 CAN 11/500"
            "7" -> "ISO 15765-4 CAN 29/500"
            "8" -> "ISO 15765-4 CAN 11/250"
            "9" -> "ISO 15765-4 CAN 29/250"
            "A" -> "SAE J1939 CAN"
            "B" -> "ISO 15765-4 CAN (usuario)"
            "C" -> "ISO 15765-4 CAN (usuario)"
            else -> token.trim()
        }
    }

    /** `ATRV`: "12.6V" -> 12.6. */
    fun voltage(raw: String): Float? {
        val match = Regex("""([0-9]{1,2}(?:[.,][0-9]{1,2})?)\s*V""").find(raw)
        return match?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull()
    }
}

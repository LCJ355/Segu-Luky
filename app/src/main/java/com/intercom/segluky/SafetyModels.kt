package com.intercom.segluky

import java.util.Locale

const val MAP_LINK_TOKEN = "{MAPA}"
const val DEFAULT_WHATSAPP_MESSAGE = "Segu-Luky: posible accidente. Mi ubicacion: {MAPA}"

enum class SafetyPhase {
    STOPPED,
    MONITORING,
    VALIDATING,
    FALL_CANDIDATE,
    COUNTDOWN
}

data class SafetySnapshot(
    val phase: SafetyPhase = SafetyPhase.STOPPED,
    val detail: String = "",
    val speedKmh: Float? = null,
    val movementLevel: Float = 0f,
    val movementG: Float? = null,
    val gpsAccuracyMeters: Float? = null,
    val gpsAvailable: Boolean = false,
    val gpsFixAgeSeconds: Long? = null,
    val sensorAvailable: Boolean = false,
    val sensorDetail: String = "",
    val orientationAvailable: Boolean = false,
    val orientationTiltDegrees: Float? = null,
    val orientationDetail: String = "",
    val intercomInstalled: Boolean = false,
    val validationStartedAt: Long? = null,
    val immobileSince: Long? = null,
    val countdownEndsAt: Long? = null,
    val eventLog: List<String> = emptyList(),
    val voiceLevelDb: Float? = null,
    val voiceRecognizing: Boolean = false,
    val voiceDetail: String = "",
    val dispatchInProgress: Boolean = false,
    /** Umbral realmente aplicado (ajuste del usuario o piso aprendido del movil). */
    val effectiveThresholdG: Float? = null,
    /** Pico de aceleracion desde que arranco el monitor. */
    val ridePeakG: Float? = null,
    /** Ruido en reposo que ha medido este movil. */
    val deviceNoiseG: Float? = null,
    /** p99 de la aceleracion normal de marcha de este movil. */
    val deviceP99G: Float? = null,
    /** Muestras por segundo que entrega el sensor de verdad (medidas, no pedidas). */
    val samplingHz: Int? = null,
    /** Ventana de aprendizaje activa, en minutos. */
    val adaptationWindowMinutes: Int = 2,
    /** true cuando la ventana la elige la app y no el usuario. */
    val adaptationWindowAuto: Boolean = true,
    /** Uso acumulado con el que se ha afinado el perfil de este movil. */
    val learningUsageMs: Long = 0L,
    /** Resultado de la PRUEBA DE GOLPE. */
    val hitTestVerdict: String? = null,
    val hitTestRunning: Boolean = false
)

data class EmergencyContact(
    val name: String,
    val phone: String,
    val callEnabled: Boolean = true,
    val whatsappEnabled: Boolean = true,
    val whatsappMessage: String = DEFAULT_WHATSAPP_MESSAGE,
    val telegramEnabled: Boolean = false,
    val telegramUsername: String = "",
    val telegramChatId: String = ""
) {
    fun normalized(): EmergencyContact = copy(
        name = name.trim(),
        phone = phone.trim(),
        whatsappMessage = ensureMapLinkToken(whatsappMessage),
        telegramUsername = TelegramSos.normalizeUsername(telegramUsername).orEmpty(),
        telegramChatId = TelegramSos.normalizeChatId(telegramChatId).orEmpty()
    )

    fun whatsappMessageFor(mapLink: String?): String =
        ensureMapLinkToken(whatsappMessage).replace(MAP_LINK_TOKEN, mapLink ?: "ubicacion GPS no disponible")

    /** El mismo texto sirve para WhatsApp y para Telegram. */
    fun sosMessageFor(mapLink: String?): String = whatsappMessageFor(mapLink)

    val telegramReadyForBot: Boolean
        get() = telegramEnabled && telegramChatId.isNotEmpty()
}

fun ensureMapLinkToken(template: String): String {
    val message = template.trim().ifBlank { DEFAULT_WHATSAPP_MESSAGE }
    return if (message.contains(MAP_LINK_TOKEN)) message else "$message\nUbicacion: $MAP_LINK_TOKEN"
}

fun googleMapsLink(latitude: Double, longitude: Double): String =
    "https://www.google.com/maps/search/?api=1&query=" +
        String.format(Locale.US, "%.6f,%.6f", latitude, longitude)

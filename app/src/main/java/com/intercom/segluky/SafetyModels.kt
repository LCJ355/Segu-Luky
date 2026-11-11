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
    val dispatchInProgress: Boolean = false
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

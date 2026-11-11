package com.intercom.segluky

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Canal SOS de Telegram.
 *
 * Via principal: Bot API (`sendMessage`), que envia el aviso sin abrir ninguna app
 * ni necesitar la pantalla desbloqueada. Via de respaldo: enlace `https://t.me/...`
 * abierto por el servicio de accesibilidad, que escribe el mensaje en el chat.
 */
object TelegramSos {
    const val PACKAGE = "org.telegram.messenger"

    private const val BOT_API_BASE = "https://api.telegram.org"
    private const val TIMEOUT_MS = 15_000
    private const val MAX_DETAIL_LENGTH = 160

    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    fun chatUrl(username: String): String = "https://t.me/$username"

    /** Acepta "@nombre", "nombre" o un enlace t.me completo. */
    fun normalizeUsername(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val candidate = trimmed
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .removePrefix("t.me/")
            .removePrefix("telegram.me/")
            .substringBefore('?')
            .substringBefore('/')
            .removePrefix("@")
            .trim()
        if (candidate.length !in MIN_USERNAME_LENGTH..MAX_USERNAME_LENGTH) return null
        if (candidate.startsWith("_") || candidate.endsWith("_")) return null
        if (!candidate.all { it.isUsernameChar() }) return null
        return candidate
    }

    /** Chat id numerico del Bot API; los grupos son negativos. */
    fun normalizeChatId(raw: String): String? {
        val candidate = raw.trim()
        if (candidate.isEmpty()) return null
        val digits = candidate.removePrefix("-")
        if (digits.isEmpty() || digits.length > 20) return null
        if (!digits.all { it in '0'..'9' }) return null
        return candidate
    }

    fun normalizeBotToken(raw: String): String? {
        val candidate = raw.trim()
        val separator = candidate.indexOf(':')
        if (separator <= 0) return null
        val id = candidate.substring(0, separator)
        val secret = candidate.substring(separator + 1)
        if (id.length !in 5..15 || !id.all { it in '0'..'9' }) return null
        if (secret.length < 25) return null
        if (!secret.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }) {
            return null
        }
        return candidate
    }

    data class BotResult(val ok: Boolean, val detail: String = "")

    /** Bloqueante: llamar siempre desde un hilo de trabajo. */
    fun sendViaBot(token: String, chatId: String, message: String): BotResult {
        val normalizedToken = normalizeBotToken(token)
            ?: return BotResult(false, "token del bot no valido")
        val normalizedChat = normalizeChatId(chatId)
            ?: return BotResult(false, "chat id no valido")
        val body = buildString {
            append("chat_id=").append(encode(normalizedChat))
            append("&text=").append(encode(message))
            append("&disable_web_page_preview=true")
        }
        return runCatching {
            val connection = URL("$BOT_API_BASE/bot$normalizedToken/sendMessage")
                .openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8"
                )
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val ok = code in 200..299 &&
                    runCatching { JSONObject(response).optBoolean("ok") }.getOrDefault(false)
                if (ok) {
                    BotResult(true)
                } else {
                    val description = runCatching {
                        JSONObject(response).optString("description")
                    }.getOrDefault("").ifBlank { "HTTP $code" }
                    BotResult(false, description.take(MAX_DETAIL_LENGTH))
                }
            } finally {
                connection.disconnect()
            }
        }.getOrElse { BotResult(false, it.message.orEmpty().take(MAX_DETAIL_LENGTH)) }
    }

    internal fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun Char.isUsernameChar(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_'

    private const val MIN_USERNAME_LENGTH = 4
    private const val MAX_USERNAME_LENGTH = 32
}

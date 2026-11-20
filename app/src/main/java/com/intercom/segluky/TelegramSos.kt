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

    /** Un chat que ya ha hablado con el bot: destino valido del Bot API. */
    data class TelegramChat(val id: String, val title: String, val kind: String) {
        val label: String get() = if (title == id) "$kind $id" else "$title ($kind $id)"
    }

    /** Resultado de pedir al bot los chats que le han escrito. */
    sealed class ChatSearch {
        /** Chats ya vistos por el bot; la lista puede venir vacia. */
        data class Found(val chats: List<TelegramChat>) : ChatSearch()

        data class Failed(val detail: String) : ChatSearch()
    }

    /**
     * Chats que ya escribieron al bot, para no tener que copiar numeros a mano.
     *
     * Un bot no puede iniciar una conversacion: hasta que alguien no le escriba,
     * `getUpdates` viene vacio. Se pide con `offset=-100` para recibir los ultimos
     * 100 avisos aunque otra consulta los hubiera confirmado antes.
     *
     * Bloqueante: llamar siempre desde un hilo de trabajo.
     */
    fun discoverChats(token: String): ChatSearch {
        val normalizedToken = normalizeBotToken(token)
            ?: return ChatSearch.Failed("token del bot no valido")
        return runCatching {
            val connection = URL("$BOT_API_BASE/bot$normalizedToken/getUpdates?limit=100&offset=-100")
                .openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Accept", "application/json")
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val json = runCatching { JSONObject(response) }.getOrNull()
                val ok = code in 200..299 && json?.optBoolean("ok") == true
                if (ok) {
                    ChatSearch.Found(parseChats(response))
                } else {
                    ChatSearch.Failed(discoveryFailure(code, json))
                }
            } finally {
                connection.disconnect()
            }
        }.getOrElse { ChatSearch.Failed(it.message.orEmpty().take(MAX_DETAIL_LENGTH)) }
    }

    /**
     * Chats de la respuesta de `getUpdates`, del mas reciente al mas antiguo.
     *
     * Se miran los cuatro avisos que llevan chat: un mensaje normal, uno editado,
     * una publicacion de canal y el aviso de que el bot entro o salio de un grupo.
     * Ese ultimo es la unica forma de conocer un grupo en el que aun no se ha
     * escrito nada.
     */
    internal fun parseChats(body: String): List<TelegramChat> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val updates = root.optJSONArray("result") ?: return emptyList()
        val chats = ArrayList<TelegramChat>()
        for (index in 0 until updates.length()) {
            val update = updates.optJSONObject(index) ?: continue
            val event = update.optJSONObject("my_chat_member")
                ?: update.optJSONObject("message")
                ?: update.optJSONObject("edited_message")
                ?: update.optJSONObject("channel_post")
                ?: continue
            val chat = event.optJSONObject("chat") ?: continue
            if (!chat.has("id")) continue
            val id = chat.optLong("id").toString()
            if (normalizeChatId(id) == null) continue
            val parsed = TelegramChat(id, chatTitle(chat, id), chatKind(chat.optString("type")))
            val known = chats.indexOfFirst { it.id == parsed.id }
            if (known >= 0) chats.removeAt(known)
            chats.add(0, parsed)
        }
        return chats
    }

    private fun chatTitle(chat: JSONObject, fallback: String): String {
        chat.optString("title").takeIf { it.isNotBlank() }?.let { return it }
        val name = listOf("first_name", "last_name")
            .map { chat.optString(it) }
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (name.isNotBlank()) return name
        return chat.optString("username").takeIf { it.isNotBlank() } ?: fallback
    }

    private fun chatKind(type: String): String = when (type) {
        "private" -> "privado"
        "group", "supergroup" -> "grupo"
        "channel" -> "canal"
        else -> "chat"
    }

    /** Traduce los fallos que dejan la busqueda en nada a algo accionable. */
    private fun discoveryFailure(code: Int, json: JSONObject?): String {
        val description = json?.optString("description").orEmpty()
        return when {
            description.contains("webhook", ignoreCase = true) ->
                "el bot tiene un webhook: quitalo con /setwebhook en BotFather"
            code == 401 || description.contains("Unauthorized", ignoreCase = true) ->
                "clave del bot no valida (revisa la clave que te dio BotFather)"
            description.isNotBlank() -> description.take(MAX_DETAIL_LENGTH)
            else -> "HTTP $code"
        }
    }

    private fun Char.isUsernameChar(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_'

    private const val MIN_USERNAME_LENGTH = 4
    private const val MAX_USERNAME_LENGTH = 32
}

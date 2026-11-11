package com.intercom.segluky

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ContactsRepository(context: Context) {
    private val preferences = context.getSharedPreferences("emergency_contacts", Context.MODE_PRIVATE)

    fun load(): List<EmergencyContact> {
        val stored = preferences.getString(KEY_CONTACTS_JSON, null)
        val contacts = if (stored == null) migrateLegacyContacts() else decode(stored)
        return contacts
            .mapNotNull { contact ->
                canonicalPhoneNumber(contact.phone)?.let { contact.normalized().copy(phone = it) }
            }
            .distinctBy { it.phone.filter(Char::isDigit) }
    }

    fun add(contact: EmergencyContact): Boolean {
        val contacts = load().toMutableList()
        val phone = canonicalPhoneNumber(contact.phone) ?: return false
        val normalized = contact.normalized().copy(phone = phone)
        if (contacts.any { it.phone.filter(Char::isDigit) == phone.filter(Char::isDigit) }) return false
        contacts += normalized
        save(contacts)
        return true
    }

    fun update(original: EmergencyContact, contact: EmergencyContact): Boolean {
        val contacts = load().toMutableList()
        val index = contacts.indexOfFirst { it == original }
        if (index < 0) return false

        val phone = canonicalPhoneNumber(contact.phone) ?: return false
        val normalized = contact.normalized().copy(phone = phone)
        if (contacts.withIndex().any {
                it.index != index && it.value.phone.filter(Char::isDigit) == phone.filter(Char::isDigit)
            }
        ) return false
        contacts[index] = normalized
        save(contacts)
        return true
    }

    fun remove(contact: EmergencyContact) {
        save(load().filterNot { it == contact })
    }

    fun areSosActionsEnabled(): Boolean =
        preferences.getBoolean(KEY_SOS_ACTIONS_ENABLED, false)

    fun setSosActionsEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_SOS_ACTIONS_ENABLED, enabled).apply()
    }

    /** Token del bot de Telegram usado para enviar el SOS sin intervencion. */
    fun telegramBotToken(): String =
        preferences.getString(KEY_TELEGRAM_BOT_TOKEN, "").orEmpty()

    fun setTelegramBotToken(token: String) {
        preferences.edit().putString(KEY_TELEGRAM_BOT_TOKEN, token.trim()).apply()
    }

    fun isTelegramBotConfigured(): Boolean =
        TelegramSos.normalizeBotToken(telegramBotToken()) != null

    private fun migrateLegacyContacts(): List<EmergencyContact> {
        val contacts = preferences.getStringSet(KEY_LEGACY_CONTACTS, emptySet())
            .orEmpty()
            .mapNotNull { row ->
                val separator = row.indexOf('|')
                if (separator <= 0) null else EmergencyContact(
                    name = row.substring(0, separator),
                    phone = row.substring(separator + 1)
                )
            }
        save(contacts)
        preferences.edit().remove(KEY_LEGACY_CONTACTS).apply()
        return contacts
    }

    private fun decode(raw: String): List<EmergencyContact> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString(KEY_NAME).trim()
                val phone = item.optString(KEY_PHONE).trim()
                if (name.isNotEmpty() && phone.isNotEmpty()) {
                    add(
                        EmergencyContact(
                            name = name,
                            phone = phone,
                            callEnabled = item.optBoolean(KEY_CALL_ENABLED, true),
                            whatsappEnabled = item.optBoolean(KEY_WHATSAPP_ENABLED, true),
                            whatsappMessage = item.optString(KEY_WHATSAPP_MESSAGE, DEFAULT_WHATSAPP_MESSAGE),
                            telegramEnabled = item.optBoolean(KEY_TELEGRAM_ENABLED, false),
                            telegramUsername = item.optString(KEY_TELEGRAM_USERNAME, ""),
                            telegramChatId = item.optString(KEY_TELEGRAM_CHAT_ID, "")
                        )
                    )
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun save(contacts: List<EmergencyContact>) {
        val array = JSONArray()
        contacts.forEach { contact ->
            array.put(
                JSONObject()
                    .put(KEY_NAME, contact.name)
                    .put(KEY_PHONE, contact.phone)
                    .put(KEY_CALL_ENABLED, contact.callEnabled)
                    .put(KEY_WHATSAPP_ENABLED, contact.whatsappEnabled)
                    .put(KEY_WHATSAPP_MESSAGE, contact.whatsappMessage)
                    .put(KEY_TELEGRAM_ENABLED, contact.telegramEnabled)
                    .put(KEY_TELEGRAM_USERNAME, contact.telegramUsername)
                    .put(KEY_TELEGRAM_CHAT_ID, contact.telegramChatId)
            )
        }
        preferences.edit().putString(KEY_CONTACTS_JSON, array.toString()).apply()
    }

    internal companion object {
        const val KEY_CONTACTS_JSON = "contacts_json_v2"
        const val KEY_LEGACY_CONTACTS = "contacts"
        const val KEY_NAME = "name"
        const val KEY_PHONE = "phone"
        const val KEY_CALL_ENABLED = "call_enabled"
        const val KEY_WHATSAPP_ENABLED = "whatsapp_enabled"
        const val KEY_WHATSAPP_MESSAGE = "whatsapp_message"
        const val KEY_TELEGRAM_ENABLED = "telegram_enabled"
        const val KEY_TELEGRAM_USERNAME = "telegram_username"
        const val KEY_TELEGRAM_CHAT_ID = "telegram_chat_id"
        const val KEY_SOS_ACTIONS_ENABLED = "sos_actions_enabled"
        const val KEY_TELEGRAM_BOT_TOKEN = "telegram_bot_token"

        /**
         * Canonicalizes a phone number to digits-only form for WhatsApp URLs.
         * Returns null if the number has no digits or too few to be valid.
         */
        fun canonicalPhoneNumber(phone: String): String? {
            val digits = phone.filter { it.isDigit() }
            if (digits.length < 6) return null
            // If the original starts with +, keep the + prefix
            val prefix = if (phone.trimStart().startsWith("+")) "+" else ""
            return prefix + digits
        }
    }
}

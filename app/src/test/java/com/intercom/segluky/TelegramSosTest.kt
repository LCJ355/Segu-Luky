package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelegramSosTest {
    @Test
    fun usernameAcceptsTheFormsPeopleCopyFromTelegram() {
        assertEquals("segluky_bot", TelegramSos.normalizeUsername("@segluky_bot"))
        assertEquals("segluky_bot", TelegramSos.normalizeUsername("  segluky_bot  "))
        assertEquals("segluky_bot", TelegramSos.normalizeUsername("https://t.me/segluky_bot"))
        assertEquals("segluky_bot", TelegramSos.normalizeUsername("t.me/segluky_bot"))
        assertEquals("segluky_bot", TelegramSos.normalizeUsername("https://t.me/segluky_bot?start=1"))
        assertEquals("segluky_bot", TelegramSos.normalizeUsername("telegram.me/segluky_bot"))
        assertEquals("abcd", TelegramSos.normalizeUsername("abcd"))
        assertEquals("a_1_b", TelegramSos.normalizeUsername("a_1_b"))
    }

    @Test
    fun usernameRejectsEmptyShortLongOrIllegalValues() {
        assertNull(TelegramSos.normalizeUsername(""))
        assertNull(TelegramSos.normalizeUsername("   "))
        assertNull(TelegramSos.normalizeUsername("@"))
        assertNull(TelegramSos.normalizeUsername("abc"))
        assertNull(TelegramSos.normalizeUsername("a".repeat(33)))
        assertNull(TelegramSos.normalizeUsername("_abcd"))
        assertNull(TelegramSos.normalizeUsername("abcd_"))
        assertNull(TelegramSos.normalizeUsername("ab-cd"))
        assertNull(TelegramSos.normalizeUsername("ab cd"))
    }

    @Test
    fun chatIdKeepsTheGroupSignAndRejectsNoise() {
        assertEquals("123456789", TelegramSos.normalizeChatId("123456789"))
        assertEquals("123456789", TelegramSos.normalizeChatId(" 123456789 "))
        assertEquals("-1001234567890", TelegramSos.normalizeChatId("-1001234567890"))
        assertNull(TelegramSos.normalizeChatId(""))
        assertNull(TelegramSos.normalizeChatId("-"))
        assertNull(TelegramSos.normalizeChatId("12a4"))
        assertNull(TelegramSos.normalizeChatId("1 2"))
        assertNull(TelegramSos.normalizeChatId("123456789012345678901"))
    }

    @Test
    fun botTokenRequiresIdColonSecret() {
        val valid = "123456789:" + "A".repeat(35)
        assertEquals(valid, TelegramSos.normalizeBotToken(valid))
        assertEquals(valid, TelegramSos.normalizeBotToken("  $valid  "))
        assertNull(TelegramSos.normalizeBotToken(""))
        assertNull(TelegramSos.normalizeBotToken("sin-dos-puntos"))
        assertNull(TelegramSos.normalizeBotToken("123:" + "A".repeat(35)))
        assertNull(TelegramSos.normalizeBotToken("123456789:" + "A".repeat(10)))
        assertNull(TelegramSos.normalizeBotToken("123456789:" + "A".repeat(34) + "!"))
        assertNull(TelegramSos.normalizeBotToken("1234567890123456:" + "A".repeat(35)))
    }

    @Test
    fun sendViaBotRefusesInvalidInputWithoutTouchingTheNetwork() {
        val result = TelegramSos.sendViaBot("no-valido", "123456789", "SOS")

        assertEquals(false, result.ok)
        assertEquals("token del bot no valido", result.detail)
    }

    @Test
    fun chatUrlPointsToTheUsername() {
        assertEquals("https://t.me/segluky_bot", TelegramSos.chatUrl("segluky_bot"))
    }

    @Test
    fun botEnabledContactRequiresChatId() {
        val withoutChat = EmergencyContact(
            name = "Ana",
            phone = "+34600111222",
            telegramEnabled = true,
            telegramUsername = "@ana_sos"
        )
        val withChat = withoutChat.copy(telegramChatId = "123456789")

        assertEquals(false, withoutChat.telegramReadyForBot)
        assertEquals(true, withChat.telegramReadyForBot)
        assertEquals("123456789", withChat.normalized().telegramChatId)
        assertEquals("ana_sos", withChat.normalized().telegramUsername)
    }
}

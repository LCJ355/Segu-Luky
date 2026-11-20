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
    fun discoverChatsRefusesInvalidTokenWithoutTouchingTheNetwork() {
        val search = TelegramSos.discoverChats("no-valido")

        assertEquals(true, search is TelegramSos.ChatSearch.Failed)
        assertEquals(
            "token del bot no valido",
            (search as TelegramSos.ChatSearch.Failed).detail
        )
    }

    @Test
    fun parseChatsReadsEveryKindOfUpdateAndPutsTheNewestFirst() {
        val body = """
            {"ok":true,"result":[
              {"update_id":1,"message":{"chat":{"id":111,"first_name":"Ana","last_name":"Sos","type":"private"}}},
              {"update_id":2,"my_chat_member":{"chat":{"id":-1001234567890,"title":"Motos Madrid","type":"supergroup"}}},
              {"update_id":3,"edited_message":{"chat":{"id":111,"first_name":"Ana","last_name":"Sos","type":"private"}}},
              {"update_id":4,"channel_post":{"chat":{"id":-1009876543210,"title":"Avisos","type":"channel"}}},
              {"update_id":5,"message":{"chat":{"type":"private"}}}
            ]}
        """.trimIndent()

        val chats = TelegramSos.parseChats(body)

        assertEquals(3, chats.size)
        assertEquals("-1009876543210", chats[0].id)
        assertEquals("Avisos (canal -1009876543210)", chats[0].label)
        assertEquals("111", chats[1].id)
        assertEquals("Ana Sos (privado 111)", chats[1].label)
        assertEquals("-1001234567890", chats[2].id)
        assertEquals("grupo", chats[2].kind)
    }

    @Test
    fun parseChatsFallsBackToUsernameThenToTheNumber() {
        val body = """
            {"ok":true,"result":[
              {"update_id":1,"message":{"chat":{"id":222,"username":"ana_sos","type":"private"}}},
              {"update_id":2,"message":{"chat":{"id":333,"type":"private"}}}
            ]}
        """.trimIndent()

        val chats = TelegramSos.parseChats(body)

        assertEquals("ana_sos", chats[1].title)
        assertEquals("333", chats[0].title)
        assertEquals("privado 333", chats[0].label)
    }

    @Test
    fun parseChatsReturnsNothingForAnEmptyOrBrokenAnswer() {
        assertEquals(0, TelegramSos.parseChats("").size)
        assertEquals(0, TelegramSos.parseChats("no es json").size)
        assertEquals(0, TelegramSos.parseChats("""{"ok":false,"description":"Unauthorized"}""").size)
        assertEquals(0, TelegramSos.parseChats("""{"ok":true,"result":[]}""").size)
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

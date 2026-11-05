package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppAutoSendTest {
    @Test
    fun normalizationCollapsesWhitespaceAndUnicode() {
        val normalized = WhatsAppAutoSend.normalizeMessage("  Hola\u00A0 mundo \n\n SOS  ")

        assertEquals("Hola mundo SOS", normalized)
    }

    @Test
    fun hashIsStableForEquivalentWhitespace() {
        val first = WhatsAppAutoSend.normalizedMessageSha256("SOS   ahora")
        val second = WhatsAppAutoSend.normalizedMessageSha256(" SOS ahora ")

        assertEquals(first, second)
    }

    @Test
    fun messageMatchRequiresTheExactNormalizedText() {
        val request = request(message = "SOS   ahora")

        assertTrue(WhatsAppAutoSend.messageMatches(request, "SOS ahora"))
        assertFalse(WhatsAppAutoSend.messageMatches(request, "SOS ahora mismo"))
    }

    @Test
    fun expirationUsesBothClocksAndRejectsRollback() {
        val request = request(issuedElapsed = 10_000L, issuedWall = 50_000L, ttl = 45_000L)

        assertFalse(WhatsAppAutoSend.isExpiredAt(request, 20_000L, 60_000L))
        assertTrue(WhatsAppAutoSend.isExpiredAt(request, 56_000L, 96_000L))
        assertTrue(WhatsAppAutoSend.isExpiredAt(request, 9_000L, 60_000L))
        assertTrue(WhatsAppAutoSend.isExpiredAt(request, 20_000L, 49_000L))
    }

    private fun request(
        message: String = "SOS ahora",
        issuedElapsed: Long = 10_000L,
        issuedWall: Long = 50_000L,
        ttl: Long = 45_000L
    ): WhatsAppSendRequest {
        val normalized = WhatsAppAutoSend.normalizeMessage(message)
        return WhatsAppSendRequest(
            eventId = "event-1",
            requestId = "request-1",
            recipient = "+34600111222",
            expectedPackage = WhatsAppAutoSend.WHATSAPP_PACKAGE,
            normalizedMessage = normalized,
            messageSha256 = WhatsAppAutoSend.normalizedMessageSha256(normalized),
            issuedElapsedRealtimeMillis = issuedElapsed,
            issuedWallTimeMillis = issuedWall,
            ttlMillis = ttl
        )
    }
}

package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyContactTest {
    @Test
    fun messageAddsMapTokenWhenItWasRemoved() {
        assertEquals(
            "Avisame\nUbicacion: {MAPA}",
            ensureMapLinkToken("Avisame")
        )
    }

    @Test
    fun messageReplacesMapTokenWithGoogleMapsLink() {
        val message = EmergencyContact("Luky", "+34123456789", whatsappMessage = "Estoy aqui: {MAPA}")
            .whatsappMessageFor(googleMapsLink(40.4168, -3.7038))

        assertEquals(
            "Estoy aqui: https://www.google.com/maps/search/?api=1&query=40.416800,-3.703800",
            message
        )
    }

    @Test
    fun defaultMessageIncludesMapToken() {
        assertTrue(DEFAULT_WHATSAPP_MESSAGE.contains(MAP_LINK_TOKEN))
    }
}

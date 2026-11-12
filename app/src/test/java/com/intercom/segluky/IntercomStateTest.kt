package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntercomStateTest {

    @Test
    fun `no instalado es gris`() {
        val info = IntercomInstallationInfo(installed = false)
        assertEquals(IntercomState.MISSING, info.state)
    }

    @Test
    fun `instalado sin actividad es naranja`() {
        val info = IntercomInstallationInfo(
            installed = true,
            versionName = "V-4.0",
            versionCode = 403,
            bridgeAvailable = true,
            active = false
        )
        assertEquals(IntercomState.INACTIVE, info.state)
    }

    @Test
    fun `instalado con grupo activo es verde`() {
        val info = IntercomInstallationInfo(
            installed = true,
            bridgeAvailable = true,
            active = true,
            group = "ABC123",
            peers = 2
        )
        assertEquals(IntercomState.ACTIVE, info.state)
        assertEquals(2, info.peers)
        assertEquals("ABC123", info.group)
    }

    @Test
    fun `instalado con version antigua sin puente sigue siendo naranja`() {
        val info = IntercomInstallationInfo(
            installed = true,
            versionName = "V-4.0",
            bridgeAvailable = false
        )
        assertEquals(IntercomState.INACTIVE, info.state)
        assertFalse(info.active)
    }

    @Test
    fun `el aviso de voz tiene el texto pedido`() {
        assertEquals("Posible accidente de motorista", IntercomInstallation.SOS_NOTICE_TEXT)
        assertTrue(IntercomInstallation.SOS_NOTICE_TEXT.contains("accidente"))
    }
}

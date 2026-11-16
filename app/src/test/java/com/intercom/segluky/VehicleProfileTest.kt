package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleProfileTest {

    @Test
    fun `un id vacio o inventado vuelve a movil`() {
        assertEquals(VehicleProfile.PHONE, VehicleProfile.fromId(null))
        assertEquals(VehicleProfile.PHONE, VehicleProfile.fromId(""))
        assertEquals(VehicleProfile.PHONE, VehicleProfile.fromId("  "))
        assertEquals(VehicleProfile.PHONE, VehicleProfile.fromId("inventado"))
    }

    @Test
    fun `cada perfil se recupera por su id`() {
        VehicleProfile.entries.forEach { profile ->
            assertEquals(profile, VehicleProfile.fromId(profile.id))
            assertEquals(profile, VehicleProfile.fromId(" ${profile.id} "))
        }
    }

    @Test
    fun `movil conserva las claves de siempre para no perder los ajustes`() {
        assertEquals("sensitivity_g", VehicleProfile.PHONE.key("sensitivity_g"))
        assertEquals("fall_orientation_gate", VehicleProfile.PHONE.key("fall_orientation_gate"))
    }

    @Test
    fun `coche y moto guardan sus ajustes aparte`() {
        assertEquals("sensitivity_g_coche", VehicleProfile.CAR.key("sensitivity_g"))
        assertEquals("sensitivity_g_moto", VehicleProfile.MOTO.key("sensitivity_g"))
        assertNotEquals(VehicleProfile.CAR.key("sensitivity_g"), VehicleProfile.MOTO.key("sensitivity_g"))
        assertNotEquals(VehicleProfile.PHONE.key("sensitivity_g"), VehicleProfile.CAR.key("sensitivity_g"))
    }

    @Test
    fun `el coche tambien puede confirmar un vuelco`() {
        assertTrue(VehicleProfile.CAR.showsFallGate)
        assertTrue(VehicleProfile.PHONE.showsFallGate)
        assertTrue(VehicleProfile.MOTO.showsFallGate)
        assertTrue(VehicleProfile.CAR.defaultFallGate)
        assertTrue(VehicleProfile.MOTO.defaultFallGate)
    }

    @Test
    fun `el coche arranca con un nivel mas alto que la moto y el movil`() {
        assertTrue(VehicleProfile.CAR.defaultSensitivityG > VehicleProfile.PHONE.defaultSensitivityG)
        assertTrue(VehicleProfile.CAR.defaultSensitivityG > VehicleProfile.MOTO.defaultSensitivityG)
    }

    @Test
    fun `solo el coche usa enlace con el vehiculo`() {
        assertTrue(VehicleProfile.CAR.usesVehicleLink)
        assertFalse(VehicleProfile.PHONE.usesVehicleLink)
        assertFalse(VehicleProfile.MOTO.usesVehicleLink)
    }

    @Test
    fun `la moto arranca con la voz activada`() {
        assertTrue(VehicleProfile.MOTO.defaultVoiceGate)
        assertFalse(VehicleProfile.PHONE.defaultVoiceGate)
        assertFalse(VehicleProfile.CAR.defaultVoiceGate)
    }

    @Test
    fun `los tres perfiles son seleccionables y tienen etiqueta`() {
        assertEquals(3, VehicleProfile.selectable.size)
        VehicleProfile.entries.forEach { profile ->
            assertTrue(profile.label.isNotBlank())
            assertTrue(profile.badge.isNotBlank())
            assertTrue(profile.description.isNotBlank())
            assertTrue(profile.defaultSensitivityG > 0f)
        }
    }

    @Test
    fun `cada vehiculo ofrece solo los soportes que tienen sentido`() {
        assertTrue(VehicleProfile.CAR.allowedMounts.contains(MountProfile.GLOVEBOX))
        assertTrue(VehicleProfile.CAR.allowedMounts.contains(MountProfile.POCKET))
        assertTrue(VehicleProfile.CAR.allowedMounts.contains(MountProfile.CUSTOM))
        assertFalse(VehicleProfile.CAR.allowedMounts.contains(MountProfile.TANK))
        assertFalse(VehicleProfile.CAR.allowedMounts.contains(MountProfile.HANDLEBAR))
        assertTrue(VehicleProfile.MOTO.allowedMounts.contains(MountProfile.HANDLEBAR))
        assertTrue(VehicleProfile.MOTO.allowedMounts.contains(MountProfile.TANK))
        assertFalse(VehicleProfile.MOTO.allowedMounts.contains(MountProfile.GLOVEBOX))
        assertFalse(VehicleProfile.PHONE.allowedMounts.contains(MountProfile.TANK))
        assertTrue(VehicleProfile.PHONE.allowedMounts.contains(MountProfile.POCKET))
    }

    @Test
    fun `el soporte por defecto de cada vehiculo esta permitido`() {
        VehicleProfile.entries.forEach { profile ->
            assertTrue(profile.allowedMounts.contains(profile.defaultMount))
            assertEquals(VehicleProfile.CAR.defaultMount, MountProfile.GLOVEBOX)
            assertEquals(VehicleProfile.MOTO.defaultMount, MountProfile.HANDLEBAR)
        }
    }

    @Test
    fun `el soporte de coche se guarda y se recupera`() {
        assertEquals(MountProfile.GLOVEBOX, MountProfile.fromId("guantera"))
        assertEquals("Guantera", MountProfile.GLOVEBOX.label)
    }
}

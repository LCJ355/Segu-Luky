package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SosLocationFollowUpTest {
    private val armedAt = 1_000_000L

    private fun state(
        initialLinkSent: Boolean = false,
        sentCount: Int = 0,
        lastSentAtWall: Long? = null,
        latitude: Double? = null,
        longitude: Double? = null
    ) = SosLocationFollowUpState(
        eventId = "evt-1",
        armedAtWall = armedAt,
        initialLinkSent = initialLinkSent,
        sentCount = sentCount,
        lastSentAtWall = lastSentAtWall,
        lastLatitude = latitude,
        lastLongitude = longitude
    )

    @Test
    fun sinSeguimientoNoSeEnviaNada() {
        assertFalse(
            SosLocationFollowUp.shouldSend(null, armedAt + 1_000L, 40.0, -3.0)
        )
    }

    @Test
    fun elSosSinMapaReenviaLaPrimeraPosicion() {
        assertTrue(
            SosLocationFollowUp.shouldSend(state(), armedAt + 30_000L, 40.0, -3.0)
        )
    }

    @Test
    fun elSosConMapaNoRepiteSinMovimiento() {
        // El aviso ya llevaba este punto: reenviarlo igual es ruido.
        assertFalse(
            SosLocationFollowUp.shouldSend(
                state(
                    initialLinkSent = true,
                    lastSentAtWall = armedAt,
                    latitude = 40.0,
                    longitude = -3.0
                ),
                armedAt + SosLocationFollowUp.MIN_INTERVAL_MS + 1_000L,
                40.0,
                -3.0
            )
        )
    }

    @Test
    fun elSosConMapaReenviaTrasAlejarseLoSuficiente() {
        val previous = state(
            initialLinkSent = true,
            sentCount = 1,
            lastSentAtWall = armedAt,
            latitude = 40.0,
            longitude = -3.0
        )
        val later = armedAt + SosLocationFollowUp.MIN_INTERVAL_MS + 1_000L
        assertTrue(
            SosLocationFollowUp.shouldSend(previous, later, 40.001, -3.0)
        )
    }

    @Test
    fun unMovimientoCortoNoGeneraOtroAviso() {
        val previous = state(
            sentCount = 1,
            lastSentAtWall = armedAt,
            latitude = 40.0,
            longitude = -3.0
        )
        val later = armedAt + SosLocationFollowUp.MIN_INTERVAL_MS + 1_000L
        // Unos 11 m: el GPS de un movil quieto baila mas que eso.
        assertFalse(
            SosLocationFollowUp.shouldSend(previous, later, 40.0001, -3.0)
        )
    }

    @Test
    fun dosAvisosSeguidosRespetanElIntervaloMinimo() {
        val previous = state(
            sentCount = 1,
            lastSentAtWall = armedAt,
            latitude = 40.0,
            longitude = -3.0
        )
        assertFalse(
            SosLocationFollowUp.shouldSend(previous, armedAt + 5_000L, 41.0, -3.0)
        )
    }

    @Test
    fun laVentanaCaducaYNoSeReenviaMas() {
        assertTrue(SosLocationFollowUp.isExpired(state(sentCount = 1), armedAt + SosLocationFollowUp.WINDOW_MS + 1L))
        assertFalse(SosLocationFollowUp.isExpired(state(sentCount = 1), armedAt + 60_000L))
    }

    @Test
    fun unSaltoAtrasDelRelojNoAlargaLaVentana() {
        assertTrue(SosLocationFollowUp.isExpired(state(sentCount = 1), armedAt - 1L))
    }

    @Test
    fun seAgotanLosReenviosDisponibles() {
        val exhausted = state(sentCount = SosLocationFollowUp.MAX_UPDATES)
        assertTrue(SosLocationFollowUp.isExpired(exhausted, armedAt + 1_000L))
        assertFalse(
            SosLocationFollowUp.shouldSend(exhausted, armedAt + 1_000L, 41.0, -3.0)
        )
    }

    @Test
    fun registrarUnAvisoGuardaPosicionYCuenta() {
        val recorded = SosLocationFollowUp.recordSent(
            state = state(),
            nowWall = armedAt + 20_000L,
            latitude = 40.5,
            longitude = -3.5,
            manualOffered = true
        )
        assertEquals(1, recorded.sentCount)
        assertEquals(armedAt + 20_000L, recorded.lastSentAtWall)
        assertEquals(40.5, recorded.lastLatitude!!, 0.0)
        assertTrue(recorded.manualOffered)
    }

    @Test
    fun cadaReenvioUsaSuPropioIdDeTrabajo() {
        assertEquals("evt-1#ubi1", SosLocationFollowUp.jobEventId("evt-1", sentCount = 0))
        assertEquals("evt-1#ubi2", SosLocationFollowUp.jobEventId("evt-1", sentCount = 1))
        val ids = SosLocationFollowUp.jobEventIds("evt-1")
        assertEquals(SosLocationFollowUp.MAX_UPDATES, ids.size)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun elMensajeDeReenvioLlevaElEnlaceNuevo() {
        val text = followUpSosMessage("https://maps/nuevo")
        assertTrue(text.contains("https://maps/nuevo"))
        assertFalse(text.contains("{MAPA}"))
    }

    @Test
    fun laDistanciaSeMideEnMetros() {
        // Un grado de latitud son unos 111 km.
        val oneDegree = SosLocationFollowUp.distanceMeters(40.0, -3.0, 41.0, -3.0)
        assertTrue(oneDegree in 110_000.0..112_000.0)
        assertEquals(0.0, SosLocationFollowUp.distanceMeters(40.0, -3.0, 40.0, -3.0), 0.01)
    }
}

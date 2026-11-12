package com.intercom.segluky

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El registro del aviso de voz no puede exagerar: si dice "al grupo" cuando solo
 * sono en este movil, el usuario cree que los demas motoristas ya estan avisados.
 */
class IntercomNoticeTest {
    @Test
    fun confirmadoConMotoristasConectados() {
        assertEquals(
            " en el grupo (4 motoristas)",
            IntercomNotice.detail(broadcast = true, peers = 3, active = true)
        )
    }

    @Test
    fun confirmadoSinOtrosMotoristas() {
        assertEquals(
            " al grupo",
            IntercomNotice.detail(broadcast = true, peers = 0, active = true)
        )
    }

    @Test
    fun intercomEncendidoPeroSinConfirmacion() {
        assertEquals(
            ", pero InterCOM no lo confirmo",
            IntercomNotice.detail(broadcast = false, peers = 2, active = true)
        )
    }

    @Test
    fun intercomApagadoSoloSuenaEnEsteMovil() {
        assertEquals(
            " solo en este movil (InterCOM sin grupo)",
            IntercomNotice.detail(broadcast = false, peers = 0, active = false)
        )
    }
}

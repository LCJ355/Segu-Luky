package com.intercom.segluky

/**
 * Texto que se anade al registro cuando se lanza el aviso de voz a InterCOM-Luky.
 *
 * Se separa de [IntercomInstallation] para poder probarlo sin Android: describir
 * mal el resultado (decir "al grupo" cuando solo sono en este movil) haria creer
 * que los demas motoristas estan avisados cuando no lo estan.
 */
internal object IntercomNotice {

    /**
     * @param broadcast InterCOM confirmo que difundio el aviso.
     * @param peers Motoristas conectados ademas de este movil.
     * @param active InterCOM estaba encendido segun el puente.
     */
    fun detail(broadcast: Boolean, peers: Int, active: Boolean): String = when {
        broadcast && peers > 0 -> " en el grupo (${peers + 1} motoristas)"
        broadcast -> " al grupo"
        active -> ", pero InterCOM no lo confirmo"
        else -> " solo en este movil (InterCOM sin grupo)"
    }
}

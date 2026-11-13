package com.intercom.segluky

/**
 * Dónde viaja el teléfono. El perfil aprendido se guarda por soporte para no
 * mezclar el ruido y el traqueteo de un manillar con los de un bolsillo.
 */
enum class MountProfile(
    val id: String,
    val label: String,
    val shortLabel: String
) {
    UNDEFINED("sin_definir", "Sin definir", "S/D"),
    POCKET("bolsillo", "Bolsillo", "BOLS"),
    HANDLEBAR("manillar", "Manillar", "MAN"),
    TANK("deposito", "Deposito", "DEP"),
    BACKPACK("mochila", "Mochila", "MOCH"),
    CUSTOM("personalizado", "Personalizado", "PERS");

    val isDefined: Boolean get() = this != UNDEFINED

    companion object {
        fun fromId(id: String?): MountProfile =
            entries.firstOrNull { it.id == id?.trim() } ?: UNDEFINED
    }
}

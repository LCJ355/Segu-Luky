package com.intercom.segluky

/**
 * Dónde viaja el teléfono. El perfil aprendido se guarda por soporte para no
 * mezclar el ruido y el traqueteo de un manillar con los de un bolsillo.
 */
enum class MountProfile(
    val id: String,
    val label: String
) {
    UNDEFINED("sin_definir", "Sin definir"),
    POCKET("bolsillo", "Bolsillo"),
    HANDLEBAR("manillar", "Manillar"),
    TANK("deposito", "Deposito"),
    BACKPACK("mochila", "Mochila"),
    CUSTOM("personalizado", "Personalizado");

    val isDefined: Boolean get() = this != UNDEFINED

    companion object {
        fun fromId(id: String?): MountProfile =
            entries.firstOrNull { it.id == id?.trim() } ?: UNDEFINED
    }
}

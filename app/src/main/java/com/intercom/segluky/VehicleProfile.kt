package com.intercom.segluky

/**
 * Que se conduce. Es un ajuste distinto del soporte del movil: el soporte dice
 * donde va el telefono (bolsillo, manillar, deposito...) y este perfil dice que
 * se lleva entre manos, porque cambia los controles de Sensores, el nivel por
 * defecto y, en el coche, la fuente de velocidad (enlace OBDII en vez de GPS).
 */
enum class VehicleProfile(
    val id: String,
    val label: String,
    val badge: String,
    val defaultSensitivityG: Float,
    val defaultFallGate: Boolean,
    val defaultVoiceGate: Boolean,
    val showsFallGate: Boolean,
    val usesVehicleLink: Boolean,
    val description: String,
    /** Soportes que tienen sentido en este vehiculo, en el orden de los botones. */
    val allowedMounts: List<MountProfile>,
    /** Soporte que se pone si el que habia no vale para este vehiculo. */
    val defaultMount: MountProfile
) {
    PHONE(
        id = "movil",
        label = "Movil",
        badge = "MOVIL",
        defaultSensitivityG = 3.5f,
        defaultFallGate = true,
        defaultVoiceGate = false,
        showsFallGate = true,
        usesVehicleLink = false,
        description = "El telefono va contigo. Usa los sensores del movil y la velocidad del GPS.",
        allowedMounts = listOf(MountProfile.UNDEFINED, MountProfile.POCKET, MountProfile.BACKPACK, MountProfile.CUSTOM),
        defaultMount = MountProfile.POCKET
    ),
    CAR(
        id = "coche",
        label = "Coche",
        badge = "COCHE",
        defaultSensitivityG = 4.5f,
        defaultFallGate = true,
        defaultVoiceGate = false,
        showsFallGate = true,
        usesVehicleLink = true,
        description = "El telefono va en el coche. La velocidad del coche y el enlace OBDII " +
            "mandan sobre el GPS; el golpe se confirma con la caida de velocidad o con el vuelco.",
        allowedMounts = listOf(MountProfile.UNDEFINED, MountProfile.POCKET, MountProfile.GLOVEBOX, MountProfile.CUSTOM),
        defaultMount = MountProfile.GLOVEBOX
    ),
    MOTO(
        id = "moto",
        label = "Moto",
        badge = "MOTO",
        defaultSensitivityG = 3.5f,
        defaultFallGate = true,
        defaultVoiceGate = true,
        showsFallGate = true,
        usesVehicleLink = false,
        description = "El telefono va en la moto. MODO MOTO y voz activados por defecto; " +
            "el bus CAN de la moto queda pendiente.",
        allowedMounts = listOf(MountProfile.HANDLEBAR, MountProfile.TANK, MountProfile.POCKET, MountProfile.BACKPACK, MountProfile.CUSTOM),
        defaultMount = MountProfile.HANDLEBAR
    );

    /**
     * Clave de preferencias de un ajuste que puede tener un valor distinto en cada
     * perfil. Movil sigue usando las claves de siempre para no perder los ajustes
     * de las instalaciones que ya existen.
     */
    fun key(base: String): String = if (this == PHONE) base else "${base}_$id"

    companion object {
        fun fromId(id: String?): VehicleProfile =
            entries.firstOrNull { it.id == id?.trim() } ?: PHONE

        /** Perfiles que se pueden elegir; el orden es el de los botones. */
        val selectable: List<VehicleProfile> = entries.toList()
    }
}

package dev.dalibor.blinds.gateway

data class Device(val id: Long, val name: String, val type: Int, val favourite: Boolean) {
    /**
     * Garage doors and switches are left out on purpose: everything in this app can be used from
     * the lock screen, and nobody should be able to open a garage from a locked phone.
     */
    val isBlind get() = type in BLIND_TYPES
    val supportsPosition get() = type in POSITION_TYPES

    private companion object {
        const val SENSO = 1
        const val COSMO = 2
        const val CMR = 3
        const val COSMO_CZR = 7
        const val COSMO_MZR = 8
        const val SENSO_Z = 9
        val BLIND_TYPES = setOf(SENSO, COSMO, CMR, COSMO_CZR, COSMO_MZR, SENSO_Z)
        val POSITION_TYPES = setOf(SENSO, SENSO_Z)
    }
}

/** A command as the gateway spells it: UP, DOWN, STOP or a percentage where 100% is fully open. */
@JvmInline
value class Command(val value: String) {
    companion object {
        val Up = Command("UP")
        val Down = Command("DOWN")
        val Stop = Command("STOP")
        fun position(percent: Int) = Command("${percent.coerceIn(0, 100)}%")
    }
}

/**
 * Open percentage from a reported state value, or null when unknown. The gateway reports either a
 * percentage or the last command, optionally with a tilt suffix such as "DOWN:60$".
 */
fun openPercent(stateValue: String?): Int? {
    val value = stateValue?.substringBefore(':')?.trim() ?: return null
    return when {
        value == "UP" -> 100
        value == "DOWN" -> 0
        value.endsWith("%") -> value.dropLast(1).toIntOrNull()?.coerceIn(0, 100)
        else -> null
    }
}

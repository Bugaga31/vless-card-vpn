package com.vlesscardvpn.domain

/** A failed attempt must not override the user's next server selection. */
object ConnectionSelection {
    fun current(configs: List<VlessConfig>, attempted: VlessConfig?, liveSession: Boolean): VlessConfig? {
        if (liveSession && attempted != null) return attempted
        return configs.firstOrNull { it.isActive } ?: configs.firstOrNull()
    }
}

package com.vlesscardvpn.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/** Only a watchdog already waiting for connectivity consumes network-validation hints. */
object WatchdogSignals {
    enum class Wake { SCREEN, NETWORK, TIMER }
    suspend fun wait(screen: Channel<Unit>, network: Channel<Unit>, offline: Boolean, period: Long): Wake {
        if (!offline) network.tryReceive() // don't turn a healthy validation change into an extra probe
        return withTimeoutOrNull(period) {
            select<Wake> {
                screen.onReceive { Wake.SCREEN }
                if (offline) network.onReceive { Wake.NETWORK }
            }
        } ?: Wake.TIMER
    }
}

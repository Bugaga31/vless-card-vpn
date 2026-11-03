package com.vlesscardvpn.core

/** Debug command intents are events, not persistent Activity state. Caller must gate on DEBUG. */
object E2ECommands {
    fun consume(keys: List<String>, restored: Boolean, enabled: (String) -> Boolean, remove: (String) -> Unit): Set<String> {
        val commands = if (restored) emptySet() else keys.filterTo(linkedSetOf(), enabled)
        // Clear false/unknown-state booleans too; a restored system copy must not replay commands.
        keys.forEach(remove)
        return commands
    }
}

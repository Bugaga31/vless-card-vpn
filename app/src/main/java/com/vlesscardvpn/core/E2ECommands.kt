package com.vlesscardvpn.core

/** Debug command intents are events, not persistent Activity state. Caller must gate on DEBUG. */
object E2ECommands {
    private val keys = listOf("e2e_chain", "e2e_reload", "e2e_connect", "e2e_disconnect",
        "e2e_test", "e2e_dpi", "e2e_optimize", "e2e_warp", "e2e_masks")

    fun consume(restored: Boolean, enabled: (String) -> Boolean, remove: (String) -> Unit): Set<String> {
        val commands = if (restored) emptySet() else keys.filterTo(linkedSetOf(), enabled)
        // Clear false/unknown-state booleans too; a restored system copy must not replay commands.
        keys.forEach(remove)
        return commands
    }
}

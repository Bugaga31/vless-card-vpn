package com.vlesscardvpn.core

/** A closed TCP endpoint must not receive initial round-robin traffic. This is only
 * a negative startup hint, never evidence of authenticated transfer or service PASS.
 * Unknown/timeouts and untested explicit selections are retained for real probes.
 */
object ManualPoolFilter {
    fun <T> retain(selected: List<T>, id: (T) -> String, hints: Map<String, Int>): List<T> =
        if (selected.size <= 1) selected else selected.filter { hints[id(it)] != 0 }
}

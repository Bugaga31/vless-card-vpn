package com.vlesscardvpn.core

import org.junit.Assert.*
import org.junit.Test

class E2ECommandsTest {
    private fun consume(extras: MutableMap<String, Boolean>, restored: Boolean = false) =
        E2ECommands.consume(restored, { extras[it] == true }, { extras.remove(it); Unit })

    @Test fun initialConnectAndReloadAreConsumedTogetherBeforeDispatch() {
        val extras = mutableMapOf("e2e_reload" to true, "e2e_connect" to true)
        assertEquals(setOf("e2e_reload", "e2e_connect"), consume(extras))
        assertTrue(extras.isEmpty())
        assertTrue(consume(extras).isEmpty())
    }
    @Test fun recreatedActivityNeverReplaysEvenUnmodifiedSystemIntent() {
        val systemCopy = mutableMapOf("e2e_reload" to true, "e2e_connect" to true)
        assertTrue(consume(systemCopy, restored = true).isEmpty())
        assertTrue(systemCopy.isEmpty())
    }
    @Test fun aNewIntentAfterRecreationCanStillConnect() {
        consume(mutableMapOf("e2e_connect" to true), restored = true)
        assertEquals(setOf("e2e_connect"), consume(mutableMapOf("e2e_connect" to true)))
    }
    @Test fun allSideEffectCommandsAreOneShot() {
        val keys = setOf("chain", "reload", "connect", "disconnect", "test", "dpi", "optimize", "warp", "masks").map { "e2e_$it" }.toSet()
        val extras = keys.associateWith { true }.toMutableMap()
        assertEquals(keys, consume(extras)); assertTrue(consume(extras).isEmpty())
    }
    @Test fun falseAndUnrelatedExtrasDoNotDispatch() {
        val extras = mutableMapOf("e2e_connect" to false, "unrelated" to true)
        assertTrue(consume(extras).isEmpty()); assertEquals(mapOf("unrelated" to true), extras)
    }
}

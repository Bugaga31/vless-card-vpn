package com.vlesscardvpn

import android.content.Context
import android.net.VpnService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vlesscardvpn.core.LibboxPlatformInterface
import com.vlesscardvpn.core.NativeCallbackValues
import go.Seq
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** These tests need Android's actual JNI library; JVM stubs cannot catch Go null dereferences. */
@RunWith(AndroidJUnit4::class)
class NativeCallbackContractTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        Seq.setContext(context)
    }

    @Test fun unsupportedSystemProxyIsAnObjectNotNull() {
        val status = NativeCallbackValues.disabledSystemProxy()
        assertNotNull(status)
        assertFalse(status.available)
        assertFalse(status.enabled)
    }

    @Test fun unknownOwnerIsNotRootAndHasNoInventedPackage() {
        val owner = NativeCallbackValues.unknownConnectionOwner()
        assertNotNull(owner)
        assertEquals(-1, owner.userId)
        assertEquals("", owner.androidPackageName)
    }

    @Test fun platformReturnsUnknownObjectForUnsupportedProtocolAndInvalidFlow() {
        val service = AttachedVpnService(context)
        val platform = LibboxPlatformInterface(service) { }
        assertEquals(-1, platform.findConnectionOwner(1, "127.0.0.1", 1, "127.0.0.1", 1).userId)
        assertEquals(-1, platform.findConnectionOwner(6, null, 0, null, 0).userId)
    }

    private class AttachedVpnService(context: Context) : VpnService() {
        init { attachBaseContext(context) }
    }
}

package com.vlesscardvpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.nekohasekai.libbox.InterfaceUpdateListener
import java.util.concurrent.atomic.AtomicReference
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

    @Test fun everyPlatformInterfaceAddressHasValidPrefixAndNoIpv6Zone() {
        val platform = LibboxPlatformInterface(AttachedVpnService(context)) { }
        val interfaces = platform.getInterfaces()
        while (interfaces.hasNext()) {
            val iface = interfaces.next()
            assertTrue(iface.flags and android.system.OsConstants.IFF_UP != 0)
            val addresses = iface.addresses
            while (addresses.hasNext()) {
                val prefix = addresses.next()
                assertTrue(prefix.contains('/'))
                assertFalse(prefix.contains('%'))
                val address = java.net.InetAddress.getByName(prefix.substringBefore('/'))
                val length = prefix.substringAfterLast('/').toInt()
                assertTrue(length in 0..(address.address.size * 8))
            }
        }
    }

    @Test fun monitorReportsNetworkCostNotAddressFamilies() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = requireNotNull(cm.activeNetwork)
        val caps = requireNotNull(cm.getNetworkCapabilities(active))
        val reported = AtomicReference<Pair<Boolean, Boolean>>()
        val listener = object : InterfaceUpdateListener {
            override fun updateDefaultInterface(name: String?, index: Int, expensive: Boolean, constrained: Boolean) {
                if (index >= 0) reported.set(expensive to constrained)
            }
        }
        val platform = LibboxPlatformInterface(AttachedVpnService(context)) { }
        try {
            platform.startDefaultInterfaceMonitor(listener)
            assertNotNull(reported.get())
            assertEquals(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED), reported.get().first)
            assertFalse(reported.get().second)
        } finally { platform.closeDefaultInterfaceMonitor(listener) }
    }

    @Test fun lateLossOfAnotherNetworkDoesNotClearCurrentInterface() {
        val reported = AtomicReference<Int>()
        val listener = object : InterfaceUpdateListener {
            override fun updateDefaultInterface(name: String?, index: Int, expensive: Boolean, constrained: Boolean) { reported.set(index) }
        }
        val platform = LibboxPlatformInterface(AttachedVpnService(context)) { }
        try {
            platform.startDefaultInterfaceMonitor(listener)
            assertTrue(reported.get() >= 0)
            val field = LibboxPlatformInterface::class.java.getDeclaredField("defaultNetworkCallback").apply { isAccessible = true }
            val callback = field.get(platform) as ConnectivityManager.NetworkCallback
            callback.onLost(Network.fromNetworkHandle((123456L shl 32) or 0xcafed00dL)) // Controlled out-of-order loss, not the active emulator network.
            assertTrue("The current route must remain available", reported.get() >= 0)
        } finally { platform.closeDefaultInterfaceMonitor(listener) }
    }

    private class AttachedVpnService(context: Context) : VpnService() {
        init { attachBaseContext(context) }
    }
}

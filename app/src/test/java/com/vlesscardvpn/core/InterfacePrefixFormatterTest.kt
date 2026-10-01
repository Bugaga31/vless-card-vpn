package com.vlesscardvpn.core

import java.net.InetAddress
import java.net.Inet6Address
import org.junit.Assert.*
import org.junit.Test

class InterfacePrefixFormatterTest {
    private fun ipv4() = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 5))
    private fun ipv6Bytes() = ByteArray(16).apply { this[0] = 0xfe.toByte(); this[1] = 0x80.toByte(); this[15] = 1 }

    @Test fun ipv4IncludesRealSubnetPrefix() {
        assertEquals("192.168.1.5/24", InterfacePrefixFormatter.format(ipv4(), 24))
    }
    @Test fun ipv6ZoneIsRemovedBeforeAddingPrefix() {
        val address = Inet6Address.getByAddress(null, ipv6Bytes(), 7)
        assertTrue(address.hostAddress!!.contains('%'))
        val prefix = InterfacePrefixFormatter.format(address, 64)!!
        assertFalse(prefix.contains('%'))
        assertTrue(prefix.endsWith("/64"))
        assertEquals(address, InetAddress.getByName(prefix.substringBefore('/')))
    }
    @Test fun invalidSubnetLengthsAreSkippedInsteadOfPassingPanicInputToGo() {
        assertNull(InterfacePrefixFormatter.format(ipv4(), -1))
        assertNull(InterfacePrefixFormatter.format(ipv4(), 33))
        assertNull(InterfacePrefixFormatter.format(InetAddress.getByAddress(ipv6Bytes()), 129))
    }
    @Test fun boundaryPrefixesAreRetained() {
        assertEquals("192.168.1.5/0", InterfacePrefixFormatter.format(ipv4(), 0))
        assertEquals("192.168.1.5/32", InterfacePrefixFormatter.format(ipv4(), 32))
        assertTrue(InterfacePrefixFormatter.format(InetAddress.getByAddress(ipv6Bytes()), 128)!!.endsWith("/128"))
    }
}

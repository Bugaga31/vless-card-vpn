package com.vlesscardvpn.core

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class NativeTombstoneReaderTest {
    private fun varint(value: Long): ByteArray {
        var rest = value
        val bytes = mutableListOf<Byte>()
        do { val bits = (rest and 127).toInt(); rest = rest ushr 7; bytes.add((bits or if (rest != 0L) 128 else 0).toByte()) } while (rest != 0L)
        return bytes.toByteArray()
    }
    private fun number(tag: Int, n: Long) = varint((tag * 8).toLong()) + varint(n)
    private fun blob(tag: Int, bytes: ByteArray) = varint((tag * 8 + 2).toLong()) + varint(bytes.size.toLong()) + bytes
    private fun text(tag: Int, value: String) = blob(tag, value.toByteArray())

    @Test fun extractsOnlySignalAbortAndCrashingThreadFrames() {
        val frame = number(1, 0x1234) + text(4, "runtime.abort") + text(6, "/private/path/libbox.so")
        val thread = number(1, 42) + text(2, "SECRET_THREAD") + blob(4, frame) + text(5, "SECRET_MEMORY")
        val other = number(1, 88) + blob(2, blob(4, text(4, "OTHER_THREAD")))
        val root = number(6, 42) + blob(10, number(1, 6) + text(2, "SIGABRT")) +
            text(14, "netip prefix panic") + blob(16, other) + blob(16, number(1, 42) + blob(2, thread)) +
            text(18, "SECRET_LOGCAT") + text(19, "SECRET_FD")
        val decoded = NativeTombstoneReader.read(ByteArrayInputStream(root))
        assertTrue(decoded.contains("SIGABRT (6)"))
        assertTrue(decoded.contains("netip prefix panic"))
        assertTrue(decoded.contains("pc 1234 libbox.so runtime.abort"))
        assertFalse(decoded.contains("SECRET"))
        assertFalse(decoded.contains("OTHER_THREAD"))
        assertFalse(decoded.contains("/private/path"))
    }
    @Test fun emptyTraceDoesNotInventJavaStack() {
        val decoded = NativeTombstoneReader.decode(byteArrayOf())
        assertTrue(decoded.contains("backtrace unavailable"))
        assertFalse(decoded.contains("IllegalStateException"))
    }
    @Test fun truncatedTraceIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { NativeTombstoneReader.decode(byteArrayOf(0x72, 0x7f)) }
    }
    @Test fun oversizedTraceIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { NativeTombstoneReader.read(ByteArrayInputStream(ByteArray(2 * 1024 * 1024 + 1))) }
    }
    @Test fun malformedVarintIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { NativeTombstoneReader.decode(ByteArray(12) { 0x80.toByte() }) }
    }
}

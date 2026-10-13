package com.vlesscardvpn

import com.vlesscardvpn.core.UpdatePolicy
import com.vlesscardvpn.core.Updater
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class R133Test {
    private fun sha(bytes: ByteArray) = UpdatePolicy.hash(bytes.inputStream())
    @Test fun versionParserRejectsMalformedAmbiguousAndOverflowingTags() {
        assertEquals(124, Updater.codeOf("v1.2.13"))
        assertEquals(105, Updater.codeOf("1.1.4"))
        assertEquals(100, Updater.codeOf("v1.0.100"))
        listOf("1.x.2.13", "1.2.-1", "1.2.01", "1.1.10", "1.0.101", "1.3.0", "1.2.2147483647", "1.2.9223372036854775807", "beta-v1.2.13", "1.2.13/evil").forEach {
            assertEquals(it, 0, Updater.codeOf(it))
        }
    }
    @Test fun checksumManifestRequiresOneExactFile() {
        val hash = sha("data".toByteArray()); val name = "VLESS-Card-Beta-1.2.13.apk"
        assertEquals(hash, UpdatePolicy.checksum("$hash  $name\n", name))
        assertEquals(hash, UpdatePolicy.checksum("${hash.uppercase()} *$name\n", name))
        assertTrue(runCatching { UpdatePolicy.checksum("$hash  ../$name", name) }.isFailure)
        assertTrue(runCatching { UpdatePolicy.checksum("$hash  $name\n$hash  $name", name) }.isFailure)
        assertTrue(runCatching { UpdatePolicy.checksum("BAD  $name", name) }.isFailure)
    }
    @Test fun verifiedDownloadEnforcesDigestAndLength() {
        val data = "safe bytes".toByteArray(); val out = ByteArrayOutputStream()
        assertEquals(data.size.toLong(), UpdatePolicy.copyVerified(data.inputStream(), out, sha(data), data.size.toLong()))
        assertArrayEquals(data, out.toByteArray())
        assertTrue(runCatching { UpdatePolicy.copyVerified(data.inputStream(), ByteArrayOutputStream(), sha("wrong".toByteArray())) }.isFailure)
        assertTrue(runCatching { UpdatePolicy.copyVerified(data.inputStream(), ByteArrayOutputStream(), sha(data), data.size.toLong() + 1) }.isFailure)
        assertTrue(runCatching { UpdatePolicy.copyVerified(byteArrayOf().inputStream(), ByteArrayOutputStream(), sha(byteArrayOf())) }.isFailure)
    }
    @Test fun unknownLengthCannotExceedSizeLimit() {
        val data = ByteArray(20)
        assertTrue(runCatching { UpdatePolicy.copyVerified(data.inputStream(), ByteArrayOutputStream(), sha(data), max = 10) }.isFailure)
        assertTrue(runCatching { UpdatePolicy.copyVerified(data.inputStream(), ByteArrayOutputStream(), sha(data), length = 20, max = 10) }.isFailure)
        assertEquals(20L, UpdatePolicy.copyVerified(data.inputStream(), ByteArrayOutputStream(), sha(data), max = 20))
    }
    @Test fun publisherPackageAndVersionMustAllMatch() {
        fun allowed(pkg: String = "com.app", code: Long = 124, signers: Set<String> = setOf("cert"), mine: Int = 123,
                    installed: Set<String> = setOf("cert")) = UpdatePolicy.acceptsArchive("com.app", 124, mine, pkg, code, installed, signers)
        assertTrue(allowed())
        assertFalse(allowed(pkg = "com.fake"))
        assertFalse(allowed(code = 123))
        assertFalse(allowed(code = 125))
        assertFalse(allowed(signers = setOf("wrong")))
        assertFalse(allowed(installed = emptySet()))
        assertFalse(allowed(signers = setOf("cert", "extra")))
        assertFalse(allowed(mine = 124))
    }
    @Test fun failedChecksRetryEarlierButDoNotSpamTheNetwork() {
        val now = 100_000_000L
        assertTrue(UpdatePolicy.checkDue(now, 0, 0))
        assertFalse(UpdatePolicy.checkDue(now + 899_999, 0, now))
        assertTrue(UpdatePolicy.checkDue(now + 900_000, 0, now))
        assertFalse(UpdatePolicy.checkDue(now + 900_000, now, now))
        assertTrue(UpdatePolicy.checkDue(now + 6 * 3600_000L, now, now))
        assertTrue(UpdatePolicy.checkDue(now - 1, now, now))
    }
}

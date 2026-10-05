package com.vlesscardvpn
import com.vlesscardvpn.core.ByeDpiArgs
import org.junit.Assert.*
import org.junit.Test
class ByeDpiArgsTest {
    private fun ok(line: String, mask: String = "ya.ru") = ByeDpiArgs.parse(line, mask).getOrThrow()
    private fun bad(line: String) = assertTrue(line, ByeDpiArgs.parse(line).isFailure)
    @Test fun byeByeDpiShortFormsBecomeLongArgv() {
        assertEquals(listOf("--disorder", "1", "--fake", "-1", "--ttl", "8", "--md5sig", "--fake-sni", "vk.com"),
            ok("-d1 -f-1 -t8 -S -n {sni}", "vk.com"))
        assertEquals(listOf("--oob", "1", "--auto", "t,r,s", "--disorder", "1"), ok("-o1 -At,r,s -d1"))
        assertEquals(listOf("--split", "1+s", "--fake-tls-mod", "r"), ok("-s1+s -Qr"))
        assertEquals(listOf("--fake", "-1"), ok("-f -1"))
        assertEquals(listOf("--disorder", "1", "--auto", "torst", "--fake", "-1"), ok("--disorder 1 --auto=torst --fake -1"))
        assertEquals(listOf("--md5sig", "--drop-sack", "--disorder", "1"), ok("-SY -d1"))
        assertEquals(listOf("--hosts", ":youtube.com googlevideo.com", "--split", "1+s"), ok("-H \":youtube.com googlevideo.com\" -s1+s"))
        assertEquals(listOf("--disorder", "1"), ok("ciadpi -d1"))
    }
    @Test fun appControlledAndDangerousOptionsAreRejected() {
        bad("--ip 0.0.0.0 -d1"); bad("-i0.0.0.0 -d1"); bad("--port 1080 -d1"); bad("-p1080 -d1")
        bad("-D -d1"); bad("--daemon -d1"); bad("--pidfile x -d1"); bad("-w x -d1")
        bad("-x2 -d1"); bad("--debug 2 -d1"); bad("-y - -d1"); bad("--cache-file x -d1"); bad("--transparent -d1")
        bad("-H /sdcard/list.txt -d1"); bad("--fake-data /data/x.bin -f-1"); bad("-j list.txt -d1")
        bad("-d1 ; rm -rf /"); bad("-d1 | cat"); bad("-d"); bad("--fake"); bad("-d1 \"unclosed")
        bad(""); bad("   "); bad("-S"); bad("-Y -S"); bad("--md5sig=1 -d1")
        bad("-d1 ".repeat(300))
    }
    @Test fun maskDomainIsValidated() {
        assertEquals("ya.ru", ByeDpiArgs.maskDomain(null))
        assertEquals("vk.com", ByeDpiArgs.maskDomain(" VK.com "))
        assertEquals("ya.ru", ByeDpiArgs.maskDomain("bad host"))
        assertEquals("ya.ru", ByeDpiArgs.maskDomain("a;b.ru"))
        assertEquals("ya.ru", ByeDpiArgs.maskDomain("ya.ru/../x"))
        assertEquals("ya.ru", ByeDpiArgs.maskDomain("localhost"))
        assertEquals("www.?#*.ru", ByeDpiArgs.maskDomain("www.?#*.ru"))
        assertTrue(ByeDpiArgs.MASK_DOMAINS.all { ByeDpiArgs.maskDomain(it) == it })
        assertTrue(ByeDpiArgs.parse(com.vlesscardvpn.model.Settings.DEFAULT_BYEDPI, "ya.ru").isSuccess)
    }
    @Test fun examplesParseAndLoopbackStaysAppControlled() {
        for (e in ByeDpiArgs.EXAMPLES) {
            val args = ByeDpiArgs.parse(e, "gosuslugi.ru").getOrThrow()
            assertTrue(e, ByeDpiArgs.warnings(args).isEmpty())
        }
        assertEquals(1, ByeDpiArgs.warnings(ok("-d1 -f-1 -t8 -S -n {sni} -Qo")).size)
        assertEquals(1, ByeDpiArgs.warnings(ok("-d1 -f-1 -t8 -n {sni}")).size)
        val prefix = ByeDpiArgs.loopbackPrefix(12400)
        assertEquals("127.0.0.1", prefix[prefix.indexOf("--ip") + 1])
        assertEquals("12400", prefix[prefix.indexOf("--port") + 1])
        try { ByeDpiArgs.loopbackPrefix(80); fail() } catch (_: IllegalArgumentException) { }
    }
}

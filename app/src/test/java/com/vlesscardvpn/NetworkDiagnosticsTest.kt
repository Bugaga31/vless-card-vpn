package com.vlesscardvpn
import com.vlesscardvpn.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class NetworkDiagnosticsTest {
    private fun file() = File.createTempFile("network-log", ".txt").apply { deleteOnExit() }
    @Test fun boundedRingAndPersistentRestart() {
        val f = file(); val ring = DiagnosticRing(f, 2)
        repeat(3) { ring.append(NetworkDiagnosticEvent(timeMs = it.toLong(), phase = DiagnosticPhase.AUTO_START)) }
        assertEquals(2, DiagnosticRing(f, 2).read().size); assertTrue(ring.read().last().startsWith("2 |"))
    }
    @Test fun rejectsInjectedPrivateStringsAndUnknownEnums() {
        val f = file(); f.writeText("uuid=secret\nhttps://secret-subscription\n1 | SECRET | NONE | NONE | NONE | NONE | 200 | 30")
        assertTrue(DiagnosticRing(f).read().isEmpty())
    }
    @Test fun clampsNumericFields() {
        val e = NetworkDiagnosticEvent(phase = DiagnosticPhase.HTTPS_CHECK, httpCode = 9999, latencyMs = 900000)
        assertTrue(e.line().endsWith("-1 | 60000"))
    }
    @Test fun clearAndOversizedFile() {
        val f = file(); f.writeText("x".repeat(150001)); val ring = DiagnosticRing(f)
        assertTrue(ring.read().isEmpty()); ring.append(NetworkDiagnosticEvent(phase = DiagnosticPhase.PARTIAL))
        assertEquals(1, ring.read().size); ring.clear(); assertTrue(ring.read().isEmpty())
    }
    @Test fun reportExplainsScope() {
        assertTrue(NetworkDiagnosticLog.report("1.0.49", 33, emptyList()).contains("not video playback"))
    }
}

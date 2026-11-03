package com.vlesscardvpn.core
import java.io.File
import org.junit.Assert.*
import org.junit.Test
class RecoveryDetectorR242Test {
    private fun source(): String {
        val path="src/main/java/com/vlesscardvpn/vpn/TunnelService.kt"
        return listOf(File("app/$path"),File(path)).first {it.exists()}.readText()
    }
    @Test fun requestingAutoRepairDoesNotRetireOnlyDetector() {
        val body=source().substringAfter("""Log.i("E2E", "watchdog: connection lost")""").substringBefore("if (ok && vpnMode)")
        val auto=body.substringAfter("if(settings.mode==Mode.AUTO && usedChain==null && !usedTorRoute) {").substringBefore("hardRestart(")
        assertTrue(auto.contains("recoverServerRoute()"))
        assertTrue(auto.contains("continue"))
        assertFalse(auto.contains("break"))
    }
    @Test fun lightYoutubeResultIsNotLabelledVideoProof() {
        assertFalse(source().contains("Видео YouTube:"))
        assertTrue(source().contains("YouTube · HTTP-доступ (не видео):"))
    }
    @Test fun restrictionsHaveExplicitUnknownExplanation() {
        assertTrue(ServiceProbeStatus.label(true,null,"YT_ACCESS_RESTRICTED").contains("403/451"))
        assertTrue(ServiceProbeStatus.label(true,null,"YT_SERVICE_UNAVAILABLE").contains("5xx"))
    }
}

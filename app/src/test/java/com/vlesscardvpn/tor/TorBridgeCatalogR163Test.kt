package com.vlesscardvpn.tor
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test
class TorBridgeCatalogR163Test {
    private val fp="0123456789ABCDEF0123456789ABCDEF01234567"
    private val cert=Base64.getEncoder().withoutPadding().encodeToString(ByteArray(52))
    private val obfs="obfs4 192.0.2.1:443 $fp cert=$cert iat-mode=0"
    private val web="webtunnel [2001:db8::1]:443 $fp url=https://bridge.example.test/secret-path ver=0.0.5"
    @Test fun bareObfs4LinesBecomeCanonicalBridgeLines() { assertEquals("Bridge $obfs",TorBridgeCatalog.normalize(obfs));assertEquals("Bridge $obfs",TorBridgeCatalog.normalize("Bridge $obfs")) }
    @Test fun webtunnelPreservesDocumentedDummyIpv6AndExactUrl() { assertEquals("Bridge $web",TorBridgeCatalog.normalize(web)) }
    @Test fun commentsDuplicatesPlainAndUnknownTransportsAreHandledExplicitly() { val parsed=TorBridgeCatalog.parse("# metadata\n$obfs\nBridge $obfs\n192.0.2.3:443 $fp\nsnowflake x\n");assertEquals(1,parsed.lines.size);assertEquals(2,parsed.rejected) }
    @Test fun wrongFingerprintCertIatModePortAndControlsAreRejected() { for(line in listOf(obfs.replace(fp,"BAD"),obfs.replace(cert,"abc"),obfs.replace("iat-mode=0","iat-mode=9"),obfs.replace(":443",":65536"),obfs+"\nSocksPort 0.0.0.0:9050",obfs+"\u0000",obfs+" bad=1")) assertNull(line,TorBridgeCatalog.normalize(line)) }
    @Test fun webtunnelRejectsCredentialPlaintextAndLocalUrls() { for(url in listOf("http://bridge.test/path","https://u:p@bridge.test/path","https://localhost/path","https://127.0.0.1/path","https://bridge.test:80/path")) assertNull(TorBridgeCatalog.normalize(web.replace("https://bridge.example.test/secret-path",url))) }
    @Test fun selectedTransportIsNotSilentlyReplacedByPlainOrDifferentTor() { val p=TorBridgeCatalog.parse("$obfs\n$web");assertEquals(listOf("Bridge $obfs"),p.forTransport("obfs4"));try { TorBridgeCatalog.selected(obfs,"webtunnel");fail() } catch(_:IllegalArgumentException){};assertTrue(TorBridgeCatalog.selected(obfs,"none").isEmpty()) }
    @Test fun feedSizeAndSelectedBridgeCountAreBounded() { try { TorBridgeCatalog.parse("x".repeat(TorBridgeCatalog.MAX_BYTES+1));fail() } catch(_:IllegalArgumentException){};val p=TorBridgeCatalog.parse((1..20).joinToString("\n") { obfs.replace(":443",":${4000+it}") });assertEquals(12,p.forTransport("obfs4").size) }
    @Test fun unknownTransportCannotTriggerDirectFallback() { try { TorBridgeCatalog.selected(obfs,"meek");fail() } catch(_:IllegalArgumentException){} }
    @Test fun ownerFeedFormatsCanBeValidatedOfflineWithoutConnectingToBridges() {
        val supplied=System.getenv("TOR_BRIDGE_CATALOG_FILE")
        val parsed=TorBridgeCatalog.parse(if(supplied==null) "$obfs\n$web" else java.io.File(supplied).readText())
        assertTrue(parsed.forTransport("obfs4").isNotEmpty())
        assertTrue(parsed.forTransport("webtunnel").isNotEmpty())
        println("Tor bridge syntax only: obfs4=${parsed.forTransport("obfs4").size}; webtunnel=${parsed.forTransport("webtunnel").size}; rejected=${parsed.rejected}; bootstrap NOT tested")
    }
}

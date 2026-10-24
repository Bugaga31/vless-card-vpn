package com.vlesscardvpn.core
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
class StrictTransferR163Test {
    private fun rejected(block:()->Unit) { try { block();fail("Invalid transfer accepted") } catch(_:IOException){} }
    @Test fun completeTwoWindowsAreRequired() { val sample=TransferProof.read(ByteArrayInputStream(ByteArray(TransferProof.REQUIRED_BYTES)));assertEquals(262144,sample.bytes);assertTrue(sample.slowestKbps>0) }
    @Test fun tinyBodyAndPartialSecondWindowFail() { for(n in listOf(0,16384,131072,262143)) rejected { TransferProof.read(ByteArrayInputStream(ByteArray(n))) } }
    @Test fun htmlOrJsonCannotImpersonateOctetStream() { for(prefix in listOf("<!doctype html>"," <html>","<script>","{\"error\":","[{\"error\":")) rejected { TransferProof.read(ByteArrayInputStream((prefix+"x".repeat(262144)).toByteArray())) } }
    @Test fun interruptedTransferIsNotProof() { Thread.currentThread().interrupt();try { rejected { TransferProof.read(ByteArrayInputStream(ByteArray(262144))) } } finally { Thread.interrupted() } }
    @Test fun expiredDeadlineRejectsEvenBufferedData() { rejected { TransferProof.read(ByteArrayInputStream(ByteArray(262144)),100,now={100}) } }
    @Test fun validPartialRangeMustStartAtZeroAndCoverWholeSample() { assertTrue(TransferProof.rangeValid(206,"bytes 0-262143/500000",262144));for(r in listOf(null,"bytes 1-262144/500000","bytes 0-100/500000","bytes 0-262143/200","bytes 0-99999999999999999999999/100")) assertFalse(TransferProof.rangeValid(206,r,262144)) }
    @Test fun onlyMp4OrWebmContainerHeadersCountAsVideo() { val mp4=ByteArray(12).apply { "ftyp".toByteArray().copyInto(this,4) };assertTrue(TransferProof.videoHeader(mp4,"video/mp4"));assertTrue(TransferProof.videoHeader(byteArrayOf(0x1a,0x45,0xdf.toByte(),0xa3.toByte()),"video/webm"));assertFalse(TransferProof.videoHeader(ByteArray(12),"video/mp4"));assertFalse(TransferProof.videoHeader("<html>error</html>".toByteArray(),"video/mp4")) }
    @Test fun redirectsAndHttpErrorsCannotBeSuccessfulPages() { for(c in listOf(301,302,403,429,500,503)) assertFalse(ProbeResponsePolicy.connectivity(c,"https://example.test/health"));assertEquals("limited",ProbeResponsePolicy.site(429,500));assertEquals("http",ProbeResponsePolicy.site(500,500));assertEquals("http",ProbeResponsePolicy.site(200,0));assertEquals("ok",ProbeResponsePolicy.site(200,1)) }
    @Test fun slowerBodyWindowBoundsTheSpeedRatherThanBurst() { var ticks=0L;val sample=TransferProof.read(ByteArrayInputStream(ByteArray(262144)),now={ ticks+=1000000;ticks });assertTrue(sample.slowestKbps<TransferProof.rate(131072,1000000));assertTrue(sample.slowestKbps>0) }
    @Test fun speedBalancerChoosesOneLeaderNotThirtyPercentSlowerPool() { val settings=SpeedBalance.strategy(listOf(30000,25000,1000),listOf(500,100,1)).getJSONObject("settings");assertEquals(1,settings.getInt("expected"));assertEquals(0.0,settings.getDouble("tolerance"),0.0);assertEquals(3,settings.getJSONArray("costs").length()) }
    @Test fun fragmentedReadCannotHideHtmlPrefix() {
        val body=("<html>"+"x".repeat(262144)).toByteArray()
        val input=object: java.io.InputStream() { var i=0;override fun read():Int=if(i<body.size) body[i++].toInt() and 255 else -1;override fun read(b:ByteArray,off:Int,len:Int):Int { if(i>=body.size)return -1;b[off]=body[i++];return 1 } }
        rejected { TransferProof.read(input) }
    }
}

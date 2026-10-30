package com.vlesscardvpn
import com.vlesscardvpn.core.*
import com.vlesscardvpn.health.*
import com.vlesscardvpn.model.Settings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class StabilityR206RuntimeTest {
 private fun s(ok:Boolean?=true,ms:Int=100)=LinkStabilityPolicy.Sample(ok,ms)
 @Test fun defaultThousand(){assertEquals(1000,AutoLatencyRange.Range().max)}
 @Test fun numberIsUpperBound(){assertEquals(AutoLatencyRange.Range(1,800),AutoLatencyRange.parse("800"))}
 @Test fun asciiRange(){assertEquals(AutoLatencyRange.Range(100,800),AutoLatencyRange.parse("100-800"))}
 @Test fun enDashWhitespace(){assertEquals(AutoLatencyRange.Range(100,800),AutoLatencyRange.parse(" 100 – 800 "))}
 @Test fun emDash(){assertNotNull(AutoLatencyRange.parse("100—800"))}
 @Test fun reversedReject(){assertNull(AutoLatencyRange.parse("800-100"))}
 @Test fun zeroReject(){assertNull(AutoLatencyRange.parse("0-800"))}
 @Test fun negativeReject(){assertNull(AutoLatencyRange.parse("-800"))}
 @Test fun hugeReject(){assertNull(AutoLatencyRange.parse("999999999999999"))}
 @Test fun boundReject(){assertNull(AutoLatencyRange.parse("3001"))}
 @Test fun supportedUpperBoundary(){assertNotNull(AutoLatencyRange.parse("3000"))}
 @Test fun emptyReject(){assertNull(AutoLatencyRange.parse(""))}
 @Test fun textReject(){assertNull(AutoLatencyRange.parse("abc"))}
 @Test fun duplicateDelimiterReject(){assertNull(AutoLatencyRange.parse("100--800"))}
 @Test fun fastReplyNeverPenalized(){assertTrue(AutoLatencyRange.Range(100,800).accepts(true,25,true,30))}
 @Test fun inclusiveCustomUpper(){assertTrue(AutoLatencyRange.Range(100,800).accepts(true,800,true,800))}
 @Test fun slowerCustomUpperRejected(){assertFalse(AutoLatencyRange.Range(100,800).accepts(true,801,true,800))}
 @Test fun failedTgRejected(){assertFalse(AutoLatencyRange.Range().accepts(true,100,false,100))}
 @Test fun unknownTgRejected(){assertFalse(AutoLatencyRange.Range().accepts(true,100,null,0))}
 @Test fun invalidPersistedUsesDefault(){assertEquals(AutoLatencyRange.Range(),AutoLatencyRange.safe(900,800))}
 @Test fun settingsRoundTrip(){val a=Settings(autoLatencyMinMs=100,autoLatencyMaxMs=800);val b=Settings.fromJson(a.toJson());assertEquals(100,b.autoLatencyMinMs);assertEquals(800,b.autoLatencyMaxMs)}
 @Test fun oldSettingsDefault(){assertEquals(1000,Settings.fromJson(JSONObject()).autoLatencyMaxMs)}
 @Test fun invalidSavedReset(){assertEquals(1000,Settings.fromJson(JSONObject().put("autoLatencyMaxMs",-1)).autoLatencyMaxMs)}
 @Test fun oneAnswerNotStable(){assertNull(LinkStabilityPolicy.summarize(listOf(s()),1000).stable)}
 @Test fun threeAnswersStable(){assertEquals(true,LinkStabilityPolicy.summarize(listOf(s(),s(ms=120),s(ms=140)),1000).stable)}
 @Test fun singleFailureUnstable(){assertEquals(false,LinkStabilityPolicy.summarize(listOf(s(),s(false,0),s()),1000).stable)}
 @Test fun cancelledUnknown(){assertNull(LinkStabilityPolicy.summarize(listOf(s(),s(null,0),s()),1000).stable)}
 @Test fun spreadNotRtpJitter(){val x=LinkStabilityPolicy.summarize(listOf(s(),s(ms=400),s()),1000);assertEquals(300,x.spreadMs);assertEquals(false,x.stable)}
 @Test fun slowRepliesUnstable(){assertEquals(false,LinkStabilityPolicy.summarize(listOf(s(ms=801),s(ms=801),s(ms=801)),800).stable)}
 @Test fun noTimingIsUnknown(){assertNull(LinkStabilityPolicy.summarize(listOf(s(),s(ms=0),s()),1000).stable)}
 @Test fun lastFiveOnly(){val x=LinkStabilityPolicy.summarize(listOf(s(false,0))+List(5){s()},1000);assertEquals(5,x.attempted);assertEquals(true,x.stable)}
 @Test fun customHealthThreshold(){assertEquals(ModuleStatus.SLOW,ModuleHealthPolicy.status(true,900,true,800));assertEquals(ModuleStatus.PASS,ModuleHealthPolicy.status(true,1200,true,1500))}
 @Test fun customCountersLabel(){val x=AutoSearchStatistics(800);x.start();x.observe(true,900,true,100);val r=AutoDiagnostics.searchEvent("WAVE_LIMIT",1,x.snapshot());assertEquals(800,r.getInt("latency_limit_ms"));assertEquals(1,r.getJSONObject("ordinary_probes").getInt("over_800ms"))}
 @Test fun explicitFiveHundredNotLegacy(){val x=AutoSearchStatistics(500);val a=AutoDiagnostics.searchEvent("WAVE_LIMIT",1,x.snapshot());assertEquals(a.toString(),AutoDiagnostics.compatibleSearch(a.toString()).toString())}
}

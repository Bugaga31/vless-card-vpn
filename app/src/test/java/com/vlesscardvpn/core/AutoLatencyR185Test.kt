package com.vlesscardvpn.core
import com.vlesscardvpn.model.ServerState
import org.junit.Assert.*
import org.junit.Test
class AutoLatencyR185Test {
    private fun sample(https:Int=100,tg:Int=120)=Probe(https,null,null,tgOk=true,tgMs=tg)
    @Test fun inclusive500AndFasterThan50AreAllowed() {
        assertTrue(AutoLatencyPolicy.accepts(sample(500,500)))
        assertTrue(AutoLatencyPolicy.accepts(sample(1,1)))
    }
    @Test fun slowHttpsAndSlowTelegramAreBothRejected() {
        assertFalse(AutoLatencyPolicy.accepts(sample(3001,50)))
        assertFalse(AutoLatencyPolicy.accepts(sample(50,3001)))
    }
    @Test fun unknownTimingAndUnknownServiceNeverSatisfyStrictAuto() {
        assertFalse(AutoLatencyPolicy.accepts(sample(tg=0)))
        assertFalse(AutoLatencyPolicy.accepts(sample().copy(tgOk=null)))
        assertFalse(AutoLatencyPolicy.accepts(sample().copy(tgOk=false)))
    }
    @Test fun bodyFailureCannotPassDespiteLowServiceLatency() {
        assertFalse(AutoLatencyPolicy.accepts(sample().copy(bigOk=false)))
    }
    @Test fun noHistoricalServiceSuccessWithoutTimingIsEligible() {
        val s=ServerState(autoChecksKey=AutoChecks.key(AutoChecks.current,AutoLatencyPolicy.MAX_MS),autoChecksAt=1000,realMs=100,tgOk=true,serviceNetwork="wifi",serviceMask="",serviceCheckedAt=1000)
        assertTrue(AutoEligibility.servicePool(listOf(s),{it},"wifi",3001).isEmpty())
        assertEquals(listOf(s.copy(tgMs=500)),AutoEligibility.servicePool(listOf(s.copy(tgMs=500)),{it},"wifi",3001))
    }
    @Test fun timingCannotCrossNetworksMasksOrExpiration() {
        val s=ServerState(realMs=100,tgOk=true,tgMs=100,serviceNetwork="wifi",serviceMask="",serviceCheckedAt=1000)
        assertTrue(AutoEligibility.servicePool(listOf(s),{it},"cell",3001).isEmpty())
        assertTrue(AutoEligibility.servicePool(listOf(s.copy(maskId="chrome.hello.fast")),{it},"wifi",3001).isEmpty())
        assertTrue(AutoEligibility.servicePool(listOf(s),{it},"wifi",303001).isEmpty())
    }
    @Test fun latencySurvivesRoundTripButLegacyRemainsUnknown() {
        val s=ServerState(realMs=100,tgOk=true,tgMs=333)
        assertEquals(333,ServerState.fromJson(s.toJson()).tgMs)
        assertEquals(0,ServerState.fromJson(org.json.JSONObject("{\"tg\":true}")).tgMs)
    }
    @Test fun changingMaskResetsUnknownServiceLatency() {
        val old=ServerState(realMs=100,tgOk=true,tgMs=111)
        assertEquals(0,MaskSamplePolicy.adopt(old,"wifi","chrome.hello.fast",1000,Probe(50,null,null),false,false,false).tgMs)
        assertEquals(120,MaskSamplePolicy.adopt(old,"wifi","chrome.hello.fast",1000,sample(),true,false,false).tgMs)
        assertEquals(0,AutoHandoffPolicy.resetRuntime(old,"cell").tgMs)
    }
    @Test fun threeConfirmedSlowSamplesAreNeededAndUnknownBreaksStreak() {
        assertEquals(1,AutoLatencyPolicy.slowStreak(0,sample(tg=3200)))
        assertEquals(2,AutoLatencyPolicy.slowStreak(1,sample(tg=3200)))
        assertEquals(3,AutoLatencyPolicy.slowStreak(2,sample(tg=3200)))
        assertEquals(0,AutoLatencyPolicy.slowStreak(2,sample()))
        assertEquals(0,AutoLatencyPolicy.slowStreak(2,sample().copy(tgOk=null)))
    }
}

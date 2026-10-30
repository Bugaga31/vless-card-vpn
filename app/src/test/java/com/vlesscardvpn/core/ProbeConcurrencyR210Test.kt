package com.vlesscardvpn.core

import com.vlesscardvpn.model.Settings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProbeConcurrencyR210Test {
    private fun plan(input:PowerPolicy.Input=PowerPolicy.Input(1),options:PowerPolicy.Options=PowerPolicy.Options())=PowerPolicy.evaluate(input,options)
    @Test fun defaultIsFiveForFreshAndMigratedSettings() {
        assertEquals(5,Settings().probeParallel)
        assertEquals(5,Settings.fromJson(JSONObject()).probeParallel)
    }
    @Test fun everyChoiceSurvivesJsonRoundTrip() {
        for(n in listOf(1,3,5,10)) assertEquals(n,Settings.fromJson(Settings(probeParallel=n).toJson()).probeParallel)
    }
    @Test fun malformedValuesFallBackToFive() {
        for(n in listOf(Int.MIN_VALUE,-1,0,2,4,6,100,Int.MAX_VALUE)) {
            assertEquals(5,ProbeConcurrencyPolicy.normalize(n))
            assertEquals(5,Settings.fromJson(JSONObject().put("probeParallel",n)).probeParallel)
        }
    }
    @Test fun normalForegroundHonorsEverySelectedLimit() {
        for(n in listOf(1,3,5,10)) assertEquals(n,ProbeConcurrencyPolicy.limit(20,n,plan(),true))
    }
    @Test fun defaultNormalForegroundAndBackgroundStayAtFive() {
        assertEquals(5,ProbeConcurrencyPolicy.limit(20,5,plan(),true))
        assertEquals(5,ProbeConcurrencyPolicy.limit(20,5,plan(),false))
    }
    @Test fun tenIsNotSilentlyClampedToEightOrSix() {
        assertEquals(10,ProbeConcurrencyPolicy.limit(20,10,plan(),true))
        assertEquals(10,ProbeConcurrencyPolicy.limit(20,10,plan(),false))
    }
    @Test fun requestedSingleRouteRemainsSingle() {assertEquals(1,ProbeConcurrencyPolicy.limit(1,10,plan(),true))}
    @Test fun thermalCapWinsEvenWhenAutoCoolIsOff() {
        val p=plan(PowerPolicy.Input(1,thermal=3),PowerPolicy.Options(autoCool=false))
        assertTrue(p.memory.hot);assertFalse(p.allowLight)
        assertEquals(2,ProbeConcurrencyPolicy.limit(20,10,p,true))
    }
    @Test fun lowMemoryStillCapsAtTwo() {assertEquals(2,ProbeConcurrencyPolicy.limit(20,10,plan(PowerPolicy.Input(1,lowMemory=true)),true))}
    @Test fun callStillCapsAtOne() {assertEquals(1,ProbeConcurrencyPolicy.limit(20,10,plan(PowerPolicy.Input(1,voiceCall=true)),true))}
    @Test fun saverAndEcoStillCapAtFour() {
        assertEquals(4,ProbeConcurrencyPolicy.limit(20,10,plan(PowerPolicy.Input(1,saver=true)),true))
        assertEquals(4,ProbeConcurrencyPolicy.limit(20,10,plan(options=PowerPolicy.Options(profile="eco")),true))
    }
    @Test fun lowerUserChoiceIsNotRaisedBySafetyCap() {assertEquals(1,ProbeConcurrencyPolicy.limit(20,1,plan(PowerPolicy.Input(1,thermal=3)),true))}
    @Test fun coreBatchNeverStartsMoreRoutesThanActiveSlots() {
        for(n in listOf(1,3,5,10)) assertEquals(n,ProbeConcurrencyPolicy.batch(64,n))
        assertEquals(2,ProbeConcurrencyPolicy.batch(64,2));assertEquals(1,ProbeConcurrencyPolicy.batch(1,10))
    }
}

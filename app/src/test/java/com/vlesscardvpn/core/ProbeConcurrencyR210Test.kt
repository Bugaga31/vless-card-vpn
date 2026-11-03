package com.vlesscardvpn.core

import com.vlesscardvpn.model.Settings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProbeConcurrencyR210Test {
    private fun plan(input:PowerPolicy.Input=PowerPolicy.Input(1),options:PowerPolicy.Options=PowerPolicy.Options())=PowerPolicy.evaluate(input,options)
    @Test fun defaultIsHundredForFreshAndMigratedSettings() {
        assertEquals(100,Settings().probeParallel)
        assertEquals(100,Settings.fromJson(JSONObject()).probeParallel)
    }
    @Test fun oldImplicitDefaultFiveMigratesOnceToHundred() {
        assertEquals(100,Settings.fromJson(JSONObject().put("probeParallel",5)).probeParallel)
        // Saved by this version: an explicit 5 stays 5.
        assertEquals(5,Settings.fromJson(Settings(probeParallel=5).toJson()).probeParallel)
        for(n in listOf(1,3,10)) assertEquals(n,Settings.fromJson(JSONObject().put("probeParallel",n)).probeParallel)
    }
    @Test fun everyChoiceSurvivesJsonRoundTrip() {
        for(n in listOf(1,2,3,5,7,10,64,100,333,999,1000)) assertEquals(n,Settings.fromJson(Settings(probeParallel=n).toJson()).probeParallel)
    }
    @Test fun arbitraryValuesAreKeptAndClampedToThousand() {
        for(n in listOf(2,4,6,100,250,1000)) assertEquals(n,ProbeConcurrencyPolicy.normalize(n))
        for(n in listOf(1001,5000,Int.MAX_VALUE)) assertEquals(1000,ProbeConcurrencyPolicy.normalize(n))
        for(n in listOf(Int.MIN_VALUE,-1,0)) {
            assertEquals(100,ProbeConcurrencyPolicy.normalize(n))
            assertEquals(100,Settings.fromJson(JSONObject().put("probeParallel",n).put("probeParallelSchema",2)).probeParallel)
        }
    }
    @Test fun typedValuesParseStrictly() {
        assertEquals(250,ProbeConcurrencyPolicy.parse(" 250 "));assertEquals(1000,ProbeConcurrencyPolicy.parse("1500"))
        for(t in listOf("","0","-3","1e3","abc","12a","99999999")) assertNull(t,ProbeConcurrencyPolicy.parse(t))
    }
    @Test fun hundredAndThousandAreHonoredOn64BitAndBoundedOn32Bit() {
        try {
            ProbeConcurrencyPolicy.initDevice(true)
            assertEquals(100,ProbeConcurrencyPolicy.limit(1000,100,plan(),true))
            assertEquals(1000,ProbeConcurrencyPolicy.limit(1000,1000,plan(),false))
            ProbeConcurrencyPolicy.initDevice(false)
            assertEquals(150,ProbeConcurrencyPolicy.limit(1000,1000,plan(),true))
            assertEquals(100,ProbeConcurrencyPolicy.limit(1000,100,plan(),true))
        } finally {ProbeConcurrencyPolicy.initDevice(true)}
    }
    @Test fun ioPoolNoLongerCapsAtNinetySix() {assertTrue(Bg.IO_THREADS>=ProbeConcurrencyPolicy.MAX)}
    @Test fun normalForegroundHonorsEverySelectedLimit() {
        for(n in listOf(1,3,5,10)) assertEquals(n,ProbeConcurrencyPolicy.limit(20,n,plan(),true))
    }
    @Test fun defaultNormalForegroundAndBackgroundStayAtHundred() {
        assertEquals(100,ProbeConcurrencyPolicy.limit(200,100,plan(),true))
        assertEquals(100,ProbeConcurrencyPolicy.limit(200,100,plan(),false))
    }
    @Test fun tenIsNotSilentlyClampedToEightOrSix() {
        assertEquals(10,ProbeConcurrencyPolicy.limit(20,10,plan(),true))
        assertEquals(10,ProbeConcurrencyPolicy.limit(20,10,plan(),false))
    }
    @Test fun requestedSingleRouteRemainsSingle() {assertEquals(1,ProbeConcurrencyPolicy.limit(1,10,plan(),true))}
    @Test fun explicitChoiceNotReducedWhenHot() {
        val p=plan(PowerPolicy.Input(1,thermal=3),PowerPolicy.Options(autoCool=false))
        assertTrue(p.memory.hot);assertFalse(p.allowLight)
        assertEquals(10,ProbeConcurrencyPolicy.limit(20,10,p,true))
    }
    @Test fun explicitChoiceNotReducedForMemory() {assertEquals(10,ProbeConcurrencyPolicy.limit(20,10,plan(PowerPolicy.Input(1,lowMemory=true)),true))}
    @Test fun explicitChoiceNotReducedForCall() {assertEquals(10,ProbeConcurrencyPolicy.limit(20,10,plan(PowerPolicy.Input(1,voiceCall=true)),true))}
    @Test fun explicitChoiceNotReducedBySaverAndEco() {
        assertEquals(10,ProbeConcurrencyPolicy.limit(20,10,plan(PowerPolicy.Input(1,saver=true)),true))
        assertEquals(10,ProbeConcurrencyPolicy.limit(20,10,plan(options=PowerPolicy.Options(profile="eco")),true))
    }
    @Test fun lowerUserChoiceIsNotRaisedBySafetyCap() {assertEquals(1,ProbeConcurrencyPolicy.limit(20,1,plan(PowerPolicy.Input(1,thermal=3)),true))}
    @Test fun coreBatchNeverStartsMoreRoutesThanActiveSlots() {
        for(n in listOf(1,3,5,10)) assertEquals(n,ProbeConcurrencyPolicy.batch(64,n))
        assertEquals(2,ProbeConcurrencyPolicy.batch(64,2));assertEquals(1,ProbeConcurrencyPolicy.batch(1,10))
    }
}

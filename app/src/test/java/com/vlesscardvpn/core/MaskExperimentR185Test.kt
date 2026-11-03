package com.vlesscardvpn.core

import com.vlesscardvpn.model.Settings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MaskExperimentR185Test {
    private val ids=listOf("native","fast","balanced","coarse","mobile")
    @Test fun rotatesScheduledUnknownsInsteadOfOnlyFirstTwoForever() {
        val m=MaskExperimentMemory();val first=m.choose("wifi","1","server",ids,100)
        assertEquals(ids.take(2),first);first.forEach {m.schedule("wifi","1","server",it,100)}
        assertEquals(listOf("balanced","coarse"),m.choose("wifi","1","server",ids,101))
        assertEquals(listOf("balanced","coarse"),m.choose("wifi","1","server",ids,16_000))
    }
    @Test fun completedUnknownIsNotNegativeLearning() {
        val m=MaskExperimentMemory();m.schedule("wifi","1","s","fast",100)
        m.observe("wifi","1","s","fast",100,200,null)
        assertEquals(listOf(1,0,0,1,1),m.counts())
    }
    @Test fun explicitNativeFailureIsTemporarilyDeferred() {
        val m=MaskExperimentMemory();m.schedule("wifi","1","s","fast",100)
        m.observe("wifi","1","s","fast",100,200,false)
        assertTrue(m.choose("wifi","1","s",listOf("fast"),201).isEmpty())
        assertEquals(listOf("fast"),m.choose("wifi","1","s",listOf("fast"),30_200))
    }
    @Test fun handleAndServerDoNotShareEvidence() {
        val m=MaskExperimentMemory();m.schedule("wifi","1","s","fast",100)
        m.observe("wifi","1","s","fast",100,200,false)
        assertEquals(listOf("fast"),m.choose("wifi","2","s",listOf("fast"),201))
        assertEquals(listOf("fast"),m.choose("wifi","1","other",listOf("fast"),201))
    }
    @Test fun staleObservationCannotOverwriteNewTrial() {
        val m=MaskExperimentMemory();m.schedule("n",null,"s","m",100);m.schedule("n",null,"s","m",200)
        m.observe("n",null,"s","m",100,300,false)
        assertEquals(0,m.counts()[2])
    }
    @Test fun memoryIsBoundedAndMonotonicRollbackClearsScheduling() {
        val m=MaskExperimentMemory(3)
        repeat(8){m.schedule("n",null,"s","m$it",100L+it)}
        assertEquals(3,m.counts()[4])
        assertEquals(listOf("m7"),m.choose("n",null,"s",listOf("m7"),1))
    }
    @Test fun failureTimeoutAndMissingTelegramAreUnknown() {
        assertNull(MaskExperiment.verdict(Probe(0,null,null,error="timeout")))
        assertNull(MaskExperiment.verdict(Probe(50,null,null)))
        assertEquals(false,MaskExperiment.verdict(Probe(50,null,null,tgOk=false)))
        assertEquals(true,MaskExperiment.verdict(Probe(50,null,null,tgOk=true,tgMs=120)))
    }
    @Test fun settingsOptInIsExplicitAndSurvivesRoundTrip() {
        assertFalse(Settings().experimentalMaskLab)
        assertFalse(Settings.fromJson(JSONObject()).experimentalMaskLab)
        assertTrue(Settings.fromJson(Settings(experimentalMaskLab=true).toJson()).experimentalMaskLab)
    }
}

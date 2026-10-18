package com.vlesscardvpn

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AutoSelectionTest {
    private val a = Server("a", "vless", "a.invalid", 443, "a")
    private val b = Server("b", "vless", "b.invalid", 443, "b")
    private val c = Server("c", "vless", "c.invalid", 443, "c")
    private val d = Server("d", "vless", "d.invalid", 443, "d")
    private val now = 400000L
    private fun sample(ms: Int = 100, speed: Int = 10000, at: Long = now) = ServerState(
        realMs = ms, bigOk = true, kbps = speed, speedNetwork = "wifi", speedMask = "", speedCheckedAt = at)
    private fun unknown(ms: Int = 100) = ServerState(realMs = ms)

    @Test fun refreshesAfterFiveMinutesWithoutExtendingOnConnectivityOnly() {
        assertFalse(AutoSelection.needsSample(sample(at=now-299999), "wifi", now))
        assertTrue(AutoSelection.needsSample(sample(at=now-300000).copy(checkedAt=now), "wifi", now))
        assertTrue(AutoSelection.needsSample(sample(), "cell", now))
    }
    @Test fun reservesAnUnmeasuredChallengerEvenAfterThreeCachedLeaders() {
        val states=mapOf(a to sample(), b to sample(120), c to sample(130), d to unknown(40))
        val plan=AutoSelection.finalists(listOf(a,b,c,d), { states.getValue(it) }, "wifi", now)
        assertEquals(3,plan.size); assertTrue(a in plan);assertTrue(d in plan)
    }
    @Test fun avoidsComparingThreeClonesOfTheSameHost() {
        val clone1=b.copy(address=a.address);val clone2=c.copy(address=a.address)
        val states=mapOf(a to unknown(10),clone1 to unknown(15),clone2 to unknown(20),d to unknown(25))
        val plan=AutoSelection.finalists(states.keys.toList(), {states.getValue(it)},"wifi",now)
        assertTrue(d in plan);assertEquals(a,plan.first());assertEquals(plan.size,plan.map {it.id}.distinct().size)
    }
    @Test fun thirdFinalistCanBeatFirstTwoAfterRealMeasurements() = runBlocking {
        val states=mutableMapOf(a to unknown(30),b to unknown(50),c to unknown(100))
        val called=mutableListOf<Server>();var clock=0L
        val winner=AutoSelection.compare(listOf(a,b,c),{states.getValue(it)},"wifi",{now},{clock},{true},{true},false) {server,_ ->
            called+=server;clock+=1000
            states[server]=sample(states.getValue(server).realMs,if(server==c)40000 else 1000)
        }
        assertEquals(c,winner);assertEquals(3,called.size)
    }
    @Test fun freshSamplesSkipRedundantDownloads() = runBlocking {
        val states=mapOf(a to sample(),b to sample(200));var probes=0
        assertEquals(a,AutoSelection.compare(listOf(a,b),{states.getValue(it)},"wifi",{now},{0L},{true},{true},false){_,_->probes++})
        assertEquals(0,probes)
    }
    @Test fun totalBudgetIncludesStartupAndLeavesNoUnboundedThirdProbe() = runBlocking {
        var clock=0L;val budgets=mutableListOf<Long>()
        AutoSelection.compare(listOf(a,b,c),{unknown()},"wifi",{now},{clock},{true},{true},false){_,budget->budgets+=budget;clock+=3200}
        assertEquals(listOf(2500L,2500L),budgets) // only 600 ms left, below minimum useful sample
    }
    @Test fun remainingBudgetIsPassedToTheLastProbe() = runBlocking {
        var clock=0L;val budgets=mutableListOf<Long>()
        AutoSelection.compare(listOf(a,b,c),{unknown()},"wifi",{now},{clock},{true},{true},false){_,budget->budgets+=budget;clock+=2500}
        assertEquals(listOf(2500L,2500L,2000L),budgets)
    }
    @Test fun aNetworkHandoffCannotReturnAnOldWinner() = runBlocking {
        var valid=true;var probes=0
        val winner=AutoSelection.compare(listOf(a,b),{unknown()},"wifi",{now},{0L},{valid},{true},false){_,_->probes++;valid=false}
        assertNull(winner);assertEquals(1,probes)
    }
    @Test fun networkChangeBeforeStartRunsNoDownload() = runBlocking {
        assertNull(AutoSelection.compare(listOf(a),{unknown()},"wifi",{now},{0L},{false},{true},false){_,_->fail("old network")})
    }
    @Test fun heatOrActiveTrafficStopsFurtherOptionalProbes() = runBlocking {
        var allowed=true;var count=0
        AutoSelection.compare(listOf(a,b,c),{unknown()},"wifi",{now},{0L},{true},{allowed},false){_,_->count++;allowed=false}
        assertEquals(1,count)
    }
    @Test fun gamingChoosesLatencyNotBandwidthAndDoesNotDownload() = runBlocking {
        val states=mapOf(a to sample(900,50000),b to sample(800,40000),c to sample(700,30000),d to sample(30,1000))
        assertEquals(d,AutoSelection.compare(states.keys.toList(),{states.getValue(it)},"wifi",{now},{0L},{true},{true},true){_,_->fail("gaming download")})
    }
    @Test fun failedAndDuplicateCandidatesCannotBecomeWinner() = runBlocking {
        val states=mapOf(a to sample().copy(bigOk=false),b to sample())
        assertEquals(b,AutoSelection.compare(listOf(a,b,b),{states.getValue(it)},"wifi",{now},{0L},{true},{true},false){_,_->fail("fresh")})
    }
    @Test fun parentCancellationDoesNotProduceALiveSelection() = runBlocking {
        var finished=false
        val job=launch { AutoSelection.compare(listOf(a),{unknown()},"wifi",{now},{0L},{true},{true},false){_,_->throw CancellationException("cancel")};finished=true }
        job.join();assertTrue(job.isCancelled);assertFalse(finished)
    }
    @Test fun probesNeverOverlap() = runBlocking {
        var active=0;var maxActive=0
        AutoSelection.compare(listOf(a,b,c),{unknown()},"wifi",{now},{0L},{true},{true},false){_,_->active++;maxActive=maxOf(active,maxActive);delay(5);active--}
        assertEquals(1,maxActive);assertEquals(0,active)
    }
    @Test fun perProbeDeadlineCancelsStalledWorkAndRunsCleanup() = runBlocking {
        var cleaned=false
        val winner=AutoSelection.compare(listOf(a),{unknown()},"wifi",{now},{System.nanoTime()/1000000},{true},{true},false){_,_->
            try { delay(10000) } finally { cleaned=true }
        }
        assertEquals(a,winner);assertTrue(cleaned) // timeout stays inconclusive; HTTPS candidate remains usable
    }

    @Test fun anInconclusiveRefreshCannotWinUsingOldUnreproducedBandwidth() = runBlocking {
        val states=mapOf(a to sample(10,50000,now-300000),b to sample(100,3000))
        assertEquals(b,AutoSelection.compare(listOf(a,b),{states.getValue(it)},"wifi",{now},{0L},{true},{true},false){_,_-> /* inconclusive */ })
        assertEquals(50000,states.getValue(a).kbps) // historical hint preserved, no false ban
    }

    @Test fun inconclusiveSampleIsInvalidatedForLaterLaunchesToo() = runBlocking {
        val states=mutableMapOf(a to sample(10,50000,now-300000),b to sample(100,3000))
        var invalidations=0
        val winner=AutoSelection.compare(listOf(a,b),{states.getValue(it)},"wifi",{now},{0L},{true},{true},false,
            onInconclusive={server,at->invalidations++;states[server]=ServerChoice.invalidateSpeed(states.getValue(server),"wifi",at)}) {_,_->}
        assertEquals(b,winner);assertEquals(1,invalidations)
        assertEquals(0,ServerChoice.measuredSpeed(states.getValue(a),"wifi",now))
        assertEquals(50000,states.getValue(a).kbps)
        assertEquals(b,listOf(a,b).minWithOrNull(ServerChoice.downloadOrder({states.getValue(it)},"wifi",now)))
    }
    @Test fun invalidationDoesNotEraseANewerConcurrentOrForeignSample() {
        val current=sample(at=now)
        assertEquals(current,ServerChoice.invalidateSpeed(current,"wifi",now-1))
        assertEquals(current,ServerChoice.invalidateSpeed(current,"cell",now))
        assertEquals(current,ServerChoice.invalidateSpeed(current,"wifi",0))
        assertEquals(0L,ServerChoice.invalidateSpeed(current,"wifi",now).speedCheckedAt)
    }
    @Test fun cancellationOrNetworkLossDoesNotInvalidateHistoricalHints() = runBlocking {
        var valid=true;var invalidations=0
        assertNull(AutoSelection.compare(listOf(a),{sample(at=now-300000)},"wifi",{now},{0L},{valid},{true},false,
            onInconclusive={_,_->invalidations++}) {_,_->valid=false})
        assertEquals(0,invalidations)
    }

}

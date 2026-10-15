package com.vlesscardvpn

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DownloadPriorityTest {
    private val now=500000L
    private val a=Server("a","vless","a.invalid",443,"a")
    private val b=Server("b","vless","b.invalid",443,"b")
    private val c=Server("c","vless","c.invalid",443,"c")
    private val d=Server("d","vless","d.invalid",443,"d")
    private fun sample(kbps:Int=10000,ms:Int=100)=ServerState(realMs=ms,bigOk=true,kbps=kbps,speedNetwork="wifi",speedMask="",speedCheckedAt=now)
    @Test fun thirtyMegabitsBeatsTenEvenWithHigherPing() {
        assertTrue(ServerChoice.compareDownload(sample(30000,700),sample(10000,20),"wifi",now)<0)
    }
    @Test fun pingOnlyBreaksEqualThroughputTies() {
        assertTrue(ServerChoice.compareDownload(sample(10000,20),sample(10000,700),"wifi",now)<0)
    }
    @Test fun unknownBandwidthCannotBeatMeasuredBandwidthWithTinyPing() {
        assertTrue(ServerChoice.compareDownload(sample(1000,1000),ServerState(realMs=1),"wifi",now)<0)
    }
    @Test fun staleForeignFutureOrChangedMaskCannotClaimTheFastestSlot() {
        val trusted=sample(3000)
        for (invalid in listOf(sample(100000).copy(speedNetwork="cell"),sample(100000).copy(speedCheckedAt=1),
            sample(100000).copy(speedCheckedAt=now+1),sample(100000).copy(maskId="different"))) {
            val time=if(invalid.speedCheckedAt==1L)now+1800001 else now
            assertTrue(ServerChoice.compareDownload(trusted.copy(speedCheckedAt=time),invalid,"wifi",time)<0)
        }
    }
    @Test fun failedTransferCannotWinWithHighOldSpeed() {
        assertTrue(ServerChoice.compareDownload(sample(3000),sample(Int.MAX_VALUE).copy(bigOk=false),"wifi",now)<0)
    }
    @Test fun noSampleFallsBackToConnectivityQuality() {
        assertTrue(ServerChoice.compareDownload(ServerState(realMs=50),ServerState(realMs=300),"wifi",now)<0)
    }
    @Test fun orderingIsConsistentAcrossTheWholePool() {
        val states=mapOf(a to sample(1000,10),b to sample(5000,20),c to sample(30000,500),d to sample(50000,700))
        assertEquals(listOf(d,c,b,a),states.keys.sortedWith(ServerChoice.downloadOrder({states.getValue(it)},"wifi",now)))
    }
    @Test fun fastestLeaderIsNotExcludedFromThreeFinalistsByItsPing() {
        val states=mapOf(a to sample(1000,10),b to sample(5000,20),c to sample(30000,500),d to sample(50000,700))
        assertEquals(d,AutoSelection.finalists(states.keys.toList(),{states.getValue(it)},"wifi",now).first())
    }
    @Test fun foregroundWinnerIsTheHighestMeasuredRate() = runBlocking {
        val states=mapOf(a to sample(10000,20),b to sample(30000,700))
        assertEquals(b,AutoSelection.compare(listOf(a,b),{states.getValue(it)},"wifi",{now},{0L},{true},{true},false){_,_->fail("fresh")})
    }
    @Test fun gameplayKeepsItsLatencyPreference() = runBlocking {
        val states=mapOf(a to sample(10000,20),b to sample(30000,700))
        assertEquals(a,AutoSelection.compare(listOf(a,b),{states.getValue(it)},"wifi",{now},{0L},{true},{true},true){_,_->fail("gaming")})
    }
    @Test fun backgroundRecognizesLargeBandwidthGainEvenWithHigherLatency() {
        assertTrue(ServerChoice.downloadUpgrade(sample(10000,20),sample(30000,700),"wifi",now))
    }
    @Test fun backgroundDoesNotChurnForTinyNoisyGains() {
        assertFalse(ServerChoice.downloadUpgrade(sample(10000),sample(11000),"wifi",now))
        assertFalse(ServerChoice.downloadUpgrade(sample(1000),sample(1300),"wifi",now))
        assertFalse(ServerChoice.downloadUpgrade(sample(10000),sample(5000,1),"wifi",now))
    }
    @Test fun cloudAndUnknownCannotForceBackgroundUpgrade() {
        assertFalse(ServerChoice.downloadUpgrade(sample(10000),sample(100000).copy(speedNetwork="cloud"),"wifi",now))
        assertTrue(ServerChoice.downloadUpgrade(ServerState(realMs=10),sample(3000),"wifi",now))
    }
    @Test fun parallelBandwidthSamplesCannotRefreshLiveRating() {
        assertTrue(ServerChoice.acceptsSpeedSample(10000,1));assertFalse(ServerChoice.acceptsSpeedSample(10000,6))
        assertFalse(ServerChoice.acceptsSpeedSample(0,1))
    }
    @Test fun extremeSpeedValuesDoNotOverflowUpgradeMath() {
        assertFalse(ServerChoice.downloadUpgrade(sample(Int.MAX_VALUE-1),sample(Int.MAX_VALUE),"wifi",now))
    }
}

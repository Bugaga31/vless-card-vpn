package com.vlesscardvpn.core

import org.junit.Assert.*
import org.junit.Test

class MaskSchedulingR236Test {
    private val pools=listOf(listOf("a1","a2"),listOf("b1","b2"))
    @Test fun oneSlotDoesNotSpendWholeWaveOnFirstHost() {assertEquals(listOf("a1","b1"),RecoveryWavePolicy.alternatives(pools,1))}
    @Test fun threeSlotsKeepAllBoundedAlternatives() {assertEquals(listOf("a1","b1","a2","b2"),RecoveryWavePolicy.alternatives(pools,3))}
    @Test fun fiveAndTenSlotsAreNotHardCodedToFour() {for(n in listOf(5,10))assertEquals(n,ProbeConcurrencyPolicy.normalize(n))}
    @Test fun oneSlotHasBudgetForBothHosts() {assertEquals(15000L,RecoveryWavePolicy.budgetMs(RecoveryWavePolicy.alternatives(pools,1).size,1))}
    @Test fun threeSlotsHaveBudgetForQueuedBatch() {assertEquals(15000L,RecoveryWavePolicy.budgetMs(RecoveryWavePolicy.alternatives(pools,3).size,3))}
    @Test fun fiveSlotsDoNotWaitForArtificialSecondBatch() {assertEquals(7500L,RecoveryWavePolicy.budgetMs(4,5))}
    @Test fun emptyPoolsAreSafe() {assertTrue(RecoveryWavePolicy.alternatives(emptyList<List<String>>(),5).isEmpty())}
    @Test fun emptyFirstPoolDoesNotStarveSecondHost() {assertEquals(listOf("b1","b2"),RecoveryWavePolicy.alternatives(listOf(emptyList(),pools[1]),1))}
    @Test fun duplicatesNeverWasteAProbeSlot() {assertEquals(listOf("a","b"),RecoveryWavePolicy.alternatives(listOf(listOf("a","a"),listOf("b","a")),3))}
    @Test fun pressureDoesNotIncreaseOptionalSampling() {assertTrue(AutomaticCoachPolicy.sampleDelay(true,true)>=AutomaticCoachPolicy.sampleDelay(true,false))}
    @Test fun hiddenCoachDoesNotWakeFasterUnderPressure() {assertEquals(120000L,AutomaticCoachPolicy.sampleDelay(false,true));assertEquals(120000L,AutomaticCoachPolicy.sampleDelay(false,false))}
    @Test fun pressureCannotReduceExplicitProbeCeiling() {
        val plan=PowerPolicy.evaluate(PowerPolicy.Input(0,battery=2,temp=47.0,saver=true),PowerPolicy.Options())
        for(n in listOf(1,3,5,10)) {assertEquals(n,ProbeConcurrencyPolicy.limit(20,n,plan,true));assertEquals(n,ProbeConcurrencyPolicy.limit(20,n,plan,false))}
    }
}

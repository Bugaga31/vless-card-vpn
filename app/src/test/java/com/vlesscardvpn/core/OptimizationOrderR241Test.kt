package com.vlesscardvpn.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source-order regressions, complemented by unchanged independent-UID E2E gates. */
class OptimizationOrderR241Test {
    private fun file(p:String)=listOf(File(p),File("../$p")).first {it.isFile}.readText()
    private fun operation()=file("app/src/main/java/com/vlesscardvpn/core/Actions.kt").substringAfter("suspend fun optimize(): Boolean {").substringBefore("// ---------- WARP")
    @Test fun healthSamplingPrecedesOptionalDeepWork() {
        val s=operation();assertTrue(s.indexOf("realTest(cand,")<s.indexOf("if (deepPending &&"))
    }
    @Test fun measuredRepairPrecedesOptionalPowerDeferral() {
        val s=operation();assertTrue(s.indexOf("if (switch) selectBest(")<s.indexOf("deep_power_changed"))
    }
    @Test fun changedPoolReturnsBeforeAnyDeepExploration() {
        val s=operation();assertTrue(s.indexOf("return outcome(true,\"repair_or_upgrade_applied\")")<s.indexOf("if (deepPending &&"))
        assertTrue(s.indexOf("lastOptimizeSwitched = changed")<s.indexOf("return outcome(true,\"repair_or_upgrade_applied\")"))
    }
    @Test fun deepQueueIsNotConsumedBeforeRepair() {
        val s=operation();assertTrue(s.indexOf("if (changed) {")<s.indexOf("deepPending = false"))
    }
    @Test fun laterMeasuredMaskChangeStillSignalsService() {
        val s=operation();assertTrue(s.indexOf("val finalSnapshot=Store.state.value")>s.indexOf("lightenMasks(wifi)"))
        assertTrue(s.contains("lastOptimizeSwitched=deepChanged"));assertTrue(s.contains("return outcome(ytSwitch || deepChanged"))
    }
    @Test fun networkFencesAndOriginalRepairDecisionRemain() {
        val s=operation();assertTrue(s.contains("NetworkObservation.accepts(observed,current)"))
        assertTrue(s.contains("BackgroundOptimizationPolicy.mayReplace(repairRequired,qualityGain)"))
        assertTrue(s.contains("if (!sameNetwork()) return outcome(false"))
    }
    @Test fun realTransferAndDeadMemberAssertionsAreNotWeakened() {
        val s=file("tools/e2e-android.sh")
        assertTrue(s.contains("python3 tools/e2e/verify_optimize_selection.py || fail"))
        assertTrue(s.contains("probe multi-after-optimize); okprobe"))
        assertTrue(s.contains("probe background-recovered); okprobe"))
    }
}

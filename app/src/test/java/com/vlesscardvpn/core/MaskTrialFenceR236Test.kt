package com.vlesscardvpn.core

import com.vlesscardvpn.model.*
import com.vlesscardvpn.xray.Masks
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MaskTrialFenceR236Test {
    private val cfg=Settings()
    private val server=Server("fixture","vless","node.example.test",443,"fixture",security="tls")
    private fun valid(c:Settings)=MaskTrialFence.settingsUnchanged(cfg,c)
    private fun rescue()=listOf(File("src/main/java/com/vlesscardvpn/core/Actions.kt"),File("app/src/main/java/com/vlesscardvpn/core/Actions.kt")).first{it.exists()}.readText().substringAfter("private suspend fun quickMaskRescue").substringBefore("private suspend fun chooseCompared")
    @Test fun exactSettingsRemainValid() {assertTrue(valid(cfg))}
    @Test fun changedTestUrlCannotPublishOrTrain() {assertFalse(valid(cfg.copy(testUrl="https://other.example.test/")))}
    @Test fun changedRequiredServicesInvalidateTrial() {assertFalse(valid(cfg.copy(autoChecks=setOf("youtube"))))}
    @Test fun changedModeInvalidatesTrial() {assertFalse(valid(cfg.copy(mode=Mode.SERVERS)))}
    @Test fun changedUpperLatencyInvalidatesTrial() {assertFalse(valid(cfg.copy(autoLatencyMaxMs=800)))}
    @Test fun changedMaskChoicesInvalidateTrial() {assertFalse(valid(cfg.copy(maskFamilies=listOf("hello"))));assertFalse(valid(cfg.copy(maskFps=listOf("firefox"))));assertFalse(valid(cfg.copy(maskEvolution=false)))}
    @Test fun editedProfilesInvalidateTrial() {assertFalse(valid(cfg.copy(myMasks=listOf("edited"))));assertFalse(valid(cfg.copy(autoMasks=listOf("edited"))))}
    @Test fun changedEngineSettingsInvalidateTrial() {assertFalse(valid(cfg.copy(byeDpiArgs="edited")));assertFalse(valid(cfg.copy(byeDpiSni="other.example.test")));assertFalse(valid(cfg.copy(dpiStrategy="edited")));assertFalse(valid(cfg.copy(dpiEvoPool=listOf("edited"))))}
    @Test fun themeOrProbeCeilingDoesNotCancelWork() {assertTrue(valid(cfg.copy(theme="light",probeParallel=1)))}
    @Test fun sameServerRemainsValid() {assertTrue(MaskTrialFence.serverUnchanged(server,listOf(server)))}
    @Test fun removedServerCannotReceiveOldProof() {assertFalse(MaskTrialFence.serverUnchanged(server,emptyList()))}
    @Test fun fingerprintAlpnAndPinEditsAreFencesEvenWithSameId() {
        for(edited in listOf(server.copy(fp="firefox"),server.copy(alpn="h2"),server.copy(pcs="edited"),server.copy(ech="edited"))) {
            assertEquals(server.id,edited.id);assertFalse(MaskTrialFence.serverUnchanged(server,listOf(edited)))
        }
    }
    @Test fun nativeEquivalentDoesNotWasteMaskRescueSlot() {assertFalse(AutoMaskSearchPolicy.pool(server,"",cfg){true}.any{it.id=="chrome.n"})}
    @Test fun realFragmentStillOfferedAndDpiNotInvented() {val p=AutoMaskSearchPolicy.pool(server,"",cfg){false};assertTrue(p.any{it.id=="chrome.hello.fast"});assertTrue(p.none{it.viaByeDpi});assertTrue(p.all{Masks.compatible(it,server)})}
    @Test fun explicitMaskCanStillReturnToNative() {assertTrue(AutoMaskSearchPolicy.pool(server,"chrome.hello.fast",cfg){true}.any{it.id=="chrome.n"})}
    @Test fun alternateFingerprintIsNotRemovedAsNative() {assertTrue(AutoMaskSearchPolicy.pool(server.copy(fp="firefox"),"",cfg){true}.any{it.id=="chrome.n"})}
    @Test fun memoryContextIncludesUrlAndProfile() {assertTrue(rescue().contains("val memoryContext=Roki.context(net,profile,profileLimit,start.settings.testUrl)"))}
    @Test fun onlyStartedTrialsAreScheduled() {val s=rescue();assertTrue(s.indexOf("onStarted={i->")<s.indexOf("MaskExperiment.memory.schedule"));assertTrue(s.contains("started.containsKey(i)"));assertTrue(s.contains("started.getValue(i)"))}
    @Test fun rescueNeverHardCodesFourSlots() {val s=rescue();assertTrue(s.contains("val slots=ProbeConcurrencyPolicy.normalize(start.settings.probeParallel)"));assertFalse(s.contains("else 4"));assertTrue(s.contains("RecoveryWavePolicy.alternatives"));assertTrue(s.contains("RecoveryWavePolicy.budgetMs(variants.size,slots)"))}
    @Test fun fenceProtectsTrainingAndAtomicCommit() {val s=rescue();assertTrue(s.indexOf("MaskTrialFence.settingsUnchanged")<s.indexOf("Roki.session.observe"));assertTrue(s.substringAfter("Store.update {state->").contains("MaskTrialFence.serverUnchanged"));assertTrue(s.contains("autoChecks=profile"));assertTrue(s.contains("finally {own.close()}"))}
}

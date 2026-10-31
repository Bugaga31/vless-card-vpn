package com.vlesscardvpn.core

import org.junit.Assert.*
import org.junit.Test

class HomeRuntimeTest {
    private fun entry(engine:MaskRuntimeEvidence.Engine=MaskRuntimeEvidence.Engine.RUNNING)=
        MaskRuntimeEvidence.Entry("same-name-a","same-name-b","Same name","Same name","ByeDPI",engine,true)
    @Test fun noDataIsNotCoreStarted() {assertEquals(MaskRuntimeEvidence.Config.NO_DATA,MaskRuntimeEvidence.Snapshot().config)}
    @Test fun beginDoesNotInventAppliedMask() {val e=MaskRuntimeEvidence.begin();assertEquals(e,MaskRuntimeEvidence.state.value.epoch);assertTrue(MaskRuntimeEvidence.state.value.entries.isEmpty());assertEquals(MaskRuntimeEvidence.Config.PREPARING,MaskRuntimeEvidence.state.value.config)}
    @Test fun oldStartCallbackIsRejected() {val old=MaskRuntimeEvidence.begin();val next=MaskRuntimeEvidence.begin();MaskRuntimeEvidence.started(old,listOf(entry()),false);assertEquals(next,MaskRuntimeEvidence.state.value.epoch);assertTrue(MaskRuntimeEvidence.state.value.entries.isEmpty())}
    @Test fun lateEngineCallbackIsRejected() {val old=MaskRuntimeEvidence.begin();val next=MaskRuntimeEvidence.begin();MaskRuntimeEvidence.started(next,listOf(entry()),false);MaskRuntimeEvidence.engines(old,emptyList());assertEquals(1,MaskRuntimeEvidence.state.value.entries.size)}
    @Test fun failureInvalidatesRevisionBeforeRevival() {val e=MaskRuntimeEvidence.begin();MaskRuntimeEvidence.started(e,listOf(entry()),false);val rev=MaskRuntimeEvidence.state.value.revision;MaskRuntimeEvidence.engines(e,listOf(entry(MaskRuntimeEvidence.Engine.UNAVAILABLE)));assertTrue(MaskRuntimeEvidence.state.value.revision>rev)}
    @Test fun revivalDoesNotRestoreOldRevision() {val e=MaskRuntimeEvidence.begin();MaskRuntimeEvidence.started(e,listOf(entry()),false);MaskRuntimeEvidence.engines(e,listOf(entry(MaskRuntimeEvidence.Engine.UNAVAILABLE)));MaskRuntimeEvidence.engines(e,listOf(entry()));assertEquals(2L,MaskRuntimeEvidence.state.value.revision)}
    @Test fun identicalPollDoesNotInvalidateProof() {val e=MaskRuntimeEvidence.begin();MaskRuntimeEvidence.started(e,listOf(entry()),false);MaskRuntimeEvidence.engines(e,listOf(entry()));assertEquals(0L,MaskRuntimeEvidence.state.value.revision)}
    @Test fun stoppedEngineCannotRemainRunning() {val e=MaskRuntimeEvidence.begin();MaskRuntimeEvidence.started(e,listOf(entry()),false);MaskRuntimeEvidence.stop(false);assertEquals(MaskRuntimeEvidence.Engine.STOPPED,MaskRuntimeEvidence.state.value.entries.single().engine)}
    @Test fun softRestartPreservesTunFactNotCoreSuccess() {MaskRuntimeEvidence.tun(true);MaskRuntimeEvidence.stop(true);assertTrue(MaskRuntimeEvidence.state.value.tunActive);assertEquals(MaskRuntimeEvidence.Config.STOPPED,MaskRuntimeEvidence.state.value.config)}
    @Test fun finalStopClearsTunFact() {MaskRuntimeEvidence.tun(true);MaskRuntimeEvidence.stop(false);assertFalse(MaskRuntimeEvidence.state.value.tunActive)}
    @Test fun proxyModeDoesNotInventTun() {MaskRuntimeEvidence.stop(false);val e=MaskRuntimeEvidence.begin();MaskRuntimeEvidence.started(e,listOf(entry()),true);assertTrue(MaskRuntimeEvidence.state.value.proxyOnly);assertFalse(MaskRuntimeEvidence.state.value.tunActive)}
    @Test fun sameNameDifferentIdsRemainDifferent() {val x=entry();assertEquals(x.requestedTitle,x.configuredTitle);assertNotEquals(x.requestedId,x.configuredId);assertTrue(x.fallback)}
    @Test fun oldCandidateCallbackCannotChangeNewRun() {val old=FoundAttemptEvidence.begin();FoundAttemptEvidence.begin();FoundAttemptEvidence.record(old,1,emptyList(),true);assertTrue(FoundAttemptEvidence.state.value.candidates.isEmpty())}
    @Test fun terminalAttemptIgnoresLateResults() {val e=FoundAttemptEvidence.begin();FoundAttemptEvidence.finish(e,FoundAttemptEvidence.Outcome.STALE);FoundAttemptEvidence.record(e,1,emptyList(),true);assertTrue(FoundAttemptEvidence.state.value.candidates.isEmpty())}
    @Test fun diagnosticPoolIsBounded() {val e=FoundAttemptEvidence.begin();for(i in 0..100) FoundAttemptEvidence.record(e,i,emptyList(),false);assertEquals(64,FoundAttemptEvidence.state.value.candidates.size)}
    @Test fun noneDoesNotInventServiceFailure() {val e=FoundAttemptEvidence.begin();FoundAttemptEvidence.finish(e,FoundAttemptEvidence.Outcome.NONE);assertTrue(FoundAttemptEvidence.state.value.candidates.isEmpty())}
}

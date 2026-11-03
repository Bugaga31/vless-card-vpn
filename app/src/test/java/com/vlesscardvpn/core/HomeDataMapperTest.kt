package com.vlesscardvpn.core

import org.junit.Assert.*
import org.junit.Test

class HomeDataMapperTest {
    private val all=AutoCheckProfile.of(AutoCheckProfile.allowed)
    private val good=Probe(200,true,true,tgOk=true,tgMs=210,discordOk=true,stableOk=true,callsOk=true,instagramOk=true)
    @Test fun connectedActionSurvivesBackgroundCheck() {assertEquals(HomePresentation.Action.DISCONNECT,HomePresentation.action(true,true))}
    @Test fun startingCanBeCancelled() {assertEquals(HomePresentation.Action.CANCEL,HomePresentation.action(false,true))}
    @Test fun idleCanConnect() {assertEquals(HomePresentation.Action.CONNECT,HomePresentation.action(false,false))}
    @Test fun completeEvidenceHasSevenServices() {assertEquals(7,HomeDataMapper.services(all,good,true).size)}
    @Test fun completeCurrentEvidenceIsGreen() {assertTrue(HomeDataMapper.services(all,good,true).all {it.verdict==HomePresentation.Verdict.PASS})}
    @Test fun staleCannotShowGreen() {assertTrue(HomeDataMapper.services(all,good,false).all {it.verdict==HomePresentation.Verdict.STALE})}
    @Test fun missingEvidenceCannotShowGreen() {assertTrue(HomeDataMapper.services(all,null,false).all {it.verdict==HomePresentation.Verdict.NO_DATA})}
    @Test fun disabledChecksAreNotSuccess() {assertEquals(HomePresentation.Verdict.NOT_REQUIRED,HomeDataMapper.services(AutoCheckProfile.of(emptySet()),good,true).first {it.id=="youtube"}.verdict)}
    @Test fun unknownIsNotFailure() {assertEquals(HomePresentation.Verdict.UNKNOWN,HomeDataMapper.services(all,good.copy(ytOk=null),true).first {it.id=="youtube"}.verdict)}
    @Test fun failedIsNotUnknown() {assertEquals(HomePresentation.Verdict.FAIL,HomeDataMapper.services(all,good.copy(ytOk=false),true).first {it.id=="youtube"}.verdict)}
    @Test fun stabilityRequiresTelegramChip() {assertEquals(HomePresentation.Verdict.PASS,HomeDataMapper.services(AutoCheckProfile.of(setOf("stability")),good,true).first {it.id=="telegram"}.verdict)}
    @Test fun incompleteTelegramSeriesIsUnknown() {assertEquals(HomePresentation.Verdict.UNKNOWN,HomeDataMapper.services(all,good.copy(stableOk=null),true).first {it.id=="stability"}.verdict)}
    @Test fun challengeDoesNotInventDpiFailure() {val s=HomeDataMapper.services(all,good.copy(ytOk=null,serviceNotes=mapOf("youtube" to "YT_LOGIN_REQUIRED")),true).first {it.id=="youtube"};assertEquals(HomePresentation.Verdict.UNKNOWN,s.verdict);assertTrue(s.detail.contains("вход"))}
    @Test fun rawUrlNeverAppearsAsNote() {assertFalse(HomePresentation.note("vless://secret@example.org").contains("secret"))}
    @Test fun arbitraryTokenNeverAppearsAsNote() {assertFalse(HomePresentation.note("Bearer secret-token").contains("token"))}
    @Test fun oldContextNotesAreNotPresented() {assertTrue(HomeDataMapper.services(all,good.copy(serviceNotes=mapOf("youtube" to "YT_LOGIN_REQUIRED")),false).all {it.detail.isEmpty()})}
    @Test fun clockRollbackIsStale() {assertFalse(HomePresentation.fresh(100,99))}
    @Test fun boundaryIsFresh() {assertTrue(HomePresentation.fresh(100,90100))}
    @Test fun pastBoundaryIsStale() {assertFalse(HomePresentation.fresh(100,90101))}
    @Test fun noSampleIsNotFresh() {assertFalse(HomePresentation.fresh(0,1))}
    @Test fun allContextGatesAreMandatory() {
        val gates=BooleanArray(7){true}
        fun check()=HomeDataMapper.proofCurrent(gates[0],gates[1],100,101,gates[2],gates[3],gates[4],gates[5],gates[6])
        assertTrue(check());for(i in gates.indices) {gates[i]=false;assertFalse("gate $i",check());gates[i]=true}
    }
}

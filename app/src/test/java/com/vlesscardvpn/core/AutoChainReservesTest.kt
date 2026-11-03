package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.core.AutoChainSelection.Layout
import com.vlesscardvpn.core.AutoChainSelection.Sample
import com.vlesscardvpn.core.AutoChainSelection.Verdict
import org.junit.Assert.*
import org.junit.Test

/** Synthetic memory/evidence tests. No physical phone, operator or Tor is exercised. */
class AutoChainReservesTest {
    private val settings=Settings(autoChainSearch=true)
    private fun value(key:String="reserve",at:Long=1000):AutoChainRunner.Measured {
        val server=Server(key,"vless","$key.test",443,"fixture",security="tls",sni="$key.test")
        val choice=AutoChainRunner.choice(server,null,Layout.SERVER,settings)
        val sample=Sample(choice.chain.id,1,at,Verdict.PASS,Verdict.PASS,Verdict.PASS,true,32768,100,40)
        return AutoChainRunner.Measured(choice,sample,"network-unit",1,AutoChainRunner.settingsKey(settings))
    }
    private fun add(c:AutoChainReserves,v:AutoChainRunner.Measured=value(),active:String?=null,now:Long=1000)=c.record(v,settings,"network-unit",1,now,active)
    private fun take(c:AutoChainReserves,now:Long=1000,net:String="network-unit",epoch:Long=1,s:Settings=settings)=c.take(s,net,epoch,now)
    @Test fun confirmedFreshAlternativeCanBeConsumedOnce() {
        val c=AutoChainReserves();val v=value();add(c,v);assertEquals(v,take(c));assertNull(take(c))
    }
    @Test fun currentRouteIsNotItsOwnReserve() {
        val c=AutoChainReserves();val v=value();add(c,v,v.choice.chain.id);assertNull(take(c))
    }
    @Test fun maximumTwoAlternativesAreRetained() {
        val c=AutoChainReserves();val a=value("a");val b=value("b");val d=value("c")
        listOf(a,b,d).forEach {add(c,it)}
        assertEquals(setOf(b.choice.chain.id,d.choice.chain.id),setOf(take(c)!!.choice.chain.id,take(c)!!.choice.chain.id));assertNull(take(c))
    }
    @Test fun unknownServiceIsNotConfirmedReserve() {
        val c=AutoChainReserves();val v=value();add(c,v.copy(sample=v.sample.copy(telegram=Verdict.UNKNOWN)));assertNull(take(c))
    }
    @Test fun newerServiceUnknownInvalidatesEarlierConfirmedReserve() {
        val c=AutoChainReserves();val v=value();add(c,v)
        add(c,v.copy(sample=v.sample.copy(telegram=Verdict.UNKNOWN)));assertNull(take(c))
    }
    @Test fun explicitServiceFailureIsExcluded() {
        val c=AutoChainReserves();val v=value();add(c,v.copy(sample=v.sample.copy(youtube=Verdict.FAIL)));assertNull(take(c))
    }
    @Test fun bodyProofCannotBeInventedByCache() {
        val c=AutoChainReserves();val v=value();add(c,v.copy(sample=v.sample.copy(verifiedBody=false)));assertNull(take(c))
    }
    @Test fun differentNetworkRejectsAndDropsOldEvidence() {
        val c=AutoChainReserves();add(c);assertNull(take(c,net="other-network"));assertNull(take(c))
    }
    @Test fun differentGenerationRejectsOldEvidence() {
        val c=AutoChainReserves();add(c);assertNull(take(c,epoch=2))
    }
    @Test fun changedUserSettingsInvalidateReserve() {
        val c=AutoChainReserves();add(c);assertNull(take(c,s=settings.copy(autoChainPurpose="interactive")))
    }
    @Test fun expiryDoesNotAutomaticallyPromoteOldSample() {
        val c=AutoChainReserves();add(c);assertNull(take(c,now=61001))
    }
    @Test fun futureEvidenceIsNotAccepted() {
        val c=AutoChainReserves();add(c,value(at=1001));assertNull(take(c))
    }
    @Test fun clearPreventsLateReuse() {
        val c=AutoChainReserves();add(c);c.clear();assertNull(take(c));assertTrue(c.candidateServers().isEmpty())
    }
    @Test fun duplicateMeasurementsDoNotGrowCache() {
        val c=AutoChainReserves();repeat(20){add(c)};assertNotNull(take(c));assertNull(take(c))
    }
    @Test fun endpointHintsDoNotExposeOrReuseSamples() {
        val c=AutoChainReserves();val v=value();add(c,v)
        assertEquals(listOf(v.choice.server),c.candidateServers());assertEquals(v,take(c))
    }
    @Test fun measuredTorFailureCooldownOverridesFreshReserve() {
        val c=AutoChainReserves();val s=settings.copy(torAuto=true)
        val choice=AutoChainRunner.choice(value("cooldown-reserve-unique").choice.server,null,Layout.TOR_SERVER,s)
        val v=value().copy(choice=choice,sample=value().sample.copy(chainId=choice.chain.id,exitCountry="DE"),settingsKey=AutoChainRunner.settingsKey(s))
        c.record(v,s,"network-unit",1,1000,null)
        AutoChainRunner.reject(choice,"network-unit",1000,true)
        assertNull(take(c,now=1001,s=s))
    }
    @Test fun laterFailedOrUnknownTrialDiscardsPriorPositiveReserve() {
        val c=AutoChainReserves();val v=value();add(c,v)
        c.discard(v.choice.chain.id);assertNull(take(c))
    }
    @Test fun newlyActiveRouteCannotBeReappliedAsBackup() {
        val c=AutoChainReserves();val v=value();add(c,v)
        assertNull(c.take(settings,"network-unit",1,1000,v.choice.chain.id))
    }
}

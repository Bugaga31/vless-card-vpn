package com.vlesscardvpn
import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.ServerState
import org.junit.Assert.*
import org.junit.Test
class HandoffR197Test {
 private fun path(iface:String="wlan0",mtu:Int=1500,addresses:List<String> = listOf("192.0.2.1/24"),routes:List<String> = listOf("0.0.0.0/0 via 192.0.2.254"),dns:List<String> = listOf("1.1.1.1"))=NetworkPath.revision(iface,mtu,addresses,routes,dns)
 @Test fun stablePathHasStableRevision(){assertEquals(path(),path())}
 @Test fun addressRenewalChangesRevision(){assertNotEquals(path(),path(addresses=listOf("192.0.2.2/24")))}
 @Test fun ipv6RenumberingChangesRevision(){assertNotEquals(path(addresses=listOf("2001:db8::1/64")),path(addresses=listOf("2001:db8::2/64")))}
 @Test fun routeChangeChangesRevision(){assertNotEquals(path(),path(routes=listOf("0.0.0.0/0 via 192.0.2.253")))}
 @Test fun mtuChangeChangesRevision(){assertNotEquals(path(),path(mtu=1280))}
 @Test fun interfaceChangeChangesRevision(){assertNotEquals(path(),path(iface="rmnet0"))}
 @Test fun dnsChangeChangesRevision(){assertNotEquals(path(),path(dns=listOf("8.8.8.8")))}
 @Test fun reorderingDoesNotRestart(){assertEquals(path(addresses=listOf("a","b"),routes=listOf("c","d"),dns=listOf("e","f")),path(addresses=listOf("b","a"),routes=listOf("d","c"),dns=listOf("f","e")))}
 @Test fun duplicateEntriesDoNotRestart(){assertEquals(path(dns=listOf("a")),path(dns=listOf("a","a")))}
 @Test fun fieldsCannotAlias(){assertNotEquals(path(iface="ab",addresses=listOf("c")),path(iface="a",addresses=listOf("bc")))}
 @Test fun noRawIdentifiersInRevision(){assertEquals(64,path().length);assertTrue(path().all {it in "0123456789abcdef"});assertFalse(path().contains("192.0.2"))}
 @Test fun sameHandleDhcpChangeInvalidatesOldWork(){val gate=NetworkHandoff();gate.seed(NetworkHandoff.Snapshot("101","wifi",path()));val changed=gate.update(NetworkHandoff.Snapshot("101","wifi",path(addresses=listOf("new"))))!!;assertTrue(gate.valid(changed.revision));assertNull(gate.update(NetworkHandoff.Snapshot("101","wifi",path(addresses=listOf("new")))));gate.update(null);assertFalse(gate.valid(changed.revision))}
 @Test fun oldTwoArgumentSnapshotsRemainCompatible(){val gate=NetworkHandoff();gate.seed(NetworkHandoff.Snapshot("a","wifi"));assertNull(gate.update(NetworkHandoff.Snapshot("a","wifi")))}
 @Test fun noGainKeepsWorkingRoute(){assertFalse(BackgroundOptimizationPolicy.mayReplace(false,false))}
 @Test fun repairDoesNotWaitForSpeedGain(){assertTrue(BackgroundOptimizationPolicy.mayReplace(true,false))}
 @Test fun gainAllowsReplacement(){assertTrue(BackgroundOptimizationPolicy.mayReplace(false,true))}
 @Test fun oneRouteDoesNotRestartForWeightNoise(){assertFalse(BackgroundOptimizationPolicy.weightsNeedRefresh(1,"a","b"));assertFalse(BackgroundOptimizationPolicy.weightsNeedRefresh(0,"a","b"))}
 @Test fun multipleRoutesCanRefreshChangedBalance(){assertTrue(BackgroundOptimizationPolicy.weightsNeedRefresh(2,"a","b"));assertFalse(BackgroundOptimizationPolicy.weightsNeedRefresh(2,"a","a"))}
 private fun sample(speed:Int)=ServerState(realMs=100,bigOk=true,kbps=speed,speedNetwork="wifi",speedMask="",speedCheckedAt=1000)
 @Test fun tenPercentDoesNotTriggerBackgroundSwitch(){assertFalse(ServerChoice.downloadUpgrade(sample(10000),sample(11000),"wifi",1000))}
 @Test fun twentyPercentWithMeaningfulGainAllowsSwitch(){assertTrue(ServerChoice.downloadUpgrade(sample(10000),sample(12000),"wifi",1000))}
 @Test fun staleOtherNetworkCannotUpgrade(){assertFalse(ServerChoice.downloadUpgrade(sample(10000),sample(20000).copy(speedNetwork="cell"),"wifi",1000))}
 @Test fun statusIsActualNotGuaranteedResult(){assertTrue(BackgroundOptimization.message(BackgroundOptimization.Stage.RUNNING).contains("Текущий маршрут"));assertFalse(BackgroundOptimization.message(BackgroundOptimization.Stage.RUNNING).contains("найден"))}
 @Test fun pauseReasonsAreDistinct(){assertEquals(BackgroundOptimization.Stage.values().size,BackgroundOptimization.Stage.values().map {BackgroundOptimization.message(it)}.distinct().size)}
 @Test fun statusCanBeClearedWithoutNetworkWork(){try{BackgroundOptimization.show(BackgroundOptimization.Stage.RUNNING);assertTrue(BackgroundOptimization.status.value.isNotBlank())}finally{BackgroundOptimization.clear()};assertEquals("",BackgroundOptimization.status.value)}
}

package com.vlesscardvpn
import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.tor.TorConnectionPolicy
import org.junit.Assert.*
import org.junit.Test
class ForegroundAutoR176Test {
 @Test fun explicitFastSearchRequestsEightNotBackgroundTwo() {assertEquals(8,ConnectPolicy.fastStart().parallel);assertEquals(2,ConnectPolicy.fastStart(true).parallel)}
 @Test fun balancedForegroundIsEight() {val p=PowerPolicy.evaluate(PowerPolicy.Input(0),PowerPolicy.Options());assertEquals(8,p.foregroundTcpParallel);assertEquals(8,p.foregroundRealParallel)}
 @Test fun ecoDoesNotTurnUserTcpSearchIntoBackgroundTwo() {val p=PowerPolicy.evaluate(PowerPolicy.Input(0),PowerPolicy.Options(profile="eco"));assertEquals(8,p.foregroundTcpParallel)}
 @Test fun mobileForegroundNotLimitedByBackgroundWifiPreference() {val p=PowerPolicy.evaluate(PowerPolicy.Input(0,metered=true),PowerPolicy.Options());assertFalse(p.allowLight);assertEquals(8,p.foregroundTcpParallel);assertEquals(8,p.foregroundRealParallel)}
 @Test fun hotPhoneStillProtected() {val p=PowerPolicy.evaluate(PowerPolicy.Input(0,thermal=3),PowerPolicy.Options());assertEquals(2,p.foregroundTcpParallel);assertEquals(2,p.foregroundRealParallel)}
 @Test fun lowMemoryStillProtected() {val p=PowerPolicy.evaluate(PowerPolicy.Input(0,lowMemory=true),PowerPolicy.Options());assertEquals(2,p.foregroundTcpParallel);assertEquals(2,p.foregroundRealParallel)}
 @Test fun voiceCallStillProtectsHeavyHttpsProbes() {val p=PowerPolicy.evaluate(PowerPolicy.Input(0,voiceCall=true),PowerPolicy.Options());assertEquals(1,p.foregroundRealParallel);assertEquals(8,p.foregroundTcpParallel)}
 @Test fun automaticTorNeedsNoManualBridgeAndDoesNotDeleteSavedLines() {val s=TorConnectionPolicy.automatic(Settings(torBridge="obfs4",torBridgeLines="saved"));assertEquals("none",s.torBridge);assertEquals("saved",s.torBridgeLines);assertTrue(s.torAll);assertTrue(s.torNetworkRecommendations);assertFalse(s.torDirect)}
}

package com.vlesscardvpn.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.pow

/** Source/contrast regression, NOT a Compose screenshot or device test. */
class HomeUiWiringTest {
    private fun source(name:String):String {val p="src/main/java/com/vlesscardvpn/$name.kt";return listOf(File(p),File("app/$p")).first {it.isFile}.readText()}
    @Test fun statusThemeFollowsAppNotSystem() {val s=source("ui/HomeStatusComponents");assertTrue(s.contains("MaterialTheme.colorScheme.surface.luminance()"));assertFalse(s.contains("isSystemInDarkTheme"))}
    @Test fun heroHasNoFixedHeightOrLinearSteps() {val s=source("ui/ConnectionOverview");assertTrue(s.contains("FlowRow"));assertFalse(s.contains("Modifier.height("));assertFalse(s.contains("StepPill"))}
    @Test fun mainActionKeepsExistingCallbacks() {val s=source("ui/HomeScreen");assertTrue(s.contains("HomePresentation.action(on,busy)"));assertTrue(s.contains("else onDisconnect()"))}
    @Test fun foundCountUsesActualAdmission() {assertTrue(source("ui/FoundRoutesCard").contains("AutoEligibility.servicePool"))}
    @Test fun consentStillUsesActualOfferValidation() {assertTrue(source("ui/FoundRoutesCard").contains("SlowRouteConsent.approve(pinned)"))}
    @Test fun proofHasRuntimeAndNetworkIdentity() {val s=source("ui/ConnectionOverview");assertTrue(s.contains("NetworkObservation.accepts"));assertTrue(s.contains("e.runtimeRevision==runtime.revision"));assertTrue(s.contains("e.runtimeEpoch==runtime.epoch"))}
    @Test fun coreStartPrecedesAppliedRuntimeRecord() {val s=source("vpn/TunnelService");assertTrue(s.indexOf("c.start(config)")<s.indexOf("MaskRuntimeEvidence.started"))}
    @Test fun serviceRecordsTunOnlyAfterEstablish() {val s=source("vpn/TunnelService");assertTrue(s.indexOf("startTun(settings, socks)")<s.indexOf("MaskRuntimeEvidence.tun(tun!=null)"))}
    @Test fun diagnosticsNeverUsesProbeError() {val s=source("core/HomeDataMapper")+source("core/HomePresentation");assertFalse(s.contains("probe.error"));assertFalse(s.contains("Regex("))}
    @Test fun paletteContentContrastAtLeastFourPointFive() {
        fun lum(rgb:Long):Double {fun ch(n:Int):Double {val x=((rgb shr n) and 255)/255.0;return if(x<=.04045) x/12.92 else ((x+.055)/1.055).pow(2.4)};return .2126*ch(16)+.7152*ch(8)+.0722*ch(0)}
        val pairs=Regex("0x(FF[0-9A-F]{6}) to 0x(FF[0-9A-F]{6})").findAll(source("ui/HomeStatusComponents")).toList()
        assertEquals(8,pairs.size)
        for(p in pairs) {val a=lum(p.groupValues[1].toLong(16));val b=lum(p.groupValues[2].toLong(16));assertTrue((maxOf(a,b)+.05)/(minOf(a,b)+.05)>=4.5)}
    }
    @Test fun publicationUsesStartContextNotCompletionContext() {val s=source("vpn/TunnelService");assertEquals(2,Regex("val sampledUiRuntime=").findAll(s).count());assertEquals(2,Regex("val sampledUiNetwork=").findAll(s).count());assertEquals(2,Regex("sampledUiNetwork,sampledUiRuntime").findAll(s).count());assertTrue(source("core/AutoCheckEvidence").contains("MaskRuntimeEvidence.matches(sampledRuntime)"))}
    @Test fun requestedMaskIsCapturedBeforeSafeFallback() {val s=source("vpn/TunnelService");assertTrue(s.contains("requestedMasks[srv.id]=requested"));assertTrue(s.indexOf("requestedMasks[srv.id]=requested")<s.indexOf("srv to if (safeMasks) null else requested"));assertFalse(s.contains("if(safeMasks) Masks.byId(st.state(server).maskFor(network))"))}
    @Test fun primaryControlPrecedesVerboseHeroHeading() {val s=source("ui/ConnectionOverview");assertTrue(s.indexOf("control()")<s.indexOf("Text(ConnectionPresentationPolicy.heading"));assertTrue(s.indexOf("control()")<s.indexOf("Text("Режим · ${'$'}modeLabel""))}
}

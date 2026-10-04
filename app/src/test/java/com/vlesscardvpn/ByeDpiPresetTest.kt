package com.vlesscardvpn
import com.vlesscardvpn.domain.*
import org.junit.Assert.*
import org.junit.Test
class ByeDpiPresetTest {
    private fun node(n: Int) = VlessConfig(name = "Node", address = "n$n.example.org", port = 443,
        uuid = "00000000-0000-4000-8000-000000000001", security = "tls", sni = "example.org", flow = "")
    @Test fun variantsUseActualDifferentNativeArguments() {
        assertEquals(listOf("--split", "1+s"), ByeDpiPreset.TCP_ONLY.extraArgs)
        assertEquals(listOf("--tlsrec", "1+s"), ByeDpiPreset.TLS_RECORD_ONLY.extraArgs)
        assertEquals(4, ByeDpiPreset.COMBINED.extraArgs.size)
    }
    @Test fun loopbackOnlyAndNoShellOrUntrustedArguments() {
        val args = ByeDpiPreset.TCP_ONLY.arguments(12400)
        assertEquals("127.0.0.1", args[args.indexOf("--ip") + 1])
        assertEquals("12400", args[args.indexOf("--port") + 1])
        assertFalse(args.any { it.contains(';') || it.contains('|') })
    }
    @Test fun invalidPortRejected() {
        try { ByeDpiPreset.COMBINED.arguments(0); fail("Invalid port accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun knownVariantIsRestoredAndUnknownVariantCannotInjectOptions() {
        assertEquals(ByeDpiPreset.TLS_RECORD_ONLY, ByeDpiPreset.order(0, "BYEDPI#TLS_RECORD_ONLY").first())
        assertNull(ByeDpiPreset.remembered("BYEDPI#--fake-packets"))
        assertEquals(ByeDpiPreset.COMBINED, ByeDpiPreset.order(0, "BYEDPI").first())
    }
    @Test fun firstVariantsAreSpreadAcrossNodesWithinTheBudget() {
        val plan = AutoSearchPolicy.plan((1..12).map { node(it) to it })
        val firstVariants = plan.drop(12).take(12).map { it.byeDpiPreset }
        assertEquals(12, firstVariants.toSet().size)
        assertTrue(firstVariants.all { it in ByeDpiPreset.entries })
        assertEquals(36, plan.size)
    }
    @Test fun allVariantsExistForSingleNodeWithoutReplacingItsCredentials() {
        val c = node(1); val plan = AutoSearchPolicy.plan(listOf(c to -1))
        assertEquals(ByeDpiPreset.entries.toSet(), plan.filter { it.profile == RouteProfile.BYEDPI }.map { it.byeDpiPreset }.toSet())
        assertTrue(plan.all { it.config == c }); assertEquals(ByeDpiPreset.entries.size + 2, plan.size)
    }
    @Test fun confirmedVariantMemoryIsRestoredBeforeOtherRoutes() {
        val c = node(1)
        val first = AutoSearchPolicy.plan(listOf(c to 12), mapOf(AutoConnectPolicy.identity(c) to "BYEDPI#TCP_ONLY")).first()
        assertEquals(RouteProfile.BYEDPI, first.profile); assertEquals(ByeDpiPreset.TCP_ONLY, first.byeDpiPreset)
    }
    @Test fun differentVariantKeysDoNotCollide() {
        val keys = AutoSearchPolicy.plan(listOf(node(1) to 1)).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }
    @Test fun newSniOffsetsUsePinnedNativeSyntaxAndDoNotReplaceDomains() {
        assertEquals(listOf("--split", "0+sm", "--tlsrec", "0+sm"), ByeDpiPreset.SNI_MIDDLE.extraArgs)
        assertEquals(listOf("--split", "1+s", "--split", "-1+se", "--tlsrec", "1+s", "--tlsrec", "-1+se"), ByeDpiPreset.SNI_EDGES.extraArgs)
        assertTrue(ByeDpiPreset.entries.filterNot { it.masked }.all { "--fake-sni" !in it.extraArgs })
        assertTrue(ByeDpiPreset.entries.all { "--tlsminor" !in it.extraArgs })
    }
    @Test fun newPresetsRestoreFromMemoryAndAllFiveAreReachableForOneNode() {
        val c = node(1)
        for (preset in listOf(ByeDpiPreset.SNI_MIDDLE, ByeDpiPreset.SNI_EDGES)) {
            val p = AutoSearchPolicy.plan(listOf(c to 1), mapOf(AutoConnectPolicy.identity(c) to "BYEDPI#${preset.name}"))
            assertEquals(preset, p.first().byeDpiPreset)
            assertEquals(ByeDpiPreset.entries.size + 2, p.size)
            assertEquals(ByeDpiPreset.entries.toSet(), p.mapNotNull { it.byeDpiPreset }.toSet())
            assertTrue(p.all { it.config == c })
        }
    }
    @Test fun competitorDerivedDisorderAndFakeUsePinnedNativeSyntax() {
        assertEquals(listOf("--disorder", "1"), ByeDpiPreset.DISORDER.extraArgs)
        assertEquals(listOf("--split", "1+s", "--disorder", "3+s"), ByeDpiPreset.SPLIT_DISORDER.extraArgs)
        val fake = ByeDpiPreset.DISORDER_THEN_FAKE.extraArgs
        // Fake is only a fallback group after a DPI reset/timeout, never the first action.
        assertTrue(fake.indexOf("--auto=torst") in 1 until fake.indexOf("--fake"))
        assertEquals("8", fake[fake.indexOf("--ttl") + 1])
        assertTrue(ByeDpiPreset.entries.filterNot { it.masked }.all { "--md5sig" !in it.extraArgs && "--fake-sni" !in it.extraArgs })
        assertEquals(15, ByeDpiPreset.entries.size)
    }
    @Test fun newStrategiesRestoreFromMemoryAndStayLoopbackOnly() {
        val c = node(1)
        for (preset in listOf(ByeDpiPreset.DISORDER, ByeDpiPreset.SPLIT_DISORDER, ByeDpiPreset.DISORDER_THEN_FAKE)) {
            val p = AutoSearchPolicy.plan(listOf(c to 1), mapOf(AutoConnectPolicy.identity(c) to "BYEDPI#${preset.name}"))
            assertEquals(preset, p.first().byeDpiPreset)
            val args = preset.arguments(12400)
            assertEquals("127.0.0.1", args[args.indexOf("--ip") + 1])
        }
    }
    @Test fun byeByeDpiOobAndMultiDisorderStrategiesUsePinnedSyntax() {
        assertEquals(listOf("--oob", "1"), ByeDpiPreset.OOB.extraArgs)
        assertEquals(listOf("--disoob", "1"), ByeDpiPreset.DISOOB.extraArgs)
        val oobAuto = ByeDpiPreset.OOB_THEN_DISORDER.extraArgs
        // Fallback disorder is only used after a DPI reset/timeout on the first group.
        assertTrue(oobAuto.indexOf("--auto=torst") in 1 until oobAuto.indexOf("--disorder"))
        val multi = ByeDpiPreset.MULTI_DISORDER.extraArgs
        assertEquals(3, multi.count { it == "--disorder" }); assertEquals(3, multi.count { it == "--split" })
        // No option takes a shell metacharacter, a file path, or a replaced hostname.
        assertTrue(ByeDpiPreset.entries.all { p -> p.extraArgs.none { it.contains('/') || it.contains(';') || it.contains('|') } })
        assertTrue(ByeDpiPreset.entries.all { "--fake-data" !in it.extraArgs && "--hosts" !in it.extraArgs && "--udp-fake" !in it.extraArgs })
    }
    @Test fun everyStrategyHasUniqueLabelAndArguments() {
        assertEquals(ByeDpiPreset.entries.size, ByeDpiPreset.entries.map { it.label }.toSet().size)
        assertEquals(ByeDpiPreset.entries.size, ByeDpiPreset.entries.map { it.extraArgs }.toSet().size)
    }
    @Test fun oobStrategiesRestoreFromMemory() {
        val c = node(1)
        for (preset in listOf(ByeDpiPreset.OOB, ByeDpiPreset.OOB_THEN_DISORDER, ByeDpiPreset.DISOOB, ByeDpiPreset.MULTI_DISORDER)) {
            val p = AutoSearchPolicy.plan(listOf(c to 1), mapOf(AutoConnectPolicy.identity(c) to "BYEDPI#${preset.name}"))
            assertEquals(preset, p.first().byeDpiPreset)
            assertEquals(ByeDpiPreset.entries.size + 2, p.size)
        }
    }
    @Test fun maskingPresetsSubstituteTheChosenDomainAndKeepLoopback() {
        val masked = ByeDpiPreset.entries.filter { it.masked }
        assertEquals(listOf(ByeDpiPreset.MASK_FAKE, ByeDpiPreset.MASK_SPLIT_FAKE, ByeDpiPreset.MASK_AUTO_FAKE), masked)
        for (preset in masked) {
            val args = preset.arguments(12400, "vk.com")
            assertEquals("vk.com", args[args.indexOf("--fake-sni") + 1])
            assertFalse(args.any { "{sni}" in it })
            assertEquals("127.0.0.1", args[args.indexOf("--ip") + 1])
            assertEquals("ya.ru", preset.arguments(12400, "bad host;rm").let { it[it.indexOf("--fake-sni") + 1] })
            assertEquals("8", args[args.indexOf("--ttl") + 1])
        }
        // Without MD5 the fake is only a fallback after a DPI reset/timeout.
        val auto = ByeDpiPreset.MASK_AUTO_FAKE.extraArgs
        assertTrue(auto.indexOf("--auto=torst") in 1 until auto.indexOf("--fake"))
        assertFalse("--md5sig" in auto)
        assertTrue("--md5sig" in ByeDpiPreset.MASK_FAKE.extraArgs)
        assertEquals("ya.ru", ByeDpiPreset.MASK_FAKE.arguments(12400).let { it[it.indexOf("--fake-sni") + 1] })
    }
    @Test fun maskingPresetsRestoreFromMemory() {
        val c = node(1)
        for (preset in listOf(ByeDpiPreset.MASK_FAKE, ByeDpiPreset.MASK_SPLIT_FAKE, ByeDpiPreset.MASK_AUTO_FAKE)) {
            val p = AutoSearchPolicy.plan(listOf(c to 1), mapOf(AutoConnectPolicy.identity(c) to "BYEDPI#${preset.name}"))
            assertEquals(preset, p.first().byeDpiPreset)
        }
    }
}

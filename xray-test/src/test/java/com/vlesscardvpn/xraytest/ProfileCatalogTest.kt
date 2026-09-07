package com.vlesscardvpn.xraytest
import org.junit.Test
import org.junit.Assert.*
import java.util.Base64

class ProfileCatalogTest {
    private val link = "vless://11111111-1111-4111-8111-111111111111@example.org:443?security=tls"
    private fun parse(text: String) = ProfileCatalog.parse(text, "Test") { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }
    @Test fun mixedImportKeepsSupportedNodes() { val r = parse("$link#One\nvmess://unsupported\n$link&allowInsecure=1"); assertEquals(1, r.profiles.size); assertEquals(2, r.skipped) }
    @Test fun base64SubscriptionSupported() { assertEquals(1, parse(Base64.getEncoder().encodeToString(link.toByteArray())).profiles.size) }
    @Test fun queryOrderAndNamesDoNotCreateDuplicates() { assertEquals(1, parse("$link&type=tcp#One\n${link.substringBefore('?')}?type=tcp&security=tls#Two").profiles.size) }
    @Test fun mergeDoesNotReplaceExistingFavorite() { val p = parse(link).profiles.single().copy(favorite = true); val merged = ProfileCatalog.merge(listOf(p), parse("$link#New").profiles); assertEquals(listOf(p), merged) }
    @Test fun emptyFavoritesNeverFallsBackToAll() { assertTrue(ProfileCatalog.candidates(parse(link).profiles, true, true, null).isEmpty()) }
    @Test fun manualSelectionDoesNotPickAnotherNode() { assertTrue(ProfileCatalog.candidates(parse(link).profiles, false, false, "missing").isEmpty()) }
    @Test fun autoFiltersFavorites() { val p = parse(link).profiles.single().copy(favorite = true); assertEquals(listOf(p), ProfileCatalog.candidates(listOf(p), true, true, null)) }
    @Test fun codecRoundTripPreservesFavoriteAndLabel() { val entries = parse("$link#My%20node").profiles.map { it.copy(favorite = true) }; assertEquals(entries, ProfileCatalog.decode(ProfileCatalog.encode(entries))) }
    @Test(expected = IllegalArgumentException::class) fun unsupportedStorageVersionDoesNotBecomeEmpty() { ProfileCatalog.decode("{\"version\":2,\"profiles\":[]}") }
    @Test fun mergeEnforcesCap() { val many = (1..120).map { parse(link.replace(":443?", ":${1000 + it}?")).profiles.single() }; assertEquals(100, ProfileCatalog.merge(emptyList(), many).size) }
    @Test fun balancedMergeDoesNotLetFirstSourceConsumeCatalogue() {
        fun group(prefix: String) = (1..100).map { parse(link.replace("example.org:443", "$prefix.example.org:${1000 + it}")).profiles.single() }
        val merged = ProfileCatalog.mergeBalanced(emptyList(), listOf(group("first"), group("second")))
        assertEquals(100, merged.size)
        assertEquals(50, merged.count { it.node().host.startsWith("first") })
        assertEquals(50, merged.count { it.node().host.startsWith("second") })
    }
    @Test fun htmlEncodedQuerySeparatorsAreNormalized() {
        val encoded = link.replace("?security=tls", "?security=tls&amp;type=tcp")
        assertEquals("tcp", parse(encoded).profiles.single().node().transport)
    }
}

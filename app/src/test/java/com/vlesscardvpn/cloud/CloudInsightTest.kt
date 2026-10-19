package com.vlesscardvpn.cloud

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.RouteEvidence
import com.vlesscardvpn.model.ServerState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudInsightTest {
    private val now = 2_000_000L
    private val speedJson = """{"stamp":1999,"isolated":true,"kbps":{"route":9000},"measured_at":{"route":1999}}"""

    @Test fun notModifiedChangesOnlyHttpCheckTime() {
        val before = CloudFreshness.Timeline(10, 20, 30, 40)
        assertEquals(CloudFreshness.Timeline(10, 99, 30, 40), CloudFreshness.notModified(before, 99))
    }

    @Test fun aNewFetchDoesNotRenewOldMeasurementOrPublishedStamp() {
        val index = CloudSpeed.parseIndex(speedJson, now)
        assertEquals(9000, CloudSpeed.lookup(index, "route", now))
        assertEquals(0, CloudSpeed.lookup(index, "route", now + CloudFreshness.SPEED_TTL_MS + 1))
        val previous = CloudFreshness.Timeline(10, 20, 30, 0)
        val fetched = CloudFreshness.fromSpeedDocument(speedJson, previous, now + 500, now + 500, now + 500)!!
        assertEquals(now + 500, fetched.downloadedAt)
        assertEquals(1_999_000L, fetched.publishedAt)
        assertEquals(1_999_000L, index.measuredAt["route"])
    }

    @Test fun legacyFetchedDoesNotInventDownloadedAt() {
        val timeline = CloudFreshness.fromSpeedDocument(speedJson, CloudFreshness.Timeline(), 0, 0, now)!!
        assertEquals(0L, timeline.downloadedAt)
        assertEquals(0L, timeline.checkedAt)
        assertEquals(1_999_000L, timeline.publishedAt)
    }

    @Test fun isolatedAndDatedSpeedIsRequired() {
        assertTrue(CloudSpeed.parseIndex(speedJson.replace("true", "false"), now).kbps.isEmpty())
        assertTrue(CloudSpeed.parseIndex("""{"stamp":1999,"isolated":true,"kbps":{"route":9000}}""", now).kbps.isEmpty())
        assertTrue(CloudSpeed.parseIndex("broken", now).kbps.isEmpty())
    }

    @Test fun timestampUnknownFutureBoundariesAndOverflowAreExplicit() {
        assertNull(CloudFreshness.ageMillis(0, now))
        assertNull(CloudFreshness.ageMillis(now + 1, now))
        assertNull(CloudFreshness.secondsToMillis(Long.MAX_VALUE))
        assertNull(CloudFreshness.secondsToMillis(-1))
        assertTrue(CloudFreshness.isFreshMeasurement(now - CloudFreshness.SPEED_TTL_MS, now))
        assertFalse(CloudFreshness.isFreshMeasurement(now - CloudFreshness.SPEED_TTL_MS - 1, now))
        assertFalse(CloudFreshness.isFreshMeasurement(now + 1, now))
        assertNotNull(CloudFreshness.publishedAt(now / 1000 + 300, now))
        assertNull(CloudFreshness.publishedAt(now / 1000 + 301, now))
    }

    @Test fun manifestAgeCanBeReadAfterExpiryButExpiredSnapshotIsNotUsable() {
        val manifest = JSONObject().put("schema", 1).put("stamp", 1000).put("expires", 1050)
            .put("nodes", JSONArray().put(JSONObject().put("link", "vless://00000000-0000-0000-0000-000000000001@node.example:443?security=reality&pbk=test&sni=example.com")
                .put("tier", "silver").put("cloudScore", .7)))
        val at = 2_000_000L
        val snapshot = CloudControl.parse(manifest.toString(), at)
        val timeline = CloudFreshness.fromManifestDocument(manifest.toString(), CloudFreshness.Timeline(), 0, 0, at)!!
        assertTrue(snapshot.entries.isNotEmpty())
        assertFalse(snapshot.usable(at))
        assertEquals(1_000_000L, timeline.publishedAt)
        assertTrue(CloudFreshness.expired(timeline, at))
        assertEquals(1_000_000L, CloudFreshness.ageMillis(timeline.publishedAt, at))
    }

    @Test fun networkAndMaskServiceEvidenceMustMatchAndBeFresh() {
        val now = 10_000_000L
        val state = ServerState(
            netMasks = mapOf("wifi-a" to "mask-a"),
            serviceNetwork = "wifi-a", serviceMask = "mask-a", serviceCheckedAt = now,
            serviceYtOk = true, serviceTgOk = true,
        )
        assertEquals(0, AutoEligibility.serviceRank(state, "wifi-a", now))
        assertEquals(1, AutoEligibility.serviceRank(state, "wifi-b", now))
        assertEquals(1, AutoEligibility.serviceRank(state.copy(serviceMask = "old-mask"), "wifi-a", now))
        assertEquals(1, AutoEligibility.serviceRank(state, "wifi-a", now + AutoEligibility.SERVICE_TTL_MS + 1))
        assertEquals(1, AutoEligibility.serviceRank(state, "wifi-a", now - 1))
    }

    @Test fun anotherMaskDoesNotInheritLocalNetworkEvidence() {
        val evidence = RouteEvidence().observe(true, 90, 1000)
        val state = ServerState(netMasks = mapOf("wifi-a" to "mask-a"), netEvidence = mapOf("wifi-a|mask-a" to evidence))
        assertEquals(1, state.evidenceFor("wifi-a").checks)
        assertEquals(0, state.copy(netMasks = mapOf("wifi-a" to "mask-b")).evidenceFor("wifi-a").checks)
        assertEquals(0, state.evidenceFor("wifi-b").checks)
    }

    @Test fun routeContextTokenDoesNotContainNetworkIdentifiers() {
        val raw = "Wi-Fi · ssid=secret-name gateway=192.168.1.1"
        val token = RouteInsight.token(raw)
        assertEquals(16, token.length)
        assertFalse(token.contains("secret-name"))
        assertFalse(token.contains("192.168.1.1"))
    }

    @Test fun cloudParsingDoesNotCreateLocalSuccesses() {
        val manifest = JSONObject().put("schema", 1).put("stamp", 1999).put("expires", 2050)
            .put("nodes", JSONArray().put(JSONObject().put("link", "vless://00000000-0000-0000-0000-000000000001@node.example:443?security=reality&pbk=test&sni=example.com")
                .put("tier", "silver").put("cloudScore", .7)))
        assertTrue(CloudControl.parse(manifest.toString(), now).entries.isNotEmpty())
        val untouched = ServerState()
        assertEquals(0, untouched.evidenceFor("wifi-a").checks)
        assertEquals(0, untouched.evidenceFor("wifi-a").successes)
    }
}
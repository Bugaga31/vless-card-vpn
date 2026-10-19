package com.vlesscardvpn.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

object CloudFreshness {
    const val MAX_CLOCK_SKEW_MS = 5 * 60_000L
    const val SPEED_TTL_MS = 60 * 60_000L

    data class Timeline(
        val downloadedAt: Long = 0,
        val checkedAt: Long = 0,
        val publishedAt: Long = 0,
        val expiresAt: Long = 0,
    )

    val speedTimeline = MutableStateFlow(Timeline())
    val manifestTimeline = MutableStateFlow(Timeline())

    fun secondsToMillis(seconds: Long): Long? =
        if (seconds < 0) null else runCatching { Math.multiplyExact(seconds, 1000L) }.getOrNull()

    fun publishedAt(seconds: Long, now: Long): Long? {
        val value = secondsToMillis(seconds) ?: return null
        val latest = runCatching { Math.addExact(now, MAX_CLOCK_SKEW_MS) }.getOrDefault(Long.MAX_VALUE)
        return value.takeIf { it > 0 && it <= latest }
    }

    fun isFreshMeasurement(measuredAt: Long, now: Long, ttlMs: Long = SPEED_TTL_MS): Boolean =
        measuredAt > 0 && now >= measuredAt && now - measuredAt <= ttlMs.coerceAtLeast(0)

    fun certifySpeed(isolated: Boolean, stampSeconds: Long, measuredSeconds: Long, now: Long): Long? {
        if (!isolated) return null
        val published = publishedAt(stampSeconds, now) ?: return null
        if (published < (now - SPEED_TTL_MS).coerceAtLeast(0)) return null
        val measuredAt = secondsToMillis(measuredSeconds) ?: return null
        return measuredAt.takeIf { isFreshMeasurement(it, now) }
    }

    fun fromSpeedDocument(json: String, previous: Timeline, downloadedAt: Long, checkedAt: Long, now: Long): Timeline? = runCatching {
        val o = JSONObject(json)
        val published = publishedAt(o.getLong("stamp"), now) ?: return null
        previous.copy(
            downloadedAt = downloadedAt.takeIf { it > 0 } ?: previous.downloadedAt,
            checkedAt = checkedAt.takeIf { it > 0 } ?: previous.checkedAt,
            publishedAt = published,
            expiresAt = 0,
        )
    }.getOrNull()

    fun fromManifestDocument(json: String, previous: Timeline, downloadedAt: Long, checkedAt: Long, now: Long): Timeline? = runCatching {
        val o = JSONObject(json)
        val published = publishedAt(o.getLong("stamp"), now) ?: return null
        val expires = secondsToMillis(o.getLong("expires")) ?: return null
        require(expires >= published && expires - published <= 24 * 60 * 60_000L)
        previous.copy(
            downloadedAt = downloadedAt.takeIf { it > 0 } ?: previous.downloadedAt,
            checkedAt = checkedAt.takeIf { it > 0 } ?: previous.checkedAt,
            publishedAt = published,
            expiresAt = expires,
        )
    }.getOrNull()

    fun notModified(previous: Timeline, checkedAt: Long): Timeline =
        previous.copy(checkedAt = checkedAt.takeIf { it > 0 } ?: previous.checkedAt)

    fun expired(timeline: Timeline, now: Long): Boolean =
        timeline.expiresAt > 0 && now >= timeline.expiresAt

    fun ageMillis(timestamp: Long, now: Long): Long? =
        if (timestamp <= 0 || now < timestamp) null else now - timestamp

    fun load(context: Context, name: String): Timeline {
        val p = context.getSharedPreferences("cloud_freshness", Context.MODE_PRIVATE)
        val prefix = "$name."
        return Timeline(
            downloadedAt = p.getLong(prefix + "downloaded", 0),
            checkedAt = p.getLong(prefix + "checked", 0),
            publishedAt = p.getLong(prefix + "published", 0),
            expiresAt = p.getLong(prefix + "expires", 0),
        ).also { publish(name, it) }
    }

    fun save(context: Context, name: String, timeline: Timeline) {
        val prefix = "$name."
        context.getSharedPreferences("cloud_freshness", Context.MODE_PRIVATE).edit()
            .putLong(prefix + "downloaded", timeline.downloadedAt)
            .putLong(prefix + "checked", timeline.checkedAt)
            .putLong(prefix + "published", timeline.publishedAt)
            .putLong(prefix + "expires", timeline.expiresAt)
            .apply()
        publish(name, timeline)
    }

    private fun publish(name: String, timeline: Timeline) {
        when (name) {
            "speed" -> speedTimeline.value = timeline
            "manifest" -> manifestTimeline.value = timeline
        }
    }
}
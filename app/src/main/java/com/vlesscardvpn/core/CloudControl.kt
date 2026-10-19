package com.vlesscardvpn.core

import android.content.Context
import com.vlesscardvpn.model.LinkParser
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.xray.Mask
import com.vlesscardvpn.xray.Masks
import org.json.JSONObject

/** Bounded, data-only manifest; no remote code, shared credentials or claimed local successes. */
object CloudControl {
    const val SOURCE = "cloud-manifest"
    data class Entry(val server: Server, val score: Double, val tier: String, val kbps: Int = 0, val speedAt: Long = 0, val sources: List<String> = emptyList())
    data class Snapshot(val stamp: Long, val expires: Long, val entries: List<Entry>, val recipes: JSONObject, val ispTags: JSONObject) {
        val byId: Map<String,Entry> = entries.associateBy { it.server.id }
        fun usable(now: Long): Boolean = expires > now
    }
    @Volatile private var snapshot: Snapshot? = null
    @Volatile private var attempted = 0L
    fun parse(text: String, now: Long): Snapshot {
        require(text.length <= 2 * 1024 * 1024)
        val o = JSONObject(text); require(o.getInt("schema") == 1)
        val stampSeconds = o.getLong("stamp"); val expiresSeconds = o.getLong("expires")
        val stamp = CloudFreshness.publishedAt(stampSeconds, now) ?: throw IllegalArgumentException("invalid manifest stamp")
        val expires = CloudFreshness.secondsToMillis(expiresSeconds) ?: throw IllegalArgumentException("invalid manifest expiry")
        require(expires >= stamp && expires - stamp in 1..86400000)
        val a = o.getJSONArray("nodes"); require(a.length() <= 6000)
        val entries = (0 until a.length()).mapNotNull { i ->
            val row = a.getJSONObject(i); val tier = row.getString("tier")
            if (tier !in setOf("gold", "silver")) return@mapNotNull null
            val score = row.getDouble("cloudScore"); require(score.isFinite() && score in 0.0..1.0)
            val server = LinkParser.parse(row.getString("link"), SOURCE) ?: return@mapNotNull null
            if (server.protocol !in setOf("vless", "vmess", "trojan", "shadowsocks")) return@mapNotNull null
            Entry(server,score,tier,row.optInt("kbps",0).coerceIn(0,10000000),CloudFreshness.secondsToMillis(row.optLong("speed_measured_at",0)) ?: 0,
                row.optJSONArray("sources")?.let { urls -> (0 until minOf(urls.length(),16)).mapNotNull { j ->
                    urls.optString(j).takeIf { it.length<=2048 && it.startsWith("https://") &&
                        runCatching { java.net.URI(it).let { u -> u.host != null && u.rawUserInfo == null && u.rawFragment == null } }.getOrDefault(false) }
                }.distinct() } ?: emptyList())
        }.distinctBy { it.server.id }
        return Snapshot(stamp,expires,entries,o.optJSONObject("recipes") ?: JSONObject(),o.optJSONObject("isp_tags") ?: JSONObject())
    }
    fun usable(server: Server, now: Long = System.currentTimeMillis()): Boolean = server.source != SOURCE ||
        snapshot?.takeIf { it.usable(now) }?.byId?.containsKey(server.id) == true
    fun recipesUsable(now: Long = System.currentTimeMillis()): Boolean = snapshot?.usable(now) == true
    fun sources(server: Server): List<String> = snapshot?.takeIf { it.usable(System.currentTimeMillis()) }?.byId?.get(server.id)?.sources.orEmpty()
    fun prior(server: Server, now: Long = System.currentTimeMillis()): Double? = snapshot?.takeIf { it.usable(now) }?.byId?.get(server.id)?.score
    fun ranked(servers: List<Server>, n: Int): List<Server> {
        val entries = snapshot?.takeIf { it.usable(System.currentTimeMillis()) }?.byId.orEmpty()
        return ServerChoice.diverse(servers.filter { it.id in entries && AutoEligibility.allowed(it) }
            .sortedWith(compareByDescending<Server> { speed(it) }.thenByDescending { entries.getValue(it.id).score }),n)
    }
    fun clear() {
        snapshot=null;attempted=0;Masks.setCloud(emptyList())
        CloudFreshness.manifestTimeline.value = CloudFreshness.Timeline()
    }
    fun count(now: Long = System.currentTimeMillis()): Int = snapshot?.takeIf { it.usable(now) }?.entries?.size ?: 0
    fun speedAt(server: Server, now: Long = System.currentTimeMillis()): Long? = snapshot?.takeIf { it.usable(now) }?.byId?.get(server.id)
        ?.speedAt?.takeIf { it > 0 && CloudFreshness.isFreshMeasurement(it, now) }
    fun speed(server: Server, now: Long = System.currentTimeMillis()): Int = if (speedAt(server, now) != null)
        snapshot?.byId?.get(server.id)?.kbps ?: 0 else 0
    fun maskHints(family: String): List<String> = hints("masks",family).filter { Masks.byId(it) != null }
    fun dpiHints(family: String): List<String> = hints("dpi",family).filter { id -> DpiStrategies.BUILT_IN.any { it.id == id } }.take(3)
    private fun hints(kind: String, family: String): List<String> {
        val s = snapshot?.takeIf { it.usable(System.currentTimeMillis()) } ?: return emptyList()
        val a = s.recipes.optJSONObject("families")?.optJSONObject(family)?.optJSONArray(kind) ?: s.recipes.optJSONObject("all")?.optJSONArray(kind)
        return a?.let { (0 until minOf(it.length(),12)).map { i -> it.optString(i).take(100) } }?.distinct().orEmpty()
    }
    fun fragments(recipes: JSONObject): List<Mask> {
        val a = recipes.optJSONArray("fragments") ?: return emptyList()
        return (0 until minOf(a.length(),12)).mapNotNull { i -> runCatching {
            val r = a.getJSONObject(i); val lo=r.getInt("minLength");val hi=r.getInt("maxLength");val delay=r.getInt("delayMs")
            require(lo in 1..1000 && hi in lo..1000 && delay in 0..20)
            val fp=r.optString("fingerprint","chrome");require(fp in Masks.REALITY_FPS)
            val sni=r.optString("sni");require(sni.isEmpty() || sni in Masks.WHITE_POOL)
            val id="cloudfrag#"+Net.fingerprint("recipe-v1",fp,lo.toString(),hi.toString(),delay.toString(),sni)
            Mask(id,"Облачный рецепт",fp,packets="tlshello",length="$lo-$hi",delay="$delay-$delay",sni=sni)
        }.getOrNull() }
    }
    private fun apply(ctx: Context, s: Snapshot) {
        if (!Report.enabled(ctx)) return
        snapshot=s
        if (!s.usable(System.currentTimeMillis())) {
            Masks.setCloud(emptyList())
            return
        }
        Masks.setCloud(fragments(s.recipes))
        CloudSpeed.cacheNet(ctx,s.ispTags.toString(),Net.family(Net.key(ctx)))
        Store.update { st ->
            val keep=st.servers.filter { it.source != SOURCE || st.state(it).selected }
            val ids=keep.map { it.id }.toHashSet()
            st.copy(servers=keep+s.entries.map { it.server }.filter { ids.add(it.id) })
        }
    }
    fun loadCached(ctx: Context) {
        if (!Report.enabled(ctx)) { clear(); return }
        if (snapshot != null) return
        val p = ctx.getSharedPreferences("cloud_control",0)
        var timeline = CloudFreshness.load(ctx, "manifest")
        p.getString("json",null)?.let { text -> runCatching {
            val now = System.currentTimeMillis(); val parsed = parse(text, now)
            timeline = CloudFreshness.fromManifestDocument(text, timeline, 0, 0, now) ?: timeline
            CloudFreshness.save(ctx, "manifest", timeline)
            apply(ctx, parsed)
        } }
    }
    @Synchronized fun refresh(ctx: Context) {
        loadCached(ctx);if (!Report.enabled(ctx) || !PhonePower.refresh(ctx).allowLight) return
        val now=System.currentTimeMillis()
        val p=ctx.getSharedPreferences("cloud_control",0)
        var timeline=CloudFreshness.load(ctx,"manifest")
        if (!CloudSpeed.refreshDue(now,maxOf(timeline.downloadedAt,timeline.checkedAt),maxOf(attempted,timeline.checkedAt),snapshot != null)) return
        attempted=now
        runCatching {
            val d=CloudTransport.fetch(ctx,"cloud-manifest.json",p.getString("etag",null)) { parse(it,now) }
            if (d.text != null) {
                val s=parse(d.text,d.checkedAt)
                timeline=CloudFreshness.fromManifestDocument(d.text,timeline,d.downloadedAt,d.checkedAt,d.checkedAt) ?:
                    timeline.copy(downloadedAt=d.downloadedAt,checkedAt=d.checkedAt)
                CloudFreshness.save(ctx,"manifest",timeline)
                apply(ctx,s);p.edit().putString("json",d.text).putString("etag",d.etag).apply()
            } else if (d.unchanged) {
                timeline=CloudFreshness.notModified(timeline,d.checkedAt)
                CloudFreshness.save(ctx,"manifest",timeline)
            }
        }
    }
}

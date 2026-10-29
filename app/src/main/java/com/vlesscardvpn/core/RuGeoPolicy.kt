package com.vlesscardvpn.core
import com.vlesscardvpn.model.Settings

/** Opt-in broad direct policy, never enabled by importing VPN subscriptions. */
object RuGeoPolicy {
    fun enabled(s: Settings) = s.ruAllDirect && !s.torAll
    private fun exclusions(s: Settings) = (s.alwaysVpn + if(s.torRouting) Services.domains(s.services) else emptyList())
        .map {it.removePrefix("domain:").removePrefix("full:").trim().trimEnd('.').lowercase()}
    fun domains(s: Settings): List<String> {
        // A geosite service category cannot be safely subtracted from the broad .ru DNS group.
        // Keep those DNS queries encrypted; protected Tor routes still precede GeoIP routing.
        if(!enabled(s) || s.torRouting && s.services.isNotEmpty()) return emptyList()
        val excluded=exclusions(s)
        val exact=KaringRuData.exact.filter {d -> excluded.none {v -> d==v || d.endsWith(".$v")}}
        // Conservatively remove a whole broad suffix if a protected child is inside it.
        val suffix=KaringRuData.suffix.filter {d -> excluded.none {v -> d==v || d.endsWith(".$v") || v.endsWith(".$d")}}
        return exact.map {"full:$it"} + suffix.map {"domain:$it"}
    }
    // IPv6 remains blocked by the existing app-wide policy; no silent IPv6 unblocking.
    fun ips(s: Settings) = if(enabled(s)) KaringRuData.ipv4 else emptyList()
}

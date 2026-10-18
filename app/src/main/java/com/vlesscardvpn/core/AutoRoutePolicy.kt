package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.xray.Masks

/** Soft exploration target, not a promised minimum speed or a connectivity requirement. */
object AutoRoutePolicy {
    const val TARGET_KBPS = 5000
    const val SEARCH_MS = 14000L
    const val MASK_MS = 4000L

    fun needsFaster(state: ServerState, net: String, now: Long): Boolean =
        ServerChoice.measuredSpeed(state, net, now) < TARGET_KBPS || AutoSelection.needsSample(state, net, now)

    fun configuration(server: Server, state: ServerState, net: String): String = server.id + "|" + state.maskFor(net)

    fun shouldCompareMasks(state: ServerState, net: String, now: Long): Boolean =
        needsFaster(state,net,now) || Masks.byId(state.maskFor(net))?.let {
            it.packets.isNotEmpty() || it.mss > 0 || it.mux || it.viaByeDpi || it.hasNoise
        } == true

    /** Auto must launch the exact route whose speed was measured, never a random old mask. */
    fun launchMask(automatic: Boolean, rotate: Boolean, measured: String, pool: List<String>, pick: (List<String>) -> String): String =
        if (!automatic && rotate && pool.isNotEmpty()) pick(pool) else measured

    /** Native link first, then a gentle fragment. No SNI/key changes or heavyweight DPI trial here. */
    fun maskTrials(server: Server, current: String, settings: Settings): List<String> {
        if (server.protocol !in setOf("vless", "vmess", "trojan")) return emptyList()
        val allowed = Masks.searchOrder(false, server, settings.maskFamilies, settings.maskFps).map { it.id }.toSet()
        val nativeChrome = server.fp.ifEmpty { "chrome" } == "chrome"
        return listOf("", "chrome.n", "chrome.h6").filter { id ->
            id != current && (id.isEmpty() || id in allowed) &&
                !(nativeChrome && (id == "chrome.n" || id.isEmpty() && current == "chrome.n"))
        }.take(2)
    }

    /** Only an isolated, full successful download can replace the current mask. */
    fun improved(current: ServerState, net: String, now: Long, sample: Probe): Boolean =
        sample.works && sample.bigOk == true && sample.kbps > 0 &&
            ServerChoice.measuredSpeed(current, net, now).let { old ->
                old <= 0 || sample.kbps.toLong() >= old.toLong() * 120 / 100 && sample.kbps.toLong() - old >= 256
            }

    fun apply(current: ServerState, net: String, mask: String, now: Long, sample: Probe): ServerState = current.copy(
        maskId = mask, netMasks = current.netMasks + (net to mask),
        realMs = sample.realMs, bigOk = sample.bigOk, kbps = sample.kbps,
        speedNetwork = net, speedMask = mask, speedCheckedAt = now, checkedAt = now)
}

package com.vlesscardvpn.core

import com.vlesscardvpn.model.Base64
import org.json.JSONObject

/**
 * «Резервная копия» (vcbackup:// link): settings, the user's own servers (added by hand and WARP — subscriptions
 * come back by themselves), their results and what was learned about networks (masks, DPI). For a new phone or
 * after reinstalling. Contains keys of the servers — share only with yourself.
 */
object Backup {
    const val SCHEME = "vcbackup://"
    private val LINK = Regex("vcbackup://([A-Za-z0-9_\\-=+/]+)")

    fun pack(st: AppState): String {
        val own = st.servers.filter { it.source == "manual" || it.source == "warp" || it.source.isEmpty() }
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(Store.encode(st.copy(servers = own)).toString().toByteArray()) }
        return SCHEME + Base64.encode(bos.toByteArray()).replace('+', '-').replace('/', '_').trimEnd('=')
    }

    fun find(text: String): String? = LINK.find(text)?.groupValues?.get(1)

    fun unpack(body: String): AppState? = runCatching {
        val bytes = Base64.decode(body) ?: return null
        Store.decode(JSONObject(String(java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.readBytes() })))
    }.getOrNull()

    /** Settings from the backup; servers, results and mask stats added to what is here (this phone wins on conflicts). */
    fun merge(cur: AppState, b: AppState): Pair<AppState, Int> {
        val known = cur.servers.map { it.id }.toHashSet()
        val fresh = b.servers.filter { known.add(it.id) }
        val ids = fresh.map { it.id }.toHashSet()
        val stats = (b.maskStats.keys + cur.maskStats.keys).associateWith { net -> b.maskStats[net].orEmpty() + cur.maskStats[net].orEmpty() }
        return cur.copy(servers = cur.servers + fresh, states = cur.states + b.states.filterKeys { it in ids },
            settings = b.settings, maskStats = stats) to fresh.size
    }
}

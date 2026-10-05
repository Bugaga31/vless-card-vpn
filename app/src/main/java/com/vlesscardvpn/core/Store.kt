package com.vlesscardvpn.core

import android.content.Context
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState
import com.vlesscardvpn.model.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class AppState(
    val servers: List<Server> = emptyList(),
    val states: Map<String, ServerState> = emptyMap(),
    val settings: Settings = Settings(),
) {
    fun state(s: Server): ServerState = states[s.id] ?: ServerState()
    val selected: List<Server> get() = servers.filter { state(it).selected }
}

/** App state persisted as one JSON file (servers, test results, settings). */
object Store {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var file: File? = null
    private var saveJob: Job? = null
    private val lock = Any()

    fun init(context: Context) = synchronized(lock) {
        if (file != null) return
        val f = File(context.filesDir, "state.json"); file = f
        if (f.isFile) runCatching { _state.value = decode(JSONObject(f.readText())) }
    }

    /** Re-reads state.json (used by the debug e2e hook after the file was replaced). */
    fun reload(context: Context) = synchronized(lock) {
        val f = File(context.filesDir, "state.json"); file = f
        val o = if (f.isFile) runCatching { JSONObject(f.readText()) }.getOrNull() else null
        var st = o?.let { runCatching { decode(it) }.getOrNull() } ?: AppState()
        // e2e helpers: select / mask servers by name (ids are computed by the app).
        val sel = o?.optJSONArray("e2e_select"); val masks = o?.optJSONObject("e2e_masks")
        if (sel != null || masks != null) {
            val names = sel?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
            st = st.copy(states = st.servers.associate { s ->
                s.id to st.state(s).copy(selected = s.name in names, maskId = masks?.optString(s.name).orEmpty())
            })
        }
        _state.value = st
    }

    fun update(change: (AppState) -> AppState) {
        _state.update(change)
        synchronized(lock) {
            saveJob?.cancel()
            saveJob = scope.launch { delay(400); save() }
        }
    }

    fun setState(id: String, change: (ServerState) -> ServerState) =
        update { st -> st.copy(states = st.states + (id to change(st.states[id] ?: ServerState()))) }

    fun saveNow() { saveJob?.cancel(); save() }

    private fun save() {
        val f = file ?: return
        val tmp = File(f.path + ".tmp")
        tmp.writeText(encode(_state.value).toString())
        tmp.renameTo(f)
    }

    fun encode(s: AppState): JSONObject = JSONObject()
        .put("servers", JSONArray().apply { s.servers.forEach { put(it.toJson()) } })
        .put("states", JSONObject().apply { s.states.forEach { (k, v) -> if (s.servers.any { it.id == k }) put(k, v.toJson()) } })
        .put("settings", s.settings.toJson())

    fun decode(o: JSONObject): AppState {
        val arr = o.optJSONArray("servers") ?: JSONArray()
        val servers = (0 until arr.length()).mapNotNull { runCatching { Server.fromJson(arr.getJSONObject(it)) }.getOrNull() }
        val so = o.optJSONObject("states") ?: JSONObject()
        val states = so.keys().asSequence().associateWith { ServerState.fromJson(so.getJSONObject(it)) }
        val settings = o.optJSONObject("settings")?.let { Settings.fromJson(it) } ?: Settings()
        return AppState(servers, states, settings)
    }

    /** Adds servers (dedupe by id). Returns number of new ones. */
    fun addServers(list: List<Server>): Int {
        var added = 0
        update { st ->
            val known = st.servers.map { it.id }.toHashSet()
            val fresh = list.filter { known.add(it.id) }
            added = fresh.size
            st.copy(servers = st.servers + fresh)
        }
        return added
    }

    /** Replaces the servers of one subscription, keeping results/selection of the ones still present. */
    fun replaceSource(source: String, list: List<Server>) = update { st ->
        val keep = st.servers.filter { it.source != source }
        val ids = keep.map { it.id }.toHashSet()
        st.copy(servers = keep + list.filter { ids.add(it.id) })
    }

    fun wipe(context: Context) = synchronized(lock) {
        saveJob?.cancel()
        _state.value = AppState()
        runCatching { File(context.filesDir, "state.json").delete() }
        runCatching { File(context.filesDir, "hev-socks5-tunnel.yaml").delete() }
        runCatching { context.cacheDir.deleteRecursively() }
    }

    fun remove(ids: Set<String>) = update { st -> st.copy(servers = st.servers.filterNot { it.id in ids }, states = st.states - ids) }
}

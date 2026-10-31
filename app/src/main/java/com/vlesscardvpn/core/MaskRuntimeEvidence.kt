package com.vlesscardvpn.core

import kotlinx.coroutines.flow.MutableStateFlow

/** Ephemeral facts for the running core, never an admission proof or persisted configuration. */
object MaskRuntimeEvidence {
    enum class Config { NO_DATA, PREPARING, CORE_STARTED, STOPPED, ERROR }
    enum class Engine { NOT_REQUIRED, RUNNING, UNAVAILABLE, STOPPED }
    data class Entry(val requestedId:String?,val configuredId:String?,val requestedTitle:String,
        val configuredTitle:String,val engineName:String,val engine:Engine,val fallback:Boolean)
    data class Snapshot(val epoch:Long=0,val config:Config=Config.NO_DATA,val entries:List<Entry> = emptyList(),
        val tunActive:Boolean=false,val proxyOnly:Boolean=false,val revision:Long=0)
    val state=MutableStateFlow(Snapshot())
    @Synchronized fun begin():Long {val n=state.value.epoch+1;state.value=Snapshot(epoch=n,config=Config.PREPARING,tunActive=state.value.tunActive);return n}
    @Synchronized fun started(epoch:Long,entries:List<Entry>,proxyOnly:Boolean) {
        if(state.value.epoch==epoch) state.value=state.value.copy(config=Config.CORE_STARTED,entries=entries,proxyOnly=proxyOnly)
    }
    @Synchronized fun engines(epoch:Long,entries:List<Entry>) {
        if(state.value.epoch!=epoch || state.value.config!=Config.CORE_STARTED) return
        // A restart invalidates old UI proof even if the process has already revived.
        val changed=entries!=state.value.entries
        state.value=state.value.copy(entries=entries,revision=state.value.revision+if(changed) 1 else 0)
    }
    /** A measurement begun before a restart must not be stamped as belonging to the new core. */
    @Synchronized fun matches(sampled:Snapshot):Boolean = sampled.config==Config.CORE_STARTED &&
        state.value.config==Config.CORE_STARTED && state.value.epoch==sampled.epoch &&
        state.value.revision==sampled.revision && state.value.entries.none {it.engine==Engine.UNAVAILABLE}
    @Synchronized fun tun(active:Boolean) {state.value=state.value.copy(tunActive=active)}
    @Synchronized fun stop(keepTun:Boolean,error:Boolean=false) {
        state.value=state.value.copy(epoch=state.value.epoch+1,config=if(error) Config.ERROR else Config.STOPPED,
            entries=state.value.entries.map {it.copy(engine=if(it.engine==Engine.NOT_REQUIRED) it.engine else Engine.STOPPED)},
            tunActive=keepTun && state.value.tunActive,revision=state.value.revision+1)
    }
}

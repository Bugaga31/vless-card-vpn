package com.vlesscardvpn.core

/** Session-local scheduling for the optional Hello experiment. No transport, keys or telemetry.
 * An absent/cancelled observation never becomes a failure. All times are monotonic.
 */
class MaskExperimentMemory(private val capacity:Int=512) {
    private data class Key(val network:String,val handle:String?,val server:String,val mask:String)
    private data class Entry(val scheduled:Long,val observed:Long=0,val ok:Boolean?=null,val streak:Int=0)
    private val entries=linkedMapOf<Key,Entry>()
    private var clock=0L
    private var scheduled=0; private var passed=0; private var rejected=0; private var unknown=0
    private fun tick(now:Long) {if(now<clock) entries.clear();clock=now}
    private fun cooling(e:Entry,now:Long):Boolean = when {
        e.observed<=0 -> now-e.scheduled<15_000L
        e.ok==false -> now-e.observed<minOf(240_000L,30_000L*(1L shl (e.streak-1).coerceIn(0,3)))
        else -> false
    }
    @Synchronized fun choose(network:String,handle:String?,server:String,masks:List<String>,now:Long,limit:Int=2):List<String> {
        tick(now)
        return masks.distinct().filter {id->entries[Key(network,handle,server,id)]?.let { !cooling(it,now) } ?: true}
            .sortedWith(compareBy<String> {id -> entries[Key(network,handle,server,id)]?.let {if(it.ok==true && now-it.observed<=600_000L) 0 else 1} ?: 1}
                .thenBy {id->entries[Key(network,handle,server,id)]?.scheduled ?: Long.MIN_VALUE})
            .take(limit.coerceIn(0,2))
    }
    @Synchronized fun schedule(network:String,handle:String?,server:String,mask:String,now:Long) {
        tick(now);val key=Key(network,handle,server,mask);val before=entries.remove(key)
        entries[key]=Entry(now,streak=before?.streak ?: 0)
        while(entries.size>capacity.coerceIn(1,512)) entries.remove(entries.keys.first())
        scheduled=(scheduled+1).coerceAtMost(1_000_000)
    }
    @Synchronized fun observe(network:String,handle:String?,server:String,mask:String,started:Long,now:Long,result:Boolean?) {
        tick(now);val key=Key(network,handle,server,mask);val before=entries[key] ?: return
        if(before.scheduled!=started || now<started) return
        if(result==null) {unknown=(unknown+1).coerceAtMost(1_000_000);return}
        entries[key]=before.copy(observed=now,ok=result,streak=if(result) 0 else (before.streak+1).coerceAtMost(4))
        if(result) passed=(passed+1).coerceAtMost(1_000_000) else rejected=(rejected+1).coerceAtMost(1_000_000)
    }
    @Synchronized fun counts():List<Int> = listOf(scheduled,passed,rejected,unknown,entries.size)
}

object MaskExperiment {
    val memory=MaskExperimentMemory()
    fun verdict(p:Probe):Boolean? = if(!p.works || p.tgOk==true && !AutoLatencyPolicy.accepts(p)) null else p.tgOk
    fun report():org.json.JSONObject {
        val c=memory.counts()
        return org.json.JSONObject().put("scope","session_hello_probe_trials_not_dpi_resistance")
            .put("scheduled",c[0]).put("confirmed",c[1]).put("rejected_mtproto",c[2]).put("unknown",c[3]).put("entries",c[4])
    }
}

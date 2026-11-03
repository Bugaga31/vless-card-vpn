package com.vlesscardvpn.core

/** Bounded device-session memory, separated by network + server + mask. Not a server deletion. */
class RouteStabilityMemory(private val capacity:Int=512) {
    private data class Entry(var at:Long,val samples:MutableList<LinkStabilityPolicy.Sample>,var badWindows:Int=0,var until:Long=0)
    private val entries=linkedMapOf<Triple<String,String,String>,Entry>()
    companion object {
        val session=RouteStabilityMemory()
        const val QUARANTINE_MS=120_000L
        fun mayAttribute(ordinaryAuto:Boolean,servers:Int,p:PowerPolicy.Plan) = ordinaryAuto && servers==1 &&
            !p.voiceCallActive && !p.memory.hot && !p.deferTraffic && (p.allowLight || p.allowReserve)
    }
    @Synchronized fun record(net:String,id:String,mask:String,sample:LinkStabilityPolicy.Sample,now:Long,maxMs:Int):Boolean {
        val key=Triple(net,id,mask)
        var e=entries.remove(key)
        if(e==null || now<e.at || now-e.at>180_000L) e=Entry(now,mutableListOf())
        e.at=now
        e.samples.add(sample);while(e.samples.size>5)e.samples.removeAt(0)
        val failed=e.samples.count {it.ok==false}
        val assessment=LinkStabilityPolicy.summarize(e.samples,maxMs)
        // One timeout does not quarantine a server. Require repeated failure or repeated bad spread/latency.
        val bad=e.samples.size>=3 && (failed>=2 || failed==0 && assessment.stable==false)
        e.badWindows=if(bad) (e.badWindows+1).coerceAtMost(3) else 0
        val newly=e.badWindows>=3 && now>=e.until
        if(newly) {e.until=now+QUARANTINE_MS;e.badWindows=0}
        entries[key]=e
        while(entries.size>capacity.coerceAtLeast(1))entries.remove(entries.keys.first())
        return newly
    }
    /** A complete fresh selected-service probe supersedes old failure history on this exact route.
     * A lone good watchdog sample or partial HTTPS result must NOT clear quarantine. */
    @Synchronized fun confirmed(net:String,id:String,mask:String,passed:Boolean) {
        if(passed) entries.remove(Triple(net,id,mask))
    }
    @Synchronized fun cooling(net:String,id:String,mask:String,now:Long):Boolean {
        val key=Triple(net,id,mask);val e=entries[key] ?: return false
        if(now<e.at) {entries.remove(key);return false}
        return now<e.until
    }
}

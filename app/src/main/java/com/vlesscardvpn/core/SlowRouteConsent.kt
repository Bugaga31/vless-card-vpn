package com.vlesscardvpn.core

import android.content.Context
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState
import kotlinx.coroutines.flow.MutableStateFlow

/** Pure proof check shared by the offer and the route-specific, session-only grant. */
object SlowRoutePolicy {
    fun measured(s:ServerState,profile:AutoCheckProfile,limit:Int,net:String,now:Long):Boolean =
        s.works && s.serviceNetwork==net && s.serviceMask==s.maskFor(net) &&
        AutoChecks.fresh(s.autoChecksKey,profile,Int.MAX_VALUE,s.autoChecksAt,now) &&
        s.realMs>0 && (!profile.youtube || s.ytOk==true) && (!profile.telegram || s.tgOk==true && s.tgMs>0) &&
        maxOf(s.realMs,if(profile.telegram) s.tgMs else 0)>limit
    fun matches(o:SlowRouteConsent.Offer,server:Server?,path:NetworkHandoff.Snapshot?,profile:AutoCheckProfile,
        limit:Int,url:String,mask:String):Boolean = server==o.server && path==o.path && profile==o.profile &&
        limit==o.limit && url==o.url && mask==o.mask
    fun latency(s:ServerState,profile:AutoCheckProfile)=maxOf(s.realMs,if(profile.telegram) s.tgMs else 0)
}

/** Consent never changes settings or admits failed/unknown services; it belongs to one exact route/path. */
object SlowRouteConsent {
    data class Offer(val server:Server,val path:NetworkHandoff.Snapshot,val mask:String,
        val profile:AutoCheckProfile,val limit:Int,val url:String,val measuredAt:Long,val ms:Int)
    val offer=MutableStateFlow<Offer?>(null)
    val count=MutableStateFlow(0)
    private val candidates=java.util.concurrent.ConcurrentHashMap<String,Offer>()
    private var context:Context?=null
    @Volatile private var grant:Offer?=null
    fun init(app:Context) {context=app.applicationContext}
    @Synchronized fun clear() {offer.value=null;count.value=0;candidates.clear();grant=null}
    @Synchronized fun hide() {offer.value=null;count.value=0;candidates.clear()}
    private fun valid(o:Offer):Boolean {
        val app=context ?: return false
        val st=Store.state.value
        return st.settings.mode==com.vlesscardvpn.model.Mode.AUTO && SlowRoutePolicy.matches(o,
            st.servers.firstOrNull {it==o.server},Net.observation(app),AutoChecks.current,
            AutoLatencyPolicy.MAX_MS,st.settings.testUrl,st.state(o.server).maskFor(o.path.profile))
    }
    private fun usable(o:Offer,now:Long):Boolean {
        if(!valid(o) || now-o.measuredAt !in 0..60_000L) return false
        val s=Store.state.value.state(o.server)
        return s.autoChecksAt==o.measuredAt && SlowRoutePolicy.measured(s,o.profile,o.limit,o.path.profile,now)
    }
    @Synchronized fun remember(servers:List<Server>,after:Long) {
        val app=context ?: return
        val path=Net.observation(app) ?: return
        val st=Store.state.value;val profile=AutoChecks.current;val limit=AutoLatencyPolicy.MAX_MS
        val now=System.currentTimeMillis()
        servers.forEach {s ->
            val x=st.state(s)
            if(x.checkedAt>=after && AutoEligibility.vlessServer(s) &&
                SlowRoutePolicy.measured(x,profile,limit,path.profile,now)) {
                candidates[s.id]=Offer(s,path,x.maskFor(path.profile),profile,limit,st.settings.testUrl,
                    x.autoChecksAt,SlowRoutePolicy.latency(x,profile))
            } else candidates.remove(s.id)
        }
        expose()
    }
    @Synchronized fun expose() {
        val now=System.currentTimeMillis()
        val ranked=FoundRoutePolicy.rank(candidates.values.toList(),{usable(it,now)},{it.ms},{it.server.id})
        val keep=ranked.map {it.server.id}.toSet()
        candidates.keys.removeAll {it !in keep}
        count.value=ranked.size
        offer.value=ranked.firstOrNull()
    }
    @Synchronized fun approve(expected:Offer?=offer.value):Boolean {
        val o=expected ?: return false
        if(candidates[o.server.id]!=o || !usable(o,System.currentTimeMillis())) {expose();return false}
        grant=o;hide();return true
    }
    fun permits(server:Server?,s:ServerState,net:String,now:Long):Boolean {
        val o=grant ?: return false
        return server==o.server && net==o.path.profile && valid(o) &&
            AutoChecks.fresh(s.autoChecksKey,o.profile,Int.MAX_VALUE,s.autoChecksAt,now) && s.works
    }
    fun runtime(servers:List<Server>):Boolean = grant?.let {o->servers.singleOrNull()==o.server && valid(o)}==true
    fun runtimeLimit(servers:List<Server>)=if(runtime(servers)) Int.MAX_VALUE else AutoLatencyPolicy.MAX_MS
}

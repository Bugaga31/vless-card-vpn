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
        s.realMs>0 && (!profile.telegram || s.tgOk==true && s.tgMs>0) &&
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
    private var context:Context?=null
    @Volatile private var pending:Offer?=null
    @Volatile private var grant:Offer?=null
    fun init(app:Context) {context=app.applicationContext}
    fun clear() {offer.value=null;pending=null;grant=null}
    fun hide() {offer.value=null;pending=null}
    private fun valid(o:Offer):Boolean {
        val app=context ?: return false
        val st=Store.state.value
        return st.settings.mode==com.vlesscardvpn.model.Mode.AUTO && SlowRoutePolicy.matches(o,
            st.servers.firstOrNull {it==o.server},Net.observation(app),AutoChecks.current,
            AutoLatencyPolicy.MAX_MS,st.settings.testUrl,st.state(o.server).maskFor(o.path.profile))
    }
    fun remember(servers:List<Server>,after:Long) {
        val app=context ?: return
        val path=Net.observation(app) ?: return
        val st=Store.state.value;val profile=AutoChecks.current;val limit=AutoLatencyPolicy.MAX_MS
        val s=servers.filter {st.state(it).checkedAt>=after && AutoEligibility.vlessServer(it) &&
            SlowRoutePolicy.measured(st.state(it),profile,limit,path.profile,System.currentTimeMillis())}
            .minByOrNull {SlowRoutePolicy.latency(st.state(it),profile)} ?: return
        val x=st.state(s)
        val next=Offer(s,path,x.maskFor(path.profile),profile,limit,st.settings.testUrl,x.autoChecksAt,SlowRoutePolicy.latency(x,profile))
        val old=pending
        if(old==null || !valid(old) || next.ms<old.ms) pending=next
    }
    fun expose() {
        val o=pending ?: return
        if(valid(o) && System.currentTimeMillis()-o.measuredAt in 0..60_000L) offer.value=o else hide()
    }
    fun approve():Boolean {
        val o=offer.value ?: return false
        val s=Store.state.value.state(o.server)
        if(!valid(o) || System.currentTimeMillis()-o.measuredAt !in 0..60_000L ||
            s.autoChecksAt!=o.measuredAt || !SlowRoutePolicy.measured(s,o.profile,o.limit,o.path.profile,System.currentTimeMillis())) {hide();return false}
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

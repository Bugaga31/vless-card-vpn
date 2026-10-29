package com.vlesscardvpn.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Ephemeral country of a SOCKS probe circuit, never a claimed country for every Tor stream. */
object TorExitStatus {
    data class Info(val active:Boolean=false,val country:String?=null)
    private var epoch=0L
    private val mutable=MutableStateFlow(Info())
    val state=mutable.asStateFlow()
    @Synchronized fun begin():Long {epoch++;mutable.value=Info(true);return epoch}
    @Synchronized fun clear() {epoch++;mutable.value=Info()}
    @Synchronized fun publish(ticket:Long,code:String?):Boolean {
        if(ticket!=epoch || !mutable.value.active) return false
        val country=code?.uppercase(Locale.ROOT)?.takeIf { it in Locale.getISOCountries().toSet() }
        mutable.value=Info(true,country);return true
    }
    fun label(info:Info):String = if(info.country==null) "Tor · страна выхода не определена"
        else "Tor · страна выхода: "+Countries.title(info.country)
}

package com.vlesscardvpn.core

/** Local, short-lived forecast affects optional app work only, never admission or tunnel control. */
object PhoneForecastPolicy {
    const val TTL_MS=180_000L
    fun fresh(at:Long,now:Long)=at>0 && now>=at && now-at<TTL_MS
    fun cleanWindow(beforeCharging:Boolean,charging:Boolean,foregroundSearch:Boolean)=beforeCharging==charging && !foregroundSearch
}

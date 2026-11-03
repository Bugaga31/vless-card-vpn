package com.vlesscardvpn.core

/** Process UID estimates only. Never a foreign-traffic classifier or a carrier bill. */
object TrafficAccountingPolicy {
    fun total(rx:Long,tx:Long):Long? = if(rx<0 || tx<0) null else if(rx>Long.MAX_VALUE-tx) Long.MAX_VALUE else rx+tx
    fun delta(previous:Long?,current:Long?):Long = if(previous==null || current==null || previous<0 || current<0 || current<previous) 0 else current-previous
    fun add(previous:Long,bytes:Long):Long {
        val old=previous.coerceAtLeast(0);val increment=bytes.coerceAtLeast(0)
        return if(old>Long.MAX_VALUE-increment) Long.MAX_VALUE else old+increment
    }
    fun nearLimit(bytes:Long,limit:Long)=limit>0 && bytes>=limit-limit/10
}

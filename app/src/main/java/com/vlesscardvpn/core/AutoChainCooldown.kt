package com.vlesscardvpn.core

/** In-memory, monotonic quarantine of exact Tor configurations, not whole countries.
 * A real FAIL quarantines immediately; two inconclusive trials within the window
 * quarantine too. Cancellation/network changes must never be reported as a trial.
 * Expiry permits a fresh probe; it never makes old evidence eligible again.
 */
class AutoChainCooldown(private val ttlMs:Long=600_000,private val capacity:Int=128) {
    init {require(ttlMs in 1..3_600_000);require(capacity in 1..1024)}
    private data class Entry(val strikes:Int,val at:Long,val until:Long)
    private val entries=LinkedHashMap<String,Entry>()
    private fun key(chain:AutoChainSelection.Chain,networkKey:String)=networkKey+":"+chain.id
    @Synchronized fun allowed(chain:AutoChainSelection.Chain,networkKey:String,now:Long):Boolean {
        if(!chain.layout.tor)return true
        val k=key(chain,networkKey);val e=entries[k] ?: return true
        // Clock rollback or TTL expiry drops evidence rather than extending a ban forever.
        if(now<e.at || now-e.at>=ttlMs){entries.remove(k);return true}
        return e.until==0L || now>=e.until
    }
    @Synchronized fun record(chain:AutoChainSelection.Chain,networkKey:String,now:Long,success:Boolean,definiteFailure:Boolean=false) {
        require(now>=0 && now<=Long.MAX_VALUE-ttlMs)
        if(!chain.layout.tor)return
        val k=key(chain,networkKey)
        if(success){entries.remove(k);return}
        val prior=entries.remove(k)?.takeIf {now>=it.at && now-it.at<ttlMs}
        val strikes=if(definiteFailure)2 else ((prior?.strikes ?: 0)+1).coerceAtMost(2)
        entries[k]=Entry(strikes,now,if(strikes>=2)now+ttlMs else 0)
        while(entries.size>capacity)entries.remove(entries.keys.first())
    }
    @Synchronized fun size()=entries.size
}

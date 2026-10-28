package com.vlesscardvpn.core

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject

/** A single test-core permit. Foreground gets the next permit, not an unsafe kill of a running probe. */
object ProbeArbiter {
    data class Snapshot(val active:String="idle",val foregroundWaiting:Int=0,val lastWaitMs:Long=0,val completed:Long=0)
    val state=MutableStateFlow(Snapshot())
    private val mutex=Mutex()
    private val urgent=AtomicInteger()
    private fun waiting() {state.update {it.copy(foregroundWaiting=urgent.get().coerceAtLeast(0))}}
    suspend fun <T> withPermit(foreground:Boolean=false,block:suspend ()->T):T {
        val start=SystemClock.elapsedRealtime()
        var registered=foreground
        if(registered) {urgent.incrementAndGet();waiting()}
        try {
            while(true) {
                if(!foreground && urgent.get()>0) {delay(20);continue}
                mutex.lock()
                if(!foreground && urgent.get()>0) {mutex.unlock();delay(1);continue}
                if(registered) {urgent.decrementAndGet();registered=false}
                state.update {it.copy(active=if(foreground) "foreground" else "background",foregroundWaiting=urgent.get().coerceAtLeast(0),lastWaitMs=(SystemClock.elapsedRealtime()-start).coerceAtLeast(0))}
                try {return block()}
                finally {
                    state.update {it.copy(active="idle",foregroundWaiting=urgent.get().coerceAtLeast(0),completed=(it.completed+1).coerceAtMost(1_000_000))}
                    mutex.unlock()
                }
            }
        } finally {if(registered) {urgent.decrementAndGet();waiting()}}
    }
    fun report():JSONObject {
        val s=state.value
        return JSONObject().put("active",s.active).put("foreground_waiting",s.foregroundWaiting)
            .put("last_permit_wait_ms",s.lastWaitMs).put("completed_batches",s.completed)
    }
}

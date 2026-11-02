package com.vlesscardvpn.core

/** Three serial MTProto samples share the candidate deadline. Queue/HTTPS waiting is not RTT. */
object StabilitySeriesProbe {
    data class Result(val stable:Boolean?,val ignoringLatency:Boolean?,val reason:String="")
    /** Keep all timings in one series on the first proven DC; missing or mismatched
     * target identity is inconclusive, never a substitute for valid MTProto evidence. */
    fun collectTelegram(first:TelegramProbe.Result,remaining:()->Long,maxMs:Int,
                        measure:(Long,Int)->TelegramProbe.Result):Result {
        val target=first.targetIndex
        val firstSample=LinkStabilityPolicy.Sample(if(first.ok==true && target==null) null else first.ok,first.ms)
        return collect(firstSample,remaining,maxMs) { allowance ->
            if(target==null) LinkStabilityPolicy.Sample(null,0)
            else {
                val next=measure(allowance,target)
                LinkStabilityPolicy.Sample(if(next.ok==true && next.targetIndex!=target) null else next.ok,next.ms)
            }
        }
    }
    fun collect(first:LinkStabilityPolicy.Sample,remaining:()->Long,maxMs:Int,
                measure:(Long)->LinkStabilityPolicy.Sample):Result {
        val samples=mutableListOf(first)
        repeat(2) {
            if(Thread.currentThread().isInterrupted) return Result(null,null,"CANCELLED")
            val left=remaining()
            // TelegramProbe requires >=200 ms. Never claim a completed series without it.
            if(left<200) return Result(null,null,"TIMEOUT")
            samples+=measure(minOf(2000L,left))
        }
        val summary=LinkStabilityPolicy.summarize(samples,maxMs)
        val relaxed=LinkStabilityPolicy.summarize(samples,Int.MAX_VALUE)
        val reason=when {
            summary.stable==true -> ""
            samples.any {it.ok==false} -> "STABILITY_RESPONSE_FAILED"
            summary.unknown>0 -> "STABILITY_INCOMPLETE"
            (summary.spreadMs ?: 0)>150 -> "STABILITY_SPREAD"
            else -> "STABILITY_LATENCY"
        }
        return Result(summary.stable,relaxed.stable,reason)
    }
}

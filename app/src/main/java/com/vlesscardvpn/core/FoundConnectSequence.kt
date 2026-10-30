package com.vlesscardvpn.core

/** Found-only flow has no callback for global discovery: fast proof starts immediately;
 * otherwise exactly one bounded revalidation is permitted, and context changes abort. */
object FoundConnectSequence {
    enum class Result { READY,NONE,STALE }
    suspend fun run(valid:()->Boolean,select:suspend ()->Boolean,probe:suspend ()->Unit):Result {
        if(!valid()) return Result.STALE
        if(select()) return Result.READY
        if(!valid()) return Result.STALE
        probe()
        if(!valid()) return Result.STALE
        return if(select()) Result.READY else if(valid()) Result.NONE else Result.STALE
    }
}

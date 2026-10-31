package com.vlesscardvpn.core

import kotlinx.coroutines.flow.MutableStateFlow

/** Bounded, process-local diagnostics; ordinal labels instead of server secrets, names or endpoints. */
object FoundAttemptEvidence {
    enum class Outcome { CHECKING, READY, NONE, STALE, ERROR, CANCELLED }
    data class Candidate(val ordinal:Int,val services:List<HomePresentation.Service>,val ready:Boolean)
    data class Snapshot(val run:Long=0,val outcome:Outcome=Outcome.NONE,val candidates:List<Candidate> = emptyList())
    val state=MutableStateFlow(Snapshot())
    @Synchronized fun begin():Long {val n=state.value.run+1;state.value=Snapshot(n,Outcome.CHECKING);return n}
    @Synchronized fun record(run:Long,ordinal:Int,services:List<HomePresentation.Service>,ready:Boolean) {
        if(state.value.run!=run || state.value.outcome!=Outcome.CHECKING || ordinal !in 1..64) return
        state.value=state.value.copy(candidates=(state.value.candidates.filterNot {it.ordinal==ordinal}+Candidate(ordinal,services,ready)).sortedBy {it.ordinal}.take(64))
    }
    @Synchronized fun finish(run:Long,outcome:Outcome) {if(state.value.run==run) state.value=state.value.copy(outcome=outcome)}
}

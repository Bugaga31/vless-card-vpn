package com.vlesscardvpn.core

import kotlinx.coroutines.flow.MutableStateFlow

/** Real-chain work, not TCP pings. Every eligible route gets a turn before the next cycle.
 * Queue admission is NOT a successful probe; cancelled/deadline work is retried next cycle.
 * RAM-only context partitions never store URLs, keys or network identifiers in the UI/telemetry.
 */
class BackgroundCatalogQueue {
    companion object {
        const val BATCH = 4
        const val ROUND_MS = 18_000L
        val progress = MutableStateFlow(Progress())
    }
    data class Progress(val total: Int=0,val issued: Int=0,val completed: Int=0,val cycle: Long=1) {
        fun text()="Фон через Xray, остальные вне пула: $completed из $total · в очереди выдано $issued · круг $cycle"
    }
    data class Ticket(val context: String,val cycle: Long,val routes: List<String>)
    private class Round(var cycle: Long=1) {
        val issued=linkedSetOf<String>();val completed=linkedSetOf<String>();var catalog=emptySet<String>()
    }
    private val rounds=linkedMapOf<String,Round>()
    private var visible=""
    private fun round(context: String, catalog: List<String>): Round {
        val r=rounds.remove(context) ?: Round();rounds[context]=r
        while(rounds.size>12) rounds.remove(rounds.keys.first())
        r.catalog=catalog.toSet();r.issued.retainAll(r.catalog);r.completed.retainAll(r.catalog)
        return r
    }
    private fun publish(context: String,r: Round) {
        if(visible==context) progress.value=Progress(r.catalog.size,r.issued.size,r.completed.size,r.cycle)
    }
    @Synchronized fun pending(context: String,catalog: List<String>): Boolean {
        visible=context
        val r=round(context,catalog);publish(context,r);return catalog.any {it !in r.issued}
    }
    @Synchronized fun next(context: String,catalog: List<String>,limit: Int=BATCH): Ticket {
        visible=context
        val unique=catalog.distinct();val r=round(context,unique)
        if(unique.isNotEmpty() && r.issued.containsAll(unique)) {r.cycle++;r.issued.clear();r.completed.clear()}
        val chosen=unique.filter {it !in r.issued}.take(limit.coerceIn(0,BATCH))
        r.issued.addAll(chosen);publish(context,r)
        return Ticket(context,r.cycle,chosen)
    }
    @Synchronized fun completed(ticket: Ticket,route: String) {
        val r=rounds[ticket.context] ?: return
        if(r.cycle!=ticket.cycle || route !in ticket.routes || route !in r.catalog) return
        r.completed+=route;publish(ticket.context,r)
    }
    @Synchronized fun snapshot(context: String): Progress? = rounds[context]?.let {
        Progress(it.catalog.size,it.issued.size,it.completed.size,it.cycle)
    }
}

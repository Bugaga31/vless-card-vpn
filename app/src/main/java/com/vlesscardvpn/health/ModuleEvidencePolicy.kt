package com.vlesscardvpn.health

/** Merge only measurements actually made. A light HTTPS probe never refreshes old DNS/media evidence. */
object ModuleEvidencePolicy {
    data class Frame(val assessment: HealthAssessment, val sampledAt: Map<HealthModule, Long>)
    fun merge(previous: HealthAssessment?, times: Map<HealthModule, Long>,
              measured: List<ModuleObservation>, now: Long): Frame {
        require(now >= 0)
        require(measured.map { it.module }.distinct().size == measured.size)
        val updates = measured.associateBy { it.module }
        val old = previous?.observations.orEmpty().associateBy { it.module }
        val observations = HealthModule.entries.map { module ->
            updates[module] ?: old[module] ?: ModuleObservation(module, ModuleStatus.UNKNOWN)
        }
        val stamps = observations.mapNotNull { observation ->
            val module = observation.module
            val at = if (module in updates) now else times[module]
            at?.let { module to it }
        }.toMap()
        return Frame(HealthAssessment(observations, previous?.blocked ?: false), stamps)
    }
}

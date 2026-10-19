package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

object RouteInsight {
    enum class Stage { FINALIST, SELECTED_POOL }
    enum class Criterion { DOWNLOAD, GAME_PROFILE }

    data class RouteFact(
        val index: Int,
        val localSpeedKbps: Int,
        val localSpeedAt: Long,
        val localChecks: Int,
        val localSuccesses: Int,
        val localEvidenceAt: Long,
        val youtube: Boolean?,
        val telegram: Boolean?,
        val serviceCheckedAt: Long,
        val cloudSpeedAt: Long,
        val manifestRatingHint: Boolean,
    )

    data class Decision(
        val decidedAt: Long,
        val networkToken: String,
        val stage: Stage,
        val criterion: Criterion,
        val sourceListSize: Int,
        val facts: List<RouteFact>,
        val modelObservations: Int,
        val modelNetworkScoped: Boolean,
        val speedTimeline: CloudFreshness.Timeline,
        val manifestTimeline: CloudFreshness.Timeline,
    )

    const val MAX_DECISIONS = 12
    const val MAX_ROUTES = 8
    private val processSalt = UUID.randomUUID().toString()
    val decisions = MutableStateFlow<List<Decision>>(emptyList())
    val currentNetworkToken = MutableStateFlow<String?>(null)

    fun token(network: String): String = Net.fingerprint(processSalt, network)

    @Synchronized fun record(
        stage: Stage,
        criterion: Criterion,
        sourceListSize: Int,
        routes: List<Server>,
        network: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val snapshot = Store.state.value
        val facts = routes.distinctBy { it.id }.take(MAX_ROUTES).mapIndexed { index, server ->
            val state = snapshot.state(server)
            val mask = state.maskFor(network)
            val evidence = state.evidenceFor(network)
            val localSpeed = ServerChoice.measuredSpeed(state, network, now)
            val serviceIsCurrent = state.serviceNetwork == network && state.serviceMask == mask &&
                state.serviceCheckedAt > 0 && now >= state.serviceCheckedAt && now - state.serviceCheckedAt <= AutoEligibility.SERVICE_TTL_MS
            val key = Report.serverKey(server)
            val cloudAt = CloudSpeed.measuredAt(key, now) ?: CloudControl.speedAt(server, now) ?: 0L
            RouteFact(
                index = index + 1,
                localSpeedKbps = localSpeed,
                localSpeedAt = state.speedCheckedAt.takeIf { localSpeed > 0 } ?: 0,
                localChecks = evidence.checks,
                localSuccesses = evidence.successes,
                localEvidenceAt = evidence.at,
                youtube = state.serviceYtOk.takeIf { serviceIsCurrent },
                telegram = state.serviceTgOk.takeIf { serviceIsCurrent },
                serviceCheckedAt = state.serviceCheckedAt.takeIf { serviceIsCurrent } ?: 0,
                cloudSpeedAt = cloudAt,
                manifestRatingHint = CloudControl.prior(server, now) != null,
            )
        }
        val model = Brain.observationCount(network)
        val decision = Decision(
            decidedAt = now,
            networkToken = token(network),
            stage = stage,
            criterion = criterion,
            sourceListSize = sourceListSize.coerceAtLeast(0),
            facts = facts,
            modelObservations = model.count,
            modelNetworkScoped = model.networkScoped,
            speedTimeline = CloudFreshness.speedTimeline.value,
            manifestTimeline = CloudFreshness.manifestTimeline.value,
        )
        decisions.value = (listOf(decision) + decisions.value).take(MAX_DECISIONS)
    }

    fun age(timestamp: Long, now: Long): Long? = CloudFreshness.ageMillis(timestamp, now)

    fun currentFor(decision: Decision): Boolean? = currentNetworkToken.value?.let { it == decision.networkToken }
}
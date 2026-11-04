package com.vlesscardvpn.core

import com.vlesscardvpn.core.AutoChainSelection.Chain
import com.vlesscardvpn.core.AutoChainSelection.Layout
import com.vlesscardvpn.core.AutoChainSelection.Sample
import com.vlesscardvpn.core.AutoChainSelection.Verdict
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.xray.ChainProbeConfig
import com.vlesscardvpn.xray.Mask
import com.vlesscardvpn.xray.Masks
import com.vlesscardvpn.xray.XrayConfigBuilder
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest

/** Actual isolated Xray + real HTTPS body/MTProto/media probes. No Store server evidence writes. */
object AutoChainRunner {
    private val cooldown=AutoChainCooldown()
    fun reject(choice:Choice,network:String,now:Long,definiteFailure:Boolean) =
        cooldown.record(choice.chain,digest(network),now,success=false,definiteFailure=definiteFailure)

    data class Choice(val chain: Chain, val server: Server?, val mask: Mask?, val dpiStrategy: String = "")
    data class Measured(val choice: Choice, val sample: Sample, val network: String, val epoch: Long,
                        val settingsKey: String)
    fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun settingsKey(s: Settings) = digest(listOf(s.autoChainSearch,s.autoChainPurpose,s.torAuto,s.torNetworkRecommendations,s.torBridge,s.torBridgeLines,
        s.services,s.alwaysDirect,s.alwaysVpn,s.warpChain,s.smartYoutube,s.torRotation,s.torRotationSeconds,s.ruDirect,s.ruDns,s.ruWhitelist,s.ruAllDirect,s.autoChecks.sorted(),s.autoLatencyMaxMs).joinToString("|"))
    fun profileKey(s: Settings) = digest(s.torBridge+"|"+s.torBridgeLines)
    fun choice(server: Server?, mask: Mask?, layout: Layout, s: Settings, dpiIdentity: String = "", dpiStrategy: String = ""): Choice {
        val native = mask?.copy(dpi = "")
        return Choice(Chain(layout,serverKey=server?.let { digest(it.toJson().toString()) }.orEmpty(),
            maskKey=digest(native?.toString().orEmpty()),torProfileKey=if(layout.tor)profileKey(s) else "",
            dpiKey=if(layout.dpi)digest(dpiIdentity) else "",tcpServer=server?.isTcpBased ?: true,
            serverExitAllowed=server?.let(AutoEligibility::vlessServer) ?: false),server,native,dpiStrategy)
    }
    fun fresh(m: Measured, s: Settings, network: String, epoch: Long, now: Long): Boolean =
        s.autoChainSearch && cooldown.allowed(m.choice.chain,digest(network),now) && m.network == network && m.epoch == epoch && m.settingsKey == settingsKey(s) &&
        (!m.choice.chain.layout.tor || s.torAuto) && AutoChainSelection.eligible(m.sample,m.choice.chain,epoch,now)

    suspend fun compare(candidates: List<Choice>, s: Settings, network: String, epoch: Long,
                        generation: () -> Long, elapsed: () -> Long, currentId: String?,
                        currentDpi: Int?, dpiPorts: Map<String,Int>, torReady: Boolean,
                        onMeasured: ((Measured) -> Unit)? = null,
                        onRejected: ((Choice) -> Unit)? = null): Measured? =
        withTimeoutOrNull(AutoChainSelection.TOTAL_MS) {
            Tester.exclusiveProbe {
                val networkKey=digest(network)
                val pool=candidates.distinctBy { it.chain.id }.filter {cooldown.allowed(it.chain,networkKey,elapsed())}.take(AutoChainSelection.MAX_CANDIDATES)
                val index=pool.associateBy { it.chain.id }; val samples=HashMap<String,Sample>()
                val result=AutoChainSelection.select(pool.map {it.chain},
                    AutoChainSelection.Capabilities(ChainProbeConfig.implemented, s.torAuto, torReady),
                    AutoChainSwitchPolicy.purpose(s.autoChainPurpose),elapsed,generation,onTrial={chain,sample,at ->
                        cooldown.record(chain,networkKey,at,
                            success=sample!=null && AutoChainSelection.eligible(sample,chain,epoch,at),
                            definiteFailure=sample?.let {it.data==Verdict.FAIL || it.telegram==Verdict.FAIL || it.youtube==Verdict.FAIL}==true)
                        if (generation() == epoch) {
                            if (sample != null && AutoChainSelection.eligible(sample,chain,epoch,at))
                                onMeasured?.invoke(Measured(index.getValue(chain.id),sample,network,epoch,settingsKey(s)))
                            else onRejected?.invoke(index.getValue(chain.id))
                        }
                    }) { chain ->
                    val option=index.getValue(chain.id)
                    val port=ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use { it.localPort }
                    val core=XrayCore.Instance("auto-chain")
                    try {
                        val dpiPort=if(option.dpiStrategy==Masks.CURRENT_DPI)currentDpi else dpiPorts[option.dpiStrategy]
                        val config=ChainProbeConfig.build(chain,port,option.server?.let { XrayConfigBuilder.outbound(it,"chain-main",option.mask) },
                            if(chain.layout.tor) com.vlesscardvpn.tor.TorCard.DEFAULT_SOCKS_PORT else null,dpiPort)
                        CancellableProbe.run { bind ->
                            val cancelled=java.util.concurrent.atomic.AtomicBoolean(false)
                            bind { cancelled.set(true);core.stop() }
                            check(!cancelled.get()) { "Chain startup cancelled" }
                            core.start(config)
                            if(cancelled.get()){core.stop();error("Late chain startup cancelled")}
                        }
                        currentCoroutineContext().ensureActive()
                        check(core.running) { "Chain Xray did not start" }
                        object:AutoChainSelection.Session {
                            override suspend fun measure(remainingMs:Long):Sample {
                                var country:String?=null
                                val p=CancellableProbe.run { bind ->
                                    val started=elapsed()
                                    if(chain.layout.tor)country=Tester.exitCountry(port,minOf(800,remainingMs.coerceAtLeast(1)),onCall={call->bind {call.cancel()}})
                                    val budget=(remainingMs-(elapsed()-started)).coerceAtLeast(1)
                                    Tester.probeSocks(port,s.testUrl,big=true,youtube=true,attempts=1,budgetMs=budget,onCall={call->bind {call.cancel()}},onCancel=bind,autoChecks=AutoCheckProfile.of(s.autoChecks))
                                }
                                fun status(v:Boolean?)=when(v){true->Verdict.PASS;false->Verdict.FAIL;null->Verdict.UNKNOWN}
                                val sampled=Sample(chain.id,epoch,elapsed(),if(p.works && p.bigOk==true && AutoCheckProfile.of(s.autoChecks).accepts(p,s.autoLatencyMaxMs))Verdict.PASS else Verdict.FAIL,
                                    if(AutoCheckProfile.of(s.autoChecks).telegram) status(p.tgOk) else Verdict.PASS,if(AutoCheckProfile.of(s.autoChecks).youtube) status(p.ytOk) else Verdict.PASS,p.bigOk==true,p.bodyBytes,p.bodyMs,p.realMs.toLong(),country,youtubeKbps=p.ytKbps)
                                samples[chain.id]=sampled
                                return sampled
                            }
                            override fun close() {core.stop();check(!core.running){"Chain core did not stop"}}
                        }
                    } catch(e:Throwable) {core.stop();throw e}
                }
                currentCoroutineContext().ensureActive()
                val winner=result.selected?.let { index[it.id] } ?: return@exclusiveProbe null
                val sample=result.sample ?: return@exclusiveProbe null
                val current=AutoChainSwitchPolicy.currentEvidence(
                    currentId?.let(samples::get),currentId,epoch,elapsed())
                if(!AutoChainSwitchPolicy.shouldSwitch(current,sample,AutoChainSwitchPolicy.purpose(s.autoChainPurpose),currentExpected=currentId!=null)) return@exclusiveProbe null
                Measured(winner,sample,network,epoch,settingsKey(s))
            }
        }
}

package com.vlesscardvpn.health

/** Original Android implementation of module supervision, inspired by Civilsphere's
 * manager/backend/report separation. No Redis, PCAP, IDS model, addresses or account data.
 */
enum class HealthModule { HTTPS, PAYLOAD, DNS, TELEGRAM, YOUTUBE }
enum class ModuleStatus { PASS, FAIL, UNKNOWN, SLOW, DISABLED }
enum class HealthAction { NONE, RECHECK, REPAIR_ROUTE, RECHECK_DNS, REPAIR_TELEGRAM, RECHECK_MEDIA, OPTIMIZE }
data class ModuleObservation(val module:HealthModule,val status:ModuleStatus,val latencyMs:Int?=null)
data class HealthInput(
    val https:Boolean, val httpsMs:Int=0, val payload:Boolean?=null,
    val dns:Boolean?=null,val telegram:Boolean?=null,val telegramMs:Int=0,val youtube:Boolean?=null,
)
data class HealthRequirements(
    val dns:Boolean=true,val telegram:Boolean=true,val youtube:Boolean=false,
    val strictLatency:Boolean=true,val rejectKnownMediaFailure:Boolean=true,
)
data class HealthAssessment(val observations:List<ModuleObservation>,val blocked:Boolean=false) {
    val ready:Boolean? get() = when {
        blocked || observations.any {it.status==ModuleStatus.FAIL || it.status==ModuleStatus.SLOW} -> false
        observations.any {it.status==ModuleStatus.UNKNOWN} -> null
        else -> true
    }
    val action:HealthAction get() = when {
        blocked -> HealthAction.REPAIR_ROUTE
        failed(HealthModule.HTTPS) || failed(HealthModule.PAYLOAD) -> HealthAction.REPAIR_ROUTE
        failed(HealthModule.DNS) -> HealthAction.RECHECK_DNS
        failed(HealthModule.TELEGRAM) -> HealthAction.REPAIR_TELEGRAM
        failed(HealthModule.YOUTUBE) -> HealthAction.RECHECK_MEDIA
        observations.any {it.status==ModuleStatus.UNKNOWN} -> HealthAction.RECHECK
        observations.any {it.status==ModuleStatus.SLOW} -> HealthAction.OPTIMIZE
        else -> HealthAction.NONE
    }
    private fun failed(module:HealthModule)=observations.any {it.module==module && it.status==ModuleStatus.FAIL}
}
object ModuleHealthPolicy {
    const val MAX_LATENCY_MS=1000
    fun status(value:Boolean?,latencyMs:Int=0,strict:Boolean=false):ModuleStatus = when {
        value==null -> ModuleStatus.UNKNOWN
        !value -> ModuleStatus.FAIL
        strict && latencyMs<=0 -> ModuleStatus.UNKNOWN
        strict && latencyMs>MAX_LATENCY_MS -> ModuleStatus.SLOW
        else -> ModuleStatus.PASS
    }
    /** Allocation-free admission path shared by Auto and the module supervisor. */
    fun fastLink(works:Boolean,httpsMs:Int,telegram:Boolean?,telegramMs:Int):Boolean =
        works && status(true,httpsMs,true)==ModuleStatus.PASS && status(telegram,telegramMs,true)==ModuleStatus.PASS
    fun assess(input:HealthInput,requirements:HealthRequirements,blocked:Boolean=false):HealthAssessment {
        fun observation(module:HealthModule,ok:Boolean?,required:Boolean=true,ms:Int=0,strict:Boolean=false)=
            ModuleObservation(module,if(required) status(ok,ms,strict) else ModuleStatus.DISABLED,
                ms.takeIf {required && ok==true && it>0})
        return HealthAssessment(listOf(
            observation(HealthModule.HTTPS,input.https,ms=input.httpsMs,strict=requirements.strictLatency),
            observation(HealthModule.PAYLOAD,input.payload,required=input.payload!=null),
            observation(HealthModule.DNS,input.dns,requirements.dns),
            observation(HealthModule.TELEGRAM,input.telegram,requirements.telegram,input.telegramMs,requirements.strictLatency),
            observation(HealthModule.YOUTUBE,input.youtube,requirements.youtube || requirements.rejectKnownMediaFailure && input.youtube==false),
        ),blocked)
    }
}

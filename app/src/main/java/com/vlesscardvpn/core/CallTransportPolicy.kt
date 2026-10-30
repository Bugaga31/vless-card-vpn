package com.vlesscardvpn.core

/** A short STUN packet train is a UDP transport sample, not an authenticated media session. */
object CallTransportPolicy {
    const val PACKETS = 20
    const val INTERVAL_MS = 50L
    enum class Grade { UNKNOWN, NO_UDP, LOSS, SLOW, UNSTABLE, CAUTION, PASS }
    data class Summary(val sent:Int,val received:Int,val lossPercent:Int?,val medianMs:Int?,val p95Ms:Int?,
        val rttSpreadMs:Int?,val grade:Grade)
    fun assess(sent:Int, rttMs:List<Int>, complete:Boolean, maxMs:Int):Summary {
        val samples=rttMs.filter {it>0}.take(sent.coerceIn(0,PACKETS)).sorted()
        val n=sent.coerceIn(0,PACKETS)
        val loss=if(n>0) (n-samples.size)*100/n else null
        val median=samples.takeIf {it.isNotEmpty()}?.let {it[it.size/2]}
        val p95=samples.takeIf {it.isNotEmpty()}?.let {it[((it.size*95+99)/100-1).coerceIn(0,it.lastIndex)]}
        val spread=samples.takeIf {it.isNotEmpty()}?.let {it.last()-it.first()}
        val grade=when {
            !complete || n!=PACKETS -> Grade.UNKNOWN
            samples.isEmpty() -> Grade.NO_UDP
            (loss ?: 100)>5 -> Grade.LOSS
            (p95 ?: Int.MAX_VALUE)>maxMs.coerceIn(1,500) -> Grade.SLOW
            (spread ?: Int.MAX_VALUE)>80 -> Grade.UNSTABLE
            (loss ?: 100)>0 || (p95 ?: Int.MAX_VALUE)>300 || (spread ?: Int.MAX_VALUE)>40 -> Grade.CAUTION
            else -> Grade.PASS
        }
        return Summary(n,samples.size,loss,median,p95,spread,grade)
    }
    fun label(s:Summary):String = when(s.grade) {
        Grade.UNKNOWN -> "Проба не завершена; качество не подтверждено"
        Grade.NO_UDP -> "UDP не подтверждён: ответов нет"
        Grade.LOSS -> "Потери ответов — возможны обрывы звука"
        Grade.SLOW -> "Высокая задержка — возможна задержка речи"
        Grade.UNSTABLE -> "Задержка скачет — возможны заикания"
        Grade.CAUTION -> "Есть отклонения; для звонка требуется осторожность"
        Grade.PASS -> "Короткий транспортный тест пройден"
    } + " · ${s.received}/${s.sent} ответов" + (s.lossPercent?.let {" · потери проб $it%"} ?: "") +
        (s.p95Ms?.let {" · RTT p95 $it мс"} ?: "") + (s.rttSpreadMs?.let {" · разброс RTT $it мс"} ?: "")
}

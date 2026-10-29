package com.vlesscardvpn.core

import com.vlesscardvpn.model.ServerState

/** Atomic observations for one exact network+mask. Unknown checks never inherit another mask's success. */
object MaskSamplePolicy {
    fun unchanged(current:ServerState,initial:ServerState,net:String):Boolean =
        current.maskFor(net)==initial.maskFor(net) && current.checkedAt==initial.checkedAt

    fun adopt(current:ServerState,net:String,mask:String,now:Long,sample:Probe,
              telegramSampled:Boolean,youtubeSampled:Boolean,trustedSpeed:Boolean):ServerState {
        if(!sample.works) return current
        val measured=trustedSpeed && sample.bigOk==true && sample.kbps>0
        return current.copy(maskId=mask,netMasks=current.netMasks+(net to mask),realMs=sample.realMs,
            bigOk=sample.bigOk,tgOk=if(telegramSampled) sample.tgOk else null,tgMs=if(telegramSampled) sample.tgMs else 0,ytOk=if(youtubeSampled) sample.ytOk else null,
            serviceNetwork=if(telegramSampled || youtubeSampled) net else "",
            serviceMask=if(telegramSampled || youtubeSampled) mask else "",serviceCheckedAt=if(telegramSampled || youtubeSampled) now else 0,
            kbps=if(measured) sample.kbps else 0,speedNetwork=if(measured) net else "",
            speedMask=if(measured) mask else "",speedCheckedAt=if(measured) now else 0,checkedAt=now)
    }
    fun invalidate(current:ServerState):ServerState = current.copy(realMs=-1,bigOk=null,ytOk=null,tgOk=null,tgMs=0,kbps=0,
        checkedAt=0,serviceNetwork="",serviceMask="",serviceCheckedAt=0,speedNetwork="",speedMask="",speedCheckedAt=0)

    /** Both isolated samples must complete; the more conservative measured throughput is used. */
    fun confirmed(first:Probe,second:Probe):Probe? = if(AutoLatencyPolicy.accepts(first) && AutoLatencyPolicy.accepts(second) && first.works && second.works && first.bigOk==true && second.bigOk==true &&
        first.tgOk==true && second.tgOk==true && first.kbps>0 && second.kbps>0)
        second.copy(kbps=minOf(first.kbps,second.kbps)) else null
}

package com.vlesscardvpn.core

import com.vlesscardvpn.model.ServerState
import org.junit.Assert.*
import org.junit.Test

class MaskEvidenceR183Test {
    private val before=ServerState(realMs=50,bigOk=true,ytOk=true,tgOk=true,kbps=5000,checkedAt=100,
        maskId="old",serviceNetwork="wifi",serviceMask="old",serviceCheckedAt=100,
        speedNetwork="wifi",speedMask="old",speedCheckedAt=100)
    private fun good(speed:Int=8000)=Probe(60,true,null,tgOk=true,kbps=speed)
    @Test fun unknownYoutubeIsNotInheritedByNewMask() {
        val next=MaskSamplePolicy.adopt(before,"wifi","new",200,good(),true,false,true)
        assertNull(next.ytOk);assertEquals(true,next.tgOk);assertEquals("new",next.serviceMask)
    }
    @Test fun parallelDownloadIsNotTrustedSpeed() {
        val next=MaskSamplePolicy.adopt(before,"wifi","new",200,good(),true,false,false)
        assertEquals(0,next.kbps);assertEquals(0L,next.speedCheckedAt)
    }
    @Test fun failedSampleCannotReplaceWorkingMask() {
        assertEquals(before,MaskSamplePolicy.adopt(before,"wifi","new",200,Probe(0,null,null),true,true,true))
    }
    @Test fun changedMaskOrNewResultRejectsLateCommit() {
        assertFalse(MaskSamplePolicy.unchanged(before.copy(maskId="new"),before,"wifi"))
        assertFalse(MaskSamplePolicy.unchanged(before.copy(checkedAt=101),before,"wifi"))
    }
    @Test fun twoSamplesUseConservativeThroughput() {
        assertEquals(7000,MaskSamplePolicy.confirmed(good(9000),good(7000))!!.kbps)
        assertNull(MaskSamplePolicy.confirmed(good(),good().copy(tgOk=null)))
    }
    @Test fun manualMaskChangeClearsAllOldClaims() {
        val next=MaskSamplePolicy.invalidate(before)
        assertNull(next.tgOk);assertNull(next.ytOk);assertEquals(0,next.kbps)
        assertEquals(0L,next.checkedAt);assertFalse(next.works)
    }
}

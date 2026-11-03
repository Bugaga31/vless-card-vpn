package com.vlesscardvpn.core

import org.junit.Assert.*
import org.junit.Test

class SameEndpointStabilityR252Test {
    @Test fun followingSamplesUseTheFirstWinningEndpoint() {
        val requested=mutableListOf<Int>()
        val first=TelegramProbe.Result(true,40,1)
        val result=StabilitySeriesProbe.collectTelegram(first,{2000},800) { _, target ->
            requested+=target
            TelegramProbe.Result(true,40,target)
        }
        assertEquals(listOf(1,1),requested)
        assertEquals(true,result.stable)
    }
    @Test fun genuineSpreadStillFails() {
        val times=ArrayDeque(listOf(240,40))
        val result=StabilitySeriesProbe.collectTelegram(TelegramProbe.Result(true,40,0),{2000},800) {_,target -> TelegramProbe.Result(true,times.removeFirst(),target)}
        assertEquals(false,result.stable);assertEquals("STABILITY_SPREAD",result.reason)
    }
    @Test fun explicitFailureNeverBecomesSuccess() {
        val result=StabilitySeriesProbe.collectTelegram(TelegramProbe.Result(true,40,0),{2000},800) {_,target -> TelegramProbe.Result(false,0,target)}
        assertEquals(false,result.stable);assertEquals("STABILITY_RESPONSE_FAILED",result.reason)
    }
    @Test fun unknownOrDifferentTargetNeverBecomesSuccess() {
        val unknown=StabilitySeriesProbe.collectTelegram(TelegramProbe.Result(true,40,0),{2000},800) {_,_ -> TelegramProbe.Result(null,0,null)}
        val wrong=StabilitySeriesProbe.collectTelegram(TelegramProbe.Result(true,40,0),{2000},800) {_,_ -> TelegramProbe.Result(true,40,1)}
        assertNull(unknown.stable);assertNull(wrong.stable)
    }
    @Test fun noFirstTargetCannotInventEvidence() {
        var calls=0
        val result=StabilitySeriesProbe.collectTelegram(TelegramProbe.Result(null,0),{2000},800) {_,_->calls++;TelegramProbe.Result(true,40,0)}
        assertNull(result.stable);assertEquals(0,calls)
    }
    @Test fun budgetIsStillSharedAndBounded() {
        var calls=0
        val result=StabilitySeriesProbe.collectTelegram(TelegramProbe.Result(true,40,0),{199},800) {_,_->calls++;TelegramProbe.Result(true,40,0)}
        assertNull(result.stable);assertEquals("TIMEOUT",result.reason);assertEquals(0,calls)
    }
}

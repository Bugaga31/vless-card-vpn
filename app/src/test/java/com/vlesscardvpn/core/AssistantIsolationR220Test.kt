package com.vlesscardvpn.core

import com.vlesscardvpn.model.Mode
import org.junit.Assert.*
import org.junit.Test

class AssistantIsolationR220Test {
    private val facts=Assistant.Facts("fixture-net",emptyMap(),true,"fixture-route","ok",3,2,emptyList(),Mode.AUTO,null,false,null)
    private fun command(q:String)=Assistant.answer(q,facts).let {if(it.auto) it.buttons.first().cmd else ""}
    @Test fun deviceAccelerationCannotTriggerYoutube() {
        listOf("ускорь телефон","разгони телефон","оптимизируй телефон","сохрани заряд батареи, экономь","автоускор").forEach {
            assertEquals(it,"!device",command(it))
        }
    }
    @Test fun deviceCoolingIsAdviceOnly() {
        listOf("охлади телефон","остуди устройство").forEach {assertEquals("!device",command(it))}
        assertEquals("!cool",command("охлади"))
    }
    @Test fun explicitYoutubeRequestRemainsSeparate() {assertEquals("!yt",command("ускорь ютуб пожалуйста"));assertEquals("!yt",command("видео тормозит, ускорь"))}
    @Test fun savedDeviceAliasesOnlyInvokeSensorCallback() {
        var reads=0
        listOf("!device","!cool","!boostphone").forEach {cmd->
            val r=AssistantDeviceCommands.execute(cmd){reads++;listOf("fixture sensor advice")}
            assertNotNull(r);assertTrue(r!!.contains("VPN, маски, MTU и параллелизм не изменены"));assertTrue(r.contains("fixture sensor advice"))
        }
        assertEquals(3,reads)
    }
    @Test fun unknownAndNetworkCommandsDoNotReadSensors() {
        listOf("!yt","!connect","!nextmask","!set turbo on","!device-extra").forEach {
            assertNull(AssistantDeviceCommands.execute(it){throw AssertionError("unexpected sensor read")})
        }
    }
    @Test fun unavailableSensorsNeverFallThroughToVpnCommands() {
        val r=AssistantDeviceCommands.execute("!cool"){throw IllegalStateException("fixture")}
        assertNotNull(r);assertTrue(r!!.contains("оставлены без изменений"));assertFalse(r.contains("fixture"))
    }
    @Test fun sensorAdviceIsBounded() {
        val r=AssistantDeviceCommands.execute("!device"){List(20){"fixture-$it"}}!!
        assertEquals(8,r.lines().count{it.startsWith("• ")});assertFalse(r.contains("fixture-19"))
    }
    @Test fun noDataDoesNotPromiseSpeedOrCooling() {
        val r=AssistantDeviceCommands.execute("!boostphone"){emptyList()}!!
        assertTrue(r.contains("ускорение не обещается"))
    }
    @Test fun historicalYoutubeComparisonIsNotCurrentDirectRoute() {
        val r=Assistant.answer("как youtube",facts.copy(ytDpi=true)).text
        assertTrue(r.contains("не подтверждает текущий маршрут"));assertFalse(r.contains("идёт напрямую"))
    }
    @Test fun noWorkingServerCannotPromiseAutomaticDirectFallback() {
        val r=Assistant.answer("не работает",facts.copy(working=0,dpiKnown=true)).text
        assertTrue(r.contains("прямой выход не включается"));assertFalse(r.contains("Авто включит сам"))
    }
}

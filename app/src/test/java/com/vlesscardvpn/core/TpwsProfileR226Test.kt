package com.vlesscardvpn.core

import com.vlesscardvpn.xray.MyMasks
import com.vlesscardvpn.xray.Masks
import com.vlesscardvpn.model.Settings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TpwsProfileR226Test {
    private fun valid(s:String) = TpwsProfile.parse(s).isSuccess
    @Test fun examplesAccepted() { TpwsProfile.EXAMPLES.forEach { assertTrue(valid(it)) } }
    @Test fun defaultFirstBlock() { assertTrue(TpwsProfile.parse("--split-pos=midsld").getOrThrow().contains("--tamper-cutoff=n2")) }
    @Test fun canonicalOrder() { assertEquals(TpwsProfile.encode("--tlsrec=host --split-pos=1"), TpwsProfile.encode("--split-pos=1 --tlsrec=host")) }
    @Test fun decodeRoundTrip() { val id=TpwsProfile.encode(TpwsProfile.EXAMPLES.first());assertEquals(id,TpwsProfile.encode(TpwsProfile.line(id)!!)) }
    @Test fun noUnknownFlags() { assertFalse(valid("--split-pos=1 --bind-addr=0.0.0.0")) }
    @Test fun noFiles() { listOf("--hostlist=/sdcard/a","--lua-init=a","@file","--pidfile=/tmp/x").forEach { assertFalse(valid("--split-pos=1 $it")) } }
    @Test fun noOtherEngines() { listOf("nfqws2 --dpi-desync=fake", "tpws --split-pos=1", "-d1 -r1+s").forEach { assertFalse(valid(it)) } }
    @Test fun noShell() { assertFalse(valid("--split-pos=1;touch /tmp/x"));assertFalse(valid("--tlsrec=$(id)")) }
    @Test fun boundedPositions() { listOf("0","32768","-32769","host+99999","other","1,1",(1..9).joinToString(",")).forEach { assertFalse(valid("--split-pos=$it")) } }
    @Test fun validMarkers() { listOf("host","endhost-1","midsld+2","sniext+1","sld","endsld").forEach { assertTrue(valid("--tlsrec=$it")) } }
    @Test fun noDuplicateOptions() { assertFalse(valid("--tlsrec=host --tlsrec=midsld")) }
    @Test fun splitRequiredForDisorder() { assertFalse(valid("--tlsrec=host --disorder=tls"));assertTrue(valid("--split-pos=1 --disorder=tls")) }
    @Test fun noUnlimitedTamper() { assertFalse(valid("--split-pos=1 --tamper-cutoff=n0"));assertFalse(valid("--split-pos=1 --tamper-cutoff=9999")) }
    @Test fun oobByteValidation() { assertFalse(valid("--split-pos=1 --oob-data=0x00"));assertTrue(valid("--split-pos=1 --oob=tls --oob-data=0x00"));assertFalse(valid("--split-pos=1 --oob=tls --oob-data=0x100")) }
    @Test fun malformedIdsRejected() { assertNull(TpwsProfile.decode(TpwsProfile.PREFIX+"??"));assertNull(TpwsProfile.decode(TpwsProfile.encode("--split-pos=1")+"=")) }
    @Test fun boundedInput() { assertFalse(valid("--split-pos=1\n--tlsrec=host"));assertFalse(valid(" ".repeat(769)));assertFalse(valid("")) }
    @Test fun maskRoundTripKeepsEngine() { val id=TpwsProfile.encode(TpwsProfile.EXAMPLES.first());val m=MyMasks.fromJson(JSONObject().put("fp","chrome").put("dpi",id));assertEquals(id, MyMasks.decode(MyMasks.encode(m)).dpi);assertEquals(m.id,MyMasks.decode(MyMasks.encode(m)).id) }
    @Test fun noMixedEnginesOrUdp() { val id=TpwsProfile.encode("--split-pos=1");for(extra in listOf(JSONObject().put("p","tlshello").put("l","100-200").put("d","0-1"),JSONObject().put("n","z5"),JSONObject().put("h",Masks.HOP_LOCAL))) { extra.put("dpi",id);assertTrue(runCatching { MyMasks.fromJson(extra) }.isFailure) } }
    @Test fun unknownEngineDoesNotSilentlyDisappear() { assertTrue(runCatching { MyMasks.fromJson(JSONObject().put("dpi","TPWS#UNKNOWN")) }.isFailure) }
    @Test fun exactEngineResolved() { val id=TpwsProfile.encode("--split-pos=1");val strategy=DpiStrategies.byId(id,Settings())!!;assertEquals(DpiEngine.TPWS,strategy.engine);assertEquals(id,strategy.id);assertTrue(strategy.argv(19090,"ya.ru").contains("--bind-addr=127.0.0.1"));assertFalse(strategy.argv(19090,"ya.ru").any { it.contains("ya.ru") }) }
    @Test fun builtinFirstBlockProfiles() { val st=DpiStrategies.BUILT_IN.filter { it.id.startsWith("TPWS#HELLO_") };assertEquals(3,st.size);st.forEach { assertTrue(valid(it.args.joinToString(" ")));assertTrue(it.args.contains("--tamper-cutoff=n2")) } }
    @Test fun titleDoesNotAffectIdentity() { val id=TpwsProfile.encode("--tlsrec=host");val a=MyMasks.fromJson(JSONObject().put("dpi",id).put("t","A"));val b=MyMasks.fromJson(JSONObject().put("dpi",id).put("t","B"));assertEquals(a.id,b.id) }
}

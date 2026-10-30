package com.vlesscardvpn.watson

import com.vlesscardvpn.model.Settings
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class WatsonR215Test {
    private fun app(requested:Set<String> = emptySet(),granted:Set<String> = emptySet())=WatsonApp("fixture.test","Fixture",requested,granted)
    @Test fun noSignalsIsNotSafetyVerdict() {
        val f=WatsonPolicy.app(app()).single();assertEquals("no_signals",f.id);assertTrue(f.detail.contains("не антивирусный"))
    }
    @Test fun unreadableIsUnknown() {assertEquals("unreadable",WatsonPolicy.app(app().copy(error=true)).single().id)}
    @Test fun requestedIsNotGranted() {
        val f=WatsonPolicy.app(app(setOf("android.permission.READ_SMS"))).single()
        assertTrue(f.detail.contains("не сообщает о выдаче"));assertFalse(f.detail.contains("разрешение выдано"))
    }
    @Test fun grantedDoesNotProveUse() {
        val p="android.permission.RECORD_AUDIO";val f=WatsonPolicy.app(app(setOf(p),setOf(p))).single()
        assertTrue(f.detail.contains("разрешение выдано"));assertTrue(f.detail.contains("не доказательство"))
    }
    @Test fun unrelatedGrantedPermissionDoesNotCreateFinding() {
        assertEquals("no_signals",WatsonPolicy.app(app(granted=setOf("android.permission.READ_SMS"))).single().id)
    }
    @Test fun declarationsDoNotClaimAccessEnabled() {
        val fs=WatsonPolicy.app(app().copy(accessibilityDeclared=true,notificationDeclared=true,vpnDeclared=true))
        assertEquals(setOf("accessibility","notifications","vpn"),fs.map{it.id}.toSet())
        assertTrue(fs.first{it.id=="accessibility"}.detail.contains("не проверена"))
        assertTrue(fs.first{it.id=="notifications"}.detail.contains("не означает"))
        assertTrue(fs.first{it.id=="vpn"}.detail.contains("не признак заражения"))
    }
    @Test fun exportedIsNotVulnerability() {assertTrue(WatsonPolicy.app(app().copy(exportedCount=3)).single().detail.contains("не доказывает уязвимость"))}
    @Test fun debugIsFactNotMalware() {assertEquals("debug",WatsonPolicy.app(app().copy(debuggable=true)).single().id)}
    @Test fun directRuRulesProduceFindings() {
        assertTrue(WatsonPolicy.privacy(Settings(ruWhitelist=true,ruAllDirect=true)).map{it.id}.containsAll(setOf("ru_list","ru_all")))
    }
    @Test fun torDoesNotFalselyFlagDisabledRuExceptions() {
        assertFalse(WatsonPolicy.privacy(Settings(torAll=true,ruWhitelist=true,ruAllDirect=true)).any{it.id in setOf("ru_list","ru_all")})
    }
    @Test fun strictFlagsDoNotProduceAllSafeShield() {
        val ids=WatsonPolicy.privacy(Settings(ruWhitelist=false,ruAllDirect=false)).map{it.id}.toSet()
        assertEquals(setOf("lockdown","capture","metadata","own_uid"),ids)
    }
    @Test fun auditNeverChangesSettings() {
        val s=Settings(ruWhitelist=true,ruAllDirect=true);val before=s.toJson().toString();WatsonPolicy.privacy(s);assertEquals(before,s.toJson().toString())
    }
    @Test fun historicalModelHasFiniteValidParameters() {
        assertEquals(23,WatsonUrlModel.mean.size);assertEquals(23,WatsonUrlModel.scale.size);assertEquals(23,WatsonUrlModel.weights.size)
        assertTrue(WatsonUrlModel.scale.all{it>0 && it.isFinite()});assertTrue(WatsonUrlModel.weights.all{it.isFinite()})
        assertTrue(WatsonUrlModel.threshold in 0.0..1.0)
    }
    @Test fun pythonFeatureAndDecisionParityOnSupportedSubset() {
        val text=javaClass.getResourceAsStream("/watson/url-parity.json")!!.bufferedReader().use{it.readText()}
        val a=JSONArray(text)
        assertTrue(a.length()>=12)
        repeat(a.length()){i->
            val f=a.getJSONObject(i);val input=f.getString("url");val x=WatsonUrl.features(input) ?: error("fixture unsupported: $i")
            val want=f.getJSONArray("features");assertEquals(23,x.size)
            repeat(23){j->assertEquals("fixture $i feature $j",want.getDouble(j),x[j],1e-10)}
            val result=WatsonUrl.check(input);assertTrue(result.measured);assertEquals(f.getBoolean("warning"),result.warning)
        }
    }
    @Test fun unsupportedFormsAreUnknownNotSafe() {
        for(s in listOf("", "ftp://example.test", "https://пример.рф", "https://[2001:db8::1]/", "https://u:p@example.test", "https://example.test:65536", "https://example.test:", "https://ex ample.test", "https://example.test/\\login", "https://example.test/\nlogin", "x".repeat(8193))) {
            val result=WatsonUrl.check(s);assertFalse(result.measured);assertFalse(result.warning)
        }
    }
    @Test fun inputIsNeverRetainedByResult() {
        val result=WatsonUrl.check("https://example.test/?secret=fixture-only")
        assertFalse(result.toString().contains("secret"));assertFalse(result.toString().contains("example.test"))
    }
    @Test fun repeatedInferenceIsDeterministic() {val s="https://example.test/login";assertEquals(WatsonUrl.check(s),WatsonUrl.check(s))}
    @Test fun surroundingWhitespaceIsTrimmedOnly() {
        assertEquals(WatsonUrl.check("https://example.test"),WatsonUrl.check("  https://example.test  "))
    }
}

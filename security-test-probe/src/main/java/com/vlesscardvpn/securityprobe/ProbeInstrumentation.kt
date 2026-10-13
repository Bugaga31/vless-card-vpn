package com.vlesscardvpn.securityprobe

import android.app.Instrumentation
import android.os.Bundle
import com.vlesscardvpn.core.LocalSecrets
import com.vlesscardvpn.model.Base64
import org.json.JSONObject
import java.io.File

/** No Xray/native code. Uses the exact production encryption implementation with a separate test UID. */
class ProbeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val ctx = targetContext
        val results = ArrayList<String>(); var failures = 0
        fun test(name: String, block: () -> Unit) {
            try { block(); results += "PASS: $name" } catch (e: Throwable) { failures++; results += "FAIL: $name (${e.javaClass.simpleName})" }
        }
        val legacy = File(ctx.filesDir, "state.json")
        val enc = File(ctx.noBackupFilesDir, "state.enc")
        val prefs = ctx.getSharedPreferences("cloud", 0)
        test("verified legacy state migration") {
            LocalSecrets.destroy(ctx); prefs.edit().clear().commit()
            val text = """{"servers":[],"test-secret":"private-key"}"""
            legacy.writeText(text)
            check(LocalSecrets.readOrMigrateState(ctx, legacy) { JSONObject(it) } == text)
            check(!legacy.exists()); check(LocalSecrets.readState(ctx) == text)
            check(!String(enc.readBytes()).contains("private-key"))
        }
        test("legacy temporary files are recovered and removed after verified migration") {
            LocalSecrets.deleteState(ctx)
            val tmp = File(legacy.path + ".tmp"); tmp.writeText("{}")
            check(LocalSecrets.readOrMigrateState(ctx, legacy) { JSONObject(it) } == "{}")
            check(!tmp.exists()); check(LocalSecrets.readState(ctx) == "{}")
        }
        test("invalid legacy data is preserved, not silently discarded") {
            LocalSecrets.deleteState(ctx); legacy.writeText("bad-json")
            check(runCatching { LocalSecrets.readOrMigrateState(ctx, legacy) { JSONObject(it) } }.isFailure)
            check(legacy.readText() == "bad-json"); check(!LocalSecrets.hasState(ctx))
            legacy.delete()
        }
        test("corrupt ciphertext blocks fallback and preserves old data") {
            LocalSecrets.writeState(ctx, "{}")
            val valid = enc.readBytes(); val corrupt = valid.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            enc.writeBytes(corrupt); legacy.writeText("{}")
            check(runCatching { LocalSecrets.readOrMigrateState(ctx, legacy) { JSONObject(it) } }.isFailure)
            check(legacy.exists()); check(enc.readBytes().contentEquals(corrupt))
            enc.writeBytes(valid); legacy.delete()
        }
        test("keystore encrypted token migration") {
            prefs.edit().clear().putString("token", "test-token-not-a-real-secret").commit()
            check(LocalSecrets.readToken(ctx) == "test-token-not-a-real-secret")
            check(!prefs.contains("token")); check(prefs.contains("token_enc"))
            check(!prefs.getString("token_enc", "").orEmpty().contains("test-token"))
            check(LocalSecrets.readToken(ctx) == "test-token-not-a-real-secret")
        }
        test("tampered token never falls back to plaintext") {
            val good = prefs.getString("token_enc", null)!!
            val bad = Base64.decode(good)!!.also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            prefs.edit().putString("token_enc", Base64.encode(bad)).putString("token", "fallback-must-not-be-used").commit()
            check(runCatching { LocalSecrets.readToken(ctx) }.isFailure)
            check(prefs.getString("token", "") == "fallback-must-not-be-used")
            prefs.edit().clear().commit()
        }
        test("key deletion makes old ciphertext unreadable") {
            LocalSecrets.writeState(ctx, "{}")
            val old = enc.readBytes()
            LocalSecrets.destroy(ctx); enc.writeBytes(old)
            check(runCatching { LocalSecrets.readState(ctx) }.isFailure)
            check(enc.readBytes().contentEquals(old))
            LocalSecrets.destroy(ctx)
        }
        finish(if (failures == 0) -1 else 0, Bundle().apply {
            putInt("failures", failures); putInt("tests", results.size); putString("stream", "\n" + results.joinToString("\n") + "\n")
        })
    }
}

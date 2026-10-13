package com.vlesscardvpn.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.vlesscardvpn.model.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Versioned authenticated envelope. Pure crypto so corruption and purpose binding are JVM-tested. */
object SecretEnvelope {
    private val header = byteArrayOf(86, 67, 83, 1)
    fun seal(key: SecretKey, purpose: String, text: String): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        require(c.iv.size == 12)
        c.updateAAD(header + purpose.toByteArray(Charsets.UTF_8))
        return header + c.iv + c.doFinal(text.toByteArray(Charsets.UTF_8))
    }
    fun open(key: SecretKey, purpose: String, bytes: ByteArray): String {
        require(bytes.size >= 32 && bytes.copyOfRange(0, 4).contentEquals(header)) { "Неверный формат защищённых данных" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(4, 16)))
        c.updateAAD(header + purpose.toByteArray(Charsets.UTF_8))
        return String(c.doFinal(bytes.copyOfRange(16, bytes.size)), Charsets.UTF_8)
    }
}

/** Device-bound encryption. Never fall back to plaintext if the keystore is unavailable. */
object LocalSecrets {
    private const val ALIAS = "vless-card-local-v1"
    @Synchronized private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    private fun stateFile(ctx: Context) = AtomicFile(File(ctx.noBackupFilesDir, "state.enc"))
    fun hasState(ctx: Context): Boolean = File(ctx.noBackupFilesDir, "state.enc").exists() || File(ctx.noBackupFilesDir, "state.enc.bak").exists()
    @Synchronized fun readState(ctx: Context): String? {
        if (!hasState(ctx)) return null
        val bytes = stateFile(ctx).openRead().use { BoundedInput.read(it, 32 * 1024 * 1024) }
        return SecretEnvelope.open(key(), "state", bytes)
    }
    @Synchronized fun writeState(ctx: Context, text: String) {
        require(text.toByteArray(Charsets.UTF_8).size <= 32 * 1024 * 1024 - 32) { "Сохранение слишком большое" }
        val data = SecretEnvelope.seal(key(), "state", text)
        val f = stateFile(ctx); val out = f.startWrite()
        try { out.write(data); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out); throw e }
    }
    /** Never delete old data until the encrypted replacement is validated and read back successfully. */
    @Synchronized fun readOrMigrateState(ctx: Context, legacy: File, validate: (String) -> Unit): String? {
        val encrypted = readState(ctx)
        val text = encrypted ?: when {
            legacy.isFile || File(legacy.path + ".bak").isFile -> AtomicFile(legacy).openRead().use { String(BoundedInput.read(it, 32 * 1024 * 1024)) }
            File(legacy.path + ".tmp").isFile -> File(legacy.path + ".tmp").inputStream().use { String(BoundedInput.read(it, 32 * 1024 * 1024)) }
            else -> null
        }
        if (text == null) return null
        validate(text)
        if (encrypted == null) {
            writeState(ctx, text)
            check(readState(ctx) == text)
        }
        AtomicFile(legacy).delete(); File(legacy.path + ".tmp").delete()
        return text
    }
    @Synchronized fun readToken(ctx: Context): String {
        val p = ctx.getSharedPreferences("cloud", Context.MODE_PRIVATE)
        val encrypted = p.getString("token_enc", null)
        if (encrypted != null) return SecretEnvelope.open(key(), "github-token", Base64.decode(encrypted) ?: error("Неверный формат токена"))
        val old = p.getString("token", "").orEmpty()
        if (old.isNotEmpty()) writeToken(ctx, old) // old value removed only after encryption + verified roundtrip + commit
        return old
    }
    @Synchronized fun writeToken(ctx: Context, token: String) {
        val k = key(); val sealed = SecretEnvelope.seal(k, "github-token", token)
        check(SecretEnvelope.open(k, "github-token", sealed) == token)
        check(ctx.getSharedPreferences("cloud", Context.MODE_PRIVATE).edit().putString("token_enc", Base64.encode(sealed)).remove("token").commit())
    }
    @Synchronized fun deleteState(ctx: Context) { stateFile(ctx).delete() }
    @Synchronized fun destroy(ctx: Context) {
        stateFile(ctx).delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(ALIAS) }
    }
}

object BoundedInput {
    fun read(input: java.io.InputStream, max: Int): ByteArray {
        require(max >= 0)
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf); if (n == -1) break
            require(n <= max - out.size()) { "Данные превышают допустимый размер" }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

package com.vlesscardvpn.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** HTTPS subscription tokens encrypted at rest; this does not encrypt the existing Room node table. */
class SubscriptionStore(context: Context) {
    private val prefs = context.getSharedPreferences("encrypted_subscriptions", Context.MODE_PRIVATE)
    companion object { private val keyLock = Any() }
    private fun key(): SecretKey = synchronized(keyLock) {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("vless_subscriptions_aes_v1", null) as? SecretKey)?.let { return it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("vless_subscriptions_aes_v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    @Synchronized fun load(): List<String> {
        val raw = prefs.getString("urls", null) ?: return emptyList()
        val parts = raw.split(':'); require(parts.size == 2) { "Хранилище подписок повреждено" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        cipher.updateAAD("vless_subscriptions_v1".toByteArray())
        val array = JSONArray(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
        return (0 until array.length()).map { array.getString(it) }
    }
    @Synchronized fun add(urls: List<String>) {
        val all = (load() + urls).distinct()
        require(all.size <= 12) { "Разрешено не более 12 подписок" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD("vless_subscriptions_v1".toByteArray())
        val encrypted = cipher.doFinal(JSONArray(all).toString().toByteArray())
        check(prefs.edit().putString("urls", Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "Не удалось сохранить подписку" }
    }
}

package com.vlesscardvpn.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Non-exportable platform key. Hardware backing depends on the device; it is not claimed. */
class AndroidNodeKey(private val alias: String = "vless_node_payload_aes_v1") {
    private var cached: SecretKey? = null
    @Synchronized fun get(create: Boolean): SecretKey {
        cached?.let { return it }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { cached = it; return it }
        check(create) { "Ключ защищённого хранилища отсутствует" }
        val result = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256).build())
        }.generateKey()
        cached = result
        return result
    }
}
object NodeSecrets {
    private val provider = AndroidNodeKey()
    val cipher = EnvelopeCipher(provider::get)
}

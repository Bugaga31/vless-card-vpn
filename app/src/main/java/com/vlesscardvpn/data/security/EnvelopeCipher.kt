package com.vlesscardvpn.data.security

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Standard AES-256-GCM. The provider controls key creation; decrypt must never create a key. */
class EnvelopeCipher(private val key: (create: Boolean) -> SecretKey) {
    fun seal(recordId: String, plaintext: String): String {
        require(recordId.isNotBlank())
        val bytes = plaintext.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_PLAINTEXT) { "Конфигурация слишком велика для защищённого хранения" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD(aad(recordId))
        val ciphertext = cipher.doFinal(bytes)
        check(cipher.iv.size == 12)
        return "v1:${hex(cipher.iv)}:${hex(ciphertext)}"
    }
    fun open(recordId: String, envelope: String): String {
        try {
            require(recordId.isNotBlank())
            require(envelope.length <= MAX_PLAINTEXT * 2 + 128)
            val parts = envelope.split(':')
            require(parts.size == 3 && parts[0] == "v1")
            val nonce = unhex(parts[1]); val ciphertext = unhex(parts[2])
            require(nonce.size == 12 && ciphertext.size >= 16)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad(recordId))
            return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            // No ciphertext, address, credentials or underlying provider message in public error.
            throw SecurityException("Ключ или защищённая запись недоступны", e)
        }
    }
    private fun aad(id: String) = "vless-node-payload-v1|$id".toByteArray(Charsets.UTF_8)
    companion object {
        const val MAX_PLAINTEXT = 65_536
        private val digits = "0123456789abcdef"
        private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
            bytes.forEach { b -> val n = b.toInt() and 255; append(digits[n ushr 4]); append(digits[n and 15]) }
        }
        private fun unhex(s: String): ByteArray {
            require(s.length % 2 == 0 && s.all { it in digits })
            return ByteArray(s.length / 2) { i -> ((digits.indexOf(s[i * 2]) shl 4) or digits.indexOf(s[i * 2 + 1])).toByte() }
        }
    }
}

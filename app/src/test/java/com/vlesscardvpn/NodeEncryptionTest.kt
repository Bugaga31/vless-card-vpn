package com.vlesscardvpn

import com.vlesscardvpn.data.security.*
import com.vlesscardvpn.data.db.*
import com.vlesscardvpn.domain.VlessConfig
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class NodeEncryptionTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val cipher = EnvelopeCipher { key }
    private val config = VlessConfig(id = "record-1", name = "Мой сервер", address = "vpn.example.org", port = 443,
        uuid = "00000000-0000-4000-8000-000000000001", protocolType = "vless", flow = "", security = "tls",
        sni = "sni.example.org", fingerprint = "chrome", publicKey = "public-fixture", shortId = "aabb",
        transport = "ws", wsHost = "ws.example.org", wsPath = "/ws?token=private-fixture", serviceName = "grpc-private",
        source = "https://subscription.example.org/token-secret", remark = "Private remark", isFavorite = true,
        isActive = true, pingMs = 12, healthState = "HEALTHY", failureCount = 2)
    @Test fun aesGcmRoundTripPreservesUnicode() {
        assertEquals("Привет 🌍", cipher.open("id", cipher.seal("id", "Привет 🌍")))
    }
    @Test fun randomizedNonceChangesEveryEncryption() {
        assertNotEquals(cipher.seal("id", "same"), cipher.seal("id", "same"))
    }
    @Test fun samePayloadCannotBeMovedToAnotherRow() {
        fails { cipher.open("other-id", cipher.seal("id", "secret")) }
    }
    @Test fun wrongKeyFailsClosed() {
        val other = EnvelopeCipher { SecretKeySpec(ByteArray(32) { 42 }, "AES") }
        fails { other.open("id", cipher.seal("id", "secret")) }
    }
    @Test fun tamperedCiphertextFailsAuthentication() {
        val raw = cipher.seal("id", "secret")
        fails { cipher.open("id", raw.dropLast(1) + if (raw.last() == '0') "1" else "0") }
    }
    @Test fun truncatedNonceAndUnknownVersionsAreRejected() {
        for (raw in listOf("v2:00:00", "plaintext-password", "v1:00:00", "v1:zz:00", "v1:0:00")) fails { cipher.open("id", raw) }
    }
    @Test fun missingKeyIsNotRecreatedDuringDecryption() {
        val raw = cipher.seal("id", "secret")
        val provider = EnvelopeCipher { create -> assertFalse(create); throw IllegalStateException("missing") }
        fails { provider.open("id", raw) }
    }
    @Test fun privateValuesAreAbsentFromPlainColumnsAndCiphertext() {
        val row = config.toEntity(cipher)
        assertEquals("", row.uuid); assertEquals("", row.address); assertEquals("", row.name)
        assertEquals("", row.source); assertEquals("", row.sni); assertEquals(0, row.port)
        for (secret in listOf(config.uuid, config.address, config.source, "private-fixture", config.publicKey))
            assertFalse(row.encryptedPayload.contains(secret))
    }
    @Test fun encryptedRowRoundTripPreservesAllConnectionAndMetadataFields() {
        assertEquals(config, config.toEntity(cipher).toDomain(cipher))
    }
    @Test fun grpcTransportSurvivesPersistenceInsteadOfBecomingTcp() {
        val grpc = config.copy(transport = "grpc", wsPath = "/", serviceName = "rpc-token")
        assertEquals(grpc, grpc.toEntity(cipher).toDomain(cipher))
    }
    @Test fun metadataOnlyUpdatesDoNotBreakAuthenticatedPayload() {
        val updated = config.toEntity(cipher).copy(isFavorite = false, isActive = false, pingMs = 90, failureCount = 4)
        assertEquals(config.copy(isFavorite = false, isActive = false, pingMs = 90, failureCount = 4), updated.toDomain(cipher))
    }
    @Test fun plaintextLegacyRowsAreNeverSilentlyAccepted() {
        fails { VlessConfigEntity("old", "Old", "host", 443, "secret").toDomain(cipher) }
    }
    @Test fun oversizedPlaintextIsRejected() {
        try { cipher.seal("id", "a".repeat(EnvelopeCipher.MAX_PLAINTEXT + 1)); fail("Expected rejection") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun incompleteAuthenticatedJsonIsRejected() {
        fails { NodePayload.decode(config, "{}") }
    }
    private fun fails(block: () -> Unit) { try { block(); fail("Expected security failure") } catch (_: SecurityException) { } }
}

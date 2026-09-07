package com.vlesscardvpn.xraytest

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object LoungeStorage {
    private val mutex = Mutex()
    private fun file(c: Context) = File(c.filesDir, "lounge-profiles.enc")
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("lounge-profiles-v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("lounge-profiles-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun read(c: Context): List<CardProfile> {
        val f = file(c)
        if (!f.exists() && !File(f.path + ".bak").exists()) return emptyList()
        val bytes = AtomicFile(f).openRead().use { stream ->
            val data = stream.readBytes()
            require(data.size in 29..2097152) { "Invalid encrypted catalogue" }
            data
        }
        require(bytes[0].toInt() == 1) { "Unknown encryption version" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        return ProfileCatalog.decode(String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8))
    }
    private fun write(c: Context, entries: List<CardProfile>) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        require(cipher.iv.size == 12)
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(ProfileCatalog.encode(entries).toByteArray(Charsets.UTF_8))
        val atomic = AtomicFile(file(c))
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
    }
    suspend fun load(c: Context): List<CardProfile> = withContext(Dispatchers.IO) { mutex.withLock { read(c) } }
    suspend fun change(c: Context, transform: (List<CardProfile>) -> List<CardProfile>): List<CardProfile> = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val result = transform(read(c))
            require(result.size <= ProfileCatalog.LIMIT)
            write(c, result)
            result
        }
    }
}
object RunningLabels { var names: List<String> = emptyList() }
object PublicSources {
    val sources = listOf(
        "kort0881 · clean VLESS" to "https://raw.githubusercontent.com/kort0881/vpn-vless-configs-russia/main/githubmirror/clean/vless.txt",
        "igareck · Reality" to "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
        "barry-far · VLESS" to "https://raw.githubusercontent.com/barry-far/V2ray-Config/main/Splitted-By-Protocol/vless.txt",
        "Epodonios · VLESS" to "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/Splitted-By-Protocol/vless.txt"
    )
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    fun parse(text: String, origin: String) = ProfileCatalog.parse(text, origin) { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) }
    data class Result(val profiles: List<CardProfile>, val skipped: Int, val failures: Int)
    suspend fun fetch(progress: suspend (String) -> Unit): Result {
        val entries = mutableListOf<CardProfile>()
        var skipped = 0
        var failures = 0
        for ((i, source) in sources.withIndex()) {
            currentCoroutineContext().ensureActive()
            progress("Источник ${i + 1}/${sources.size}: ${source.first}")
            try {
                val body = readSource(source.second)
                val parsed = withContext(Dispatchers.Default) { parse(body, "Публичный · ${source.first}") }
                entries.addAll(parsed.profiles); skipped += parsed.skipped
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failures++ }
        }
        return Result(entries.distinctBy { it.node() }, skipped, failures)
    }
    private suspend fun readSource(url: String): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val text = response.use {
                        if (it.code != 200) throw IOException("Public source unavailable")
                        val body = it.body ?: throw IOException("Empty response")
                        val source = body.source()
                        source.request(2097153)
                        if (source.buffer.size > 2097152) throw IOException("Source too large")
                        source.readUtf8()
                    }
                    continuation.resume(text)
                } catch (e: Exception) { continuation.resumeWithException(e) }
            }
        })
    }
}

package com.vlesscardvpn.core

import android.content.Context
import android.os.Build
import com.vlesscardvpn.domain.ByeDpiPreset
import kotlinx.coroutines.*
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Only the encrypted server connection is chained here. Never silently route apps directly. */
class ByeDpiRunner(private val context: Context) : AutoCloseable {
    private var process: Process? = null
    suspend fun start(preset: ByeDpiPreset = ByeDpiPreset.COMBINED): Int = withContext(Dispatchers.IO) {
        close()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) throw UnsupportedOperationException("ByeDPI требует Android 8 или новее")
        val executable = File(context.applicationInfo.nativeLibraryDir, "libbyedpi.so")
        check(executable.isFile && executable.canExecute()) { "Встроенный ByeDPI недоступен для этой архитектуры" }
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val child = ProcessBuilder(listOf(executable.absolutePath) + preset.arguments(port))
            .redirectOutput(File("/dev/null")).redirectError(File("/dev/null")).start()
        process = child
        try {
            withTimeout(2500) {
                while (true) {
                    ensureActive()
                    check(child.isAlive) { "ByeDPI завершился при запуске" }
                    val ready = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 150) }; true }.getOrDefault(false)
                    if (ready) return@withTimeout
                    delay(50)
                }
            }
            port
        } catch (e: Throwable) { close(); throw e }
    }
    override fun close() {
        val old = process.also { process = null } ?: return
        old.destroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { if (!old.waitFor(200, TimeUnit.MILLISECONDS)) old.destroyForcibly() }
        }
    }
}

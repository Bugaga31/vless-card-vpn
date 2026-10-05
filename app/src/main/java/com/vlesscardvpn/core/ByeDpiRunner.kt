package com.vlesscardvpn.core

import android.content.Context
import android.os.Build
import com.vlesscardvpn.domain.ByeDpiArgs
import com.vlesscardvpn.domain.ByeDpiPreset
import com.vlesscardvpn.domain.DirectStrategy
import com.vlesscardvpn.domain.DpiEngine
import kotlinx.coroutines.*
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Server connections are chained here; "без сервера" mode (explicit user choice) points the TUN at it directly. */
class ByeDpiRunner(private val context: Context) : AutoCloseable {
    private var process: Process? = null
    suspend fun start(preset: ByeDpiPreset = ByeDpiPreset.COMBINED, mask: String = ByeDpiArgs.DEFAULT_MASK): Int =
        spawn { port -> preset.arguments(port, mask) }
    /** [customArgs] must come from [ByeDpiArgs.parse]; the loopback listener is always app-controlled. */
    suspend fun startCustom(customArgs: List<String>): Int = spawn { port -> ByeDpiArgs.loopbackPrefix(port) + customArgs }
    /** Runs a "без сервера" strategy on a fixed loopback [port] (sing-box keeps pointing at it between strategies). */
    suspend fun startDirect(strategy: DirectStrategy, port: Int, mask: String = ByeDpiArgs.DEFAULT_MASK, allowLocalTargets: Boolean = false): Int =
        spawn(strategy.engine, port, allowLocalTargets) { p -> strategy.argv(p, mask) }
    private suspend fun spawn(arguments: (Int) -> List<String>): Int = spawn(DpiEngine.BYEDPI, null, false, arguments)
    private suspend fun spawn(engine: DpiEngine, fixedPort: Int?, allowLocal: Boolean, arguments: (Int) -> List<String>): Int = withContext(Dispatchers.IO) {
        close()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) throw UnsupportedOperationException("${engine.title} требует Android 8 или новее")
        val executable = File(context.applicationInfo.nativeLibraryDir, engine.library)
        check(executable.isFile && executable.canExecute()) { "Встроенный ${engine.title} недоступен для этой архитектуры" }
        val port = fixedPort ?: ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val builder = ProcessBuilder(listOf(executable.absolutePath) + arguments(port))
            .redirectOutput(File("/dev/null")).redirectError(File("/dev/null"))
        if (allowLocal) builder.environment()["VCVPN_TPWS_ALLOW_LOCAL"] = "1"
        val child = builder.start()
        process = child
        try {
            withTimeout(2500) {
                while (true) {
                    ensureActive()
                    check(child.isAlive) { "${engine.title} завершился при запуске" }
                    val ready = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 150) }; true }.getOrDefault(false)
                    if (ready) return@withTimeout
                    delay(50)
                }
            }
            port
        } catch (e: Throwable) { close(); throw e }
    }
    companion object {
        fun available(context: Context, engine: DpiEngine): Boolean =
            File(context.applicationInfo.nativeLibraryDir, engine.library).let { it.isFile && it.canExecute() }
    }
    override fun close() {
        val old = process.also { process = null } ?: return
        old.destroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { if (!old.waitFor(300, TimeUnit.MILLISECONDS)) { old.destroyForcibly(); old.waitFor(300, TimeUnit.MILLISECONDS) } }
        }
    }
}

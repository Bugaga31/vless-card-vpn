package com.vlesscardvpn.core

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Built-in ByeDPI (hufrea/byedpi) as a local SOCKS5 proxy. Its sockets bypass the VPN (app UID is excluded). */
class ByeDpi(private val context: Context) : AutoCloseable {
    private var process: Process? = null
    var port: Int = 0; private set

    val available: Boolean get() = Build.VERSION.SDK_INT >= 26 && exe().let { it.isFile && it.canExecute() }
    private fun exe() = File(context.applicationInfo.nativeLibraryDir, "libbyedpi.so")

    /** Starts with a user strategy (ByeByeDPI syntax). Returns the loopback port. */
    suspend fun start(args: String, sni: String): Int = withContext(Dispatchers.IO) {
        close()
        check(available) { "ByeDPI недоступен на этом устройстве (нужен Android 8+)" }
        val argv = ByeDpiArgs.parse(args, sni).getOrThrow()
        val p = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val child = ProcessBuilder(listOf(exe().absolutePath) + ByeDpiArgs.loopbackPrefix(p) + argv)
            .redirectOutput(File("/dev/null")).redirectError(File("/dev/null")).start()
        process = child
        try {
            withTimeout(3000) {
                while (true) {
                    ensureActive()
                    check(child.isAlive) { "ByeDPI завершился при запуске — проверьте стратегию" }
                    if (runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", p), 150) }; true }.getOrDefault(false)) break
                    delay(50)
                }
            }
            port = p; p
        } catch (e: Throwable) { close(); throw e }
    }

    val alive: Boolean get() = process?.isAlive == true

    override fun close() {
        val old = process.also { process = null } ?: return
        port = 0
        old.destroy()
        if (Build.VERSION.SDK_INT >= 26) runCatching {
            if (!old.waitFor(300, TimeUnit.MILLISECONDS)) { old.destroyForcibly(); old.waitFor(300, TimeUnit.MILLISECONDS) }
        }
    }
}

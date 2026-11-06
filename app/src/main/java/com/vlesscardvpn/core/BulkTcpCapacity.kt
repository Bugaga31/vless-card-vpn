package com.vlesscardvpn.core

import android.app.ActivityManager
import android.content.Context
import java.io.File

/** TCP-only bulk checks use non-blocking sockets. Keep descriptors for VPN, DNS, files and Android. */
object BulkTcpCapacity {
    const val DEFAULT = 250
    const val MAX = 1000
    private const val RESERVED_FDS = 128L
    val choices = listOf(50, 100, 250, 500, 1000)
    fun normalize(value: Int) = value.takeIf { it in choices } ?: DEFAULT

    fun limit(requested: Int, softLimit: Long?, openFds: Int?, lowMemory: Boolean?): Int {
        val wanted = normalize(requested)
        if (softLimit == null || openFds == null || softLimit < 0 || openFds < 0) return minOf(wanted, 64)
        val available = (softLimit - openFds.toLong() - RESERVED_FDS).coerceIn(0L, MAX.toLong()).toInt()
        return minOf(wanted, available, if (lowMemory != false) 64 else MAX)
    }

    /** Resource failures describe the phone, not the server. Do not persist error text. */
    fun resourceFailure(error: Throwable?): Boolean {
        var current = error
        repeat(4) {
            val item = current ?: return false
            val message = item.message.orEmpty().lowercase(java.util.Locale.ROOT)
            if (listOf("too many open files", "cannot allocate memory", "no buffer space", "out of memory").any { it in message }) return true
            current = item.cause
        }
        return false
    }

    fun parseSoftLimit(lines: Sequence<String>): Long? {
        val line = lines.firstOrNull { it.startsWith("Max open files") } ?: return null
        val value = line.removePrefix("Max open files").trim().split(Regex("\\s+")).firstOrNull() ?: return null
        return if (value == "unlimited") Long.MAX_VALUE else value.toLongOrNull()?.takeIf { it >= 0 }
    }

    fun current(context: Context, requested: Int): Int {
        val soft = runCatching { File("/proc/self/limits").useLines { parseSoftLimit(it) } }.getOrNull()
        val open = runCatching { File("/proc/self/fd").list()?.size }.getOrNull()
        val lowMemory = runCatching {
            val am = context.getSystemService(ActivityManager::class.java) ?: return@runCatching null
            ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.lowMemory
        }.getOrNull()
        return limit(requested, soft, open, lowMemory)
    }
}

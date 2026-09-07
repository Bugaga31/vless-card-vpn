package com.vlesscardvpn.xraytest

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object TestState {
    val profiles = MutableStateFlow<List<Node>>(emptyList())
    private val mutable = MutableStateFlow(Session())
    val session = mutable.asStateFlow()
    fun update(s: Session) { mutable.value = s }
}
data class Session(val busy: Boolean = false, val active: Boolean = false, val message: String = "Не подключено", val node: Int = -1)
object Reports {
    private lateinit var file: File
    @Synchronized fun init(context: Context) { file = File(context.filesDir, "test-report.txt") }
    @Synchronized fun add(message: String) {
        // Callers pass controlled stage labels, NEVER config, remote errors or URIs.
        runCatching {
            val previous = if (file.exists()) file.readLines().takeLast(60) else emptyList()
            file.writeText((previous + "${System.currentTimeMillis()} $message").joinToString("\n"))
        }
    }
    fun error(t: Throwable) {
        add("Exception: ${t.javaClass.name}")
        t.stackTrace.take(12).forEach { add("at ${it.className}.${it.methodName}:${it.lineNumber}") }
        t.cause?.let { add("Cause type: ${it.javaClass.name}") }
    }
    @Synchronized fun read(context: Context): String = buildString {
        appendLine("VLESS Card Test ${BuildConfig.VERSION_NAME}; Android ${Build.VERSION.RELEASE}; ${Build.MANUFACTURER} ${Build.MODEL}; ABI ${Build.SUPPORTED_ABIS.joinToString()}")
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val am = context.getSystemService(ActivityManager::class.java)
                am.getHistoricalProcessExitReasons(null, 0, 3).forEach {
                    appendLine("Previous exit: reason=${it.reason}, status=${it.status}, time=${it.timestamp}")
                }
            }
        }
        append(runCatching { file.readText() }.getOrDefault("Отчётов пока нет"))
    }
}
class TestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Reports.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Reports.error(error)
            if (previous != null) previous.uncaughtException(thread, error)
            else { android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(10) }
        }
        // Native initialization is delayed until service startup, after crash reporting is installed.
        Reports.add("Application opened")
    }
}

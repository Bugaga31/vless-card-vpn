package com.vlesscardvpn

import android.app.Application
import android.content.Context
import android.util.Log
import com.vlesscardvpn.core.CrashReportManager
import go.Seq
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VlessApplication : Application() {

    companion object {
        private const val TAG = "VlessApplication"
        private const val MAX_CRASH_LOGS = 5

        /**
         * Sanitizes any log/crash text to strictly remove UUIDs, private keys, passwords,
         * public keys, shortIds, tokens, and raw protocol URIs.
         */
        fun sanitizeLog(input: String?): String {
            if (input.isNullOrBlank()) return ""
            var text = input

            // 1. Sanitize UUIDs (e.g. 12345678-1234-1234-1234-123456789abc)
            text = text.replace(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"), "[REDACTED_UUID]")

            // 2. Sanitize Reality public keys and passwords in query params
            text = text.replace(Regex("(pbk|public_key|password|secret|key|token|uuid|sid|short_id)=([^&\\s,;\"'}{]+)", RegexOption.IGNORE_CASE), "$1=[REDACTED]")

            // 3. Sanitize JSON fields for keys and secrets
            text = text.replace(Regex("\"(uuid|password|public_key|private_key|short_id|secret|token)\"\\s*:\\s*\"[^\"]+\"", RegexOption.IGNORE_CASE), "\"$1\":\"[REDACTED]\"")

            // 4. Sanitize full protocol links (vless://, vmess://, trojan://, ss://)
            text = text.replace(Regex("(vless|vmess|trojan|ss)://[^\\s]+", RegexOption.IGNORE_CASE), "$1://[REDACTED_NODE_CONFIG]")

            return text
        }
    }

    override fun onCreate() {
        super.onCreate()
        setupGoSeqContext()
        setupUncaughtExceptionHandler()
    }

    /**
     * Initializes Go runtime environment context for Go Mobile / Libbox.
     */
    private fun setupGoSeqContext() {
        try {
            System.loadLibrary("box")
            Log.i(TAG, "Loaded libbox.so (sing-box native library)")
        } catch (t: Throwable) {
            Log.w(TAG, "Could not load libbox: ${t.message}")
        }
        try {
            Seq.setContext(applicationContext)
            Log.i(TAG, "Go Seq context initialized successfully")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize Go Seq context", t)
        }
    }

    /**
     * Installs a safe uncaught exception handler that logs and stores sanitized crash reports.
     */
    private fun setupUncaughtExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Save formatted JSON entry for crash manager & GitHub issues
                CrashReportManager.recordException(applicationContext, thread, throwable, "CRASH")
                recordCrashReport(thread, throwable)
            } catch (e: Exception) {
                Log.e(TAG, "Error saving crash report", e)
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun recordCrashReport(thread: Thread, throwable: Throwable) {
        val crashDir = File(filesDir, "crashes").apply { mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val crashFile = File(crashDir, "crash_$timeStamp.log")

        val stringWriter = StringWriter()
        throwable.printStackTrace(PrintWriter(stringWriter))
        val rawStackTrace = stringWriter.toString()

        val sanitizedReport = buildString {
            appendLine("=== VLESS CARD VPN CRASH REPORT ===")
            appendLine("Timestamp: $timeStamp")
            appendLine("Thread: ${thread.name} (id=${thread.id})")
            appendLine("Exception Type: ${throwable.javaClass.name}")
            appendLine("Sanitized Message: ${sanitizeLog(throwable.message)}")
            appendLine("--- Stack Trace ---")
            appendLine(sanitizeLog(rawStackTrace))
            appendLine("===================================")
        }

        try {
            crashFile.writeText(sanitizedReport)
            // Also write to latest_crash.log for easy inspection
            File(crashDir, "latest_crash.log").writeText(sanitizedReport)

            // Prune old crash logs keeping only MAX_CRASH_LOGS
            val files = crashDir.listFiles { f -> f.name.startsWith("crash_") && f.name.endsWith(".log") }
            if (files != null && files.size > MAX_CRASH_LOGS) {
                files.sortedBy { it.lastModified() }
                    .take(files.size - MAX_CRASH_LOGS)
                    .forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write sanitized crash file", e)
        }
    }
}

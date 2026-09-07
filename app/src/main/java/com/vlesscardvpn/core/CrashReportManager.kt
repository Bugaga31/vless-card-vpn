package com.vlesscardvpn.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.vlesscardvpn.VlessApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

data class CrashLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val formattedDate: String,
    val type: String,
    val threadName: String,
    val message: String,
    val stackTrace: String,
    val deviceModel: String,
    val androidVersion: String,
    val appVersion: String,
    var isSent: Boolean = false
)

object CrashReportManager {

    private const val TAG = "CrashReportManager"
    private const val MAX_SAVED_CRASHES = 10
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun recordException(context: Context, thread: Thread, throwable: Throwable, type: String = "CRASH") {
        try {
            val crashDir = File(context.filesDir, "crashes").apply { mkdirs() }
            val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val fileTimeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val crashFile = File(crashDir, "crash_${fileTimeStamp}_${UUID.randomUUID().toString().take(6)}.json")

            val appVer = try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                "${pInfo.versionName} (${pInfo.versionCode})"
            } catch (_: Exception) {
                "1.0.0"
            }

            val rawStackTrace = Log.getStackTraceString(throwable)
            val sanitizedMsg = VlessApplication.sanitizeLog(throwable.message ?: throwable.javaClass.simpleName)
            val sanitizedStack = VlessApplication.sanitizeLog(rawStackTrace)

            val json = JSONObject().apply {
                put("id", UUID.randomUUID().toString())
                put("timestamp", System.currentTimeMillis())
                put("formattedDate", timeStamp)
                put("type", type)
                put("threadName", thread.name)
                put("message", sanitizedMsg)
                put("stackTrace", sanitizedStack)
                put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})")
                put("androidVersion", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                put("appVersion", appVer)
                put("isSent", false)
            }

            crashFile.writeText(json.toString(2))
            Log.i(TAG, "Recorded sanitized crash report: ${crashFile.name}")

            // Prune excess crash files
            val files = crashDir.listFiles { f -> f.name.startsWith("crash_") && f.name.endsWith(".json") }
            if (files != null && files.size > MAX_SAVED_CRASHES) {
                files.sortedBy { it.lastModified() }
                    .take(files.size - MAX_SAVED_CRASHES)
                    .forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record crash exception", e)
        }
    }

    fun getSavedCrashes(context: Context): List<CrashLogEntry> {
        val crashDir = File(context.filesDir, "crashes")
        if (!crashDir.exists()) return emptyList()
        val list = mutableListOf<CrashLogEntry>()

        val files = crashDir.listFiles { f -> f.name.startsWith("crash_") && f.name.endsWith(".json") } ?: return emptyList()
        for (file in files.sortedByDescending { it.lastModified() }) {
            try {
                val json = JSONObject(file.readText())
                list.add(
                    CrashLogEntry(
                        id = json.optString("id", file.nameWithoutExtension),
                        timestamp = json.optLong("timestamp", file.lastModified()),
                        formattedDate = json.optString("formattedDate", "Unknown date"),
                        type = json.optString("type", "CRASH"),
                        threadName = json.optString("threadName", "main"),
                        message = json.optString("message", "No message"),
                        stackTrace = json.optString("stackTrace", ""),
                        deviceModel = json.optString("deviceModel", "Unknown device"),
                        androidVersion = json.optString("androidVersion", "Android Unknown"),
                        appVersion = json.optString("appVersion", "1.0.0"),
                        isSent = json.optBoolean("isSent", false)
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse crash file: ${file.name}", e)
            }
        }
        return list
    }

    fun markAsSent(context: Context, crashId: String) {
        val crashDir = File(context.filesDir, "crashes")
        val files = crashDir.listFiles { f -> f.name.startsWith("crash_") && f.name.endsWith(".json") } ?: return
        for (file in files) {
            try {
                val json = JSONObject(file.readText())
                if (json.optString("id") == crashId) {
                    json.put("isSent", true)
                    file.writeText(json.toString(2))
                    break
                }
            } catch (_: Exception) {}
        }
    }

    fun clearAllCrashes(context: Context) {
        val crashDir = File(context.filesDir, "crashes")
        if (crashDir.exists()) {
            crashDir.listFiles()?.forEach { it.delete() }
        }
    }

    /**
     * Builds Markdown report suitable for GitHub Issues.
     */
    fun buildMarkdownReport(report: CrashLogEntry): String {
        return buildString {
            appendLine("### 🐛 VLESS Card VPN - Отчет об ошибке")
            appendLine()
            appendLine("- **Тип сбоя:** `${report.type}`")
            appendLine("- **Дата:** `${report.formattedDate}`")
            appendLine("- **Устройство:** `${report.deviceModel}`")
            appendLine("- **ОС:** `${report.androidVersion}`")
            appendLine("- **Версия приложения:** `${report.appVersion}`")
            appendLine("- **Поток:** `${report.threadName}`")
            appendLine()
            appendLine("#### Описание ошибки:")
            appendLine("```text")
            appendLine(report.message)
            appendLine("```")
            appendLine()
            appendLine("#### Стек вызовов (Sanitized):")
            appendLine("<details>")
            appendLine("<summary>Нажмите, чтобы развернуть Stack Trace</summary>")
            appendLine()
            appendLine("```java")
            appendLine(report.stackTrace.take(4000))
            appendLine("```")
            appendLine("</details>")
            appendLine()
            appendLine("---")
            appendLine("*Все конфиденциальные данные (UUID, ключи, SNI, пароли) были автоматически обезличены перед отправкой.*")
        }
    }

    /**
     * Creates an Intent to open GitHub new issue page in browser with pre-filled title & markdown body.
     */
    fun createGitHubIssueIntent(report: CrashLogEntry, githubRepo: String): Intent {
        val cleanRepo = githubRepo.trim().removePrefix("https://github.com/").removeSuffix(".git")
        val title = "[Авто-отчет] ${report.type}: ${report.message.take(60)}"
        val body = buildMarkdownReport(report)

        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val encodedBody = URLEncoder.encode(body, "UTF-8")
        val url = "https://github.com/$cleanRepo/issues/new?title=$encodedTitle&body=$encodedBody&labels=bug,crash-report"

        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Sends crash report directly to GitHub Issues via REST API if GitHub Personal Token is provided.
     */
    suspend fun sendReportViaGitHubApi(
        report: CrashLogEntry,
        githubRepo: String,
        token: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanRepo = githubRepo.trim().removePrefix("https://github.com/").removeSuffix(".git")
        val apiUrl = "https://api.github.com/repos/$cleanRepo/issues"

        val title = "[Авто-отчет] ${report.type}: ${report.message.take(60)}"
        val body = buildMarkdownReport(report)

        val payload = JSONObject().apply {
            put("title", title)
            put("body", body)
            put("labels", JSONArray().apply {
                put("bug")
                put("auto-crash-report")
            })
        }

        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("Authorization", "Bearer ${token.trim()}")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseJson = JSONObject(response.body?.string().orEmpty())
                    val issueUrl = responseJson.optString("html_url", "Создан Issue")
                    Result.success(issueUrl)
                } else {
                    val errBody = response.body?.string().orEmpty()
                    Result.failure(Exception("HTTP ${response.code}: $errBody"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

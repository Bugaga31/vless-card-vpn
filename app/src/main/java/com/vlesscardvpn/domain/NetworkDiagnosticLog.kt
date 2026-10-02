package com.vlesscardvpn.domain

import java.io.File

/** Allow-listed fields only: no arbitrary strings, configs, URLs, exception messages or IDs. */
enum class DiagnosticPhase { AUTO_START, ROUTE_START, PERMISSION_CHECK, CONFIG_LOAD, CORE_INIT, CONFIG_VALIDATE, CORE_START, TUN_READY, HTTPS_CHECK, CORE_FAILURE, CONNECTED, PARTIAL, STOPPED }
enum class DiagnosticTarget { NONE, INTERNET, YOUTUBE, TELEGRAM }
enum class DiagnosticFailure { NONE, CONNECT, PROXY, TLS, HTTP, TIMEOUT, CORE, NO_ROUTE }
data class NetworkDiagnosticEvent(
    val timeMs: Long = System.currentTimeMillis(),
    val phase: DiagnosticPhase,
    val target: DiagnosticTarget = DiagnosticTarget.NONE,
    val profile: RouteProfile? = null,
    val preset: ByeDpiPreset? = null,
    val failure: DiagnosticFailure = DiagnosticFailure.NONE,
    val httpCode: Int = -1,
    val latencyMs: Int = -1,
    val stage: DiagnosticFailure = DiagnosticFailure.NONE
) {
    fun line(): String = listOf(timeMs.coerceAtLeast(0), phase.name, target.name,
        profile?.name ?: "NONE", preset?.name ?: "NONE", failure.name,
        httpCode.takeIf { it in 100..599 } ?: -1, latencyMs.coerceIn(-1, 60000), stage.name).joinToString(" | ")
}
class DiagnosticRing(private val file: File, private val capacity: Int = 200) {
    init { require(capacity in 1..500) }
    // Re-validate disk contents before display/export. Never export externally injected free text.
    private fun valid(line: String): Boolean = runCatching {
        val p = line.split(" | ")
        require(p.size in 8..9 && p[0].toLong() >= 0)
        DiagnosticPhase.valueOf(p[1]); DiagnosticTarget.valueOf(p[2])
        if (p[3] != "NONE") RouteProfile.valueOf(p[3])
        if (p[4] != "NONE") ByeDpiPreset.valueOf(p[4])
        DiagnosticFailure.valueOf(p[5])
        if (p.size == 9) DiagnosticFailure.valueOf(p[8])
        val code = p[6].toInt(); require(code == -1 || code in 100..599)
        require(p[7].toInt() in -1..60000)
        true
    }.getOrDefault(false)
    @Synchronized fun read(): List<String> = runCatching {
        if (!file.exists() || file.length() > 150000) emptyList() else file.readLines().filter(::valid).takeLast(capacity).map { if (it.split(" | ").size == 8) "$it | NONE" else it }
    }.getOrDefault(emptyList())
    @Synchronized fun append(event: NetworkDiagnosticEvent) {
        val rows = (read() + event.line()).takeLast(capacity)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(rows.joinToString("\n"))
        check(temp.renameTo(file)) { "Diagnostic write failed" }
    }
    @Synchronized fun clear() { file.delete(); File(file.parentFile, file.name + ".tmp").delete() }
}
object NetworkDiagnosticLog {
    @Volatile var ring: DiagnosticRing? = null
    fun record(event: NetworkDiagnosticEvent) { runCatching { ring?.append(event) } }
    fun report(version: String, sdk: Int, rows: List<String>): String = buildString {
        appendLine("VLESS Card Lab — network diagnostics")
        appendLine("version=$version; Android SDK=$sdk")
        appendLine("Time epoch_ms | phase | target | profile | preset | failure | HTTP | latency_ms | stage")
        appendLine("Checks: authenticated native VPN outbound; not video playback, MTProto or all-app TUN verification.")
        rows.forEach { appendLine(it) }
    }
}

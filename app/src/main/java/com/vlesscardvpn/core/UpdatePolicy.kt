package com.vlesscardvpn.core

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Pure update-validation rules. Checksums detect corruption; matching publisher certificates are required too. */
object UpdatePolicy {
    const val MAX_APK = 128 * 1024 * 1024L
    fun codeOf(tag: String): Int {
        val m = Regex("^v?1\\.(0|1|2)\\.(0|[1-9][0-9]*)$").matchEntire(tag.trim()) ?: return 0
        val minor = m.groupValues[1].toInt(); val patch = m.groupValues[2].toLongOrNull() ?: return 0
        if (minor == 0 && patch > 100 || minor == 1 && patch > 9) return 0
        val code = patch + when (minor) { 1 -> 101; 2 -> 111; else -> 0 }
        return if (code in 1..Int.MAX_VALUE.toLong()) code.toInt() else 0
    }
    fun checksum(manifest: String, fileName: String): String {
        require(manifest.length <= 64 * 1024)
        val entries = manifest.lineSequence().mapNotNull { line ->
            Regex("^([0-9a-fA-F]{64})[ \\t]+\\*?(.+)$").matchEntire(line.trim())
                ?.takeIf { it.groupValues[2] == fileName }?.groupValues?.get(1)?.lowercase(java.util.Locale.ROOT)
        }.toList()
        require(entries.size == 1) { "Нет однозначной контрольной суммы APK" }
        return entries.single()
    }
    fun acceptsArchive(expectedPackage: String, expectedCode: Int, mine: Int, candidatePackage: String, candidateCode: Long,
                       installedSigners: Set<String>, candidateSigners: Set<String>): Boolean =
        candidatePackage == expectedPackage && expectedCode > mine && candidateCode == expectedCode.toLong() &&
            installedSigners.isNotEmpty() && candidateSigners == installedSigners

    fun hash(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(64 * 1024)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
    fun copyVerified(input: InputStream, out: OutputStream, sha: String, length: Long = -1, max: Long = MAX_APK, progress: (Long) -> Unit = {}): Long {
        require(Regex("^[0-9a-f]{64}$").matches(sha))
        require(max > 0 && length <= max && length >= -1) { "Недопустимый размер APK" }
        val md = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(64 * 1024); var done = 0L
        while (true) {
            val n = input.read(buf); if (n < 0) break
            require(n.toLong() <= max - done) { "APK превышает допустимый размер" }
            out.write(buf, 0, n); md.update(buf, 0, n); done += n; progress(done)
        }
        require(done > 0 && (length == -1L || done == length)) { "APK скачался не полностью" }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        require(actual == sha) { "Контрольная сумма APK не совпала — установка остановлена" }
        return done
    }
    fun checkDue(now: Long, success: Long, attempt: Long): Boolean =
        (success == 0L || now < success || now - success >= 3600_000L) &&
            (attempt == 0L || now < attempt || now - attempt >= 15 * 60_000L)
}

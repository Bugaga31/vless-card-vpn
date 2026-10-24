package com.vlesscardvpn.core

/** Pure response validation: redirects/login HTML/compressed payloads must not impersonate a speed test. */
object ProbeResponsePolicy {
    fun connectivity(code: Int, url: String): Boolean {
        val noContent = runCatching { java.net.URI(url).path.orEmpty().trimEnd('/').endsWith("/generate_204") }.getOrDefault(false)
        return if (noContent) code == 204 else code in 200..299
    }
    fun site(code: Int, bytes: Int): String = when {
        code in setOf(403,451) -> "denied"
        code==429 -> "limited"
        code==204 || code in 200..299 && bytes>0 -> "ok"
        else -> "http"
    }
    fun download(code: Int, contentType: String?, encoding: String?): Boolean {
        if (code != 200 && code != 206) return false
        if (!encoding.isNullOrBlank() && !encoding.equals("identity", ignoreCase = true)) return false
        val type = contentType.orEmpty().substringBefore(';').trim().lowercase(java.util.Locale.ROOT)
        return type !in setOf("text/html", "application/xhtml+xml", "application/json", "application/xml", "text/xml") &&
            !type.endsWith("+json") && !type.endsWith("+xml")
    }
}

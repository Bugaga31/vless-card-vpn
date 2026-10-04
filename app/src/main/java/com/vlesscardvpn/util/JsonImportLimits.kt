package com.vlesscardvpn.util

/** Bound parser recursion before org.json sees untrusted subscription/file content. */
internal object JsonImportLimits {
    const val MAX_TEXT = 2 * 1024 * 1024
    const val MAX_OUTBOUNDS = 1000
    fun accepts(text: String): Boolean {
        if (text.length > MAX_TEXT) return false
        var depth = 0; var quoted = false; var escaped = false
        for (ch in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') quoted = false
            } else when (ch) {
                '"' -> quoted = true
                '{', '[' -> { depth++; if (depth > 64) return false }
                '}', ']' -> { depth--; if (depth < 0) return false }
            }
        }
        return depth == 0 && !quoted
    }
}

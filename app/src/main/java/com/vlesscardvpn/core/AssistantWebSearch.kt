package com.vlesscardvpn.core

import java.net.URLEncoder

/** Explicit user query only. Never adds facts(), network identifiers, server lists, keys or chat history.
 * A browser search is not an LLM and its results do not get execution permissions in the app.
 */
object AssistantWebSearch {
    fun query(text: String): String? {
        val prefix = listOf("найди в интернете ", "поиск в интернете ", "search web ").firstOrNull { text.startsWith(it, true) } ?: return null
        return text.substring(prefix.length).trim().takeIf { it.length in 2..300 && safe(it) }
    }
    fun safe(query: String): Boolean = query.length in 2..300 && !query.contains(Regex("[\\p{Cntrl}]")) &&
        !query.contains(Regex("(?i)(vless|vmess|trojan|ss|hysteria2|hy2|wireguard)://|-----BEGIN|(?:password|token|secret|uuid|private.?key|пароль|токен|ключ)\\s*[:=]|[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}|[a-zA-Z0-9+/=_-]{40,}"))
    fun url(query: String): String? = if (safe(query)) "https://duckduckgo.com/?q=" + URLEncoder.encode(query, "UTF-8") else null
}

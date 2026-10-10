package com.vlesscardvpn.core

/**
 * Human text for an exception. Raw messages like "A0 was cancelled" (a cancelled coroutine, its class name shrunk
 * by R8) or "failed to connect to /1.2.3.4 (port 443) after 5000ms" meant nothing to people.
 */
object Errors {
    fun human(e: Throwable): String = when (e) {
        is kotlinx.coroutines.TimeoutCancellationException -> "нет ответа (вышло время)"
        is kotlinx.coroutines.CancellationException -> "прервано — запущено другое действие"
        is java.net.UnknownHostException -> "имя сервера не находится (DNS)"
        is java.net.SocketTimeoutException -> "нет ответа (вышло время)"
        is java.net.ConnectException -> "соединение не устанавливается"
        is javax.net.ssl.SSLException -> if ((e.message ?: "").contains("reset", true)) "соединение сброшено (DPI?)" else "шифрование (TLS) не прошло"
        is java.net.SocketException -> if ((e.message ?: "").contains("reset", true)) "соединение сброшено (DPI?)" else "соединение оборвалось"
        is java.io.InterruptedIOException -> "нет ответа (вышло время)"
        else -> e.message?.takeIf { it.isNotBlank() && !it.endsWith("was cancelled") }?.take(120) ?: "ошибка"
    }
}

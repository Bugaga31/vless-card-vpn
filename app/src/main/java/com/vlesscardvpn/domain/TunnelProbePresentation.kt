package com.vlesscardvpn.domain

/** UI copy derived only from bounded protocol outcomes, never raw errors or server details. */
object TunnelProbePresentation {
    fun status(probe: TunnelProbe?, checked: Boolean): String = when {
        !checked -> "Не проверено"
        probe == null -> "Нет результата"
        probe.passed -> "Доступен"
        probe.failure == DiagnosticFailure.TIMEOUT -> when (probe.stage) {
            DiagnosticFailure.CONNECT -> "Таймаут порта"
            DiagnosticFailure.PROXY -> "Таймаут SOCKS"
            DiagnosticFailure.TLS -> "Таймаут TLS"
            DiagnosticFailure.HTTP -> "Таймаут HTTP"
            else -> "Таймаут"
        }
        probe.httpCode in 100..599 -> "HTTP ${probe.httpCode}"
        probe.failure == DiagnosticFailure.TLS || probe.stage == DiagnosticFailure.TLS -> "Ошибка TLS"
        probe.failure == DiagnosticFailure.PROXY || probe.stage == DiagnosticFailure.PROXY -> "Ошибка SOCKS"
        probe.failure == DiagnosticFailure.CONNECT || probe.stage == DiagnosticFailure.CONNECT -> "Нет соединения"
        probe.failure == DiagnosticFailure.NO_ROUTE -> "Нет маршрута"
        probe.failure == DiagnosticFailure.HTTP || probe.stage == DiagnosticFailure.HTTP -> "Ответ некорректен"
        else -> "Нет ответа"
    }
}

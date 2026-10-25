package com.vlesscardvpn.core

/** Debug E2E diagnostics only. Never serialize Throwable.message, route names, URLs or credentials. */
object ConnectDiagnostics {
    private val scopes = setOf("connect", "action", "chain")
    private val phases = setOf("init", "selection", "dpi", "config", "core", "tun", "notification", "action", "chain_check")
    private val types = setOf("IllegalStateException", "IllegalArgumentException", "IOException", "FileNotFoundException", "BindException", "SocketException", "SocketTimeoutException", "UnknownHostException", "CancellationException", "TimeoutCancellationException", "SecurityException", "NullPointerException", "OutOfMemoryError", "RuntimeException", "Exception")
    private val files = setOf("TunnelService.kt", "Actions.kt", "XrayCore.kt", "DpiSet.kt", "DpiProxy.kt", "Tester.kt", "RuntimeChainE2E.kt", "AutoChainRunner.kt", "XrayConfigBuilder.kt", "Store.kt")

    /** Only source locations from known application files; never class names/messages/paths. */
    fun startCaller(stack: Array<StackTraceElement>): String = stack.firstOrNull {
        it.className.startsWith("com.vlesscardvpn.") && it.fileName in
            setOf("MainActivity.kt", "Actions.kt", "Assistant.kt", "BootReceiver.kt", "VpnWidget.kt", "ToggleTileService.kt") &&
            it.lineNumber in 1..100000
    }?.let { "${it.fileName}:${it.lineNumber}" } ?: "unknown"

    /** Fixed lifecycle labels only: no instance names derived from server IDs or core messages. */
    fun coreEvent(name: String, id: Long, event: String, wall: Long, elapsed: Long): String {
        val role = name.takeIf { it in setOf("vpn", "test", "test1", "speed", "auto-chain") } ?: "other"
        val action = event.takeIf { it in setOf("start_begin", "start_return", "start_throw", "stop_begin", "stop_return", "stop_throw") } ?: "other"
        return "core instance=${id.coerceAtLeast(0)} role=$role event=$action wall_ms=${wall.coerceAtLeast(0)} elapsed_ms=${elapsed.coerceAtLeast(0)}"
    }

    fun describe(scope: String, phase: String, error: Throwable): String {
        val chain = mutableListOf<Throwable>()
        var current: Throwable? = error
        while (chain.size < 5) {
            val item = current ?: break
            if (chain.any { it === item }) break
            chain += item; current = item.cause
        }
        val code = when {
            chain.any { it is java.net.BindException } -> "bind"
            chain.any { it.message.orEmpty().contains("address already in use", true) } -> "address_in_use"
            chain.any { it.message.orEmpty().contains("too many open files", true) } -> "file_limit"
            chain.any { it.message.orEmpty().contains("permission denied", true) } -> "permission"
            chain.any { it.message == "No running DPI set" } -> "dpi_missing"
            chain.any { it.message == "No verified DPI-server winner" } -> "chain_unconfirmed"
            chain.any { it.message == "Measured chain was not applied" } -> "chain_not_applied"
            chain.any { it.message.orEmpty().startsWith("Xray не запустился:") } -> "core_not_running"
            else -> "unclassified"
        }
        val origin = chain.asReversed().asSequence().flatMap { it.stackTrace.asSequence() }
            .firstOrNull { it.className.startsWith("com.vlesscardvpn.") && it.fileName in files && it.lineNumber in 1..100000 }
            ?.let { "${it.fileName}:${it.lineNumber}" } ?: "unknown"
        val names = chain.map { it.javaClass.simpleName.takeIf { name -> name in types } ?: "Other" }
        return "failure scope=${scope.takeIf { it in scopes } ?: "connect"} phase=${phase.takeIf { it in phases } ?: "init"} type=${names.first()} code=$code origin=$origin causes=${names.joinToString(",")}"
    }
}

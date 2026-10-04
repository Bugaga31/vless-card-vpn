package com.vlesscardvpn.domain

/** Share-link transport names used by Xray panels (3x-ui, Marzban, Remnawave) mapped to what sing-box runs. */
object ConfigTransport {
    /** Transports the bundled sing-box core actually implements. XHTTP/SplitHTTP, mKCP and QUIC are not among them. */
    val SUPPORTED = setOf("tcp", "ws", "grpc", "h2", "httpupgrade")
    fun normalize(raw: String?): String = when (val t = raw.orEmpty().trim().lowercase()) {
        "", "tcp", "raw" -> "tcp" // Xray 24.9+ renamed TCP to RAW; links from new panels carry type=raw.
        "ws", "websocket" -> "ws"
        "grpc", "gun" -> "grpc"
        "h2", "http", "http2" -> "h2"
        "httpupgrade", "http-upgrade" -> "httpupgrade"
        else -> t // Never silently turn an unsupported transport (xhttp, kcp, quic) into TCP.
    }
    fun isSupported(raw: String?): Boolean = normalize(raw) in SUPPORTED
}

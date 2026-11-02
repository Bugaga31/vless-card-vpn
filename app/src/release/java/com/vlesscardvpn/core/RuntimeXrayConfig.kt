package com.vlesscardvpn.core

/** Build-variant boundary: fixture aliases exist only in debug APKs. */
object RuntimeXrayConfig {
    fun prepare(config: String): String = config
}

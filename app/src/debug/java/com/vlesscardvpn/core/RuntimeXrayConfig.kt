package com.vlesscardvpn.core

/** Build-variant boundary: fixture aliases exist only in debug APKs. */
object RuntimeXrayConfig {
    fun prepare(config: String): String {
        val root = org.json.JSONObject(config)
        val outbounds = root.optJSONArray("outbounds") ?: return config
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.getJSONObject(i)
            if (outbound.optString("protocol") != "vless") continue
            val servers = outbound.optJSONObject("settings")?.optJSONArray("vnext") ?: continue
            for (j in 0 until servers.length()) {
                val server = servers.getJSONObject(j)
                // Exact synthetic aliases only; no generic DNS overrides, CA changes or bypass of authentication.
                if (Regex("^r163-pool-[0-7]\\.test$").matches(server.optString("address")))
                    server.put("address", "10.0.2.2")
            }
        }
        return root.toString()
    }
}

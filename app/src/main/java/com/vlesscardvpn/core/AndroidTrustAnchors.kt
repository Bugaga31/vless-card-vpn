package com.vlesscardvpn.core

import android.util.Base64
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import io.nekohasekai.libbox.StringIterator

internal object CertificatePem {
    fun wrap(base64: String): String {
        require(base64.matches(Regex("[A-Za-z0-9+/]+={0,2}")))
        return "-----BEGIN CERTIFICATE-----\n" + base64.chunked(64).joinToString("\n") + "\n-----END CERTIFICATE-----\n"
    }
}

/** Platform-default trust anchors, not a trust-all manager and not server leaf certificates. */
internal object AndroidTrustAnchors {
    private val certificates: List<String> by lazy {
        runCatching {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            factory.trustManagers.filterIsInstance<X509TrustManager>().flatMap { manager ->
                manager.acceptedIssuers.map { CertificatePem.wrap(Base64.encodeToString(it.encoded, Base64.NO_WRAP)) }
            }.distinct()
        }.getOrDefault(emptyList()) // Native system-pool fallback; never disable certificate verification.
    }
    fun iterator(): StringIterator {
        val values = certificates; var index = 0
        return object : StringIterator {
            override fun hasNext() = index < values.size
            override fun len() = values.size
            override fun next() = values.getOrNull(index++) ?: ""
        }
    }
}

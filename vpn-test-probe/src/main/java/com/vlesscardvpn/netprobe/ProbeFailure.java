package com.vlesscardvpn.netprobe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Test-only bounded error metadata. No exception messages, stack traces or peer data leave this helper. */
public final class ProbeFailure {
    private ProbeFailure() { }
    private static final Set<String> TYPES = Collections.unmodifiableSet(new java.util.HashSet<>(Arrays.asList(
        "SSLHandshakeException", "SSLProtocolException", "SSLPeerUnverifiedException", "SSLException",
        "CertificateException", "CertificateExpiredException", "CertificateNotYetValidException",
        "CertPathValidatorException", "CertificateParsingException", "UnknownHostException", "ConnectException",
        "SocketException", "SocketTimeoutException", "EOFException", "IOException", "ProtocolException",
        "MalformedURLException", "InterruptedException", "SecurityException", "IllegalStateException",
        "IllegalArgumentException", "Exception")));

    private static List<Throwable> chain(Throwable error) {
        List<Throwable> out = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable e = error; e != null && out.size() < 5 && seen.add(e); e = e.getCause()) out.add(e);
        return out;
    }
    public static String type(Throwable error) {
        String name = error.getClass().getSimpleName();
        return TYPES.contains(name) ? name : "Other";
    }
    public static String[] causes(Throwable error) {
        List<String> out = new ArrayList<>();
        for (Throwable e : chain(error)) out.add(type(e));
        return out.toArray(new String[0]);
    }
    public static String reason(Throwable error) {
        List<Throwable> all = chain(error);
        for (Throwable e : all) {
            if (e instanceof java.security.cert.CertificateException || e instanceof java.security.cert.CertPathValidatorException)
                return "certificate_validation";
            if (e instanceof javax.net.ssl.SSLPeerUnverifiedException) return "peer_unverified";
        }
        for (Throwable e : all) {
            if (e instanceof java.net.SocketTimeoutException) return "timeout";
            if (e instanceof java.net.UnknownHostException) return "dns";
            if (e instanceof java.net.ConnectException) return "connect";
            if (e instanceof java.io.EOFException) return "eof";
            if (e instanceof javax.net.ssl.SSLProtocolException) return "tls_protocol";
        }
        // Inspect only for fixed labels. Never serialize messages (which may include credentials/URLs).
        for (Throwable e : all) {
            String message = e.getMessage();
            if (message == null) continue;
            String lower = message.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("connection reset")) return "connection_reset";
            if (lower.contains("connection closed")) return "connection_closed";
            if (message.equals("Refused HTTPS downgrade")) return "https_downgrade";
            if (message.equals("Missing Location or redirect limit")) return "redirect_policy";
        }
        for (Throwable e : all) if (e instanceof javax.net.ssl.SSLHandshakeException) return "tls_handshake";
        return "unclassified";
    }
}

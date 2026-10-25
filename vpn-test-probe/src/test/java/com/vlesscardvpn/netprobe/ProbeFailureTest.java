package com.vlesscardvpn.netprobe;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProbeFailureTest {
    @Test public void nestedEofIsKeptWithoutPrivateMessages() {
        javax.net.ssl.SSLHandshakeException e = new javax.net.ssl.SSLHandshakeException("https://private.example token=SECRET");
        e.initCause(new java.io.EOFException("203.0.113.8 PRIVATE"));
        assertArrayEquals(new String[]{"SSLHandshakeException","EOFException"}, ProbeFailure.causes(e));
        assertEquals("eof", ProbeFailure.reason(e));
    }
    @Test public void certificateFailureIsNotReportedAsTransportClosure() {
        javax.net.ssl.SSLHandshakeException e = new javax.net.ssl.SSLHandshakeException("connection closed");
        e.initCause(new java.security.cert.CertificateException("private cert"));
        assertEquals("certificate_validation", ProbeFailure.reason(e));
    }
    @Test public void depthIsBounded() {
        Throwable e = new Exception("secret");
        for (int i=0;i<20;i++) e = new java.io.IOException("secret", e);
        assertEquals(5, ProbeFailure.causes(e).length);
    }
    @Test public void cycleIsBounded() {
        Exception a = new Exception("secret"), b = new Exception("secret"); a.initCause(b); b.initCause(a);
        assertEquals(2, ProbeFailure.causes(a).length);
    }
    static class SecretCredential extends Exception { }
    @Test public void unknownClassNamesAreNotSerialized() { assertEquals("Other", ProbeFailure.type(new SecretCredential())); }
    @Test public void timeoutAndDnsAreDistinct() {
        assertEquals("timeout", ProbeFailure.reason(new java.net.SocketTimeoutException("private")));
        assertEquals("dns", ProbeFailure.reason(new java.net.UnknownHostException("private")));
    }
    @Test public void closedConnectionDoesNotInventWhoClosedIt() {
        assertEquals("connection_closed", ProbeFailure.reason(new javax.net.ssl.SSLHandshakeException("connection closed SECRET")));
    }
}

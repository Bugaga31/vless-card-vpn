package com.vlesscardvpn.netprobe;

import android.app.Activity;
import android.os.Bundle;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.widget.TextView;
import java.io.*;
import java.net.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.Collections;
import javax.net.ssl.*;
import org.json.JSONObject;

/** Separate, test-only UID. It never accepts user URLs, user keys or insecure TLS. */
public final class ProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this); view.setText("Controlled VPN route test"); setContentView(view);
        String nonce = getIntent().getStringExtra("nonce");
        if (nonce == null || !nonce.matches("[a-f0-9]{32}")) { finish(); return; }
        new Thread(() -> probe(nonce), "test-only-network-probe").start();
    }
    private void probe(String nonce) {
        JSONObject result = new JSONObject();
        try {
            result.put("nonce", nonce);
            result.put("uid", android.os.Process.myUid());
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkCapabilities caps = null;
            long readyUntil = System.currentTimeMillis() + 5000;
            do {
                caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) break;
                Thread.sleep(100);
            } while (System.currentTimeMillis() < readyUntil);
            boolean vpn = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
            result.put("vpn", vpn);
            if (!vpn) throw new IllegalStateException("No VPN for helper UID");
            result.put("stage", "DNS");
            InetAddress address = InetAddress.getByName("fixture.test");
            result.put("address", address.getHostAddress());
            if (!"198.18.0.1".equals(address.getHostAddress())) throw new IOException("Unexpected fixture DNS answer");
            java.security.cert.Certificate ca;
            try (InputStream input = new FileInputStream("/data/local/tmp/vless-fixture-ca.pem")) {
                ca = CertificateFactory.getInstance("X.509").generateCertificate(input);
            }
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null);
            trust.setCertificateEntry("controlled-fixture", ca);
            TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(trust);
            SSLContext tls = SSLContext.getInstance("TLS"); tls.init(null, tm.getTrustManagers(), null);
            try (Socket raw = new Socket()) {
                result.put("stage", "TCP_CONNECT");
                raw.connect(new InetSocketAddress(address, 18443), 15000); raw.setSoTimeout(15000);
                try (SSLSocket socket = (SSLSocket) tls.getSocketFactory().createSocket(raw, "fixture.test", 18443, true)) {
                    socket.setSoTimeout(15000);
                    SSLParameters params = socket.getSSLParameters(); params.setEndpointIdentificationAlgorithm("HTTPS");
                    params.setServerNames(Collections.singletonList(new SNIHostName("fixture.test"))); socket.setSSLParameters(params);
                    result.put("stage", "TLS_HANDSHAKE");
                    socket.startHandshake();
                    result.put("stage", "HTTP_STATUS");
                    socket.getOutputStream().write("GET /generate_204 HTTP/1.1\r\nHost: fixture.test\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"));
                    socket.getOutputStream().flush();
                    StringBuilder line = new StringBuilder(); InputStream in = socket.getInputStream();
                    while (line.length() < 512) { int b = in.read(); if (b < 0) throw new EOFException(); if (b == 10) break; if (b != 13) line.append((char) b); }
                    if (!line.toString().matches("HTTP/1\\.[01] 204(?: .*|)")) throw new IOException("Unexpected HTTP status");
                    result.put("code", 204); result.put("failure", "NONE");
                }
            }
        } catch (Exception error) {
            try { result.put("code", -1); result.put("failure", error.getClass().getSimpleName()); } catch (Exception ignored) { }
        } finally {
            try {
                File tmp = new File(getFilesDir(), "result.tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(result.toString().getBytes("UTF-8")); }
                if (!tmp.renameTo(new File(getFilesDir(), "result.json"))) throw new IOException("Cannot publish fixture result");
            } catch (Exception ignored) { }
            runOnUiThread(this::finish);
        }
    }
}

package com.vlesscardvpn.netprobe;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.widget.TextView;
import java.io.*;
import java.net.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.Collections;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.*;
import org.json.JSONObject;

/** Separate, test-only UID. No user URLs/keys, direct fallback or insecure TLS. */
public final class ProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this); view.setText("Controlled VPN route comparison"); setContentView(view);
        String nonce = getIntent().getStringExtra("nonce");
        if (nonce == null || !nonce.matches("[a-f0-9]{32}")) { finish(); return; }
        new Thread(() -> probe(nonce), "test-only-network-probe").start();
    }
    private void probe(String nonce) {
        JSONObject result = new JSONObject();
        try {
            result.put("nonce", nonce); result.put("uid", android.os.Process.myUid());
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network network = null; NetworkCapabilities caps = null;
            long readyUntil = SystemClock.elapsedRealtime() + 5000;
            do {
                network = cm.getActiveNetwork(); caps = cm.getNetworkCapabilities(network);
                if (network != null && caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) break;
                Thread.sleep(100);
            } while (SystemClock.elapsedRealtime() < readyUntil);
            boolean vpn = network != null && caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
            result.put("vpn", vpn);
            if (!vpn) throw new IllegalStateException("No VPN for helper UID");
            LinkProperties links = cm.getLinkProperties(network);
            result.put("dns_count", links == null ? 0 : links.getDnsServers().size());
            result.put("route_count", links == null ? 0 : links.getRoutes().size());
            result.put("mtu", links == null ? 0 : links.getMtu());
            java.security.cert.Certificate ca;
            try (InputStream input = new FileInputStream("/data/local/tmp/vless-fixture-ca.pem")) {
                ca = CertificateFactory.getInstance("X.509").generateCertificate(input);
            }
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null);
            trust.setCertificateEntry("controlled-fixture", ca);
            TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(trust);
            SSLContext tls = SSLContext.getInstance("TLS"); tls.init(null, tm.getTrustManagers(), null);
            // Default path runs FIRST. A successful explicitly bound probe cannot warm
            // DNS before or replace the default-route result required by the strict gate.
            JSONObject normal = measure(null, tls.getSocketFactory());
            result.put("stage", normal.optString("stage", "NONE"));
            result.put("address", normal.optString("address", ""));
            result.put("code", normal.optInt("code", -1));
            result.put("failure", normal.optString("failure", "OTHER"));
            result.put("default_network_same", network.equals(cm.getActiveNetwork()));
            result.put("pinned", measure(network, tls.getSocketFactory()));
            NetworkCapabilities after = cm.getNetworkCapabilities(network);
            result.put("pinned_network_still_vpn", after != null && after.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
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
    private JSONObject measure(Network network, SSLSocketFactory tls) {
        AtomicReference<String> stage = new AtomicReference<>("DNS");
        AtomicReference<String> address = new AtomicReference<>("");
        AtomicReference<Socket> active = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        FutureTask<JSONObject> task = new FutureTask<>(() -> {
            try {
                InetAddress resolved = network == null ? InetAddress.getByName("fixture.test") : network.getByName("fixture.test");
                if (!"198.18.0.1".equals(resolved.getHostAddress())) throw new IOException("Unexpected fixture DNS answer");
                address.set("198.18.0.1");
                if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                try (Socket raw = new Socket()) {
                    active.set(raw);
                    if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    if (network != null) { stage.set("NETWORK_BIND"); network.bindSocket(raw); }
                    stage.set("TCP_CONNECT");
                    raw.connect(new InetSocketAddress(resolved, 18443), 15000); raw.setSoTimeout(15000);
                    try (SSLSocket socket = (SSLSocket) tls.createSocket(raw, "fixture.test", 18443, true)) {
                        socket.setSoTimeout(15000);
                        SSLParameters params = socket.getSSLParameters(); params.setEndpointIdentificationAlgorithm("HTTPS");
                        params.setServerNames(Collections.singletonList(new SNIHostName("fixture.test"))); socket.setSSLParameters(params);
                        stage.set("TLS_HANDSHAKE"); socket.startHandshake(); stage.set("HTTP_STATUS");
                        socket.getOutputStream().write("GET /generate_204 HTTP/1.1\r\nHost: fixture.test\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"));
                        socket.getOutputStream().flush();
                        StringBuilder line = new StringBuilder(); InputStream in = socket.getInputStream();
                        while (line.length() < 512) { int b = in.read(); if (b < 0) throw new EOFException(); if (b == 10) break; if (b != 13) line.append((char) b); }
                        if (!line.toString().matches("HTTP/1\\.[01] 204(?: .*|)")) throw new IOException("Unexpected HTTP status");
                        return outcome(stage.get(), address.get(), 204, "NONE");
                    }
                }
            } catch (Exception error) { return outcome(stage.get(), address.get(), -1, error.getClass().getSimpleName()); }
        });
        Thread worker = new Thread(task, "controlled-route-probe"); worker.setDaemon(true); worker.start();
        try { return task.get(20, TimeUnit.SECONDS); }
        catch (TimeoutException timeout) { return outcome(stage.get(), address.get(), -1, "DEADLINE"); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return outcome(stage.get(), address.get(), -1, "InterruptedException");
        } catch (Exception error) { return outcome(stage.get(), address.get(), -1, "OTHER"); }
        finally {
            cancelled.set(true); task.cancel(true);
            Socket socket = active.getAndSet(null);
            if (socket != null) try { socket.close(); } catch (IOException ignored) { }
            // Android's system DNS resolver may finish later; helper force-stop owns
            // the remaining daemon worker. This is not a production DNS-cancel API.
        }
    }
    private JSONObject outcome(String stage, String address, int code, String failure) {
        JSONObject value = new JSONObject();
        try { value.put("stage", stage); value.put("address", address); value.put("code", code); value.put("failure", failure); }
        catch (Exception ignored) { }
        return value;
    }
}

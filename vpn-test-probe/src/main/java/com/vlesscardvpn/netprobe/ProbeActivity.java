package com.vlesscardvpn.netprobe;

import android.app.Activity;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;

/** Test-only app with its own UID: its traffic must go through the VPN. Fetches the given URLs and writes result.json. */
public final class ProbeActivity extends Activity {
    private static final java.util.concurrent.atomic.AtomicLong RUNS = new java.util.concurrent.atomic.AtomicLong();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        final String urls = getIntent().getStringExtra("urls");
        final String tag = getIntent().getStringExtra("tag");
        new Thread(() -> probe(urls == null ? "" : urls, tag == null ? "" : tag), "probe").start();
    }

    private void probe(String urls, String tag) {
        JSONObject result = new JSONObject();
        long run = RUNS.incrementAndGet();
        try {
            result.put("tag", tag);
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            boolean vpn = false;
            long until = SystemClock.elapsedRealtime() + 8000;
            while (SystemClock.elapsedRealtime() < until) {
                Network n = cm.getActiveNetwork();
                NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) { vpn = true; break; }
                Thread.sleep(100);
            }
            result.put("vpn", vpn);
            try { result.put("dns", InetAddress.getByName("example.com").getHostAddress()); } catch (Exception e) { result.put("dns", "FAIL " + ProbeFailure.type(e)); }
            JSONArray out = new JSONArray();
            int index = 0;
            for (String u : urls.split(",")) {
                if (u.isEmpty()) continue;
                JSONObject r = new JSONObject(); r.put("url", u);
                int request = index++;
                long t0 = SystemClock.elapsedRealtime();
                r.put("request_index", request); r.put("run_id", run);
                r.put("started_wall_ms", System.currentTimeMillis()); r.put("started_elapsed_ms", t0);
                android.util.Log.i("E2E", ProbeTimeline.event(run, request, "begin", "url", System.currentTimeMillis(), t0));
                String phase = "url";
                long received = 0;
                try {
                    URL current = new URL(u);
                    JSONArray redirects = new JSONArray();
                    for (int hop = 0; ; hop++) {
                        if (!"https".equalsIgnoreCase(current.getProtocol())) throw new Exception("Refused HTTPS downgrade");
                        HttpURLConnection h = (HttpURLConnection) current.openConnection();
                        try {
                            h.setConnectTimeout(15000); h.setReadTimeout(20000); h.setInstanceFollowRedirects(false);
                            phase = "connect_headers";
                            int code = h.getResponseCode();
                            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                                phase = "redirect";
                                String location = h.getHeaderField("Location");
                                if (location == null || hop >= 5) throw new Exception("Missing Location or redirect limit");
                                URL next = new URL(current, location);
                                redirects.put(next.toString()); current = next; continue;
                            }
                            phase = "body";
                            long bytes = 0;
                            try (InputStream in = code < 400 ? h.getInputStream() : h.getErrorStream()) {
                                byte[] buf = new byte[16384]; int k;
                                while (in != null && bytes < 2 * 1024 * 1024 && (k = in.read(buf)) > 0) { bytes += k; received += k; }
                            }
                            r.put("code", code); r.put("bytes", bytes);
                            r.put("finalUrl", current.toString()); r.put("redirects", redirects);
                            break;
                        } finally { h.disconnect(); }
                    }
                } catch (Exception e) {
                    r.put("code", -1); r.put("error", ProbeFailure.type(e));
                    r.put("failure_phase", phase); r.put("failure_reason", ProbeFailure.reason(e));
                    r.put("causes", new JSONArray(java.util.Arrays.asList(ProbeFailure.causes(e))));
                    r.put("body_bytes_before_error", received);
                }
                long ended = SystemClock.elapsedRealtime();
                r.put("ended_wall_ms", System.currentTimeMillis()); r.put("ended_elapsed_ms", ended);
                android.util.Log.i("E2E", ProbeTimeline.event(run, request, r.optInt("code", -1) < 0 ? "error" : "complete", phase,
                    r.getLong("ended_wall_ms"), ended));
                r.put("ms", ended - t0);
                out.put(r);
            }
            result.put("results", out);
        } catch (Exception e) {
            try { result.put("error", ProbeFailure.type(e)); } catch (Exception ignored) { }
        } finally {
            try {
                File tmp = new File(getFilesDir(), "result.tmp");
                try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(result.toString().getBytes("UTF-8")); }
                tmp.renameTo(new File(getFilesDir(), "result.json"));
            } catch (Exception ignored) { }
            runOnUiThread(this::finish);
        }
    }
}

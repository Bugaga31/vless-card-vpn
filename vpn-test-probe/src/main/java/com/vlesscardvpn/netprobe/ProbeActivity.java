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
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        final String urls = getIntent().getStringExtra("urls");
        final String tag = getIntent().getStringExtra("tag");
        new Thread(() -> probe(urls == null ? "" : urls, tag == null ? "" : tag), "probe").start();
    }

    private void probe(String urls, String tag) {
        JSONObject result = new JSONObject();
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
            try { result.put("dns", InetAddress.getByName("example.com").getHostAddress()); } catch (Exception e) { result.put("dns", "FAIL " + e.getClass().getSimpleName()); }
            JSONArray out = new JSONArray();
            for (String u : urls.split(",")) {
                if (u.isEmpty()) continue;
                JSONObject r = new JSONObject(); r.put("url", u);
                long t0 = SystemClock.elapsedRealtime();
                try {
                    HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
                    h.setConnectTimeout(15000); h.setReadTimeout(20000);
                    int code = h.getResponseCode(); long bytes = 0;
                    try (InputStream in = code < 400 ? h.getInputStream() : h.getErrorStream()) {
                        byte[] buf = new byte[16384]; int k;
                        while (in != null && (k = in.read(buf)) > 0) bytes += k;
                    }
                    r.put("code", code); r.put("bytes", bytes);
                } catch (Exception e) { r.put("code", -1); r.put("error", e.getClass().getSimpleName() + ": " + e.getMessage()); }
                r.put("ms", SystemClock.elapsedRealtime() - t0);
                out.put(r);
            }
            result.put("results", out);
        } catch (Exception e) {
            try { result.put("error", e.toString()); } catch (Exception ignored) { }
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

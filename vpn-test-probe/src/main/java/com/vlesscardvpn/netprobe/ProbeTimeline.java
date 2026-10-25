package com.vlesscardvpn.netprobe;

/** Test-only request timeline. No URLs, headers, route names, payloads or exception messages. */
final class ProbeTimeline {
    static String event(long run, int index, String event, String phase, long wall, long elapsed) {
        String action = "begin".equals(event) || "complete".equals(event) || "error".equals(event) ? event : "other";
        String stage = "url".equals(phase) || "connect_headers".equals(phase) || "redirect".equals(phase) || "body".equals(phase) ? phase : "other";
        return "probe run=" + Math.max(0, run) + " request=" + Math.max(0, Math.min(index, 99)) + " event=" + action +
            " phase=" + stage + " wall_ms=" + Math.max(0, wall) + " elapsed_ms=" + Math.max(0, elapsed);
    }
}

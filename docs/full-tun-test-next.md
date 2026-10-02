# Expanded emulator route checks

This change adds controlled tests, not a user APK or a universal bypass guarantee.

The existing API 26 / API 33 workflow now requires exactly 28 instrumented checks (previously 19). Four added outbound checks cover a live REALITY + Vision server, rejected REALITY keys, rejected VLESS UUIDs, and rejected inner HTTPS hostname mismatches. Five added full-TUN tests cover compatible TLS, core TLS fragmentation and the three ByeDPI presets. Each full-TUN test opens a real Android VpnService TUN twice, and requires a separate test-only application UID to observe the VPN network, resolve fixture.test through tunnel DNS and obtain HTTP 204 over hostname-verified HTTPS to 198.18.0.1.

The helper module is separate from the VPN APK, marked testOnly, accepts only a nonce and fixed controlled endpoints, and is never a release dependency. VPN permission is automated only on an emulator. Test certificates and the DNS fixture do not change production trust. Generated private fixture keys are ephemeral and not uploaded.

Limits: clean native-core stop/reopen is not a simulation of Wi-Fi-to-cellular recovery, UI Auto selection or a real operator outage. Controlled HTTPS is not YouTube playback, Telegram MTProto, a Russian-ISP test, an ARM-device test, a packet-shape assessment or proof that public subscriptions work. Production bootstrap DNS, slow mobile-route timeout policy, device power management and user-network diagnostics still require separate investigation.

Local checks: JVM regressions passed; the new instrumentation sources and helper APK compiled. The host fixture passed controlled HTTPS, UDP DNS and a REALITY/Vision smoke test. Android execution results are recorded only after CI finishes; adding or compiling a test is not a passed Android test.

First Android run: the five new TUN tests failed because their unregistered VpnService subclass was rejected by Android Builder. The fixture now uses the manifest-declared VlessVpnService class with a test-attached ContextWrapper. This does not exercise production onStartCommand / Auto UI lifecycle. A new matrix run is required.

Second run https://github.com/Bugaga31/vless-card-vpn/actions/runs/37012092866: API 33 passed all 28 tests. API 26 failed five full-TUN HTTPS checks with SocketTimeoutException after successful tunnel DNS. A sixth full-TUN test now compares the gVisor stack with the existing mixed stack; helper diagnostics identify DNS / TCP connect / TLS / HTTP stages. The gate increases to 29 tests and remains strict; failures are not skipped. No production stack change has been made on this evidence alone.

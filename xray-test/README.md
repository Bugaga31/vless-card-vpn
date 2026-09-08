# VLESS Card Test — v2ray candidate

EXPERIMENTAL, not yet compiled or device-validated. This is not an update for the sing-box app.
Application ID: `com.vlesscardvpn.xraytest`. Existing app/data stay untouched.

## Build
`./gradlew :xray-test:testDebugUnitTest :xray-test:assembleDebug`
Output: `xray-test/build/outputs/apk/debug/xray-test-debug.apk`.
Requires Android SDK 34/JDK 17, Go, gomobile and Android NDK for the core build.
`prepareV2ray` builds `libv2ray.aar` locally from v2fly/v2ray-core v4.45.2 + tun2socks
via `v2ray-mobile/build-aar.sh` (no prebuilt binary download).
No libbox dependency is included in this module/process. The TUN fd is forwarded by
tun2socks into the core's loopback SOCKS inbound.

## First device test
1. Install beside the existing application. Only one Android VPN can be active at a time.
2. Paste 1–50 own VLESS links, one per line. No automatic public sources or seeded profiles.
3. Enable consent for target probes. For first connection select MANUAL and one known-working server.
4. Grant VPN permission. Check an external browser, Telegram and YouTube, then disconnect/reconnect.
5. Enable AUTO with multiple imported profiles; test a dead first node and a live second node.
6. Repeat with Wi-Fi/mobile handover, background/foreground and cancellation during probing.
7. After a crash reopen → Report → Copy. Review before sharing. Native crashes may only show a numeric Android exit reason (Android 11+).

## Actual scope and limitations
- Minimal Private Lounge test UI, not all original screens.
- VLESS TLS over TCP/raw, WS, gRPC; REALITY/XTLS Vision are NOT supported by v2ray-core
  and are rejected on import, no silent conversion.
- Input profiles live only in process memory. They must be imported again after process death.
- Auto START iterates all imported profiles until both HTTPS targets pass at least 2/3 samples.
- Background monitoring belongs to the VPN service, not Compose. Three failed rounds and at least 60s before switching. No favorites in this test module. No persistence/restart/autostart on boot.
- Telegram WEBSITE and YouTube generate_204 checks via explicit loopback SOCKS, not messenger/video tests and not default-route pings. Median and sample count in report.
- The app is excluded from VPN to prevent recursion; all other apps use the IPv4/IPv6 TUN. DNS requests from them are routed through the sole proxy outbound. Server-hostname bootstrap may use system DNS.
- No direct fallback outbound. During switching the TUN stays open, but this is NOT a kill switch: after stopping/failure/OS termination Android may restore direct traffic. Do not rely on this test build for sensitive activity.
- Native crashes, TUN forwarding and device compatibility cannot be validated by pure JVM tests. No claim that the original crash or old auto mode is fixed.
- Failed native startup aborts rather than retrying partially initialized core state.
- Reports contain controlled stages and exception classes/stack locations, no original links or remote error messages. No automatic telemetry.

## Known old auto-mode mismatch
Legacy AutoPilotEngine monitors an already CONNECTED session and returns otherwise; it did not implement initial automatic connection. Its enable/consent state is spread across two preference files and its lifetime is tied to UI. The experimental service has an explicit initial selection loop and a single per-session consent flag. Legacy production code is unchanged.

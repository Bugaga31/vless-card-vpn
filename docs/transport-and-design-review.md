# Transport and design review

## What changed now

- Three **static design concepts**, not a working HTML VPN: Quiet (light, explicit manual connect + Auto), Night (dark with compact diagnostics/navigation), Route (mode selection and readable stages). The sample server and counts are illustrative, not live measurements. Open `design-options/*.html` locally; no external dependencies, analytics or subscription URLs.
- The current Compose home screen now offers an expandable network diagnostics panel after a connection error or partial access. Opening the panel does **not** send a report, probe a site or restart the tunnel. The existing crash-report action is retained. Network diagnostics are not offered when storage is locked.
- The home instrumentation smoke case exercises expanding/collapsing the panel and retaining crash reports; execution results must come from Android CI, not from compilation alone.
- The host TLS check now includes a negative hostname case with SNI_EDGES. It must reject the certificate with hostname-mismatch error, not merely fail by timeout, and still observe three ClientHello records.

## Competitor evidence, not a security audit

### qWDTT / proxy-turn-vk-android

Reviewed commit `a296c57eaba69bb9479a24f9157856490890e47d`:
- [README](https://github.com/SpaceNeuroX/proxy-turn-vk-android/blob/a296c57eaba69bb9479a24f9157856490890e47d/README.md) describes Android VPN + its own VPS + TURN transport.
- [obfs.go](https://github.com/SpaceNeuroX/proxy-turn-vk-android/blob/a296c57eaba69bb9479a24f9157856490890e47d/go_client/obfs.go) actually calls ChaCha20-Poly1305 and builds an RTP-like outer header, padding and replay-window state. Its comments claiming indistinguishability are **not** independent evidence of DPI resistance. This review does not establish nonce safety, forward secrecy or full protocol security.
- A server adapter and credentials are necessary; an APK-only switch cannot make an existing VLESS endpoint speak TURN. Use owned or explicitly authorized relays; do not extract or reuse embedded accounts from APKs.

### OpenFlux family

Reviewed server `da06e78a43520d5c3f320882d17bb680ec8168fa`, app `88865494e7e6705f54331e08a7d2da7933e9247d`:
- [Android README](https://github.com/wlruscfd/openflux-app/blob/88865494e7e6705f54331e08a7d2da7933e9247d/README.ru.md) documents Yandex Docs transport, TCP + DNS-over-TCP, no general UDP/QUIC, IPv6 interception/drop, and unsupported MAX Android path. These limitations matter for video and calls.
- [Server tunnel](https://github.com/wlruscfd/openflux-server/blob/da06e78a43520d5c3f320882d17bb680ec8168fa/tunnel/tunnel.go) distinguishes privileged raw forwarding from TCP proxy mode and reports the actual fallback mode. This is a useful design principle: show actual capabilities, not only requested settings.
- [Deploy](https://github.com/wlruscfd/openflux-deploy) supplies infrastructure; it is not an Android client library. No install scripts or remote servers were run here.
- [AntiNet module](https://github.com/wlruscfd/openflux-antinet-module) describes a host-managed module lifecycle/protected sockets. Native downloadable plugins need independent trust, signature, ABI and lifecycle review before inclusion.
- License boundaries require a file-level review. No competitor source or artwork was copied into the app in this change.

## APK review scope

Only static APK packaging metadata/library names were inspected. None of the supplied APKs was installed or executed; subscription assets, credentials and endpoint lists were not imported. Observed names include gojni/hev libraries in VlessForU, V2 Whitelist and Cyberportal; ByeDPI + hev in both ByeByeDPI versions; Flutter + libbox in Protasov. Library names alone do not establish the exact core version, routing behavior, encryption quality, or freedom from malicious code. The unrelated modified HTML builder was excluded.

## Next experiments (not implemented)

1. Capability-aware routing: explicit TCP/UDP/IPv6/DNS support and per-stage failure reasons, before any automatic selection. Never call an HTTP 204 video/MTProto success.
2. Own TURN testbed with an independently reviewed standard secure transport, correct certificate/key authentication, strict relay allowlist, packet/byte limits and no production account reuse. Test loss/reordering/MTU, Android protected sockets, reconnect and cancellation before exposing it in Auto.
3. Keep server protocol/SNI/keys immutable. Profile selection may change supported outer transmission behavior, not disable certificate validation.
4. Orbot with user-provided bridges remains a separate, explicit integration task. Android has one active VpnService per user/profile; simultaneous Orbot VPN and this VPN are not a design. Any Tor integration needs a deliberate proxy/TUN topology and lifecycle tests.

## Evidence boundaries

Loopback record tests are not a test on a Russian ISP, do not prove YouTube playback or Telegram MTProto, and are not a native ARM phone test. The earlier Android 13 CI passed 40 cases; Android 8 still had five DNS/TUN failures. Do not mark the whole app green or publish an "all networks" guarantee from the host test.

## Checks executed for this change

- `:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin -x buildByeDpi --no-daemon`: success, **241 JVM tests, 0 failures/errors/skips**. Native packaging was deliberately skipped because this sandbox lacks the configured NDK; this is not an APK build or device execution.
- `python -m unittest discover -s tools -p test_tls_record_observer.py -q`: **9 passed**.
- `bash tools/run-byedpi-host-check.sh`: TCP_ONLY -> HTTPS 204 / 1 ClientHello record; SNI_MIDDLE -> HTTPS 204 / 2 records; SNI_EDGES -> HTTPS 204 / 3 records; WRONG_HOST -> hostname verification rejected / 3 records.
- All three static designs rendered and visually inspected at 390px and 1440px. No horizontal or viewport overflow at the selected 1100px capture height. Principal text/accent contrast pairs exceed 4.5:1. The mockups are static; buttons are conceptual, not connected to Android behavior.
- Current Android instrumentation has compiled but **has not executed in this sandbox**. Strict Android 26/33 CI must still run against this commit.

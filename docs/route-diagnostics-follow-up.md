# Route context and Android network-monitor follow-up

## Changes

- Corrected the four-argument libbox default-interface callback: its last booleans are network cost/constrained flags, not IPv4/IPv6 presence. Metered state now comes from Android capabilities; constrained remains false when there is no equivalent platform signal. Address families continue to come from interface addresses. Contract reference: https://github.com/SagerNet/sing-box/blob/v1.13.4/experimental/libbox/platform.go and https://github.com/SagerNet/sing-box/blob/v1.11.0/experimental/libbox/monitor.go.
- A delayed loss callback for another network no longer clears a currently available replacement interface. Capability changes also update the current interface.
- A native core start cannot publish a connected state without an established Android TUN descriptor. This is an activation invariant, not proof that all applications pass traffic.
- Health checks retain one atomic session snapshot (proxy/profile/ByeDPI preset); cleanup of an old proxy cannot clear a replacement session. ServiceReachability pins one proxy for all six samples instead of mixing servers during reconnect.
- HTTPS diagnostics retain the actual failing stage and measured elapsed time, including deadlines at SOCKS, TLS handshake and HTTP response. Refused local TCP is distinguished from SOCKS failure. Context is enum-only: no added URLs, IDs, exception strings or credentials.
- The journal adds a ninth stage column and reads old eight-column rows with an explicit unknown stage NONE. It still rejects arbitrary injected strings.
- The controlled full-TUN fixture reports bounded native-log counters and helper failure stages. These are log counters, not wire-packet capture or proof of DPI evasion. The fixture's verbose native level is not applied to production configuration.

## Verification

Local: 224 JVM tests, zero failures/errors/skips; Android instrumentation sources and the separate test-only helper compiled successfully. New coverage includes legacy journal rows, stage injection rejection, old-session cleanup, exact profile/preset attribution, refused listener, TLS timeout and HTTP timeout.

The strict Android gate increases from 29 to 31 cases with two live platform callback checks. A new API 26 / API 33 run must verify this source commit. Prior green Android 13 results are not a result for this patch. API 26 full-TUN timeouts remain unresolved until a new executed run demonstrates otherwise; changing network flags is not assumed to fix them.

No main merge, new signing key, user APK or release is produced by this change. Lab 1.0.49 already installed on a phone does not receive source-only fixes. Realme ARM hardware, Russian-ISP behavior, YouTube video, Telegram MTProto and foreground-service/Auto UI lifecycle remain outside these controlled tests.

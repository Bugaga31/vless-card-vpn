# SNI-position TCP/TLS splitting profiles (experimental)

These are new profiles for this application, not a newly invented cipher. They use existing position syntax in the pinned MIT-licensed ByeDPI source (`ba532298de7b28cfe854aea83d061369d13ca290`). Upstream contract: https://github.com/hufrea/byedpi/blob/ba532298de7b28cfe854aea83d061369d13ca290/main.c and `desync.c`.

## Profiles

- `SNI_MIDDLE`: `--split 0+sm --tlsrec 0+sm`, relative to the middle of the existing SNI.
- `SNI_EDGES`: `--split 1+s --split -1+se --tlsrec 1+s --tlsrec -1+se`, near both edges of the existing SNI.

Actual SNI, credentials, certificate verification, REALITY public keys and encrypted VPN routing are preserved. No fake SNI, TLS downgrade, direct fallback, new cipher or Tor bridges were added. Auto enumerates all five ByeDPI presets with unchanged node/attempt/time limits. A remembered preset is rechecked, never accepted as proof of current connectivity.

## Executed verification

Source: `1b8648205a09e4e583b8580f32c627b28550c5db`.
Android CI: https://github.com/Bugaga31/vless-card-vpn/actions/runs/37037069233

- Local: 236 JVM tests, zero failures/errors/skips; Android instrumentation compiled successfully.
- Nine observer unit tests cover TCP chunking, split handshake headers, incomplete/invalid framing, payload disposal and bounded metadata.
- Reproducible native-C host check: `bash tools/run-byedpi-host-check.sh`. HTTPS 204 with certificate/hostname validation; actual ClientHello framing was one record for TCP_ONLY, two for SNI_MIDDLE and three for SNI_EDGES. This is Linux/OpenSSL evidence, not Android or ISP evidence. Host checks also ran in CI before Android instrumentation.
- Android 13 / API 33: **39 executed, 38 passed, 1 failed**. All six new checks passed: observed VLESS/TLS and REALITY/Vision framing for both new profiles and both full-TUN/DNS/reopen cases. The existing compatible TLS test also verified its single-record baseline. The failure was the older TCP_ONLY full-TUN case: DNS/UnknownHostException on cycle 1 after restart. Its earlier baseline passed, so there is an observed intermittent/reopen problem. The cause is not established; do not dismiss it as harmless flakiness.
- Android 8 / API 26: **39 executed, 31 passed, 8 failed**. All four new authenticated-outbound/record-observation cases passed. All eight full-TUN cases failed, including both new ones, mostly at TCP connect and some at DNS. This does not establish usability on Android 8.
- Both matrix jobs are red. The strict gate still requires all 39 cases; no failures/skips were hidden or removed.

## Observer and evidence limits

Loopback-only observers relay bytes unchanged ahead of the controlled VLESS/TLS and REALITY listeners. They publish a bounded ring of sequence/record/byte counts, never payloads, SNI contents or keys. Their independent HTTPS control channel uses the temporary fixture CA with hostname validation. Production trust is unchanged.

The observer checks TLS record framing, not TCP packet boundaries or DPI effectiveness. Passing controlled tests does not prove Russian-provider access, YouTube playback, Telegram MTProto, real ARM phone behavior, foreground-service/Auto UI correctness or universal bypass.

Previous source baseline: https://github.com/Bugaga31/vless-card-vpn/actions/runs/37034985063 (`6d60fad0e7d8b3c081a75f01f5d1bd099b657df7`). API 33 passed all 33 cases; API 26 had six full-TUN failures. The callback test's API-compatibility exception no longer occurred. Those older results are not new-profile evidence.

## Release gate

No main merge, APK release or signing-key changes. Profiles remain experimental. Resolve the Android 13 reopen failure and Android 8 full-TUN failures; do not disable certificate checks or weaken the gate to ship.

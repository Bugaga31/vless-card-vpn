# SNI-position TCP/TLS splitting profiles (experimental)

These are two **new profiles for this application**, not a newly invented encryption algorithm. They use existing position syntax in the pinned, MIT-licensed ByeDPI source (`ba532298de7b28cfe854aea83d061369d13ca290`). Upstream contract: https://github.com/hufrea/byedpi/blob/ba532298de7b28cfe854aea83d061369d13ca290/main.c and `desync.c`.

## Profiles

- `SNI_MIDDLE`: `--split 0+sm --tlsrec 0+sm`. Split relative to the middle of the existing SNI.
- `SNI_EDGES`: `--split 1+s --split -1+se --tlsrec 1+s --tlsrec -1+se`. Split near both edges of the existing SNI; create three ClientHello TLS records when applied successfully.

The actual hostname/SNI, credentials, TLS certificate verification, REALITY public key and encrypted VPN routing are not replaced. No fake SNI, TLS-version downgrade, plaintext/direct fallback, new cipher or Tor bridge implementation is added.

Auto enumerates all five ByeDPI presets instead of a hard-coded three. The node/attempt/time limits stay unchanged. A remembered verified preset can be tried first; successful port pings or remembered profiles alone are still insufficient to mark a route connected.

## Tests and evidence

Local executed:
- 236 JVM tests, zero failures/errors/skips; Android instrumentation compiled.
- Nine pure-Python observer tests: arbitrary TCP chunking, split handshake headers, incomplete/invalid inputs, payload disposal, bounded metadata ring.
- Reproducible actual native-C host test (`bash tools/run-byedpi-host-check.sh`): TLS with certificate and hostname verification, successful HTTPS 204. A byte-for-byte local relay observed one ClientHello record for TCP_ONLY, two for SNI_MIDDLE and three for SNI_EDGES. This is Linux/OpenSSL evidence, not Android/uTLS/REALITY or ISP evidence.

The fixture now has independent loopback-only record observers ahead of the VLESS/TLS and REALITY listeners. It emits only bounded sequence/record/byte counts, not ClientHello contents, keys, hostnames or application payloads. The control endpoint uses HTTPS with the temporary fixture CA and hostname validation. Production certificate trust is unchanged. Observer reads **TLS record framing**; it cannot prove TCP packet segmentation or DPI evasion.

Six Android checks were added: two observed VLESS/TLS cases, two observed REALITY/Vision cases and two full-TUN/DNS/reopen cases. The existing compatible TLS case also checks the single-record baseline. The strict gate increases from 33 to 39 cases and rejects failures/skips; execution of this patch is pending CI.

Previous source baseline (`6d60fad0e7d8b3c081a75f01f5d1bd099b657df7`): https://github.com/Bugaga31/vless-card-vpn/actions/runs/37034985063. API 33 passed all 33 cases. API 26 executed 33 with six full-TUN failures; the callback test's previous API-compatibility exception no longer occurred. That old green API 33 run does not validate these new profiles.

## Release gate

No main merge or APK release. Android 8 full-TUN failures remain unresolved. No real ARM phone, Russian provider, YouTube playback, Telegram MTProto or universal/perfect bypass claim. A new profile that fails native validation must be corrected or withheld, not rescued by disabling security checks.

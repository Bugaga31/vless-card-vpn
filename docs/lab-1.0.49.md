# Lab 1.0.49 — diagnostic test build

Separate package `com.vlesscardvpn.lab`, label **VLESS Card · Lab**. It installs alongside Auto. Do not uninstall Auto or erase its data. Import your original subscription/configuration explicitly into Lab; Lab cannot read the other app's private storage. Android permits only one active VPN service, so stop the other VPN before testing.

## Changes
- Last 200 network events in private no-backup storage. Only enums/numeric fields: phase, target category, profile, preset, HTTP code, latency, failure stage. No addresses, UUIDs, keys, subscription URLs, SSID, server IDs, raw exception strings, traffic bodies or cookies. Bounded storage survives restart; clearing is explicit.
- Settings → **Диагностика сети** → **Проверить подключение** / **Журнал и отправка отчёта**. HTTPS probes use the authenticated selected native outbound, never a direct fallback. Review/share is a user action; no automatic GitHub upload.
- Proxy-connect, TLS, HTTP and timeout failure stages. Not packet capture, native tombstones, complete DNS diagnosis or video playback measurement.
- Retains standard AES-256-GCM/Android Keystore configuration protection, WS/gRPC parameter persistence, bounded Auto and three actual ByeDPI presets from preceding source iterations. Encryption at rest is not network camouflage.
- Local patch to pinned ByeDPI `mpool.c`: read `%jd` into `intmax_t`, check conversion to `time_t`, reject incomplete cache records/out-of-range prefix lengths, permit full-length IPv6 literals. ARM32 previously supplied a narrower pointer to the scan. The presets do not enable cache-file CLI options; this discovery does not establish the cause of the user's earlier native crash.

## Russian-network evidence — not APK testing
Independent **direct HTTPS** Globalping probes to public YouTube endpoints. Location/network metadata and TLS authorization are reported by Globalping. These requests did not run our APK or pass through the user's VPN nodes, and no private configuration or credential was supplied. An `eyeball-network` tag is not proof of the user's mobile carrier path.

Exact compact results: `docs/lab49-ru-probes.json`.

| Path | RU probe | HTTP/result | TLS authorized | Total ms |
| --- | --- | --- | --- | --- |
| `/generate_204` | Moscow / Timeweb | 204 | True | 72 |
| `/generate_204` | Moscow / Yandex.Cloud | failed | not established | — |
| `/generate_204` | Saint Petersburg / Selectel | 204 | True | 18 |
| `/generate_204` | Moscow / Timeweb | 204 | True | 59 |
| `/generate_204` | Tomsk / Limited Company Information and Consulting Agency | failed | not established | 15000 |
| `/generate_204` | Novosibirsk / MTS | failed | not established | — |
| `/` | Moscow / Timeweb | 200 | True | 101 |
| `/` | Moscow / Yandex.Cloud | failed | not established | — |
| `/` | Saint Petersburg / Selectel | 200 | True | 61 |

Sources:
- https://globalping.io/measurements/2wZez5kt0ER1OdKNJ00021F3y
- https://globalping.io/measurements/2Fuv7SD4MwLCOOa2X00021F42
- https://globalping.io/measurements/2a58iJ7u773Oy4RST00021F44

The YouTube homepage returned 200 from two RU datacenter networks. `/generate_204` returned 204 from three of six RU probes; other probes failed TLS establishment or timed out. HTTP 200/204 does not demonstrate video CDN throughput/playback, Telegram MTProto/calls, or that Auto works on the user's ISP. No universal “works in Russia” conclusion.

## Phone test
1. Install Lab beside Auto; keep old data. Stop the other VPN/Orbot VPN.
2. Import the original subscription/configuration. Migrations cannot recover WS/gRPC fields that were never saved by older versions.
3. Press Auto and wait for its bounded search. Record full/partial/failure status.
4. In YouTube play an actual video; in Telegram send a message and separately check calls if needed. Website checks do not replace these tests.
5. Settings → Диагностика сети → Проверить подключение → Журнал и отправка отчёта. Review/share explicitly. Test Wi-Fi and mobile separately; label the network type in your accompanying message, not by sharing SSID or private credentials.
6. If a native crash returns, also send the existing crash report; network logs cannot replace a native tombstone.

## Build/signing and limits
`qa/build-lab-release.sh` requires a dedicated private Lab key and password-file path. Keep signing material outside Git and backed up. Later updates must use the same key. Lab is a prerelease, not a main/stable Auto replacement. No new custom cryptographic algorithm or certificate-verification bypass. Tor with bridges and zapret are not integrated. Android realme end-to-end testing is pending the user test; no emulator/native Android execution was available in the build environment.

## Verification so far
210 JVM tests passed (0 failures/errors). Three Linux ByeDPI preset smoke tests preserved verified TLS/HTTPS 204 and checked TLS-record shape. The local cache-parser regression harness passed host ASan/UBSan. 36 Compose frames rendered; all eight new/changed frames and the settings bottom frame were individually inspected, the other 28 remain byte-identical to previously inspected frames. Device execution remains pending.

Final build: release APK assembled, Android Lint 0 errors / 41 warnings, ZIP integrity and zipalign pass, APK v2/v3 signatures verified, ARM64 and ARMv7 libbox/ByeDPI bundled. Non-debuggable package `com.vlesscardvpn.lab`, version 49 / 1.0.49.

APK SHA-256: `0958e03e24706bb6e3e7063dfa8ffbe115b1fe7076ff7138a646e0c6352a0e21`. Lab certificate SHA-256: `c845d8fcfdd25c951416623c9039e861f4f30b20094b40b345d3c1b4ef300969`. Signature/package checks do not replace device installation/connectivity testing.

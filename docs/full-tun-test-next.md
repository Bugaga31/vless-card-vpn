# Full Android TUN and REALITY fixture results

Source commit: cac189f29a53e240e54b578ae5ce562976509fd3
Run: https://github.com/Bugaga31/vless-card-vpn/actions/runs/37013625861

## Verified results

| Environment | Result |
| --- | --- |
| Local JVM regression suite | 217 tests, zero failures/errors |
| Android API 33, x86_64 Google APIs emulator, KVM | 29/29 instrumented tests passed |
| Android API 26, x86_64 Google APIs emulator, KVM | 29 ran, six full-TUN tests failed; matrix gate is red |

The strict gate requires the exact test count, successful instrumentation, no skipped tests and no failed tests. A failed matrix is not a release-ready result. No production networking change or new user APK was published in this work.

## Current gate (read from HEAD, not from the table above)

The numbers in the table are from the historical source commit. The gate that CI enforces today is defined in `tools/run-native-android-check.sh`: it requires `OK (57 tests)` (1.0.54: +7 ByeDPI strategy full-TUN cases), instrumentation exit code 0 and no failure markers. Always take the expected count from that script on the SHA being checked; never lower it to make CI green.

On API 33 the current matrix runs three times; on API 26 once. On commit c0875356 API 33 x3 passed and API 26 failed, consistent with issue #7. On 2026-10-04 API 33 attempts are intermittently red even on unchanged baseline code (1/3 green on the main-equivalent revert); the job log annotation is required to classify these.

## API 26 diagnostic path

When the gate fails, the script publishes a public check annotation (`Native Android fixture assertions`) with assertion summaries only. For full-TUN cases each message already carries:

- probe stage/failure reported by the separate-UID helper (DNS vs TCP_CONNECT vs TLS/HTTP);
- native counters `coreMsgs`, `tunTcp`, `tunUdp`, `proxyTcp` (counts only, no addresses);
- the pinned-network comparison (`defaultSame`, `dnsCount`, `routes`, `mtu`, pinned stage/failure/code).

Interpretation to apply before any code change:

1. `tunTcp=0` with DNS resolved: SYN from the helper never reached the core TUN inbound (routing/TUN/emulator), not an outbound problem.
2. `tunTcp>0`, `proxyTcp=0`: the core accepted the TUN connection but did not open the VLESS outbound (route/stack issue).
3. `proxyTcp>0` with TCP_CONNECT timeout: the outbound was dialed; investigate the fixture backend and emulator host path.
4. DNS assertion failures with `tunUdp=0`: UDP DNS did not enter the TUN.

Only after the annotation for a specific SHA is read and classified should a targeted diagnostic or fix be added. Emulator results are not transferred to a physical ARM device.

## What is exercised

- Real JNI configuration and platform callbacks, Room/Android Keystore migration and metadata update regressions.
- Controlled VLESS/TLS outbound HTTPS for compatible TLS, core TLS fragmentation and three actual ByeDPI presets.
- Live VLESS/REALITY + Vision, rejected wrong REALITY keys, rejected wrong VLESS UUIDs, rejected inner HTTPS hostname mismatch, and HTTP redirect rejection.
- Six actual Android TUN cases: the above five profiles using the existing mixed stack, plus compatible TLS using gVisor. Each successful case must open and cleanly re-open a real VpnService TUN, and a separate application UID must observe VPN transport, resolve fixture.test through tunnel DNS and obtain HTTP 204 over hostname-verified HTTPS to 198.18.0.1. On API 33 both cycles passed for each case.

The helper is a separate test-only application, never a VPN release dependency. It accepts only a nonce and fixed controlled endpoints, not user URLs or secrets. Test CA trust and controlled DNS are injected into fixtures only, not production settings. All host listeners bind loopback and reject unknown destinations. Fresh fixture private keys are temporary and not uploaded.

## Unresolved API 26 result

Four mixed-stack cases resolved DNS but timed out at TCP_CONNECT. The mixed combined-ByeDPI case and the gVisor comparison failed the DNS assertion in the final run. An earlier run had TCP timeouts for all five mixed-stack cases. Switching stacks alone is therefore NOT a demonstrated fix and has not been applied to production. The investigation needs native TUN packet/lifecycle diagnostics and must distinguish a harness/emulator limitation from a shipping-device defect.

The first experiment also revealed a harness error: Android Builder rejected an unregistered VpnService subclass. The corrected fixture uses the manifest-declared VlessVpnService class with a test-attached public SDK ContextWrapper. It does not test production onCreate/onStartCommand, foreground-service lifecycle or Auto UI actions.

## Boundaries

This is not an ARM Realme test, Russian-ISP test, YouTube playback test, Telegram MTProto/call test, DPI evasion assessment, Wi-Fi-to-cellular recovery test or proof that public subscriptions work. Clean native-core stop/re-open is not a real network-outage simulation. Production bootstrap DNS, slow-route timeout policy, Auto UI/service integration, device power management and real-user network failures remain separate investigation targets. No claim of perfect camouflage, universal connectivity or new cryptography is made.

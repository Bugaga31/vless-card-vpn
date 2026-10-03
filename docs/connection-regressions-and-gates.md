# Connection regressions and stricter Android gates

## Reproduced before fixing

Two new regressions were executed against source `67012af0125247e49c5e02a63948aed90b01bd76` and failed as expected:

1. Saved Auto quota: `plan(...).take(6)` consumed all six saved attempts on ordinary TLS for different nodes. Supported alternate profiles were postponed until after public-source loading.
2. Truncated HTTP status: a response line beginning `HTTP/1.1 204` but exceeding the 512-character bound was accepted without a terminating newline. A truncated response is not a confirmed HTTP response.

## Production changes

- Auto passes the remaining attempt quota into the planner. The six-attempt saved pool now reserves three compatible choices across two prioritized nodes; the full 36-attempt plan still covers up to 12 nodes. Remembered profiles/favorites and the overall 60-second deadline remain; zero/exhausted quota produces no extra attempts. This improves profile coverage, not server availability.
- The HTTPS parser requires the bounded status line to terminate before accepting a code. Redirects, wrong hostname and unauthenticated SOCKS remain failures. No certificate checks or keys/SNI were weakened.
- Per-probe failure/stage survive into the dashboard, with labels such as timeout TLS, timeout SOCKS, HTTP 403 or no result. Status copy uses enum/code fields only, not raw exception text or addresses. Failed probes cannot count as success even if contradictory metadata carries a matching HTTP code.
- Actual Compose home is more compact, with status/icon on one row and separate manual/Auto buttons. Buttons stack at enlarged font scale/narrow width. Missing check results remain neutral rather than being called a service error. The first row is explicitly the control HTTPS endpoint, not an aggregate all-internet verdict.

## Stronger test coverage

- Cancellation during SOCKS greeting, authentication, remote CONNECT and TLS must close the actual socket. Deadline cases must preserve their stage.
- Six new home UI instrumented cases total cover controls, cancellation presentation, diagnostics expansion/collapse, distinct manual/Auto callbacks, TLS-stage display and enlarged text. Launcher startup is also included in the strict gate.
- Two emulator-only tests exercise the **real Android foreground VPN service**, not an attached adapter: manual connect and favorites-only Auto to a loopback TLS blackhole, wait for a real TLS record, cancel, require probe cleanup/service stop, then check that a late result cannot revive the session. They do not assert successful VPN access or invoke public configuration sources.
- Default and bound VPN comparisons use distinct fresh nonce-derived DNS names per cycle, with fixture-only wildcard certificates and zero TTL. The first mandatory default-path result remains first and is never replaced by a later success. Arbitrary DNS names are rejected by the loopback fixture. This isolates cache effects; it is not a production DNS fix or a guaranteed API 26 solution.
- Strict native runner now requires **49 executed Android cases**, no failures/skips, rather than 40. Existing failed DNS/TUN assertions were not removed or retried until success.

## Executed locally

- **258 JVM tests**, zero failures/errors/skips, including the two formerly failing regression tests and real-socket cancellation cases.
- Android instrumentation compiled; separate-UID helper debug APK assembled. The application native-packaging task was skipped locally because the sandbox lacks the configured NDK. No new user-installable APK was produced.
- **15 Python unit tests** passed (9 TLS-record observer + 6 controlled DNS).
- Native ByeDPI host test: verified HTTPS 204 with 1/2/3 observed ClientHello records; wrong-hostname negative case rejected with 3 records.
- Fourteen relevant Compose screenshot states visually inspected, including dark/light, loading, manual connect, partial access, locked storage, small screen and 1.5x text. No overlapping controls; scrolling is intentional on longer states.

## Latest executed Android baseline, before this patch

https://github.com/Bugaga31/vless-card-vpn/actions/runs/37130005553

- API 33 succeeded on its 40-case gate.
- API 26 failed with six DNS/TUN assertions. Some default-path failures contrasted with a successful bound VPN HTTPS response; others failed both paths. LinkProperties MTU=0 is not proof of actual kernel MTU=0. These failures remain blockers; changing colors or adding encryption does not establish a repair.

The expanded 49-case suite executed on commit `9e5a1cdbd69b724c2748df72f8b65a94562d9b6c`; see the follow-up below. It did not pass. No ARM phone, ISP-specific restrictions, YouTube video playback, Telegram MTProto/calls, network-handover or battery-management guarantee is claimed. There is no claim of universal/perfect camouflage. Competitor transports still require server-side implementation, licensing/security review and independent end-to-end tests.


## Follow-up: import compatibility and executed 49-case gate

### More reproduced defects

Three added regression tests failed before the importer changes:

- Missing VLESS `flow` was replaced with `xtls-rprx-vision`, changing the supplied configuration.
- An unsupported Xray transport such as `xhttp` was silently mapped to TCP.
- Xray VMess gRPC was mapped to TCP and lost its service name.

The importer now leaves absent VLESS flow empty, keeps unsupported transports visibly unsupported, preserves gRPC service names and H2 host/path for VLESS/VMess/Trojan, and avoids inventing a Reality SNI. Unsupported VLESS encryption is rejected, not silently downgraded to `none`. Manual core generation also rejects unknown transports/protocols. A common transport generator covers supported WS/gRPC/H2 for those three protocols; an empty gRPC service name is not replaced by an invented service.

Xray outbound JSON and base64 JSON now reach the actual paste/subscription parser. Only supported outbound profile data is imported: arbitrary inbounds/routing are not executed or applied. This is not support for every Xray option, XHTTP, sing-box JSON, Clash YAML or a new encryption protocol. JSON is bounded before recursive parsing (existing text limit, nesting 64, at most 1000 outbounds); malformed-parser messages do not echo the input.

### Executed Android result (not a release gate pass)

Run: https://github.com/Bugaga31/vless-card-vpn/actions/runs/37131614467

- Android 13/API 33: 49 executed, **3 failed**.
- Android 8/API 26: 49 executed, **9 failed**.
- Both actual foreground-service cancellation cases passed on both versions. These prove controlled startup/cancellation cleanup, not a successful live public-server route.
- Six API 26 default-path DNS/TUN assertions still failed. In several cases the bound VPN comparison passed while the required default path failed. They remain blockers; no fallback or retry-until-green replaced the default result.
- Three additional failures were in the test setup/assertions: the newly added `*.fixture.test` certificate now legitimately covers the former "wrong.fixture.test" negative hostname; two UI visibility assertions did not scroll to the target on the emulator. The negative hostname was changed to `wrong.fixture.invalid`, outside the certificate SAN, and UI checks now require the target to be displayed after scrolling. Launcher also explicitly asserts that its launch/recreation leaves the service disconnected and restores the original preference. Certificate verification is not disabled.

### Follow-up validation

- Local full JVM suite: **270 tests**, zero failures/errors/skips. Includes the 12 added import/transport regressions and prior cancellation/parser/Auto regressions.
- Android instrumentation compilation succeeds locally with native packaging skipped (configured NDK absent). This is not an installable release build.
- Added one instrumented native-schema case with 12 WS/gRPC/H2 configurations across VLESS/VMess/Trojan. Schema acceptance, once executed, is not an end-to-end transport test.
- Strict next Android gate is **50 cases**. The revised Android tests and new native-schema case require another CI execution; do not infer a green result from local compilation.

No universal network/phone compatibility, perfect camouflage, Russian ISP test, YouTube video or Telegram MTProto success is claimed. Current source remains on the test branch/draft PR; no new user APK or main-branch release is declared.


### Executed follow-up (50 cases, commit `8ad2625eae5353236bd8ecc7df899f7f97c85a15`)

https://github.com/Bugaga31/vless-card-vpn/actions/runs/37132709909

- API 33: 50 executed, **1 failed** (launcher UI expectation).
- API 26: 50 executed, **7 failed** (six DNS/TUN cases plus launcher UI expectation).
- Actual native transport-schema checks, the wrong-hostname TLS rejection and scrolled service-error UI assertion now passed on both versions. Schema validation is still not a live server-compatibility claim.
- The launcher state assertion confirms DISCONNECTED, but its home-label expectation did not find the node after recreation. The test had slept the instrumentation thread without advancing Compose's clock for the splash delay/animation. The next revision explicitly advances that UI clock and waits for idle; its outcome needs a fresh execution, not an assumed pass.
- API 26 DNS failures vary between profiles/cycles across runs. No production DNS repair or green overall gate is claimed.

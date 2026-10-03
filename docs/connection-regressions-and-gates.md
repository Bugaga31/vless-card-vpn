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

The expanded 49-case suite still needs execution against this patch. No ARM phone, ISP-specific restrictions, YouTube video playback, Telegram MTProto/calls, network-handover or battery-management guarantee is claimed. There is no claim of universal/perfect camouflage. Competitor transports still require server-side implementation, licensing/security review and independent end-to-end tests.

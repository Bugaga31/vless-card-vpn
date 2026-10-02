# Default versus explicitly selected Android VPN network

This changes controlled **test diagnostics**, not production VPN routing or user APK behavior.

The separate-UID helper now runs two verified HTTPS probes through the existing TUN:
1. Android's normal default DNS/socket path, first, preserving the previous required behavior.
2. DNS via the selected VPN Network and a socket bound to that same Network using public APIs.

There is no success fallback: the full-TUN fixture continues requiring default DNS/HTTPS and now also requires the default network to remain unchanged and the pinned-network comparison to succeed. It never binds to an underlying Wi-Fi/mobile network on failure. TLS fixture-CA and hostname validation remain enabled for both paths.

Results include phase/error/code for the pinned path, whether the default network changed, whether the selected network still advertises VPN, DNS/route counts and MTU. They do not export actual network IDs, DNS-server lists, routes, browsing data or exception messages. The only returned address is the expected controlled 198.18.0.1 answer.

Each probe has a 20-second caller deadline; cancellation closes its active socket and uses an explicit cancellation flag to prevent a late DNS completion from starting another connection. The system DNS resolver itself is not cancellable; any remaining helper daemon is owned by emulator force-stop. The fixture waits at most 60 seconds for the pair. These are test-only deadlines and do not increase production Auto timeouts.

Executed locally: 236 JVM tests, zero failures/errors/skips; updated Android instrumentation compiled and the separate helper APK assembled. No emulator/KVM is available in the local sandbox. The strict Android gate stays at 39 tests.

Executed first comparison source: `e4096a7b22a5134dc62055d309e95b61825a4667`.
CI: https://github.com/Bugaga31/vless-card-vpn/actions/runs/37051706890
- API 33: 39 tests executed, two full-TUN reopen failures. In both cases the default and pinned paths failed at the same stage. The default network remained unchanged, with one DNS server, four routes and MTU 1400. The gvisor case failed DNS in both paths; TLS_RECORD_ONLY failed TCP connect in both. Explicit binding alone does not fix these observed failures.
- API 26: stopped during the build/JVM step, before instrumented tests. Public annotations did not establish the reason. Detailed job logs require a separate browser sign-in; no failure cause was guessed and this is not a new measured TUN result.

Follow-up test changes (executed as source 3fe4bfb500d4446a5f062a66b0b7adf828a03141):
- Every reopen allocates a fresh local probe endpoint and starts/stops a fresh ByeDPI child where needed, matching production resource ownership rather than retaining a child/port across core instances.
- Teardown independently closes the service, command server, default-interface monitor and descriptor. Production already uses independent cleanup; this is fixture alignment, not a claimed production fix.
- All default, pinned, DNS, HTTPS, UID and reopen assertions remain enabled; no failure was removed or retried until success.
- Public JVM CI annotations now expose only failing class/method identifiers and totals, not exception messages, URLs, keys or captured output. A temporary controlled negative test verified the failure annotation and absence of its sentinel message; the temporary class was removed. The complete 236-case suite was rerun green and instrumentation compiled afterward.

Executed strengthened fixture: https://github.com/Bugaga31/vless-card-vpn/actions/runs/37054255092. API 33 passed all 39 cases. API 26 executed 39: the gvisor full-TUN case passed both default/bound paths and both reopen cycles; seven mixed-profile cases failed TCP on both paths. That is one measured result, not proof that resource isolation alone resolved every intermittent restart failure.

Subsequent narrow API 26 gvisor-default policy is recorded in `docs/api26-tun-stack-workaround.md`: run 37056542029 passed all 40 API 33 cases, but API 26 still had five DNS failures. Three of those normal-path DNS failures contrasted with a successful bound VPN HTTPS 204 result. The mandatory normal-path checks were kept; this is partial compatibility progress, not an all-device fix.

Prior blocker run: https://github.com/Bugaga31/vless-card-vpn/actions/runs/37037069233. API 33 had one default-path DNS failure on reopen; API 26 had eight full-TUN failures. New SNI profiles passed authenticated VLESS/TLS and REALITY record observation. A default/pinned contrast is evidence to investigate, not automatic proof of a kernel or framework defect.

No production fix, main merge or APK release is claimed by this diagnostics-only patch. Actual Auto UI/foreground-service lifecycle, ARM hardware and ISP behavior remain outside the fixture.

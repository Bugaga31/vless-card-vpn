# Default versus explicitly selected Android VPN network

This changes controlled **test diagnostics**, not production VPN routing or user APK behavior.

The separate-UID helper now runs two verified HTTPS probes through the existing TUN:
1. Android's normal default DNS/socket path, first, preserving the previous required behavior.
2. DNS via the selected VPN Network and a socket bound to that same Network using public APIs.

There is no success fallback: the full-TUN fixture continues requiring default DNS/HTTPS and now also requires the default network to remain unchanged and the pinned-network comparison to succeed. It never binds to an underlying Wi-Fi/mobile network on failure. TLS fixture-CA and hostname validation remain enabled for both paths.

Results include phase/error/code for the pinned path, whether the default network changed, whether the selected network still advertises VPN, DNS/route counts and MTU. They do not export actual network IDs, DNS-server lists, routes, browsing data or exception messages. The only returned address is the expected controlled 198.18.0.1 answer.

Each probe has a 20-second caller deadline; cancellation closes its active socket and uses an explicit cancellation flag to prevent a late DNS completion from starting another connection. The system DNS resolver itself is not cancellable; any remaining helper daemon is owned by emulator force-stop. The fixture waits at most 60 seconds for the pair. These are test-only deadlines and do not increase production Auto timeouts.

Executed locally: 236 JVM tests, zero failures/errors/skips; updated Android instrumentation compiled and the separate helper APK assembled. No emulator/KVM is available in the local sandbox. The strict Android gate stays at 39 tests; a fresh API 26/33 run is required for this patch.

Prior blocker run: https://github.com/Bugaga31/vless-card-vpn/actions/runs/37037069233. API 33 had one default-path DNS failure on reopen; API 26 had eight full-TUN failures. New SNI profiles passed authenticated VLESS/TLS and REALITY record observation. A default/pinned contrast is evidence to investigate, not automatic proof of a kernel or framework defect.

No production fix, main merge or APK release is claimed by this diagnostics-only patch. Actual Auto UI/foreground-service lifecycle, ARM hardware and ISP behavior remain outside the fixture.

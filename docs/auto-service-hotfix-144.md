# Auto service-budget hotfix

## Why

A real-device report for 1.2.32 described a long search, unconfirmed Telegram and no usable result. Existing green local-service fixtures did not prove public service access on that phone.

Concrete code defects addressed:
- A 3.5s cheap reachability budget (4s including startup) covered MTProto, player discovery and media. MTProto allowed only 1.5s per DC including SOCKS/tunnel setup.
- Bulk/media work shared the same worker queue with Telegram; generic HTTPS ran before either service was scheduled.
- The same initial HTTPS candidates and inconclusive service wave could be checked twice.
- Unknown control-resource evidence triggered negative live-route learning/reconnection; failed final service proof could repeat three starts without useful evidence.

## Changes

- Separate bounded service budgets: 8s total service/DC pair (at most 4s per official Telegram DC), 10s final-chain proof. Two concurrent service candidates; final throughput comparison stays isolated.
- Separate bounded worker queues, unchanged ceiling of twelve workers. Services scheduled before generic HTTPS; bulk download runs afterward. Cancellation closes tracked MTProto sockets and cancels private HTTP calls.
- One 45s coroutine deadline for foreground preparation, including deep fallback work; bounded native setup/cleanup can extend wall time. A closed search cannot invoke a queued connection callback.
- No duplicate initial HTTPS wave, no automatic re-probe of a just-tested inconclusive service wave. Repeated complete player resource challenges stop wasteful retries without blacklisting the catalog. Manual selection remains available.
- Explain MTProto timeout/invalid response separately from YouTube player/login/media failures. Search summaries do not claim that different service successes prove one route.
- UNKNOWN on an already admitted live route does not train a failure or cause an immediate restart. It triggers a guarded, calm recheck. Initial Auto admission still requires both service checks.
- Immediate health verification may reuse the directly supplied final-chain proof for at most 30s, only for the same core/network handle; it still verifies HTTPS, does not refresh the service timestamp, and never persists that proof.
- Isolated measured video throughput can feed speed ranking; concurrent samples cannot.
- Saved DPI strategies can still receive one final strict actual-chain test; no unproven default fallback is labelled connected.

## Public control-resource limit

An unauthenticated watch-page check from the development environment returned `LOGIN_REQUIRED` with no streaming data. That is evidence about this control resource in this environment, not proof of the user's operator blocking YouTube or of server failure. No sign-in, anti-bot bypass, signature deciphering or fake media PASS was added. Strict Auto can still return no qualified route when a legitimate media URL is unavailable; it must explain that limitation rather than promise connectivity. A future supported, regularly validated public media resource requires separate validation.

## Validation boundary

New deterministic tests cover slow MTProto handshake, shared DC deadline, UNKNOWN/cancellation, queue isolation/overflow, player challenge/cipher-only response, isolated speed, bounded private diagnostics, cancelled handoff, and fresh exact-context proof reuse. Run the full JVM suite and Android build plus existing E2E/masks gates. Fixtures verify protocol integration, not public Telegram/YouTube availability, carrier speed, or real-device battery use. No signing key, applicationId, learning format, certificate validation or test requirement is changed.

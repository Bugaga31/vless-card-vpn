# Telegram evidence correction r208 — work in progress

Base: published experimental candidate b14d3987d7cb9f4a59a4527413cae0e3fc97e6cc.

Confirmed code defects:
- Watchdog updates ConnectionPassport.state but not the module frame consumed by ServiceHealthCard.
- A single frame timestamp is unsuitable for partial module updates: renewing it would falsely refresh unsampled DNS/media.
- Watchdog publication checked core/auth/network but omitted chain epoch and Tor-route identity.

Fix:
- Publish new HTTPS, Telegram and DNS observations to the same module model used by the card.
- Track freshness per module, preserving unsampled evidence ages and failures.
- Fresh unknown Telegram status supersedes green; fresh failure clears the healthy heading.
- Guard publication after suspended work with route epoch/Tor identity.
- Clarify MTProto label: sampled DC reachability, not authenticated application readiness.

Tests: 22 new pure policy regressions, debug/release; full JVM/host/lint run required. Android visual/runtime verification not yet executed for r208. No new APK/release published.

Remaining, not claimed fixed:
- Real app/TUN path, account-specific DCs, Telegram media/calls. Current probe succeeds on one of two unauthenticated sampled DC responses.
- Configurable service-check profile and selected-service eligibility.
- Implemented mandatory obfs4/WebTunnel presets/runtime migration, guarded owned-daemon startup and circuit-before-PASS checks. Default bounded igareck updates; cached/manual valid lines retained, explicit update opt-out respected. Tor bootstrap is not attributed to VLESS failure.
- Tor-specific upstream comparison and reserve ranking remain future work; no instant bootstrap claim.
- One-second failover cannot be guaranteed; no physical-operator or real-account call evidence.

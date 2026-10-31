# R231 — stop repeated checks after connection

Base454af549 (verified R230). Confirmed two additional sources: unconditional watchdog verifySoon revalidated full services after90s; standbyScout continuously called prepareStandby while fewer than3 warm reserves, replenishing expiring60s records. UI also converted expired evidence to a null connection verdict, showing "Проверяем соединение" even with no running check.

Now successful full checks are event-driven, not age-driven. Watchdog requests a full profile only on changed exact context; actual suspicious traffic may request a check with30s coalescing, not every second. Healthy scout no longer probes reserve servers. Recovery retains strict isolated candidate checks and lightweight liveness/two misses/urgent retry. Failed/unknown event retries remain bounded; no fabricated PASS, no extension of admission timestamps.

UI retains actual connection status while old test evidence is explicitly STALE. No stale record is granted selection authority. Manual reconnect/profile/network/engine changes still obtain fresh checks. This does not disable the light HTTPS/DNS heartbeat and does not claim E2E app or phone verification.

Own CI pending: inherited826 +12 new regressions, Android debug compilation. No release, package/signing/version/main change.

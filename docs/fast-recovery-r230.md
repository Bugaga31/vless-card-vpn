# R230 — recovery and visible checks (verification candidate, not a release)

Base: published 1.2.116, a0e3f46cc4c55de3d6946c4709c7e11f6bded3f7.

Confirmed: watchdog passed healthOnly(auto)=true as the quiet-screen parameter, forcing 30s even awake. Use actual asleep() and a bounded 10–15s awake/20s quiet light cadence, 3.5s liveness budget, same two misses and 3s urgent retry. This is not a reconnect-time guarantee or a full-profile deadline reduction. No every-second full checks; 90/30s leases retained.

Full definitive failures of selected YouTube/Discord/voice/Instagram/stability now enter the existing strict isolated reserve recovery path, not just Telegram. Unknown is not a failure. Active calls defer this extra service-only repair; existing repeated transport-failure handling is preserved. No new direct route, random SNI, or certificate override. Search stages feed the top UI status.

Home overview precedes updater/clipboard banners; current status and selected-service chips precede verbose runtime explanations. Disconnect remains first. Screen reader polite live region. Test SOCKS and TUN remain separate.

Watson: 15s lightweight advice under pressure, 60s visible normal/120s quiet normal; heavy app inventory only while visible and optional work allowed. Search entry invalidates future training window. Training still requires real future measurements and existing readiness, never grants tunnel/admission authority. No claim of charging/cooling/battery improvement measured on owner device.

ROKI: mixed-outcome gate (at least eight defined results and two of each class); stable bounded insertion with 0.08 confidence margin. Unknown/cancelled not trained. Retains every candidate and exploration order for near ties. No model change can make a failed service pass. Existing compatible measured-mask exploration and reserve selection remain enabled.

Validation pending own workflow: Android compilation, inherited 802 targeted JVM tests plus 24 R230 regressions. Published APK, version and signing untouched. Physical phone, Doze, E2E TUN, sound delivery, reconnect latency and battery not measured.

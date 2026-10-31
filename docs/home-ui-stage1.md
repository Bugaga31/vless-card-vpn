# Home UI stage 1 — verification branch

Base: 1.2.113 / versionCode 224 / 9c4959d6efde3ed14e13ef0283dc56815be31621. No version bump or release publication.

## Scope
- One primary connection card; connected Disconnect action wins over background revalidation.
- Independent test SOCKS, actual TUN descriptor, requested/configured mask, process liveness and explicit fallback display. Core accepting configuration does not prove route traversal.
- All seven service badges use real Probe fields; UNKNOWN, absent, stale and disabled results remain distinct. Measurement context is captured before the probe; a restart/revival or observed path change during the request cannot be stamped as fresh proof of the new context. Current badges require exact profile, latency limit, observed network path, runtime epoch/revision and <=90 seconds monotonic age.
- Found routes shows historical count separately from current AutoEligibility.servicePool count. Per-attempt ordinal service observations retain the existing AutoAdmissionReason.describe explanation (including measured latency and cooling), are bounded, allowlisted and explicitly historical, not permanent admission. No raw exceptions, URLs, credentials or endpoint names are copied to new diagnostic records.
- Status palette follows app surface luminance (including AMOLED), with symbols/text as well as color, opaque status backgrounds, minimum 48dp controls, FlowRow wrapping and tabular numbers.

## Invariants
No modifications to server contracts, SNI/ALPN, certificate validation, routing decisions, user parallelism, service probes, watchdog cadence, ML training or slow-route consent. DpiSet observer polls existing lifecycle; it is not instantaneous death detection or socket/packet proof. An unavailable process invalidates live UI evidence; restart changes runtime revision and does not resurrect old green proof.

## Verification limits
51 standalone local JVM tests passed using copied production Probe/Profile declarations for Android isolation: mapper semantics, runtime generations, bounded history, source wiring and eight contrast pairs. This is not a Gradle Android result, rendered Compose preview or physical-device test. CI runs these tests in the real Android module plus the existing targeted regression suites and assembleDebug. The CI debug APK is not signed with the persistent Beta update key and is not a release or supported upgrade over installed Beta2.

Still required: actual Compose/device review at 320dp and large fonts, Light/Dark/AMOLED, connected background rechecks, native restart, Wi-Fi/LTE handoff, found-route cancellation and TUN/proxy-only states. No screenshots or device results are claimed. Mask-editor redesign and ML/power work remain separate.

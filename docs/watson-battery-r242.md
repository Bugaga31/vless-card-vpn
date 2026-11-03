# R242 — sensor cache and transparent YouTube responses

Base: published 1.2.122 / 8cc9764580c347e99a745fca5a76600f4e7cf4ce.

- Ordinary PhonePower/UI refresh no longer forces a full sensor inventory. The existing 10-second cache is shared.
- Android screen, battery, charging, saver, audio-mode and thermal events invalidate sensors with an atomic generation. An event during a read remains pending; an ordinary conflated request cannot erase it. No main-thread acquisition of the sensor-read monitor was added.
- Optional DeviceCoach advice sampling changes from 120 to 300 seconds only while hidden and under pressure. Visible sampling and urgent VPN recovery are independent.
- YouTube remains one bounded GET to the expected generate_204 endpoint, with no media download. Only expected 204 passes. 403/451 are restricted UNKNOWN, 429 rate-limited UNKNOWN, 5xx service-unavailable UNKNOWN; no UNKNOWN passes admission. Cache context advances to youtube-access-v5.
- Existing strict HTTP, cancellation, no-range and foreign-final-URL tests retained. Expectations for explicitly changed reasons/cadence updated, not admission predicates.

No tunnel/watchdog/probe concurrency or routing settings are changed. No device battery percentage, charging acceleration, TSPU result or trained model claim is made. Local focused JVM tests do not substitute full Android CI or physical-device power measurement. This is not a published APK.

## Recovery detector follow-up
A confirmed Auto transport failure previously requested recoverServerRoute and unconditionally exited the watchdog. That request can be ignored while a different service repair owns recoveryJob. The detector now stays alive, skips duplicate network probes during active repair, and can retry when the owner completes. This is an identified lifecycle hazard, not proof of the exact failure on the owner's phone. The live heading now explicitly says HTTP access, not YouTube video.

Local focused suites: 34 JVM tests passed before recovery follow-up; three additional source/label regression tests added. Full seven-job Android/build/mask CI is required; no auto publication is configured.

## Full CI blocker: pinned upstream artifact moved
Run 41350694064 failed before JVM/Android tests: fetchTor received FileNotFoundException from dist.torproject.org for pinned 15.0.26. The official archive serves the same version. A transfer-error-only archive fallback is added; SHA-256 pins, native extraction, ABI requirements and version are unchanged. A checksum mismatch remains fatal. No test gate is waived.

## Remaining Android 29 gate — not waived
Run 41351924435 built successfully after archive fallback, but API29 single-route Activity recreation gate failed. The bounded log shows a physical-network refresh 56ms after first Xray return, before the Activity restored events; only one start command was logged. This is not established as command replay or an owner-phone reproduction. DEBUG-only handoff deltas now expose boolean handle/profile/path/presence changes, without raw identifiers or weakening any reconnect/test predicate. The prior failure remains red and this is not a release.

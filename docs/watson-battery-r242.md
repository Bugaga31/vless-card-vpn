# R242 — sensor cache and transparent YouTube responses

Base: published 1.2.122 / 8cc9764580c347e99a745fca5a76600f4e7cf4ce.

- Ordinary PhonePower/UI refresh no longer forces a full sensor inventory. The existing 10-second cache is shared.
- Android screen, battery, charging, saver, audio-mode and thermal events invalidate sensors with an atomic generation. An event during a read remains pending; an ordinary conflated request cannot erase it. No main-thread acquisition of the sensor-read monitor was added.
- Optional DeviceCoach advice sampling changes from 120 to 300 seconds only while hidden and under pressure. Visible sampling and urgent VPN recovery are independent.
- YouTube remains one bounded GET to the expected generate_204 endpoint, with no media download. Only expected 204 passes. 403/451 are restricted UNKNOWN, 429 rate-limited UNKNOWN, 5xx service-unavailable UNKNOWN; no UNKNOWN passes admission. Cache context advances to youtube-access-v5.
- Existing strict HTTP, cancellation, no-range and foreign-final-URL tests retained. Expectations for explicitly changed reasons/cadence updated, not admission predicates.

No tunnel/watchdog/probe concurrency or routing settings are changed. No device battery percentage, charging acceleration, TSPU result or trained model claim is made. Local focused JVM tests do not substitute full Android CI or physical-device power measurement. This is not a published APK.

# Tor latency input and measured media ranking

Base: v1.2.55, commit `b83abbf3a5db185b09e74032b0e4df6efb0a28aa`.

## Changes

- Tor HTTPS latency input accepts positive integer milliseconds, including 100,
  consistently in UI, profile import/export and runtime. Supported storage range:
  1 through 2147483647. Default remains 2500 ms; existing profiles are preserved.
- This is a preference/filter for accepted country measurements when fast-exit
  preference is enabled, not a guarantee of latency or bandwidth. No accepted
  measurements means no measured preferred country. Ordinary Tor circuit creation
  continues. Snowflake retains the existing documented double threshold.
- Chain selection and live switching in Balanced/Download use the lower of the
  measured generic HTTPS transfer rate and positive measured YouTube media rate
  when YouTube is PASS. The existing probe already produces this metric; it was
  previously discarded by AutoChainRunner. No additional requests are introduced.
- Missing media speed does not become a video PASS. Confirmed service evidence
  still beats fallback, explicit service failure still excludes a route, and the
  eight-candidate/15-second limits, cancellation and network-generation gates remain.
- Interactive priority remains latency-oriented. This patch does not measure
  Telegram voice-message download speed or repair an identified carrier issue.

## Advertising and performance limits

Existing optional domain-based ad blocking is unchanged. Blocking YouTube's
shared video origins to remove in-stream ads would also risk breaking playback.
This patch does not promise ad-free YouTube, instant voice messages, a globally
fastest route, or 100 ms Tor performance. No HTTPS interception or custom CA added.

## Regression coverage

13 new deterministic JVM tests cover custom threshold persistence/runtime,
100 ms boundary, no invented winner, Snowflake overflow, measured video bottlenecks,
missing/invalid metrics and live-switch consistency. Existing video-host protection
tests remain enabled. Synthetic tests do not prove real-world throughput.

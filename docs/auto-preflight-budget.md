# Bounded Auto TCP hints and verified-route priority

## Scope

This is an Auto-search responsiveness improvement, not a new cipher or a demonstrated DPI bypass method. Existing TLS/REALITY settings, certificate verification, server SNI and ByeDPI presets are unchanged.

- Port hints now have a 1600 ms **per-pool** deadline, at most eight concurrent probes and at most 24 candidates. The overall Auto deadline is still 60 seconds plus the existing bounded partial-route recheck. Native core startup is synchronous; this patch does not claim to interrupt JNI calls on deadline.
- Socket cancellation/deadlines close the socket. Blocking Android/Java name resolution may finish later on its IO worker; the caller need not await it. This does not cancel the system DNS resolver itself. Each invocation remains bounded to 24 candidates; this is not a global thread-count guarantee across repeated calls.
- Completed hints are retained; timed-out/unmeasured routes remain candidates with an unknown hint (-1), not a failed VPN verdict. TCP success does not indicate authentication or bypass success. Full HTTPS checks through the selected authenticated outbound are still required.
- Favorites retain priority and favorites-only filtering. A valid profile remembered for this network now receives priority before the 24-candidate and 12-node caps, instead of potentially being dropped behind arbitrary port pings. Remembered routes are rechecked and never accepted on stale memory alone. Existing seven-day memory expiry remains unchanged.
- Candidate results are indexed by position, not imported IDs; duplicate IDs cannot accidentally share a TCP result.

## Verification

Local executed: **234 JVM tests**, zero failures/errors/skips; Android instrumentation compiled successfully. Ten additional JVM cases cover the deadline, concurrency/candidate caps, parent cancellation, duplicate IDs, empty pools, real local sockets, a stalled connector, memory priority, favorite filtering and incompatible/malformed memory.

Executed commit: `135ca36130e6fee14c1aee8175fd583c22608214`.
CI: https://github.com/Bugaga31/vless-card-vpn/actions/runs/37033232141

- API 33: all **33 instrumented tests passed**, strict gate with no failures/skips.
- API 26: 33 cases executed, seven failures. The two new socket/deadline cases passed. Six existing full-TUN cases failed (mostly TCP_CONNECT timeouts; the gvisor comparison failed DNS during reopen). An additional older callback test used `Network.fromNetworkHandle`, unavailable on API 26, causing NoSuchMethodError. This is a test compatibility bug, not an app networking failure; replacing it with the public Parcelable CREATOR needs a fresh run.
- Zero native-log counters in the assertion output are not proof that no packets were sent; those counters did not expose an explanation for the full-TUN failures.
- The full matrix remains red. Do not claim Auto is fixed on every device or a new bypass method is proven.

Not validated here: the entire Auto foreground-service/UI lifecycle, real ARM phone behavior, Russian ISP filtering, YouTube video or Telegram MTProto. No main merge, APK or release signing-key changes.

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

Two additional controlled Android socket/deadline cases were added. The strict API 26 / 33 fixture gate now requires **33 instrumented tests**, with no failed/skipped tests. Their execution is pending a new CI run; previous 31-case results do not validate this source patch.

Not validated here: the entire Auto foreground-service/UI lifecycle, real ARM phone behavior, Russian ISP filtering, YouTube video or Telegram MTProto. No main merge, APK or release signing-key changes.

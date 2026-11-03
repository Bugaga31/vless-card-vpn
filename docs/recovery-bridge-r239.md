# R239 — userspace bridge lifecycle during soft recovery

Release gate: Android API 33 candidate run 41334331109 failed other-UID HTTPS transfer after automatic recovery (8447 → 8444). Two requests timed out, although internal SOCKS HTTPS/DNS passed; other requests subsequently succeeded. This is a real failed gate, not evidence of Russian-provider blocking.

The old implementation kept both Android TUN and the hev userspace session table while replacing Xray. R239 stops/joins the bridge before stopping its SOCKS core and starts the bridge with the existing interface configuration after the replacement core starts. The Android TUN descriptor and SOCKS credentials are retained; no Builder.establish, new service START, permissive direct fallback, or weakening of required service checks is introduced. Existing TCP sessions are not promised seamless survival.

Native source tunnel_fini returns without closing externally supplied TUN descriptors. Start failure now throws instead of silently continuing. Missing saved bridge configuration fails closed; normal connection error/recovery handling remains in force.

Validation must retain the original four-endpoint other-UID test, including 1 MiB transfer, after primary blackholing with MainActivity backgrounded. An extra assertion requires bridge renewal on the retained TUN. Source-wiring tests are not substitutes for Android execution. The proposed correction is not a confirmed fix until both API 29/33 gates pass. No physical-device, TSPU or battery measurements are claimed.

# R228 — automatic Watson, quiet service checks and recovery alerts

Based on verified R227 source 1a26f5d24174bb860024b9f3d01248262d17c3d6. Separate experimental branch; no main merge or APK publication.

## Repeated tests
Automatic watchdog ticks now use lightweight HTTPS/DNS liveness only. Awake automatic interval is 20–60 seconds; screen-off interval is 30 seconds. Existing urgent retry and strict recovery paths remain independent of optional power saving.

Full selected-service checks are coalesced and leased: 90 seconds after a successful complete check, 30 seconds after an unsuccessful/incomplete result. Core, network, underlying network handle, server/mask, runtime engine revision, profile and latency context changes invalidate the lease. Lightweight checks never extend full service evidence. Sleeping automatic operation does not repeatedly download service content. Unknown remains unknown; selected services and strict admission are not bypassed.

## Recovery notifications
Confirmed recovery paths post one episode notification: connection lost, looking for a working route and reconnecting. Audible high-importance channel is separate from quiet status updates. Recovery is announced only after the full live profile passes. Manual stop does not produce a recovered message. Flapping sound is rate-limited to once a minute.

Sound/heads-up delivery requires notification permission, enabled channel and suitable system settings. Do Not Disturb is respected. Existing quiet-notification preference suppresses sound. No full-screen intent or permission bypass.

## Automatic Watson
While auto cooling is enabled, charging with measured battery 1–89% defers optional in-app background work, reserve exploration and decoration. Explicit user search slots 1/3/5/10, tunnel operation and emergency recovery remain untouched. Unknown battery readings do not invent charging measurements. Thermal safeguards remain effective.

Existing on-device future-label learning is augmented by predict-before-training Brier evaluation for both ML and Fly readouts, shown on the Watson screen. Invalid predictions are rejected. This is session-local forecast validation, not measured battery savings. No fabricated training data or claim of new training on the owner's phone.

## Limits and validation
Ordinary Android permissions do not provide control of charging current or physical cooling. Optional workload reduction may reduce this application's contribution to power and heat; no charging-speed or battery-life percentage is claimed. No killing other applications, deleting foreign data, root/accessibility abuse, transport/SNI/crypto substitution or silent VPN optimization.

18 local JVM regressions passed before Android CI. Android compilation and broader targeted tests must pass before describing the change as verified. Real-device sound, Doze, charging, heat, battery and TUN recovery still need device testing. Installed APK remains unchanged until a separately built and signed release is published.

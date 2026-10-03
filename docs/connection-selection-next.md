# Connection selection and quick Auto — source-only follow-up to Lab 1.0.49

This is not a published APK update. Lab 1.0.49 remains unchanged; the user's Android connection failure has not been established as fixed.

## Confirmed client defect
HomeScreen preferred `vpnStats.activeConfig` even after the attempt failed. A newly selected stored server could therefore be ignored by the Home connect action. `ConnectionSelection.current` now prefers the saved selection when there is no live connecting/connected/stopping session; deleted/failed stale attempts do not become connection targets. Five regression tests cover the selector. It does not establish the cause of every tunnel failure.

## Responsiveness and UI
- startVpn/startAuto publish a pending CONNECTING state before asking Android to start the service. This is an acknowledgement, not a claim that a tunnel works.
- Manual connection is named “Подключаемся”; Auto uses “Подбираем маршрут”. Permission preparation has its own label.
- With a chosen server, “Подключить” is the primary action, and “Авто · найти другой маршрут” is separate. Without a server, Auto remains primary.
- Manual failure is labelled “Не удалось подключиться”, not presented as a successful connection or as a completed automatic search.

## Quick search, not a universal bypass
- Candidate pre-ping cap: 48 → 24.
- Saved phase: 18 → 6 attempts before trying fresh sources.
- Cooperative search deadline: 180 → 60 seconds; optional partial-route restore budget: 20 → 10 seconds.
- Auto fetches one group of up to four sources (up to two saved subscriptions, remaining slots public), instead of waiting for up to three feed groups. Background/manual fetch default remains 12 sources.
- Native JNI calls do not necessarily cooperate with coroutine cancellation: this is not a hard 60-second watchdog or a tested phone speed guarantee.
- Fewer attempts/sources can miss a working alternative. No working server can be manufactured from dead public configs by changing encryption or UI. Website success still does not prove video or MTProto/calls.

## Diagnostics
Added allow-listed stages: VPN permission, config loading, native environment init, native config validation, core service start, TUN creation. Still no raw configs, URLs, keys, addresses, exception messages or traffic body capture.

## Verification
217 JVM tests passed, 0 failures/errors/skips; debug and release Kotlin compilation passed. Seven changed/new Compose frames were individually inspected (light/dark disconnected, manual connecting, empty state, large-text error/top/bottom, small empty home). This invocation excluded the native rebuild; no new APK signing, emulator execution or realme connectivity test was performed.

A sanitized Lab network report after one failed manual connection is needed to identify the actual phone failure stage. Do not send keys or subscription URLs in chat. Do not mark the existing Lab prerelease as working for all users.

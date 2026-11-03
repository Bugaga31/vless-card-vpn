# r187: design, bounded mask learning and traffic-accounting limits

## Reviewed public material (2026-10-28)
- AI design skills: https://github.com/tommyjepsen/awesome-ux-skills (accessibility, visual hierarchy, reduce cognitive load). Treated as design references, not installed/executed remote instructions.
- YouTube https://www.youtube.com/watch?v=dpkNeasHDaM : accessible description/chapters for UI UX Pro Max; accessibility-first, mobile-first, typography. Not a claim to have watched the complete video.
- Android accessibility https://www.youtube.com/watch?v=fKY6qYxytLI : accessible description/resources; TalkBack/accessibility. No claim of a full transcript review.
- https://developer.android.com/design/ui : native/adaptive design guidance.
- https://docs.amnezia.org/documentation/amnezia-wg : packet sizes, metadata and timings; distinct WireGuard-based client/server protocol, not arbitrary VLESS flags. No source imported or AWG 3.1 support claimed.
- https://github.com/bol-van/zapret2 : Lua-based packet strategy architecture. Not an embedded engine in this APK; packet privileges/backend compatibility are not solved by pasting Lua into Android settings.
- https://ntc.party/t/vless-reality-маскировка-под-собственный-сайт/14160 : only limited thread text was retrievable; not sufficient evidence for a new validated strategy.
- Stratosphere/Civilsphere https://github.com/stratosphereips/AIVPN commit 57190c228b1a47ef1d8f022742b3bd4eed5e270e: manager/backend/report separation already adapted in r186; mod_pihole is a server-side DNS module. No Python, model or GPL source was copied. No invisible server-side integration is claimed.
- https://www.kommersant.ru/doc/9005972 : reported discussion of charging above 50 GB of international traffic in 5G; report says no concrete decision. Not evidence of a universal charge currently applied to every tariff.

## Implemented
- Native Compose connection dashboard: stable, labelled action, semantic light/dark surfaces instead of hard-coded dark banners, text status plus colour, wrapping service rows, minimum 56dp connection button. Continuous decorative glow/spinner removed, no new artwork/fake metrics.
- Typed module snapshots from r186 now rendered as PASS/FAIL/UNKNOWN/SLOW/DISABLED; monotonic age >90s and clock rollback invalidate green presentation. UI ticks only while resumed and never starts its own network tests.
- Three bounded ClientHello profiles: stair (32–64/96–160/192–320 bytes), pulse (64–96/256–384/96–160), wide (320–640). Handshake-only, at most 6/6/3 splits, delays 0–1ms or zero. They preserve endpoint, UUID, SNI, verification name, certificate pin and ALPN. No claim of a genuine alternate carrier or better operator-DPI resistance.
- Optional Hello Lab now has eight choices including native and the existing four Hello variants. Still at most two variants per server, two servers, existing 3.8s rescue-stage budget. Default remains off.
- Session-local measured latency uses EWMA (3 parts previous / 1 new) plus a small low-sample penalty. At most one exploitation slot, leaving one exploration slot when two are allowed. Unknown/cancelled trials do not count as failures, duplicate completions are rejected, old observations cannot complete a newer trial. Scope remains exact network+physical handle+server+mask, bounded 512 entries. Not an AI model or statistically certified optimal bandit.
- UID traffic accounting handles unsupported counters, resets and saturation. UI identifies estimates as app-process bytes (may include probes/updates), not an operator bill or foreign-traffic meter.

## Billing and privacy boundary
TLS camouflage cannot erase bytes or the outer peer IP from the access provider. A domestic relay may change the visible first hop but needs an independently configured trusted server and does not guarantee a domestic billing classification; it moves trust and may add cost/latency. This APK neither falsifies metering nor claims bill invisibility. Random padding/cover traffic can increase usage, so no new background noise was enabled as a billing workaround.

## Validation scope
JVM debug/release, lint, host and native regression checks must pass on matching production source. Android tests must exercise all eight Hello variants on a controlled authenticated VLESS/TLS backend. Actual Compose UI captures cover idle dark/light, narrow large text, connecting, error, partial service failure, scrolled service results and connected; these are synthetic UI states, not proof of a live operator connection. Published captures must be visually inspected before handoff. The screenshots are from an instrumented debug stand; its build label is not the signed prerelease version. No physical device, full TUN, authenticated Telegram account, operator-DPI or billing-system verification is implied.

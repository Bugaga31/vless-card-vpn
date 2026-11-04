# Civilsphere-inspired module supervision in VLESS Card (r186)

## Source and boundary
Reviewed Stratosphere `AIVPN` commit `57190c228b1a47ef1d8f022742b3bd4eed5e270e`, not the unrelated Rust AIVPN project.
- https://github.com/stratosphereips/AIVPN/blob/57190c228b1a47ef1d8f022742b3bd4eed5e270e/mod_manager/mod_manager.py
- https://github.com/stratosphereips/AIVPN/blob/57190c228b1a47ef1d8f022742b3bd4eed5e270e/common/database.py
- https://github.com/stratosphereips/AIVPN/blob/57190c228b1a47ef1d8f022742b3bd4eed5e270e/mod_slips/mod_slips.py
- https://github.com/stratosphereips/AIVPN/blob/57190c228b1a47ef1d8f022742b3bd4eed5e270e/mod_report/README.md

The source uses a manager, backend modules, Redis channels, PCAP analysis and separate reports. We adapt the separation of responsibilities, not the Python services themselves. New Kotlin code is an original implementation; no upstream source/model/assets have been copied. The upstream project is GPL-3.0. This is not a full security or licensing audit.

## Real integrated boundaries
| Responsibility | Implementation | Does not own |
|---|---|---|
| Transport/lifecycle | Existing Xray, HEV and TunnelService | Module verdict calculation |
| Measurements | Existing Tester / TelegramProbe / DNS / media probes | Report/UI labels or restart policy |
| Supervision | `health/ModuleHealth.kt` | Android state, sockets, endpoints, traffic contents |
| Adapter/report | `health/LiveHealthCoordinator.kt` | Packet capture, telemetry, ML detection |
| Auto admission | AutoLatencyPolicy delegates its fast link gate to the same ModuleHealthPolicy | A second divergent latency threshold |
| Presentation | ConnectionPassport stores last full module assessment with monotonic age | Claim that a past full assessment is current live proof |

## Runtime use
`TunnelService.verifySoon` passes measured HTTPS/payload/DNS/MTProto/media results to the coordinator; its nullable ready verdict determines live check status and whether Auto remembers the route. HTTPS and DNS are separate observations: DNS failure cannot rewrite the historic HTTPS success in the supervisor. The existing service retains lifecycle ownership, cancellation/network/core guards and repair actions. The supervisor emits only a suggested typed action; it cannot autonomously trigger duplicate restarts.

Unknown, slow, disabled and failed are different states. Missing timing remains unknown, not fast. Payload failure wins over low latency. Required media that wasn't measured remains unknown. Optional unmeasured media doesn't delay Auto. Explicit negative media in Auto remains a failure. Manual mode does not inherit Auto's Telegram/latency requirements.

## Not integrated / not claimed
- No Redis, Docker, OpenVPN provisioning, mail/Telegram account automation or server credentials on Android.
- No Slips process, AI/ML malware verdict, PCAP capture/export, root CA installation, HTTPS interception or user traffic upload.
- No new QUIC/WebRTC carrier and no claim of better DPI resistance from this structural change.
- Scope is existing local SOCKS probes, not complete Android TUN or an authenticated Telegram account.
- Current Kotlin package structure is improved at the health boundary; the large existing service is not yet fully decomposed.

# One-tap Auto and route verification (1.0.44)

## What the user confirmed and requested

On realme RMX3624 / Android 13, 1.0.43 connects without the earlier native abort. YouTube and Telegram still do not work. The request is a cleaner design and one additional **Auto** button that handles selection rather than asking the user to pick presets. Orbot is requested **only with bridges**; ByeDPI/ByeByeDPI and zapret remain desired future engines.

## Corrected false-positive health check

The application UID is excluded from Android VPN routing to avoid recursive tunnel traffic. Previous `URL.openConnection()` checks and service checks therefore used the app's direct route. A response from `ya.ru` or a generic 200/redirect was not proof the selected outbound carried traffic. The service also published CONNECTED before checking data transfer.

The new check uses an ephemeral `127.0.0.1` SOCKS inbound in libbox with random session-only credentials. The first route rule forces that inbound to the selected `proxy`, ahead of sniffing, DNS interception and direct exceptions. The test sends domain-form SOCKS CONNECT with username/password authentication, verifies the final HTTPS certificate and hostname, rejects redirects/wrong status, and has cancellation that closes the socket. No JVM-global proxy credentials, direct fallback, response bodies, cookies or session secrets are retained in reports.

Endpoints: Cloudflare 204, YouTube HTTPS 204 and Telegram website 200. These check only web reachability. They do not certify video playback, MTProto sessions, calls, throughput or UDP.

The VPN server hostname has an explicit direct bootstrap DNS resolver, avoiding dependence on a DoH resolver reached through the very proxy being resolved. Ordinary remote DNS still uses the proxy. Bootstrap lookups are outside the tunnel by design; this is not a claim of zero metadata exposure.

## Auto behavior

- One foreground service owns a cancellable, bounded selection (120 seconds, at most 12 native-profile attempts).
- Prioritize saved/favorite nodes, then refresh public community feeds if permitted. Preserve transport-specific configuration when deduplicating; do not call a TCP-open node HEALTHY.
- Reserve attempts for fresh candidates, download at most 12 HTTPS sources in batches of four, cap each source at 2 MiB and total candidate pool at 500. Individual HTTP calls time out within 12 seconds and cancel when the search is cancelled.
- Auto excludes unsupported transports, malformed credentials/REALITY keys and plaintext VLESS. Supported auto candidates are VLESS/VMess with TLS/REALITY, Trojan and Shadowsocks. UDP-only/Hysteria and XHTTP are not silently tested as TCP or claimed supported by Auto.
- Try server-compatible TLS, then the bundled core's actual boolean TLS `fragment` when applicable. Preserve configured SNI, UUID, public key, short ID, flow and fingerprint. Never use random operator SNI, fake IP/TTL/sequence packets or disable certificate validation.
- Auto's session policy turns off RU-direct/ad-block/per-app exceptions for public traffic and TCP Fast Open; existing saved user preferences are not replaced. Local/private destinations and the application UID remain outside the Android VPN path as documented.
- CONNECTED only follows the real route test. Manual connect needs a valid general HTTPS check and reports the individual service checks. Auto additionally requires both YouTube HTTPS and Telegram web checks; these can still fail even when an actual app would work, so they are not universal proof of censorship status.
- Auto checks every 30 seconds with the screen on, 60 seconds off. After three consecutive failed service checks it can reselect, respecting the saved failover preference, with at most two automatic recovery searches per explicit Auto request. No infinite reconnect loop. The older UI autopilot does not dispatch competing failovers for an Auto session.
- No automatic connect on an ordinary launcher open. The user's explicit Auto button or Quick Settings connect action starts selection; Android VPN permission is still required.

## Diagnostic APK installation

1.0.44 is packaged separately as `com.vlesscardvpn.auto`, displayed as **VLESS Card VPN · Auto**. It installs alongside the earlier Test app. Do not uninstall the working 1.0.43 Test app or delete its saved configuration. The Auto app has a separate data store; pressing Auto loads its own candidates, or the user can import their subscription there. Only one Android VPN can run at a time.

The new sandbox generated a different debug signing certificate, so an in-place update to 1.0.43 cannot be honestly promised. Normal production and stability-preview package IDs remain unchanged unless `-PautoPreview=true` is explicitly passed.

## UI

The main screen has a primary Auto action and ordinary connect/disconnect, a server card, honest HTTPS/service statuses, and metrics only for the active session. Large font settings stack actions rather than clipping them. Diagnostics and settings remain accessible. Screenshots use actual Compose rendering through Paparazzi, not an HTML mockup; they do not execute the Android VPN service or native core.

## Not in this build

- No bundled Orbot/Tor, Snowflake/WebTunnel/obfs4 runtime or automatic bridge retrieval. A future Orbot integration must use verified bridge configuration and a proxy-based chain with only one Android VpnService, excluding Tor's own transport from the tunnel. Auto must not silently use direct Tor without bridges.
- No embedded ByeDPI/ByeByeDPI, tpws or nfqws. A future local Anti-DPI mode must be labeled as not hiding the IP / not an encrypted VPN, require explicit opt-in before changing those privacy expectations, own native process/socket cleanup, pin upstream/license versions and have device/network tests. `nfqws` requires root; `tpws --socks` has a different root-free scope.
- No V2Ray/Xray migration or invented all-network masking protocol. Blocking by IP/allowlist, unavailable public nodes and server-side restrictions cannot be repaired by arbitrary SNI substitution.
- No claim of confirmed YouTube/Telegram app access in the user's network. Physical-device tunnel and censorship tests are still required.

## Verification

Pending final test, lint, screenshot and APK checks. The code includes local SOCKS/TLS tests (including wrong hostname, wrong HTTP status, unauthenticated proxy and timeout), policy/configuration tests, real Compose snapshots and compiled native-schema instrumentation tests. JVM tests cannot prove native libbox startup or device routing correctness. No emulator/device instrumentation execution has occurred.

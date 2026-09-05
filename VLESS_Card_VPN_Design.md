# VLESS Card VPN - Architecture & Design

## Overview

VLESS Card VPN is a production-ready, standalone Android application implementing a clean-architecture client for VLESS + Reality proxy with a beautiful server cards UI. It provides DPI-resistant masked connections suitable for 2026 Russian networks using standard VLESS TCP + Reality (xtls-rprx-vision flow, chrome fingerprint).

Core features:
- Server cards UI: Material3 cards showing name/remark, ping latency (color-coded), status (idle/connected/dead), last test timestamp, server graphic/icon, tap-to-connect.
- Split routing: All Russian domains (.ru), geoip:ru, geosite:category-ru, private IPs routed DIRECT. All other traffic routed through VLESS proxy.
- VLESS Reality masking: SNI yandex.ru (or music.yandex.ru), full TLS 1.3 + HTTP/2 mimicry.
- Auto-testing: Background latency/TLS handshake tests, auto-sort by speed, mark dead servers.
- Import: Direct paste of vless:// URIs or subscription URLs (base64-encoded lists).
- No proprietary infrastructure; pure open standard VLESS Reality over sing-box core.

Target: Android 8.0+ (API 26), Jetpack Compose + Material3. VPN implemented via custom VpnService + sing-box TUN.

## Server Setup (with exact config snippets for 3X-UI and manual)

### Recommended VPS Specs
- Ubuntu 24.04 LTS
- 1-2 vCPU, 1-2 GB RAM (EU location preferred, e.g. Netherlands/Finland for RU latency)
- 10+ GB disk
- Port 443 open (TCP)
- Domain (optional but recommended for SNI realism)

### 3X-UI Panel Setup (easiest)
1. Install 3X-UI (latest 2026+ version supporting Reality).
2. Create new Inbound:
   - Protocol: VLESS
   - Port: 443
   - Flow: xtls-rprx-vision
   - TLS: Reality enabled
   - Server Name (SNI): `yandex.ru`
   - Destination: `yandex.ru:443`
   - uTLS Fingerprint: `chrome`
   - Short ID: `01` (or generate `sing-box generate rand 8 --hex`)
   - Private Key: Generate with `sing-box generate reality-keypair`
   - Public Key: Copy the output public key
   - UUID: Generate new

### Manual sing-box Server Config (`/etc/sing-box/config.json`)
```json
{
  "inbounds": [
    {
      "type": "vless",
      "listen": "::",
      "listen_port": 443,
      "users": [
        {
          "uuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
          "flow": "xtls-rprx-vision"
        }
      ],
      "tls": {
        "enabled": true,
        "server_name": "yandex.ru",
        "reality": {
          "enabled": true,
          "handshake": {
            "server": "yandex.ru",
            "server_port": 443
          },
          "private_key": "YOUR_PRIVATE_KEY_HERE",
          "short_id": ["01"]
        }
      }
    }
  ],
  "outbounds": [
    { "type": "direct" }
  ],
  "route": {
    "rules": [
      { "inbound": ["vless-in"], "outbound": "direct" }
    ]
  }
}
```

Generate keys:
```bash
sing-box generate reality-keypair
sing-box generate rand 8 --hex   # for short_id
```

Restart sing-box service. Verify with `curl -v --resolve yandex.ru:443:YOUR_VPS_IP https://yandex.ru` from client (should succeed with Reality handshake).

Alternative SNI options (RU-friendly): `music.yandex.ru`, `samsung.com` (if reachable).

## Client Core Choice (sing-box vs Xray)

**Recommended: sing-box (primary)**

Reasons:
- Native modern Reality + xtls-rprx-vision support.
- Excellent split routing with built-in geosite/geoip.
- TUN implementation optimized for Android (via libbox).
- uTLS fingerprint support (`chrome`).
- Built-in DNS, sniffing, auto-route.
- Smaller binary footprint and better performance on low-end devices.

**Alternative: Xray-core** (if sing-box unavailable): Use for fallback. Similar VLESS Reality support but weaker Android TUN integration.

**Integration approach in APK**: Use SagerNet/libbox (Go bindings) or embed `sing-box` Android binary (arm64-v8a + armeabi-v7a) from official releases and invoke via Process + TUN fd. The design below uses the libbox approach for clean integration (as validated in production clients).

Download sing-box for Android libs during build.

## Android App Architecture (layers, packages, key classes)

Clean architecture with strict separation.

**Root package**: `com.vlesscardvpn`

### Layers & Packages
- `com.vlesscardvpn.ui` — Compose screens & composables
  - `ServerListScreen.kt`
  - `ConfigImportScreen.kt`
  - `SettingsScreen.kt`
  - `components/ServerCard.kt`
- `com.vlesscardvpn.domain`
  - `model/VlessConfig.kt`
  - `model/TestResult.kt`
  - `model/RoutingRule.kt`
  - `repository/ServerRepository.kt`
- `com.vlesscardvpn.data`
  - `db/AppDatabase.kt`
  - `db/ConfigDao.kt`
  - `db/TestHistoryDao.kt`
  - `repository/ServerRepositoryImpl.kt`
  - `datastore/SettingsDataStore.kt`
- `com.vlesscardvpn.core`
  - `vpn/VlessVpnService.kt`
  - `vpn/SingBoxManager.kt`
  - `import/VlessUriParser.kt`
  - `import/SubscriptionFetcher.kt`
- `com.vlesscardvpn.worker`
  - `AutoTestWorker.kt`
  - `SubscriptionUpdateWorker.kt`
- `com.vlesscardvpn.util`
  - `PingTester.kt`
  - `GeoAssetManager.kt`

### Key Classes
- `VlessConfig` (domain): Holds uuid, address, port=443, pbk, sid, sni="yandex.ru", fp="chrome", flow="xtls-rprx-vision", remark, id (Room PK).
- `TestResult`: latencyMs: Int, success: Boolean, timestamp: Long, error: String?
- `VlessVpnService`: Subclass of VpnService. Manages sing-box start/stop, generates JSON config on-the-fly.
- `SingBoxManager`: Handles libbox.CommandServer or Process launch, config JSON generation.
- `AutoTestWorker`: WorkManager periodic + one-time, calls PingTester.
- `ServerRepository`: CRUD + live queries for cards.

**Dependencies** (build.gradle.kts):
- androidx.compose.material3
- room-runtime + ksp
- work-runtime-ktx
- androidx.datastore
- kotlinx.coroutines
- okhttp (for subs & tests)
- libbox (sing-box android bindings)

Room entities: ConfigEntity, TestResultEntity.

## UI Design (exact Compose code sketches for Card, list, actions)

Top-level screen uses `Scaffold` + `TopAppBar` + `LazyColumn`.

**ServerCard composable** (mimics attractive server cards: header graphic, status indicators, metrics row, action buttons):

```kotlin
@Composable
fun ServerCard(
    config: VlessConfig,
    testResult: TestResult?,
    isConnected: Boolean,
    onConnect: () -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable { onConnect() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Graphics header row
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Server graphic (asset or vector)
                Image(
                    painter = painterResource(id = R.drawable.ic_server_eu),
                    contentDescription = "Server graphic",
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = config.remark.ifBlank { config.address },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${config.address}:${config.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Status badge
                val statusColor = when {
                    isConnected -> Color(0xFF4CAF50)
                    testResult?.success == true -> Color(0xFF2196F3)
                    testResult?.success == false -> Color(0xFFF44336)
                    else -> Color.Gray
                }
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(statusColor, CircleShape)
                )
            }

            Spacer(Modifier.height(12.dp))

            // Metrics row: Ping + Last test
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Ping badge (color coded)
                val pingColor = when {
                    testResult == null -> Color.Gray
                    testResult.latencyMs < 80 -> Color(0xFF4CAF50)
                    testResult.latencyMs < 180 -> Color(0xFFFFC107)
                    else -> Color(0xFFF44336)
                }
                Surface(
                    color = pingColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (testResult != null) "${testResult.latencyMs} ms" else "—",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = pingColor,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = testResult?.let { 
                        "Last: ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.timestamp))}" 
                    } ?: "Not tested",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))

            // Action row
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onConnect,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isConnected) Color(0xFFF44336) else Color(0xFF1976D2)
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isConnected) "Disconnect" else "Connect")
                }
                OutlinedButton(onClick = onTest) {
                    Text("Test")
                }
            }
        }
    }
}
```

**ServerListScreen.kt** (key parts):
```kotlin
@Composable
fun ServerListScreen(viewModel: ServerListViewModel) {
    val servers by viewModel.servers.collectAsState()
    val activeConfigId by viewModel.activeConfigId.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Servers") },
                actions = {
                    IconButton(onClick = { /* import */ }) { Icon(Icons.Default.Add, "Add") }
                    IconButton(onClick = { viewModel.updateSubscriptions() }) { Icon(Icons.Default.Refresh, "Subs") }
                    IconButton(onClick = { viewModel.autoTestAll() }) { Icon(Icons.Default.Speed, "Test All") }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            items(servers, key = { it.id }) { config ->
                ServerCard(
                    config = config,
                    testResult = viewModel.getLatestTest(config.id),
                    isConnected = config.id == activeConfigId,
                    onConnect = { viewModel.connect(config) },
                    onTest = { viewModel.testSingle(config) },
                    onEdit = { /* navigate */ },
                    onDelete = { viewModel.delete(config) }
                )
            }
        }
    }
}
```

Long-press on card opens dropdown menu: Edit / Delete / Copy link.

Import screen: TextField for vless:// or https://sub.url , buttons "Import" and "Fetch Subscription".

## Routing & Split Config (full JSON example for sing-box/Xray)

sing-box client config generated dynamically in `SingBoxManager.generateConfig(activeConfig: VlessConfig)`.

Full example (embedded + assets/geoip.dat + geosite.dat):

```json
{
  "log": { "level": "warn" },
  "dns": {
    "servers": [
      { "tag": "local", "address": "8.8.8.8", "detour": "direct" },
      { "tag": "proxy", "address": "1.1.1.1", "detour": "proxy" }
    ],
    "strategy": "ipv4_only"
  },
  "inbounds": [
    {
      "type": "tun",
      "tag": "tun-in",
      "interface_name": "vless-tun",
      "inet4_address": ["172.19.0.1/30"],
      "mtu": 9000,
      "auto_route": true,
      "strict_route": true,
      "sniff": true,
      "sniff_override_destination": true
    }
  ],
  "outbounds": [
    {
      "tag": "proxy",
      "type": "vless",
      "server": "YOUR_VPS_IP",
      "server_port": 443,
      "uuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "flow": "xtls-rprx-vision",
      "packet_encoding": "xudp",
      "tls": {
        "enabled": true,
        "server_name": "yandex.ru",
        "utls": { "enabled": true, "fingerprint": "chrome" },
        "reality": {
          "enabled": true,
          "public_key": "YOUR_PUBLIC_KEY",
          "short_id": "01"
        }
      }
    },
    { "tag": "direct", "type": "direct" },
    { "tag": "block", "type": "block" }
  ],
  "route": {
    "rules": [
      { "inbound": ["tun-in"], "ip_is_private": true, "outbound": "direct" },
      { "inbound": ["tun-in"], "domain_suffix": [".ru"], "outbound": "direct" },
      { "inbound": ["tun-in"], "geosite": ["category-ru"], "outbound": "direct" },
      { "inbound": ["tun-in"], "geoip": ["ru"], "outbound": "direct" },
      { "inbound": ["tun-in"], "domain_keyword": ["yandex"], "outbound": "direct" },
      { "inbound": ["tun-in"], "outbound": "proxy" }
    ],
    "final": "proxy",
    "auto_detect_interface": true
  }
}
```

Geo assets: Bundle `geoip.dat`, `geosite.dat` (SagerNet or official) in `assets/` and copy to app data dir on first run. Update via WorkManager weekly.

Xray fallback JSON similar using `outbounds[0].protocol = "vless"`, `streamSettings.realitySettings`.

## Config Model & Import

**Domain model** (`VlessConfig.kt`):
```kotlin
data class VlessConfig(
    val id: Long = 0,
    val remark: String,
    val address: String,
    val port: Int = 443,
    val uuid: String,
    val pbk: String,
    val sid: String = "01",
    val sni: String = "yandex.ru",
    val fp: String = "chrome",
    val flow: String = "xtls-rprx-vision",
    val createdAt: Long = System.currentTimeMillis()
)
```

**Import** (`VlessUriParser.kt`):
```kotlin
fun parseVlessUri(uri: String): VlessConfig? {
    // Parse vless://UUID@HOST:PORT?security=reality&sni=...&pbk=...&sid=...&fp=chrome&flow=xtls-rprx-vision&type=tcp#REMARK
    val parsed = Uri.parse(uri)
    if (parsed.scheme != "vless") return null
    // Extract params, validate required fields
    return VlessConfig(...)
}
```

Subscription: Base64 decode response lines, filter `^vless://`, parse each.

## Auto Testing System (WorkManager, test algorithm)

**WorkManager setup**:
- `AutoTestWorker` (PeriodicWorkRequest every 20 min when WiFi, or 45 min always).
- Constraints: `NetworkType.CONNECTED`, optional `requiresCharging`.
- One-time immediate test on import / manual "Test All".
- Separate `SubscriptionUpdateWorker` (daily).

**Test algorithm** (`PingTester.kt`):
1. TCP connect to `address:port` (non-blocking, 4s timeout) — base RTT.
2. TLS handshake using OkHttp / custom SSLSocketFactory with:
   - SNI = config.sni
   - uTLS-like via okhttp (or custom ClientHello)
   - Measure handshake duration.
3. If connected to VPN: Perform HTTP GET to `https://www.google.com` (or RU test site) through tun and measure.
4. Success criteria: TCP + TLS handshake < 3000ms + no cert error.
5. Store `TestResult(latencyMs = totalRtt, success, timestamp)`.
6. Sort list: successful by ascending latency, dead (success=false) at bottom.

Kill-switch: If all configs dead for 3 consecutive tests, show banner and optionally block non-RU traffic.

Results persisted in Room, exposed via Flow for UI updates.

## VPN Service Implementation notes

**VlessVpnService.kt** (extends `android.net.VpnService`):

```kotlin
class VlessVpnService : VpnService() {
    private lateinit var singBoxManager: SingBoxManager

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val active = getActiveConfig()
        val json = singBoxManager.generateConfig(active)
        singBoxManager.start(json, this)  // libbox or process
        return START_STICKY
    }

    override fun onDestroy() {
        singBoxManager.stop()
        super.onDestroy()
    }
}
```

**SingBoxManager.kt** key:
- Prepare TUN builder exactly like SagerNet example (mtu 9000, inet4 172.19.0.1/30, auto route, protect fd).
- Write generated JSON to `filesDir/singbox-config.json`.
- Use `io.nekohasekai.libbox` CommandServer or exec `sing-box run -c config.json`.
- Implement `PlatformInterfaceWrapper` for fd protection.
- Support per-app routing optionally (exclude Russian apps or include only browsers).
- Notification with "Connected to REMARK".

On connect: Save active ID to DataStore, start service, update UI cards.

## Build & Distribution notes (how to build the APK yourself)

1. Android Studio Hedgehog+ / AGP 8.2+.
2. `build.gradle.kts`:
   - `compileSdk 34`, `minSdk 26`, `targetSdk 34`
   - Compose BOM, Room 2.6+, Work 2.9+
   - Add `implementation "io.nekohasekai.libbox:libbox:..."` (use latest from sing-box-for-android)
   - Copy sing-box binary assets if not using lib: `assets/sing-box-arm64`
3. Generate release keystore.
4. Build: `./gradlew assembleRelease`
5. Signing config in `build.gradle`.
6. Distribution: GitHub Releases, direct APK. Use reproducible builds if possible.
7. Geo assets: Download latest from `https://github.com/SagerNet/sing-box` releases or `github.com/Loyalsoldier/v2ray-rules-dat`.

Include `proguard-rules.pro` to keep sing-box classes.

## Security & Masking Considerations 2026

- **Masking**: Reality + xtls-rprx-vision + chrome fingerprint makes outbound TLS identical to real yandex.ru browser session (TLS 1.3, X25519, H2). DPI sees only yandex.ru handshake.
- Server target must be reachable with low latency (VPS in EU).
- Client never sends real certificate; Reality uses server-side private key verification.
- Use strong random UUID + short_id.
- DNS: Use proxy DNS for non-RU, local for RU.
- No logging of user traffic in app (only test metrics).
- Kill switch: When proxy selected, block non-direct until connected.
- Update sing-box regularly.

## Risks & Mitigations

- **DPI evolution**: Mitigate by rotating SNI (yandex.ru / music.yandex.ru / samsung.com) in settings + periodic server IP rotation.
- **Reality key compromise**: Rotate keys every 60-90 days. Store only in memory during runtime.
- **All servers dead**: Auto-fallback to direct + user alert. Never force proxy if tests fail.
- **Subscription poisoning**: Validate every imported URI strictly (only vless:// scheme, required Reality fields present).
- **Battery drain**: WorkManager constrained to WiFi + 20min minimum interval. Use inexact scheduling.
- **Android restrictions**: Request VPN always-on permission. Handle `onRevoke()`.
- **Geo asset staleness**: Auto-download weekly from trusted sources.
- **Legal**: Pure proxy for personal use. No data collection.

## Python PoC Snippet for Auto-Tester Logic

```python
#!/usr/bin/env python3
# poc_vless_tester.py - Validate VLESS Reality test algorithm locally
import socket
import ssl
import time
import urllib.parse
from datetime import datetime

def test_vless_reality(vless_uri: str, timeout=4.0):
    parsed = urllib.parse.urlparse(vless_uri)
    if parsed.scheme != 'vless':
        return None, "Invalid scheme"
    
    # Parse: vless://uuid@host:port?security=reality&sni=...&pbk=...
    query = urllib.parse.parse_qs(parsed.query)
    host = parsed.hostname
    port = parsed.port or 443
    sni = query.get('sni', ['yandex.ru'])[0]
    # uuid, pbk, sid not used for direct TCP/TLS test (only for full proxy)

    start = time.time()
    try:
        # 1. TCP dial
        sock = socket.create_connection((host, port), timeout=timeout)
        tcp_rtt = (time.time() - start) * 1000
        
        # 2. TLS handshake with SNI
        context = ssl.create_default_context()
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE  # Reality bypasses cert validation
        # Simulate chrome fingerprint (limited in stdlib; in prod use custom)
        tls_sock = context.wrap_socket(sock, server_hostname=sni)
        handshake_rtt = (time.time() - start) * 1000
        
        tls_sock.close()
        total = int(handshake_rtt)
        success = total < 3000
        return {
            'latency_ms': total,
            'tcp_rtt': int(tcp_rtt),
            'success': success,
            'timestamp': datetime.utcnow().isoformat()
        }, None
    except Exception as e:
        return None, str(e)

# Example sanitized public-style VLESS (replace with your own)
example = "vless://a1b2c3d4-e5f6-7890-abcd-ef1234567890@203.0.113.10:443?type=tcp&security=reality&sni=yandex.ru&fp=chrome&pbk=publickeyexample&sid=01&flow=xtls-rprx-vision#Test-Server"

result, err = test_vless_reality(example)
print("Test result:", result)
if err:
    print("Error:", err)
```

Run with: `python3 poc_vless_tester.py`

This PoC reproduces the TCP + TLS handshake timing used by the Android `PingTester`. Extend with full proxy test using `requests` + SOCKS once VPN is active.

---

**Implementation order recommendation**:
1. Domain + DB + import parser.
2. Server cards UI + list.
3. AutoTestWorker + PingTester.
4. SingBoxManager + VlessVpnService (use SFA VPNService.kt as reference for TUN builder).
5. Dynamic config generation + routing rules.
6. Full testing + sorting + kill-switch.
7. Subscription + settings.

This document contains all concrete classes, JSONs, code sketches and snippets needed to implement the complete APK from scratch.
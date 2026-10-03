# Adaptive Auto 1.0.45

## Installation changes, not an unverified diagnosis

The user reported “package damaged” for 1.0.44. The original APK passes ZIP integrity, Android 13 v2 signature verification and GitHub digest verification; no screenshot or PackageInstaller error code is available. The cause cannot honestly be identified as signature, ABI, storage or incomplete download.

1.0.45 changes packaging for compatibility: compressed native libraries extracted by Android, ARM32 + ARM64 in the Auto APK, no unused x86 libraries. The artifact is a release build (not debuggable or testOnly), signed with the same local certificate as 1.0.44. It remains `com.vlesscardvpn.auto`, separate from the earlier 1.0.43 Test app. Do not uninstall that working Test app or delete its saved configurations. Only one VPN can run.

## Real native Anti-DPI, without silent privacy downgrade

ByeDPI is compiled from the pinned MIT-licensed upstream commit `ba532298de7b28cfe854aea83d061369d13ca290`. Its license is included in the repository and APK. Android NDK 27.2.12479018 builds PIE executables into nativeLibraryDir; the process is isolated from the UI/core process, bound only to a random loopback port, with bounded connections, no external listener and no persistent log of traffic. A launcher uses parent-death signal and race checking; cleanup terminates and, where supported, forcibly reaps the child.

Auto profiles are compatible TLS, bundled-core TLS fragmentation and **VPN + ByeDPI** (split + TLS record split). The proxy outbound stays encrypted; ByeDPI wraps only the connection to the VPN server via a SOCKS detour, not app traffic in an unencrypted direct Anti-DPI fallback. Server SNI/keys/flow/fingerprint and certificate checks are preserved. No guaranteed all-network concealment is claimed. ByeDPI changes packet/record segmentation; it does not add encryption.

The last verified profile is remembered for a hashed underlying-network interface/DNS fingerprint and configuration identity, expires after seven days and is bounded to 64 records. It stores no SSID, browsing history or raw credentials. The key is an approximation of network identity, not a guaranteed unique Wi-Fi identity. Auto still has a 120-second/12-attempt limit, explicit VPN permission, at most two recovery searches and the favorites-only restriction. ByeDPI startup requires Android 8+; older supported Android versions can still try compatible TLS/fragmentation.

## Security and routing

- Auto rejects weak/plain Shadowsocks ciphers and plaintext VLESS/VMess; it allows known AEAD Shadowsocks methods and TLS/REALITY candidates. It does not alter server-side cryptography or invent a new cipher.
- The platform now exports Android-default trusted CA certificates as PEM to libbox, replacing an empty iterator. Native upstream expects PEM and otherwise falls back to a system pool. This makes the trust-source callback explicit without setting `insecure` or installing a trust-all manager. It is not proof that missing CA certificates caused the user's failures.
- The TUN configuration includes IPv4 and IPv6 prefixes. A configured IPv6 family receives a default route when no explicit v6 route is returned; errors are not silently suppressed. Actual Android/native dual-stack routing still needs device tests.
- Authenticated loopback mixed inbound supports SOCKS route checks and HTTP CONNECT for downloading subscriptions through the active encrypted outbound. No global JVM authenticator is used and no automatic direct retry occurs if that selected route fails. With no active VPN, public-feed bootstrap necessarily uses the app's direct HTTPS route and can still be blocked.

## HTTPS subscription import and automatic refresh

The existing Servers import accepts raw VLESS/VMess/Trojan/Shadowsocks links, Base64 feeds and public HTTPS subscription URLs (multiple lines). Imported subscriptions refresh every six hours, with a battery-not-low condition. Auto prioritizes saved subscription sources before community sources. Feeds remain untrusted: TLS, limits, validation, deduplication and a real route test are required.

Saved subscription URLs/tokens are encrypted using AES-256-GCM with an Android Keystore key and authenticated context. This protects subscription addresses **at rest**, not all existing Room node records, device-root compromise or traffic metadata. Node credentials in the existing Room table are not newly encrypted in this release. Android backup is disabled.

HTTPS only; no URL-embedded username/password or fragments, local hosts/private DNS results are rejected on direct downloads, no TLS-to-HTTP redirects. Bounds: 2 MiB response, 500 candidate/import nodes per operation, 12 saved subscription URLs, four parallel source calls, 12-second request deadline, cancellation closes requests. Successful merges preserve stable IDs, favorites/active status and existing health results for identical full protocol/transport credentials. A TCP-open port never establishes health.

## Still not included / device verification required

No bundled Tor/Orbot bridge transports, bridge discovery, tpws/nfqws/zapret or V2Ray migration. Orbot with verified bridges remains a separate follow-up; no direct Tor fallback is introduced. No root request is added. No claim of confirmed YouTube video playback, Telegram MTProto/calls or Android realme installation. Web HTTPS checks do not test those app protocols; blocked IPs, unavailable servers and source bootstrap blocks may still prevent connection.

## Reproducibility and QA

On Linux install Android SDK 34/build-tools 34.0.0, JDK 17 and `sdkmanager 'ndk;27.2.12479018'`. Build with `GITHUB_RUN_NUMBER=45 ./gradlew -PautoPreview=true :app:testDebugUnitTest :app:assembleRelease :app:assembleDebugAndroidTest :app:lintRelease`. Sign the unsigned release APK only after testing; signing keys are never committed. Native source compilation is a preBuild task; binaries are generated, not checked into the source repository.

`python native/host-smoke-test.py` compiles the pinned native C code with the process wrapper, creates a local TLS fixture and checks SOCKS → actual split/TLS-record splitting → certificate-verified HTTPS 204. This validates native behavior on Linux, not Android SELinux/VpnService or provider censorship.

Final release rebuild succeeded: 131 JVM/Compose tests, 0 failures/errors; Android lint 0 errors, 41 warnings. Signed APK is 46,662,346 bytes (~44.5 MiB), version 1.0.45 (45), ARM32/ARM64, extractNativeLibs=true, not debuggable/testOnly. ZIP integrity, alignment and Android 13 signature verification pass; signer SHA-256 remains a90f21276a337bb1cbec545d23fce00e173b46cec83f5af015076e53d5488d54. The actual pinned native Linux TLS/HTTPS smoke test passes. APK SHA-256: 06561fbdfe62a1307aca9961cfae869ed8817e183320c29a4e9070cd34cf3439. Android instrumentation tests include bundled native schema with detour/dual-stack configuration; the instrumentation APK is compiled only, not executed on a physical device/emulator. Android Keystore persistence, native child execution and realme installation require device testing.

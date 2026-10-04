# Native connection crash — interface-prefix correction (1.0.43)

## Evidence

The phone report identifies realme RMX3624 (RMX3624RU), Android 13 / SDK 33, app 1.0.42, native termination status 6. The Java trace in that report was created by the previous diagnostics reader and was **not** the crashing native stack. The phone's actual native crash location remains unconfirmed.

A separate concrete code defect was found in the connection startup path: `getInterfaces()` supplied bare IP strings from `NetworkInterface.inetAddresses`. Upstream libbox `platformInterfaceWrapper.NetworkInterfaces()` passes these values directly to Go `netip.MustParsePrefix`, which panics for a bare IP or a scoped IPv6 address. The bundled arm64 library also contains the `net/netip.MustParsePrefix` and native interface-wrapper symbols.

References:
- https://github.com/SagerNet/sing-box/blob/v1.13.0/experimental/libbox/service.go
- https://github.com/aosp-mirror/platform_frameworks_base/blob/android13-release/core/java/android/app/ApplicationExitInfo.java
- https://github.com/aosp-mirror/platform_system_core/blob/master/debuggerd/proto/tombstone.proto

This is a strong candidate for the reported startup abort, not proof of the phone's exact failure. Previous non-null callback fixes remain included.

## Changes

- Use each `InterfaceAddress`'s real prefix length and send `IP/prefix` strings.
- Remove IPv6 `%zone` identifiers and skip invalid prefix lengths before calling Go.
- Supply Linux interface flags and a positive MTU.
- On Android 12+, decode the system-provided native tombstone **when available**. The saved report contains only signal, a sanitized abort message and the crashing thread's native frame information. Memory dumps, registers, logs, command lines and file-descriptor details are not exported; the raw protobuf is not saved or uploaded.
- Do not present a diagnostics-reader Java stack as the native crash stack. If the system has no trace or it cannot be decoded within limits, the report says so explicitly.
- Limit system trace input to 2 MiB and frame output to 24 frames; bound protobuf fields and text.
- Improve recognized SNI/endpoint redaction and remind readers to review reports before sharing. Sanitization cannot guarantee removal of arbitrary secrets in free-form text.

## Verification

- Main APK and instrumentation test APK built successfully using JDK 17 / Android SDK 34.
- 98 JVM tests passed: 0 failures, 0 errors, 0 skipped.
- Four new interface-prefix tests, five bounded tombstone-decoder tests and three report-sanitization tests.
- Existing random CDN-SNI test corrected to include the already-configured .tv/.us/.so/.co vendor TLDs; application pool behavior was not changed.
- Lint: 0 errors, 40 warnings. APK signature and ZIP page alignment verified.
- Signing certificate matches 1.0.42; package remains `com.vlesscardvpn.preview`, versionCode 43.
- Native instrumentation compiled, **not executed**. No physical-device connection verification has been performed.

## Install/check

This remains a separate diagnostic package `com.vlesscardvpn.preview`, displayed as **VLESS Card VPN · Test**. Version 1.0.43 uses the same local debug signing certificate as 1.0.42, so an in-place update should retain Test app data. Do not uninstall or replace the production package.

Install the new APK, open Test, verify version 1.0.43, and connect using a known-working configuration. If it exits, reopen Test and share the latest **NATIVE_CRASH** report after reviewing it for personal information. A retained 1.0.42 crash may also be imported with native details during the first launch of 1.0.43.

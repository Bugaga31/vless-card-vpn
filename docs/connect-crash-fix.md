# Connection-crash diagnostic build (Android 13)

## Confirmed code defects

- `CommandServerHandler.getSystemProxyStatus()` returned null. The upstream sing-box v1.13 Go adapter reads fields of the returned object without a nil guard. Android VPN mode now returns an explicit object with `available=false` and `enabled=false`.
- `PlatformInterface.findConnectionOwner()` returned null. The upstream Go adapter similarly dereferences the result. It now returns a non-null owner, resolves TCP/UDP ownership using Android's active-VPN API on Android 10+, and uses UID -1 (not UID 0) when ownership cannot be determined.
- Go panics/native aborts bypass Kotlin's exception handlers. On Android 11+, the next application launch imports only this package's native-crash/ANR exit metadata into local reports. Tombstones and logcat are not imported. Nothing is uploaded automatically.

References:
- https://github.com/SagerNet/sing-box/blob/v1.13.0/experimental/libbox/command_server.go
- https://github.com/SagerNet/sing-box/blob/v1.13.0/experimental/libbox/service.go

These are real unsafe callback contracts, but the reported realme crash has not been conclusively attributed to either without the phone's report. The bundled core remains sing-box/libbox; this is not a V2Ray migration or an integrated ByeDPI/zapret engine.

## Verification

Local verification for diagnostic version 1.0.42:

- Main application APK and instrumentation APK built successfully with JDK 17 / Android SDK 34.
- 86 JVM unit tests passed; 0 failures, 0 errors, 0 skipped.
- Android lint: 0 errors, 40 warnings.
- APK signature and ZIP page alignment verified.
- Added 3 native callback contract tests; instrumentation was compiled, **not executed**. No physical-device connection test has been performed.

The new diagnostic APK has a different debug signing certificate than the earlier preview. Android will not install it as an update to the old Test app; export its configurations and uninstall only the old Test app first. The production package is separate.

Physical-device testing and instrumentation execution are required before claiming the phone's connection crash fixed.

## Device check

1. Back up/export configurations before uninstalling any build.
2. Install the new diagnostic APK (`com.vlesscardvpn.preview`) and open **VLESS Card VPN · Test**, not the original app.
3. Android may reject an in-place upgrade if debug signing keys differ. In that case remove only the old **Test** app after exporting its configurations; do not uninstall the original app.
4. Import one known-working private node, use the stable TLS profile, grant the Android VPN request, and connect.
5. If it exits, reopen that same Test app and inspect **Settings → Error reports**. Native metadata appears after reopening on Android 11+; it is not a full native stack trace.

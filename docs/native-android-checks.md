# Native Android checks and late-result database fix

## Fix scope

Port checks, Auto TCP hints, and tunnel health results previously wrote an entire
server entity from a snapshot taken before the network request. A newer selection
or favorite change could therefore be overwritten by a late result. Auto's
insert-or-replace hint could also recreate a node deleted during the request.
These paths now use targeted SQL updates by ID. The updates preserve selection,
favorites, encrypted payload and unrelated measurements; absent IDs remain absent.
Failure counters update from the current row, not a stale copy. Favorite toggling
is a single SQL statement.

This does not claim to fix all import/update races or explain every failed tunnel.
Subscription merging still has its own read/merge/write path.

## Regression coverage

`NodeMetadataUpdateTest` runs three deterministic interleavings on Android Room:
late results after a selection/favorite change; late results after deletion;
and current-row failure increments/reset. A local SQLite check of the same SQL
statements passed. That check is not Android instrumentation execution.

`LauncherStartupTest` was updated to assert the current idle labels rather than
obsolete text. No startup auto-connect behavior was added.

## Controlled native outbound fixture

`NativeOutboundFixtureTest` adds five opt-in checks:

- compatible VLESS/TLS;
- the bundled core's TLS fragmentation;
- ByeDPI TCP-only, TLS-record-only and combined presets.

Each starts the actual bundled JNI core, authenticates to its loopback SOCKS
probe, and requests HTTPS through a controlled VLESS/TLS server. A 204 response
is accepted; an authenticated 302 is not incorrectly promoted to a successful
204 check. Test certificates are explicitly trusted only in this fixture;
certificate and hostname verification remain enabled. No custom crypto,
random SNI, insecure TLS, live public subscriptions or user keys are involved.

The test deliberately removes the TUN inbound. It tests the native outbound and
probe, NOT Android VpnService routing for other apps, YouTube video, Telegram
MTProto/calls or real ISP DPI. It is not a Russian-IP bypass test.

Start the local host fixture with:

```sh
python3 tools/native-route-fixture.py --sing-box /path/to/sing-box
```

The program prints the generated public CA path. Copy that certificate (not its
private key) with adb to `/data/local/tmp/vless-fixture-ca.pem` and pass
`-e fixture_ca_path /data/local/tmp/vless-fixture-ca.pem` to instrumentation.
The default fixture host is the Android emulator's `10.0.2.2` host-loopback alias.
The host listeners bind only to loopback. Ports 18443 and 24443 must be free.

The CI host backend is sing-box 1.14.2 with SHA-256-verified archive. The client
remains the repository's bundled libbox; this is not a core migration.

## Automated Android matrix — executed

`Android native route checks` is now published and runs on pushes to the
stability work branch or manual dispatch. The connector could not write the
workflow; publication was completed after explicit GitHub CLI authorization.

The first infrastructure run failed because setup-android's default package list
included the removed legacy `tools` package. The workflow now explicitly requests
platform-tools, API 34 and build-tools 34.0.0. SDK and emulator actions are
commit-pinned. The emulator runner invokes one Bash file so fixture PID, CA path,
failure handling and cleanup share the same shell.

The first actual instrumented run executed all 19 checks on both API 26 and API 33.
It found two migration failures per OS while all five controlled outbound modes
passed. The migration called `execSQL("PRAGMA secure_delete=ON")`, but Android
SQLite rejects row-returning statements through execSQL. This aborted migration
before legacy servers could load. The fix uses query, consumes the returned row
and verifies secure_delete is enabled. The transaction/rollback behavior is kept;
no database reset, record deletion or TLS-security relaxation was introduced.
This is a reproduced defect, not proof of the cause of every reported VPN failure.

### Verified successful run

- Source commit: `1f9c83b7ff9e54212c914c89c3ebd7cc13752a5f`.
- Run: https://github.com/Bugaga31/vless-card-vpn/actions/runs/37005630860
- API 26 x86_64: **OK (19 tests)**, verified from the uploaded native-results artifact.
- API 33 x86_64: **OK (19 tests)**, verified from the uploaded native-results artifact.
- Both migration success/rollback checks, Android Keystore, metadata interleavings,
  JNI callback contracts, native configuration validation and all five controlled
  VLESS/TLS outbound modes passed.

The workflow builds debug/test APKs and runs JVM tests before instrumentation.
It uploads diagnostic/test artifacts, not a signed release APK. The tested
REALITY case is schema validation only; the live controlled fixture is VLESS/TLS.

This matrix does not cover ARM/ARM64, realme firmware, every Android version,
mobile networks, power-saving policies, full TUN routing or all countries.
A passing loopback/native outbound fixture is not a YouTube/video, Telegram
MTProto/call or Russian-ISP bypass result.

## Local execution limitation

The local Linux host has no `/dev/kvm`. An API 33 x86_64 emulator was launched
with software emulation, but its first boot did not reach `sys.boot_completed=1`
within approximately 25 minutes. Installation attempts hit Android services
that were not yet initialized. No local instrumented result is claimed from
that emulator. The actual device-style results above came from hosted CI with KVM.

After the migration fix, local JVM regression tests, release Kotlin compilation
and Android-test Kotlin compilation passed. All **217 JVM tests** passed with
no failures/errors/skips. The generated host fixture also passed verified
TLS/HTTPS 204/302 and temporary-key cleanup; that host smoke test alone is not
Android/native/TUN evidence.

This change is source/test infrastructure only; no signed Lab update is included.
Do not uninstall the installed application or erase protected data to test it.

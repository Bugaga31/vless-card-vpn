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

## Automated Android matrix

`Android native route checks` is prepared locally for pushes to the stability
work branch or manual dispatch; it has NOT been added to GitHub. The connector
rejected a commit containing `.github/workflows/native-android-check.yml` with
403, while the same source/test changes without that workflow succeeded.
Publishing the workflow needs a connection authorized to write workflows.
The prepared workflow It builds debug/test APKs, executes JVM tests, and runs 19
instrumented JNI/Room/fixture checks on API 26 and API 33 x86_64 emulators with
KVM. Results are uploaded as test artifacts, not published as a release APK.
The runner is commit-pinned. Its script launches one Bash file so fixture PID,
CA path, failure handling and cleanup share the same shell.

There is no Actions run from this prepared workflow yet. Even publishing it
would not be evidence that it passed; check an actual run. This matrix does not cover ARM/ARM64, OEM firmware, every Android version,
mobile networks, power-saving policies, full TUN routing or all countries.

## Local execution limitation

The local Linux host has no `/dev/kvm`. An API 33 x86_64 emulator was launched
with software emulation, but its first boot did not reach `sys.boot_completed=1`
within approximately 25 minutes. Installation attempts hit Android services
that were not yet initialized. No local instrumented test result is claimed
from that emulator. The blocked emulator was stopped; native/test APKs and
JVM regression checks were built separately. The complete local build passed
(`testDebugUnitTest`, release Kotlin compilation, debug APK and Android-test APK).
All 217 JVM tests passed, with no failures/errors/skips. The generated host
fixture also passed verified TLS/HTTPS 204/302 and temporary-key cleanup.
The 19 Android instrumentation tests compiled but were not executed.
CI execution is blocked on workflow authorization and is not claimed.

This change is source/test infrastructure only; no signed Lab update is included.
Do not uninstall the installed application or erase its protected data to test it.

Vendored from https://github.com/bol-van/zapret (tpws/ directory)
Commit: c4d0b1990129dce2b28095277173ee0d7702d304
License: MIT (LICENSE-zapret.txt, from docs/LICENSE.txt). uthash.h: BSD (header retained). kavl.h: MIT (header retained).
andr/ (getifaddrs/netlink for old Android API levels) comes from musl libc, MIT licensed (https://musl.libc.org, COPYRIGHT).
Local change: tpws_conn.c — the "connection to local address" refusal can be disabled with the
VCVPN_TPWS_ALLOW_LOCAL environment variable. Only the instrumented loopback tests set it; the app never does.
Android executables are PIE; packaged as libtpws.so in nativeLibraryDir and run as a non-root child process in
--socks mode (no iptables, no root). Built by native/build-byedpi.sh; if the tpws build fails the APK still
builds and the app hides zapret strategies.

# Third-party components

This application no longer bundles 2dust/AndroidLibXrayLite (Xray-core).

The VPN core AAR (`libv2ray.aar`) is built locally from source via gomobile
(`v2ray-mobile/build-aar.sh`) from:

- v2ray-core v4.45.2, by v2fly and contributors, under MIT:
  https://github.com/v2fly/v2ray-core/tree/v4.45.2
  (QUIC/DoQ code paths are disabled via a compile-time stub; see
  `v2ray-mobile/quicstub/`.)
- tun2socks v2, by xjasonlyu and contributors, under MIT:
  https://github.com/xjasonlyu/tun2socks
- gomobile / golang.org/x/mobile, by the Go authors, under BSD-3-Clause.

Known functional differences from the previous Xray-core build:
no REALITY, no XTLS Vision flow, no uTLS fingerprint, no QUIC transport,
no built-in DNS module (system resolver via tun2socks is used instead).

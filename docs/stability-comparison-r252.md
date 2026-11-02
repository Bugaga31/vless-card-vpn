# R252: consistent Telegram stability series

Baseline: `v1.2.124`. This is a draft experiment, not a released APK.

## Observation and change

The previous three-sample Telegram series independently raced two built-in Telegram DCs for each sample. Different DCs can have different baseline RTTs: in a synthetic example of 40/240/40 ms, both endpoints can each be consistent while the mixed 200 ms spread fails the 150 ms gate. This is a code-level possibility, not a diagnosis of any user's network.

The initial probe still races both DCs; follow-up samples use the initial successful DC over the same local SOCKS route. Each response still needs valid MTProto framing and nonce. Failed/inconclusive samples do not pass; the spread, latency threshold, deadline, cancellation, and admission rules remain in place. A DC becoming unavailable during the series will fail or leave the result unknown; no alternate DC is silently substituted. Cache identity changes from `telegram-series-v2` to `telegram-series-v3` so prior evidence is not reused as proof of the new series.

The focused local tests include a loopback SOCKS fixture, first-race winner selection, consistent samples, true spread, failure, unknown, mismatched identity, and budget. They do not prove behavior on a physical Android device, an ISP, Telegram's real network, or overall user-server pass rates.

## Comparison and unfinished scale work

[Prihs](https://github.com/vpnsvpns/Prihs) is a public subscription collection with guides, not an Android VPN client to benchmark directly. Its guides caution against frequent node switching; no performance comparison was performed. [NetZapret](https://github.com/RixyPow/netzapret) targets Windows and provides a redacted support report; diagnostics and privacy are useful ideas, not a measured head-to-head comparison. No competitor code was copied.

The user requests a numeric choice of up to 100 or 1000 simultaneous server checks. It is **not implemented in this change**. Simply raising the current UI cap would be misleading: candidate waves and batches have separate bounds, individual Auto candidates create worker pools and SOCKS routes, and the shared Telegram pool is bounded. Resource limits, backlog/timeout semantics, cancellation, port availability, actual in-flight counts, and large-scale Android measurements need a coherent design before representing 1000 as genuinely concurrent. Never silently cap the requested setting or treat overload/unknown as proof of failure/success.

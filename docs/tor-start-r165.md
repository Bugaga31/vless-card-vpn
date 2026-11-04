# Standalone Tor startup regression

## Confirmed defect

The Home Tor selection sets `torAll=true`. `TorConnectionPolicy.runtime` uses the
serverless runtime mode, and `TunnelService` intentionally does not start the current
ByeDPI process for this route. Consequently its port is null. The configuration
builder nevertheless required a ByeDPI port and threw `ByeDPI не запущен` before
Xray and the Tor startup loop could run.

Earlier routing tests passed a dummy ByeDPI port, masking this integration boundary.
The new regression uses the actual Home selection/runtime transformation with an
empty server list and null DPI port. On the original source, 4 of its 8 initial tests failed;
three reported this exact error and one detected an unused saved VLESS outbound.

## Minimal correction

- Standalone Tor alone waives the server/DPI prerequisites.
- Saved VLESS outbounds and their balancers are not instantiated for `torAll`.
- A Tor startup failure cannot enter the ordinary Auto retry branch that changes
  masks or selects a serverless DPI fallback.
- Regular ByeDPI and Hybrid still require their process; Servers still requires a server.
- Explicit direct-domain and enabled RU-direct/DNS exceptions are retained; the main route and DNS-module route
  remain Tor. No new direct fallback is introduced.

This fixes configuration/startup preconditions, not the availability of Tor bridges
or an operator network. Real bootstrap, physical mobile/Wi-Fi and service traffic need
separate verification. A successful JVM test is not a successful Tor circuit.

The research language model is not included in this fix and no training is claimed.

An initial full-suite run caught a lost RU-direct exception (526 tests, 1 failure).
The exception was restored in code; the existing assertion was not disabled or weakened.
Two additional regressions cover RU-direct enabled and disabled without any server.

## Additional startup gate

The E2E job runs the generated standalone configuration using real Xray and a
strict loopback-only SOCKS fixture. It verifies that exactly one HTTP body transfer
uses the Tor outbound with no ByeDPI process. This is **not real Tor bootstrap**,
TLS service validation, censorship simulation or a phone/operator check.

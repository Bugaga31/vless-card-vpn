# Narrow Android 8 / API 26 TUN compatibility workaround

## Measured evidence before this patch

Source `3fe4bfb500d4446a5f062a66b0b7adf828a03141`, strict controlled CI:
https://github.com/Bugaga31/vless-card-vpn/actions/runs/37054255092

- API 33: all 39 tests passed after complete fixture resource isolation. This is one executed run, not proof that every intermittent problem is resolved.
- API 26: 39 executed, 32 passed and seven failed. The explicit gvisor case passed both reopen cycles, with verified default and VPN-bound DNS/HTTPS paths. All seven mixed-stack profile cases failed TCP connect on both paths, with an unchanged default network.
- API 26 reported one DNS server/two routes/MTU 0 through LinkProperties. That metadata is not proof that the actual kernel TUN MTU was zero; no global MTU workaround is inferred.

## Production change

`TunStackPolicy` selects **gvisor only for SDK 26**. Android 13 and all other SDKs retain mixed. API 27 was not covered by this evidence, so the workaround is not silently extended to it.

Production config generation derives the SDK from the Android Context. Controlled tests can pass a platform SDK explicitly. Contextless/offline generation with no SDK preserves its old mixed default.

No change to server credentials, TLS/REALITY verification, DNS detours, address/MTU/routes, traffic encryption, or direct/bypass behavior. This does not fix mixed itself and is not a new DPI bypass or cipher. CPU/battery and real ARM behavior are not measured here.

## Tests

Five new JVM tests cover scope, API 26/33 config selection, unknown-platform behavior and exact preservation of non-stack configuration. Executed local suite: **241 JVM tests**, zero failures/errors/skips; Android instrumentation compiled.

The seven existing default-profile full-TUN cases now exercise the application's actual selected stack instead of forcing mixed on every SDK. They assert generated policy before opening TUN. Every DNS, HTTPS, separate UID, default-network stability, VPN-bound path and reopen assertion remains mandatory; no cases are skipped or removed. An additional native check validates the context-derived policy against the running Android SDK and bundled core, bringing the strict gate from 39 to **40 tests**.

This is a documented production compatibility change, not a relabeling of failed mixed tests as passed. The prior mixed failures remain recorded above. A new run must verify all profiles on the selected stack; previous gvisor success with one compatible profile is insufficient evidence for the whole patch.

No main merge or user APK release. Realme hardware, Auto/foreground-service UI lifecycle, Russian providers, YouTube video and Telegram MTProto remain outside this fixture.

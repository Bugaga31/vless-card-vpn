# Auto baseline comparison

Sources compared: `v1.0.100` (dfae92b), `v1.1.0` (03658c8), and `v1.2.33` (bdeac329). No phone/carrier benchmark is claimed.

Both old tags use the same broad `quickTest`: TCP over the supplied list with a 1500ms timeout, nearest 30 then full route/download tests. 1.1.0 additionally learns from route probes; this comparison does not delete or reset that learning. A useful reachable route beyond the tiny first group could be found, whereas the newer bounded shortlist could miss it repeatedly.

1.2.33 also retained two wrong behaviors:
- `AutoSelection.compare` could return a service/HTTPS candidate after the throughput coroutine timed out without a sample. The existing timeout regression explicitly expected this fallback. Its assertion is corrected to require no automatic winner, preserving cleanup and manual usability checks; it is not disabled.
- The final actual-chain failure threw `ServiceProofException` and stopped the service, but left the per-route cached positive proof intact. A repeat button press could reuse it. Now only old proof for the same attempted configurations is revoked, without training a per-server failure from an unattributable chain/resource outcome.

The old full-catalog scan is not copied wholesale: a rotating window of 128 cheap candidates, at most 24 HTTPS finalists, existing traffic/power/cancellation/deadline bounds and strict service admission remain. This is coverage of deterministic selection cases, not measured lower connection time or universal access.

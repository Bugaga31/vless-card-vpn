# R240 diagnostic follow-up

R239 run 41337054071 passed retained-TUN background recovery on both API 29 and 33, but API 33 failed the earlier multi-selection optimization gate: optimize returned false, dead_selected=true, good_selected=3. The logs did not identify which context/power early return caused this result. The candidate remains blocked, even though its build and recovery correction passed.

R240 adds bounded DEBUG-only exit and aggregate sample diagnostics, plus boolean comparisons for physical-network context mismatch. It does not relax admission, network equality, power policy, transfer assertions or fixture selection. It does not expose network identifiers, URLs, server credentials or mask bodies. This is diagnostic instrumentation, not a claimed fix for the optimization failure. A fresh full run is required to establish its cause; a green re-run alone must not be described as fixing an unexplained failure.

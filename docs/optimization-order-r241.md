# R241 — repair before optional exploration

R240 diagnostic run 41338733863 passed API 33 without reproducing the R239 optimization failure. This is not proof of its exact original cause. Inspection identified an independent ordering hazard: deepPending work and its mid-pass power deferral happened before selected-pool health sampling and repair. A valid deferral could therefore exit before removing a broken member, even though optional exploration is not the same as route repair.

The bounded selected/candidate health pass and existing repair/upgrade policy now run first. If the measured pool changes, optimize returns immediately so the service can apply it; deepPending is left queued for a later healthy pass. Only an unchanged pool proceeds to optional deep exploration. No network equality, service admission, power-entry guard, user probe ceiling, QUIC policy or test expectation is relaxed. No new hard START, direct fallback or synthetic PASS is introduced.

Seven source-order regressions assert this ordering and preserve the original multi-selection and other-UID transfer gates. Source tests do not replace full API 29/33 execution or establish battery savings. R239's earlier API 33 failure remains disclosed; it is not described as a measured Russian-DPI failure. Final release requires a new full build/masks/install/E2E run of this exact snapshot.

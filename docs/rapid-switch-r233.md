# R233 — emergency server/mask search without the preceding native-only wave

Base: published Experimental1.2.117 source c137eb6302a986ab6f7442e7998ea5d2300e6036.

Confirmed queue issue: first autoRescue(level1) tested only previous works=true spares. Unknown alternatives and compatible masks waited for the next level3 attempt, native host wave and then a separate mask wave. ROKI ranked servers but could not move a mask ahead of those hard-coded phases. A certificate-pending spare could also delay a ready spare.

Emergency recovery now tries up to2 ready diverse alternatives in ROKI's existing order, interleaving each current route with its remembered compatible mask inside one bounded Tester core. Up to2 masks per server remain; user1/3/5/10 ceiling applies, up to6 variants with budget per queued cohort (max18s). No whole mask sweep, no extra native core in parallel. First complete selected-profile winner cancels siblings; cleanup finishes before selection/reconnect. No successful route's evidence is refreshed merely by ranking.

After the first real light-heartbeat miss, Auto's confirmation delay is1s instead of3s. Healthy10–15s/quiet20s heartbeat remains unchanged; two misses and call protection remain. No return to full periodic tests or healthy reserve probing. New profile/network, unknown/fail, native engine readiness and exact unchanged state remain guarded.

20 new JVM regressions include a real coroutine race: a mask wins while native waits, cancellation cleanup is asserted, and user-one-slot concurrency stays one. Own Android compile/JVM CI pending. Not a release APK, physical-phone latency, Doze, actual provider/DPI, call or battery measurement. No new claim of model training on owner's phone. ROKI ranks servers; session mask memory orders compatible mask trials; neither grants admission.

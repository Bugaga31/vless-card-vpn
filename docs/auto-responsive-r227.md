# R227 — responsive Auto and process-owned advice (draft, not a published release)

Base: 1.2.115 / fd29f4647b3ad7d6ba11e339edb9411bbb679557.

Owner reports lag, advice not automatic and slow Auto. Phone lag has not been reproduced; the changes below address observed code paths, not a measured FPS/battery improvement.

- Auto latency preset becomes 100–3000 ms. Existing pre-R227 presets migrate once; subsequent custom limits survive reload. Replies faster than 100 ms remain welcome. Freshness/profile/service checks remain mandatory; old 800-ms proof cannot be relabelled as current evidence.
- Continuous Auto tries one native cohort at the selected 1/3/5/10 ceiling, then the existing bounded 2-server × 2-mask rescue. It no longer spends up to six batches exclusively on native routes before mask rescue. Remaining native candidates remain in subsequent rounds, sources still fetch concurrently, full selected service admission remains unchanged. This does not guarantee a working node or a fixed connect time.
- Watson samples once per process while UI, VPN or explicit search is active, including when the Activity is stopped but VPN lives. Sampling stops when none is active; the separate 15-minute out-of-app Job remains opt-in. No wake lock or permission bypass; Android may suspend/kill the process.
- Full phone inventory is throttled to three minutes and UI visibility; basic advice/forecast sampling is every minute. No automatic scan of installed app contents or URLs is added. Learning remains opt-out through existing settings, and only optional background work is constrained, not the active VPN or user probe ceiling.
- PhonePower broadcasts coalesce through an IO channel. Main-thread callers read a cached plan and request refresh, instead of synchronously doing sensor/binder work. Background callers retain synchronous evaluation; state updates are serialized.
- Decorative Ocean animation pauses during explicit work; probe slots are unchanged. Actions initialization is idempotent, and obsolete model-directory cleanup runs on IO instead of recursively deleting on the UI thread at every Activity creation.
- ROKI already ranks automatically after eight defined context-specific observations. Its safety threshold and treatment of unknown/cancelled results are not weakened; no new claim of trained accuracy is made.

Validation required: Android compilation, targeted regressions including migration and service gates. Local pure-policy tests alone are not proof of Android runtime performance. No new physical-device TUN, battery, Doze, service availability or FPS measurement yet.

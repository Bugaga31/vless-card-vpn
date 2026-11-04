# Launch intro: ghost tap through the self-hiding intro (1.2.133)

## Evidence

- Release run 41890210666 (#227, `release-build-244` @ 1c87b68) failed in `emulator-install (34, google_apis)`, phase `home-ui`.
  API 30 passed in the same run. The artifact `home-ocean.png` shows the «Профиль проверок Авто» bottom sheet open over Home;
  the bounded diagnostic reported 27 app nodes, 12 labelled, a non-blank frame, last operation `home-evidence`.
- `AppUi` hides the intro 1400 ms after its first frame. `tools/capture-vibe-ui.sh` computed the centre of «Открыть приложение»
  from a hierarchy dump and tapped it afterwards. On the slower API 34 emulator (175 skipped frames at start) the intro was
  already gone, so the tap landed on the «Профиль Авто» row underneath (320×640 dp) and the sheet hid the navigation labels
  that `check_home_hierarchy.py` requires.
- Each attempt reused `/sdcard/vcard-ui.xml` on the device, so a dump that failed with exit status 0 could hand back an older frame.
- Users could hit the same path: a tap aimed at the intro button just as the intro hid itself opened whatever lay underneath.

## Changes

- `LaunchIntro` consumes every touch (a tap anywhere skips it) and fades out for 450 ms on top of Home
  (`AnimatedVisibility` + `fadeOut`), so a late tap is still caught by the intro.
- `capture-vibe-ui.sh` deletes the device dump before every attempt, rejects empty dumps, waits for the intro to hide itself
  and taps its button only if it stays through three consecutive fresh dumps. The failure diagnostic now names the missing
  required home labels and the font scale.

## Verified

- Local Robolectric reproduction at 320×640 dp (not committed; it needs Compose UI test dependencies):
  old code — a tap right after the auto-hide reached Home; new code — the same tap during the fade is swallowed,
  Home is clickable after the fade, «Открыть приложение» still dismisses the intro, a tap on the intro margin does not reach Home.
- `:app:lintRelease` and `:app:testReleaseUnitTest` (2252 tests) pass.

## Not verified

- Real phones. Only emulators and Robolectric.

## CI notes from the re-runs

- Run 41902601547 (#228): everything before `quality / e2e (33)` passed; that job failed only in the `warp` case —
  a free account was registered, but 0 of 4 Cloudflare WARP endpoints answered from that runner. The same build passed
  the `warp` case on API 29 in the same run, and API 33 passed it in #227 with the same Xray core.
- Run 41905886566 (#229): `quality / e2e (33)` hit «Timeout waiting for emulator to boot» on the first attempt and on the
  fresh-AVD retry (same runner). No app code ran there.

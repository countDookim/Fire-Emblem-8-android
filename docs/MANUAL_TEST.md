# Manual test checklist

Install `fe8-debug.apk` from the [`latest` release](../../../releases/tag/latest). Check the launcher build
string when reporting results; an older APK may not contain the latest changes. Every build requires ROM import.
For a fresh New Game test, clear app data and re-import the ROM so an existing suspend does not auto-resume.

## Ready for re-test — 2026-11-09

**Build 0.1.474 (`d91ed28`) is published; a newer build from `c339db5`+ adds the D-07 walking allocation fix.**
CI passed 639 tests locally with no failures or skips. It includes the D-08 dialogue/map restoration fixes,
D-06 rider-removal fix, D-07 unboxed walking sprite-cache lookup (`c339db5`), D-10 blinking and normal mouth
animation. Use a fresh New Game (clear app data and re-import the ROM), then check the affected rows below.
These changes still await owner device confirmation.

## Latest owner test — 2026-11-09

Build **0.1.467 (`060478a`)**:

- Walking animation itself looks correct. Off-screen exits still fail (D-06), and a rare brief stutter remains (D-07).
- Character portraits display correctly during dialogue. Blinking is missing (D-10).

Earlier device checks also confirmed boot to title, original sound, GPU rendering, touch buttons,
gamepad/keyboard, pause/resume, ROM import and close/reopen resuming turn 1.

## Open device issues

| ID | Priority | Observed behavior / re-test target |
|---|---|---|
| D-08 | High | After Seth, Eirika and Franz leave the castle, the background becomes corrupted when Seth and Franz start talking. It worsens afterward and persists for the rest of the run. **Fixes merged 6d3406c/910fcfa (freeze map animation during dialogue, then reload map graphics/palette), unverified: re-test an APK built from 910fcfa or later.** Expected: the outdoor talk background stays clean for the whole Seth/Franz/Eirika dialogue and the map looks correct afterward. Limit: the Valter battle scene that follows is not ported (battle animation). |
| D-06 | Normal | Franz and Seth should ride off-screen but still stop/remain at the screen edge. Implementing walking animation did not fix this behavior. **Fix merged 7ac8384 (refresh sprite lists after unit removal), unverified.** Re-test both riders: they should disappear completely after leaving, with no standing sprite left at the edge; continue through the next dialogue to check they stay gone. |
| D-07 | Normal | Units occasionally pause/stutter briefly while walking during the opening, then continue normally. **Fix merged c339db5 (remove per-frame `Int` boxing in `MuSpriteAnim.objectAt` during unit walking), unverified: re-test an APK built from c339db5 or later.** Expected: opening unit walks play smoothly without brief GC/allocation pauses. Limit: JVM allocation checks do not cover Android OS thread-scheduling or audio-buffer underruns. |
| D-10 | Normal | Portraits do not blink. **Fix merged 350f19a (blink proc ported), unverified: re-test on the first APK built from 350f19a or later.** Expected: during the Prologue opening dialogue each portrait's eyes close briefly every ~1-4 s (half-closed, closed, half-closed over ~10 frames). Normal mouth animation is now implemented in 28bef11; see the additional check below. |
| D-09 | Low (owner) | The Rapier-received popup never appears before the first battle. Eirika's inventory has **not** been checked on device because player-phase interactions are unavailable; do not infer that the item is missing. |

See [HANDOFF.md](../HANDOFF.md) for findings and verification status. Re-test an affected row when a fix
and its APK build are announced; include the launcher build string with the result.

## Additional check: portrait mouths (28bef11 or later)

Start a fresh New Game and watch both portrait orientations during dialogue. Mouths should move while text
prints and return to a resting shape while waiting for A; eye blinking should continue. This covers normal
speaking/resting mouths. Smiling/blended mouth paths are not reference-verified, and the exact initial animation
phase can differ from the original because reused proc memory is not reproduced. Device check pending.

## Known incomplete gameplay

The save menu/difficulty selector and player-phase actions (including inventory access) are not ported yet;
New Game is chosen automatically when there is no valid suspend. Battle animations are also incomplete;
special portrait animation modes remain unverified.

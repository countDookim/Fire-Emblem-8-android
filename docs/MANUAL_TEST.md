# Manual test checklist

Install `fe8-debug.apk` from the [`latest` release](../../../releases/tag/latest). Check the launcher build
string when reporting results; an older APK may not contain the latest changes. Every build requires ROM import.
For a fresh New Game test, clear app data and re-import the ROM so an existing suspend does not auto-resume.

## Ready for re-test — 2026-11-09

**Build 0.1.476 (`fbf71c5`) is published on `latest` (includes `c339db5` D-07 walking allocation fix).**
CI passed 639 tests with 0 failures and 0 skips. Use a fresh New Game (clear app data and re-import the ROM),
then check **D-07** below on **0.1.476 (`fbf71c5`)** or later (your Issue #5 test was on `0.1.474 / d91ed28`,
which pre-dated `c339db5`).

## Latest owner test — 2026-11-09 (Issue #5)

Build **0.1.474 (`d91ed28`)**:

- **Solved on device**: walking animations, portrait display, **D-08** dialogue background & post-dialogue map restoration (`6d3406c`/`910fcfa`), **D-10** portrait eye blinking (`350f19a`/`191449c`), and normal portrait mouth animation (`28bef11`).
- **Remaining open in Issue #5**:
  - **D-07**: brief audio stutter in the castle when the blue soldier walks up (`0.1.474` pre-dated `c339db5`; re-test on **0.1.476 / `fbf71c5`**).
  - **D-06**: Franz and Seth no longer stay stuck permanently on the edge (`7ac8384`), but pause briefly at the map edge before fading out instead of riding cleanly off-screen.
  - **Seth vs Valter battle animation**: missing (unported battle animation system; expected until M6 part 2).
  - **D-09**: missing Rapier-received popup (low priority).
  - **D-05**: player phase only moves the cursor (expected until M6 part 2 player-phase actions/menus are ported).

Earlier device checks also confirmed boot to title, original sound, GPU rendering, touch buttons,
gamepad/keyboard, pause/resume, ROM import and close/reopen resuming turn 1.

## Open device issues

| ID | Priority | Observed behavior / re-test target |
|---|---|---|
| D-07 | Normal | In `0.1.474` (`d91ed28`, before `c339db5`), there is a brief audio stutter in the castle when the blue soldier unit walks up, then the game continues normally. **Fix merged `c339db5` (remove per-frame `Int` boxing in `MuSpriteAnim.objectAt` during unit walking) and published in APK `0.1.476` (`fbf71c5`).** Steps: start a fresh New Game and listen/watch as the blue soldier walks up to Eirika and Seth in the castle. Expected: the walk starts and plays without an audio hitch. Limit: `StartMuInternal` still decompresses the soldier MU sprite sheet (~11 KB) on the walk-start frame; if the stutter persists on `0.1.476+`, report it so MU sheet decompression can be pre-cached/pooled. |
| D-06 | Normal | In `0.1.474` (`d91ed28`), Franz and Seth no longer remain permanently on the edge (`7ac8384`), but stop right on the edge for a moment before fading out instead of cleanly riding out. **Open (under investigation):** in the castle, Franz reaches `(12,11)` ~32 frames before Seth reaches `(13,11)` (`ENUN` waits for both), `MuCtr_OnEnd` un-hides the standing sprite for 1 frame before `DISA`, and outside the castle `FlashCursor(..., 60)` waits 60 frames with an unported cursor-flash hook before the dialogue/fade. No new D-06 code change beyond `7ac8384` yet. |
| D-09 | Low (owner) | The Rapier-received popup never appears before the first battle. Eirika's inventory has **not** been checked on device because player-phase interactions are unavailable; do not infer that the item is missing. |

See [HANDOFF.md](../HANDOFF.md) for findings and verification status. Re-test an affected row when a fix
and its APK build are announced; include the launcher build string with the result.

## Known incomplete gameplay

The save menu/difficulty selector, battle animations (such as Seth vs Valter), `FlashCursor` highlight sprite,
and player-phase actions (including unit selection, menus, and inventory access) are not ported yet;
New Game is chosen automatically when there is no valid suspend.

## Milestone 6 Prologue verification (M6-45)

**Acceptance target:** "New Game plays the Prologue opening; turn 1 shows units, cursor, BGM; reopen resumes"

1. **Prologue Opening Flow**:
   - Clear app data and launch `fe8-debug.apk`. Import `baserom.gba`.
   - Title screen loads and starts New Game automatically (or press A).
   - Prologue opening sequence plays: Renais castle dialogue between King Fado, Eirika, and Seth; blue soldier unit walks up; dialogue with portraits (blinking eyes, talking mouth animations); Valter cutscene.
   - Zero audio stutter during unit walk (D-07 fix) and zero frame stalls during steady-state dialogue and MU walking (M6-45 zero-allocation hot paths).
2. **Turn 1 Player Phase**:
   - Following the cutscenes, the game transitions to Prologue map (player phase, Turn 1).
   - Units (Eirika, Seth, Breguet and enemy units) are visible on map with correct faction palettes and standing map sprites.
   - Map cursor is visible and responsive to D-pad / touch / keyboard directional controls.
   - Chapter map BGM plays cleanly.
3. **Suspend & Reopen Resumption**:
   - Close the app (or press Home and kill process).
   - Reopen the app: the game loads the suspend state and immediately resumes at Turn 1 on the Prologue map with units, cursor, and BGM active.


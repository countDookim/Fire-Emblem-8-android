# Manual test checklist

Install `fe8-debug.apk` from the [`latest` release](../../../releases/tag/latest). Check the launcher build
string when reporting results; an older APK may not contain the latest changes. Every build requires ROM import.
For a fresh New Game test, clear app data and re-import the ROM so an existing suspend does not auto-resume.

## Latest owner test — 2026-11-09 16:17 (side-by-side vs original)

Build: **0.1.482 (`617b1e9`)**.

- **Equal to the original**: music, sound effects, portraits and portrait animation, walking animations,
  Seth/Franz riding off-screen (**D-06 solved**), plus the earlier D-08/D-10/mouth fixes.
- **Still different**:
  - **D-07**: audio stutters at >= 2 points in the castle part of the Prologue cutscene.
  - **D-11**: opening cinematic after New Game (before the castle cutscene) is missing.
  - **D-09**: Rapier-received popup missing.
  - **D-12**: "Player Phase" popup missing; control goes straight to the cursor.
  - **D-05**: player phase cursor moves but nothing is interactable (M6 part 2).
  - Seth vs Valter battle animation missing (M6 part 2).
  - Known: no main menu / save-file / difficulty select yet.

Earlier device checks also confirmed boot to title, original sound, GPU rendering, touch buttons,
gamepad/keyboard, pause/resume, ROM import and close/reopen resuming turn 1.

## Open device issues

| ID | Priority | Observed behavior / re-test target |
|---|---|---|
| D-07 | Normal | Owner 11-09 (`0.1.482` / `617b1e9`): stutters at >= 2 points in the castle cutscene still (persists after `c339db5` and M6-45). Earlier: in `0.1.474` (`d91ed28`, before `c339db5`), there is a brief audio stutter in the castle when the blue soldier unit walks up, then the game continues normally. **Fix merged `c339db5` (remove per-frame `Int` boxing in `MuSpriteAnim.objectAt` during unit walking) and published in APK `0.1.476` (`fbf71c5`).** Steps: start a fresh New Game and listen/watch as the blue soldier walks up to Eirika and Seth in the castle. Expected: the walk starts and plays without an audio hitch. Limit: `StartMuInternal` still decompresses the soldier MU sprite sheet (~11 KB) on the walk-start frame; if the stutter persists on `0.1.476+`, report it so MU sheet decompression can be pre-cached/pooled. |
| D-09 | Low (owner) | The Rapier-received popup never appears before the first battle. Eirika's inventory has **not** been checked on device because player-phase interactions are unavailable; do not infer that the item is missing. |
| D-11 | Normal | Opening cinematic after New Game (before the castle cutscene) is missing. |
| D-12 | Normal | "Player Phase" popup missing after the final Prologue dialogue; control goes straight to the cursor. Fix merged 11-09 (D12-1, scheduled): re-test on the first APK built after it. Steps: clear app data, import ROM, New Game, play the opening to the end. Expected: "Player Phase" banner slides in over squares and a blended band (~70 frames), then map BGM and cursor control, as in the original. Limits: the VCount blend split (band translucency per line) is not reference-checked; enemy/green phase banners untested. |

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


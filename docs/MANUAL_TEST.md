# Manual test checklist

Install `fe8-debug.apk` from the [`latest` release](../../../releases/tag/latest). Check the launcher build
string when reporting results; an older APK may not contain the latest changes. Every build requires ROM import.
For a fresh New Game test, clear app data and re-import the ROM so an existing suspend does not auto-resume.

## Newly testable — published 0.1.487 (`c998d7c`)

This APK includes the startup PCM reserve and corrected phase-banner scanline boundaries.

1. Clear app data, import your ROM, and start New Game. Listen throughout the castle scene, including the blue soldier walking up. Expected: continuous audio across short delivery delays. Report whether both reported hitches remain; D-07 is still open. Long stalls can still exhaust the reserve.
2. Continue past the final dialogue before the map begins. Expected: the "Player Phase" banner, squares and blended band appear for about 70 frames, then clear for the map music and cursor. D-12 awaits owner confirmation; exact original scanline blending and enemy/green banners remain unverified.

The subtitle loaders in this APK are standalone. Later timer/fade code is not published yet; the missing opening cinematic (D-11) is not wired. Player-phase interactions and the Rapier popup are still missing.

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
| D-07 | Normal | Persists on owner-tested 0.1.482 (`617b1e9`). Published in 0.1.487 (`c998d7c`): change `63e638e` waits for two VBlank PCM chunks before playback, keeping a reserve for short producer delays. Re-test on an APK containing that commit: fresh New Game, listen throughout the castle scene, especially the blue soldier walking up; report whether both reported hitches remain. Expected: continuous audio across short delays. Limits: deterministic simulated cadence passes, but the phone cause is unconfirmed; long stalls can still exhaust the reserve and do not trigger re-prefill. Older APKs do not contain this change. |
| D-09 | Low (owner) | The Rapier-received popup never appears before the first battle. Eirika's inventory has **not** been checked on device because player-phase interactions are unavailable; do not infer that the item is missing. |
| D-11 | Normal | Opening cinematic after New Game (before the castle cutscene) is missing. |
| D-12 | Normal | "Player Phase" popup missing after the final Prologue dialogue; control goes straight to the cursor. Fix merged 11-09 (D12-1, scheduled), with scanline correction `aefe6d8`; published in 0.1.487 (`c998d7c`). Steps: clear app data, import ROM, New Game, play the opening to the end. Expected: "Player Phase" banner slides in over squares and a blended band (~70 frames), then map BGM and cursor control, as in the original. Limits: the VCount blend split (band translucency per line) is not reference-checked; enemy/green phase banners untested. |

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
   - Listen for and report audio hitches during the castle scene (D-07 remains open). Steady-state dialogue and MU walking have zero-allocation tests; those tests do not establish smooth playback on the phone.
2. **Turn 1 Player Phase**:
   - Following the cutscenes, the game transitions to Prologue map (player phase, Turn 1).
   - Units (Eirika, Seth, Breguet and enemy units) are visible on map with correct faction palettes and standing map sprites.
   - Map cursor is visible and responsive to D-pad / touch / keyboard directional controls.
   - Chapter map BGM plays cleanly.
3. **Suspend & Reopen Resumption**:
   - Close the app (or press Home and kill process).
   - Reopen the app: the game loads the suspend state and immediately resumes at Turn 1 on the Prologue map with units, cursor, and BGM active.


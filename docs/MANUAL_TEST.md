# Manual testing

Latest owner test: **published 0.1.571 (`a1978d9`), 2026-11-11** (Rapier popup).
Castle stutter fix published in **0.1.497 (`42dc059`)**; no additional castle re-test is needed.
Popup fixes are published in **0.1.528 (`e0187ea`)**.

- **D-07 resolved on this build:** the owner heard neither castle stutter; the retest trace had zero
  repeated PCM frames. The movement-map frontier replaced repeated full-map scans.
- **D-12 player banner confirmed (owner, 2026-11-11, 0.1.488):** the Player Phase banner is correct.
  Enemy/green banners are not ready (D12-2, with M6-D).
- **D-09 resolved (owner, 2026-11-11, 0.1.571):** the Rapier popup is correct.
- **APP-1 confirmed (owner, 2026-11-11, 0.1.590):** Delete save works.

## Test now

- **PP-0a hover cursor (owner device report 11-11):**
  1. Boot into New Game or resume a Prologue save (turn 1 map).
  2. Move the map cursor over a player unit (Eirika at (4,5) or Seth at (4,4)).
     - Expected: the cursor immediately expands/widens (uses stretched frame kind 3) to frame the unit sprite rather than staying at the compact corner size.
     - After hovering for 5 frames (~0.08 s), the unit begins its active standing idle animation (MU).
  3. Move the cursor away to an empty tile (e.g. (5,5)):
     - Expected: the cursor immediately returns to the normal compact animated frame (kind 0); the player unit returns to its resting standing sprite.
  4. Limits: unit selection/movement/action menus remain stand-ins (M6-B/M6-C).

- **D-13 Start during the Prologue castle cutscene (D13-1, D13-2):**
  1. New Game, wait for the castle scene's first text box, press Start once.
     - Expected: the screen fades to black in ~4 frames, the rest of the scene is skipped, the next scene fades in
       (no visible fast-forward). Black for about 45 frames, then the next scene fades in over ~16 frames and plays
       normally (it waits for A at its first text box).

## Known differences (owner reports)

- Map cursor over a player unit: resolved by PP-0a (cursor expands to stretched frame kind 3 when hovering
  a player unit; active MU starts after 5 frames).
- Start during the Prologue in-game cutscene fast-forwards visibly instead of fading out and skipping
  (owner, 2026-11-11, 0.1.590). Fix planned as D13-cap1/D13-1.

## Currently ignore

These are known gaps; re-test them only when a relevant change is announced:

- D-11: missing cinematic between New Game and the castle scene (testable after M6-A); subtitle, Start-skip
  and world-map portrait ports remain standalone and unwired. No new device check for these yet.
- Missing unit selection, actions, menus and inventory (M6-B, M6-H), combat (M6-C), enemy turns (M6-D), save/difficulty
  menus (M6-E) and the Seth/Valter battle animation (M6-G). Combat will resolve instantly until M6-G.
  Cursor movement works; New Game currently uses Normal difficulty.

Previously confirmed: castle audio, dialogue/portraits/blinking/mouths, backgrounds, walking and rider exits;
boot, ROM import, controls, GPU rendering and suspend/resume. Report regressions if noticed.

Install `fe8-debug.apk` from the [latest release](../../../releases/tag/latest); every build requires your ROM.
Implementation status: [HANDOFF](../HANDOFF.md).

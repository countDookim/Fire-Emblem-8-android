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

- **D11-wire-b opening monologue (New Game):**
  1. From the title press START (New Game). Expected: the opening monologue now plays before the
     castle scene: the title theme stops, then a slow fade-in to the white text cards with the
     blend transitions and light flare, about a minute long, ending in a slow fade to black.
  2. Repeat and press START during the monologue (try early, mid and late). Expected: the music fades
     out quickly (about 20 frames), the screen fades to black in about 10 frames, then the castle
     scene starts.
  - Limits: the timing runs about 6 frames short of the original (scene setup runs atomically).
     Report anything that looks different from the monologue on the original game. The world map
     that follows is the next entry.

- **D11-wm9 Prologue world map (New Game):**
  1. From the title press START (New Game), let the monologue end (do not press anything for
     about 10 more seconds after the last card). Expected: the music fades out, then the big
     Magvel map appears (the screen was black before, the narration box shows at the bottom),
     with the narration text paging by itself or with A, the map scrolling to different regions,
     the nations being highlighted one after another with the portraits of their rulers at the
     side, and then the picture fading to black. The whole map takes about a minute on the phone.
  2. D11-ci5 chapter intro: after the world map the "Prologue" title card appears with drifting
     fog, flashing lights, the decal banner and scrolling backgrounds (about 9 seconds), then the
     picture fades to the Renais Castle map and the castle scene starts. Pressing A, B or Start
     after the first second skips the rest: the screen fades out (about one second) and then
     fades into the map. The sound effect fade at the end of the intro is not wired yet.
  - Limits (all known): the map sprites are not drawn yet (units move invisibly, no node icons, no
     dots); the narration box scanline effect and sound effects of the map are
     missing. The text speed follows the A button: hold A to speed it up. Report a crash or a
     stuck black screen on the world map, and anything that looks clearly different from the
     original (the order of the highlighted nations, the portraits, the text).

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

- D-11: the monologue and the world map are wired (see Test now); the map sprites and the unit
  commands (D11-wm8e) are not.
- Missing unit selection, actions, menus and inventory (M6-B, M6-H), combat (M6-C), enemy turns (M6-D), save/difficulty
  menus (M6-E) and the Seth/Valter battle animation (M6-G). Combat will resolve instantly until M6-G.
  Cursor movement works; New Game currently uses Normal difficulty.

Previously confirmed: castle audio, dialogue/portraits/blinking/mouths, backgrounds, walking and rider exits;
boot, ROM import, controls, GPU rendering and suspend/resume. Report regressions if noticed.

Install `fe8-debug.apk` from the [latest release](../../../releases/tag/latest); every build requires your ROM.
Implementation status: [HANDOFF](../HANDOFF.md).

# Manual testing

Latest owner test: **published 0.1.622 (`22cfc9d`), 2026-11-12**: monologue, world map, monologue skip and hover cursor correct; new D-14 (no fade-in after the world-map skip), D-15 (no class reel on title idle).
Castle stutter fix published in **0.1.497 (`42dc059`)**; no additional castle re-test is needed.
Popup fixes are published in **0.1.528 (`e0187ea`)**.

- **D-07 resolved on this build:** the owner heard neither castle stutter; the retest trace had zero
  repeated PCM frames. The movement-map frontier replaced repeated full-map scans.
- **D-12 player banner confirmed (owner, 2026-11-11, 0.1.488):** the Player Phase banner is correct.
  Enemy/green banners are not ready (D12-2, with M6-D).
- **D-09 resolved (owner, 2026-11-11, 0.1.571):** the Rapier popup is correct.
- **APP-1 confirmed (owner, 2026-11-11, 0.1.590):** Delete save works.

## Test now

- **D-14 world-map Start skip:** New Game, wait for the world-map narration, press Start once
  while a portrait or text is shown (try a few times, also while a portrait is up). Expected: the
  screen cuts to black at once (no slow fade), the whole chapter title card plays, black, then the
  castle scene fades in from black over about a quarter second and its first text box appears. The
  game must not freeze or stay dark. Limits: the castle scene waits about 3 frames before its fade
  (the original waits 15 because its loop overruns); pressing Start again during the title card
  skips it, and during the castle scene skips the scene (as in the original).
- **D-15 title idle:** Leave the title screen untouched for about 14 seconds. Expected: it fades out
  and the opening animation plays (no black screen); after it the title returns; idling again goes
  to the opening animation again. Limits: the class reel that the original plays first is skipped
  until M6-G (it needs battle animations).

- **PP-17 player phase (Prologue turn 1):**
  1. Resume the Prologue (suspend save) or play New Game to the turn-1 map (Eirika near the
     castle gate, three red enemies to the right). Expected: the goal window (chapter goal) at the
     top right, the terrain window at the bottom right, the unit window with Eirika's face and HP at
     the top left when the cursor is on her.
  2. Put the cursor on Eirika and press A. Expected: blue squares (move range) with a red ring
     around it (attack range), the path arrow follows the cursor, the move sound. B cancels (Eirika
     is back, squares gone).
  3. Select her again, move the cursor two tiles right and press A. Expected: she walks the arrow
     path, then the unit menu opens with Item and Wait (no Attack: no enemy is in reach). Moving
     her next to a red unit instead shows Attack first; choosing it only plays the refusal beep and
     keeps the menu open (the weapon menu is MC-3a).
  4. B in the unit menu. Expected: she is back at her start tile and selected again, the range shows.
     Move again and choose Wait. Expected: the menu closes, Eirika greys out, the cursor is free.
  5. Press A on an empty tile. Expected: the map menu (Unit, Status, Guide, Options, Suspend,
     End). Choose End. Expected: the "Enemy Phase" banner, a short scene or nothing, then, since the
     enemies do nothing, the "Player Phase" banner of turn 2 and Eirika is no longer grey.
  - Limits (stand-ins): Attack, when offered, only beeps (the weapon menu, target selection and combat are M6-C); Item opens nothing and
    answers with the refusal beep (M6-H); the map menu's Unit, Status, Guide, Options and Suspend,
    and R / L / START in the map do nothing (M6-F; Suspend M6-E); enemies do not move or attack
    (M6-D); no staff range, no traps, no after-move or tutorial events, no records (M6-C, M6-E,
    M6-H). Selecting an enemy shows no range. The save is written at each turn start as in the
    original but not at an action. Frame timing matches the captures except the five frames the
    original spends writing the suspend save after Wait.

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
     D11-wm10: the map units (Eirika's group, the soldiers) and the marked points (dots) are
     drawn: small sprites walk across the map while the narration runs, fade in and out, then
     vanish.
  2. D11-ci5 chapter intro: after the world map the "Prologue" title card appears with drifting
     fog, flashing lights, the decal banner and scrolling backgrounds (about 9 seconds), then the
     picture fades to the Renais Castle map and the castle scene starts. Pressing A, B or Start
     after the first second skips the rest: the screen fades out (about one second) and then
     fades into the map. The sound effect fade at the end of the intro is not wired yet.
  - Limits (all known): no node icons; no unit step sounds (the original plays none in the
     Prologue). New (D11-wm11): the per-line colour gradient of the narration box now runs (check
     the box at the bottom for a smooth top-to-bottom colour change, not a flat colour). The text
     speed follows the A button: hold A to speed it up. Report a crash or a
     stuck black screen on the world map, and anything that looks clearly different from the
     original (the order of the highlighted nations, the portraits, the text).

- **PP-0a hover cursor (owner device report 11-11; confirmed on 0.1.622, 11-12):**
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
- Missing attack and item commands, inventory and the info screens (M6-C, M6-F, M6-H), combat (M6-C), enemy turns (M6-D), save/difficulty
  menus (M6-E) and the Seth/Valter battle animation (M6-G). Combat will resolve instantly until M6-G.
  Cursor movement, unit select, move, Wait and End turn work (see Test now); New Game currently uses Normal difficulty.

Previously confirmed: castle audio, dialogue/portraits/blinking/mouths, backgrounds, walking and rider exits;
boot, ROM import, controls, GPU rendering and suspend/resume. Report regressions if noticed.

Install `fe8-debug.apk` from the [latest release](../../../releases/tag/latest); every build requires your ROM.
Implementation status: [HANDOFF](../HANDOFF.md).

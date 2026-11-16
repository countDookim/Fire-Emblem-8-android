# Manual testing

**APK:** install `fe8-debug.apk` from the
[latest release](https://github.com/countDookim/Fire-Emblem-8-android/releases/tag/latest)
(rolling: always the newest master build; its title names the commit). Every build needs your ROM.

Last owner test: 0.1.622 (`22cfc9d`, 2026-11-12). Everything confirmed up to then has been removed
from this page (history: git log, [HANDOFF](../HANDOFF.md) "Device issues"). Only untested items remain.

## Test now

- **APP-icon:** (1) The launcher icon is a gold shield with a sword on dark blue (original art, not
  from the game). (2) After importing the ROM (and on each later start), recents shows Eirika's
  portrait as the app icon. (3) After an import a dialog offers "Add to home screen"; the launcher
  also has an "Add to home screen" button. Accept the system prompt: the shortcut shows the portrait
  and starts the app. Limits: needs a launcher that supports pinned shortcuts; recents icon
  may depend on the Android version; before an import there is no portrait.
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
- **D-13 Start during the Prologue castle cutscene:** New Game, wait for the castle scene's first
  text box, press Start once. Expected: the screen fades to black in ~4 frames and the rest of the
  scene is skipped (no visible fast-forward, which was the reported bug). Black for about 45 frames,
  then the next scene fades in over ~16 frames and plays normally (it waits for A at its first
  text box).

- **EP-18 map battle info box (needs a build with EP-18):** attack an enemy with Eirika (PP-17 step 3d) and
  let the Soldiers attack at End (step 5). Expected: when the fight starts, a two-row box with both
  units' names and HP appears next to the fighters (top or bottom of the screen) over a coloured
  vertical gradient; it closes after the fight, and the map, menus and windows look as before
  afterwards (no leftover tiles or wrong colours). The fight takes about a third of a second longer
  than before. Limits: the HP numbers and bars do not drain yet (round animations EP-23/24 missing),
  so the box shows the HP before the fight and closes; no hit flash, death fade or exp bar yet.

- **PP-17 player phase (Prologue turn 1):**
  1. Resume the Prologue (suspend save) or play New Game to the turn-1 map (Eirika near the
     castle gate, three red enemies to the right). Expected: the goal window (chapter goal) at the
     top right, the terrain window at the bottom right, the unit window with Eirika's face and HP at
     the top left when the cursor is on her.
  2. Put the cursor on Eirika and press A. Expected: blue squares (move range) with a red ring
     around it (attack range), the path arrow follows the cursor, the move sound. B cancels (Eirika
     is back, squares gone).
  3. Select her again, move the cursor two tiles right and press A. Expected: she walks the arrow
     path, then the unit menu opens with Item and Wait (no Attack: no enemy is in reach).
     Attack flow: move her next to a red unit instead; the menu shows Attack first. Choose it.
     Expected, in order:
     a. A framed weapon list with a hover bar (icon, name, uses per row); only the red squares of
        the highlighted weapon's reach stay. Eirika's face blinks at the right and an item panel
        at the lower right shows Atk, Hit, Crit and Avoid with the weapon's type icon.
     b. A on a weapon: the list closes, the red cursor sits on the enemy in reach, Eirika turns
        towards it. The forecast window slides in from the side away from the enemy: both names,
        HP, damage, hit and crit, weapon icons, weapon triangle arrows and a swaying x2 mark.
     c. B (cancel sound) slides the forecast out and reopens the weapon list; B again closes it.
     d. A on the target (confirm sound): the fight resolves at once. The enemy's HP drops by the
        forecast damage (or stays on a miss), the weapon's uses drop, Eirika greys out as after
        Wait. HP and uses stay as they are after Wait and End turn.
     e. A kill (Seth can kill on turn 1): the enemy vanishes from its tile at once; the tile is
        empty, the cursor can stand on it and A there opens the map menu. It does not return
        after End.
  4. B in the unit menu. Expected: she is back at her start tile and selected again, the range shows.
     Move again and choose Wait. Expected: the menu closes, Eirika greys out, the cursor is free.
  5. Press A on an empty tile. Expected: the map menu (Unit, Status, Guide, Options, Suspend,
     End). Choose End. Expected: the "Enemy Phase" banner, then the two Soldiers walk one after the
     other to the tile next to Eirika with the target cursor on her (about 1 second each) and
     attack her. The result applies at once: one Soldier hits her (the HP of the unit at (4, 5)
     drops by 9) and each Soldier dies to her counter and vanishes. After each Soldier the game
     pauses for about 0.3 s (the map fade; the tiles do not change, so it is only a pause). Each
     walk has footstep sounds panned by the walker's screen position (needs sound effects on).
     Then the "Player Phase" banner of turn 2 and Eirika is no longer grey.
  - Limits: frame timing matches the captures except the five frames the original spends writing
    the suspend save after Wait. The save is written at each turn start as in the original, not at
    an action. Everything else missing here is listed under "Ignore".

## Ignore (known gaps, not bugs)

Re-test these only when a change to them is announced.

- **Combat display:** no battle animation or map round animation (the info box shows, but its HP
  does not drain), death fade, exp bar, level-up display (stat gains
  are applied but not shown), counter animation, weapon-broke or item-drop popups (M6-G, M6-H,
  MC-18). Eirika's exp is held in the unit but not shown.
- **Commands and screens:** Item answers with the refusal beep; the map menu's Unit, Status, Guide,
  Options and Suspend, and R / L / START on the map do nothing; no help box, inventory or info
  screens (M6-E, M6-F, M6-H). Selecting an enemy shows no range.
- **Map events and rules:** no staff range, traps, after-move or tutorial events, dialogue during
  the enemy phase, or records (M6-C, M6-E, M6-H). Enemy and green phase banners (D12-2).
- **Opening:** the title's class reel (D-15, M6-G); the chapter intro's closing sound-effect fade;
  world-map node icons. New Game always uses Normal difficulty (save/difficulty menus M6-E).
- **Popups:** other item-popup branches than the confirmed Rapier one (D09-3, low priority).

Report any regression in things confirmed earlier (boot, ROM import, controls, rendering, suspend and
resume, the opening monologue, world map and title card, castle scene and audio, the player phase
banner, the hover cursor, the Rapier popup, Delete save).

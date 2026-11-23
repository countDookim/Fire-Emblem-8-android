# Manual testing

**APK:** install `fe8-debug.apk` from the
[latest release](https://github.com/countDookim/Fire-Emblem-8-android/releases/tag/latest)
(rolling: always the newest master build; its title names the commit). Every build needs your ROM.

Last owner test: 0.1.852 (`5ee1049`, 2026-11-23, [issue #7](https://github.com/countDookim/Fire-Emblem-8-android/issues/7)); before that 0.1.793 ([issue #6](https://github.com/countDookim/Fire-Emblem-8-android/issues/6)); last full pass 0.1.622 (`22cfc9d`, 2026-11-12). Everything confirmed up to then has been removed
from this page (history: git log, [HANDOFF](../HANDOFF.md) "Device issues"). Only untested items remain.

## Test now

Confirmed on 0.1.852 (issue #7) and removed here: enemy phase movement (EP-w), cutscene and world-map
Start skips (D-13, D-14), title idle without hang (D-15), unit select/deselect with A/B.

- **APP-icon2 (requires newer APK than 793):** Open the launcher after importing (or re-importing)
  the ROM and open Android's recents (overview) screen. Expected: (1) no "Add to home screen?"
  dialog appears after ROM import and no "Add to home screen" button is on the launcher screen;
  (2) the app drawer launcher icon stays the gold shield and sword on dark blue (standard Android
  cannot change the launcher icon to a runtime ROM bitmap without bundling game art in the APK);
  (3) recents still shows Eirika's portrait as the app icon once a ROM is imported.

- **EP-18/EP-26 map battle info box and exp bar:** attack an enemy with Eirika (PP-17 step 3d) and
  let the Soldiers attack at End (step 5). Expected: when the fight starts, a two-row box with both
  units' names and HP appears next to the fighters (top or bottom of the screen) over a coloured
  vertical gradient; it closes after the fight, and the map, menus and windows look as before
  afterwards (no leftover tiles or wrong colours). The fight takes about a third of a second longer
  than before. Each round: the attacker lunges towards the target and steps back, the target
  flashes white on a hit with the hit sound, and the HP number and bar in the box drain. A unit at
  0 HP fades out, and a miss shows the MISS sprite above the defender.
  Limits: a 0-damage hit shows no NO DAMAGE text (`EP-24b`);
  a critical hit has no crit flash or screen shake (EP-23b).
  After the fight, when a player unit gained exp (Eirika attacking, or surviving an enemy attack),
  an exp bar window opens in the middle of the screen and fills from the old to the new exp with a
  ticking sound, then closes. When the bar reaches 100 (level up), the
  level-up announcement slides in the stat panel with the unit's portrait and animates gained stats before closing
  cleanly.

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
     d. A on the target (confirm sound): the map battle plays as described above. HP and weapon
        uses update after the rounds, and Eirika greys out as after
        Wait. HP and uses stay as they are after Wait and End turn.
     e. A kill (Seth can kill on turn 1): the enemy fades out after the death dialogue, if any; its tile is
        empty, the cursor can stand on it and A there opens the map menu. It does not return
        after End.
  4. B in the unit menu. Expected: she is back at her start tile and selected again, the range shows.
     Move again and choose Wait. Expected: the menu closes, Eirika greys out, the cursor is free.
  5. Press A on an empty tile. Expected: the map menu (Unit, Status, Guide, Options, Suspend,
     End). Choose End. Expected: the "Enemy Phase" banner, then the two Soldiers walk one after the
     other to the tile next to Eirika with the target cursor on her (about 1 second each) and
     attack her through the map battle display above. With the captured initial positions and no earlier
     combat, one Soldier deals 9 damage and both die to her counter. Different player actions can
     change that outcome. Dismiss any post-battle dialogue with A. After each Soldier the game
     pauses for about 0.3 s (the map fade; the tiles do not change, so it is only a pause). Each
     walk has footstep sounds panned by the walker's screen position (needs sound effects on).
     Then the "Player Phase" banner of turn 2 and Eirika is no longer grey.
  - Limits: resulting game state is checked against the original; whole-flow timing still needs
    comparison. Missing systems are listed under "Ignore".

Open from issue #7 (0.1.852), no fix yet:
- **D-18 kill teleport:** when Seth killed a bandit (player attack), his sprite turned grey and he
  briefly appeared on the bandit's tile before snapping to his own.
- **D-19 battle box background:** when a bandit attacked Eirika, the HP info box had no background.
- **D-20 after lord death:** if Eirika dies (no game over yet, PC-8), enemy AI stops and menus/text
  get wrong palettes. Game over itself is a known gap.

Open from issue #6 (0.1.793); D-17 not mentioned in #7, still to check (D-16 sound confirmed OK):
- **D-17 menu background:** menus always open, but sometimes their background/textures are missing
  or appear late. Seen on the level-up stat panel (0.1.793); note which other menus and what came before.
  Builds after c7658cf: the level-up panel frame (BG1 box) and its palette must appear on the panel's
  first frame every time (fix: missing sync request). Other menus not audited yet (AUD-SYNC).
- **Still to test (EP-18/EP-26):** a miss (MISS sprite) and a critical hit; in the 0.1.793 quick play
  every attack hit. Hits, damage, exp and a level-up looked right at a glance (no emulator comparison,
  growths unchecked).

## Ignore (known gaps, not bugs)

Re-test these only when a change to them is announced.

- **Combat display:** no NO DAMAGE text (`EP-24b`), crit flash (`EP-23b`),
  weapon-broke or item-drop popups (M6-G, M6-H).
- **Commands and screens:** Item answers with the refusal beep; the map menu's Unit, Status, Guide,
  Options and Suspend, and R / L / START on the map do nothing; no help box, inventory or info
  screens (M6-E, M6-F, M6-H). Selecting an enemy shows no range.
- **Prologue flow:** the boss never moves (EP-11); after the last enemy the ending dialogue plays and
  the map stays, no save menu or Chapter 1 (PC-3a..PC-5); Eirika dying gives no game over (PC-7, PC-8).
- **Map events and rules:** no staff range, traps, after-move or tutorial events, dialogue during
  the enemy phase, or records (M6-C, M6-E, M6-H). Enemy and green phase banners (D12-2).
- **Opening:** the title's class reel (D-15, M6-G); the chapter intro's closing sound-effect fade;
  world-map node icons. New Game always uses Normal difficulty (save/difficulty menus M6-E).
- **Popups:** other item-popup branches than the confirmed Rapier one (D09-3, low priority).

Report any regression in things confirmed earlier (boot, ROM import, controls, rendering, suspend and
resume, the opening monologue, world map and title card, castle scene and audio, the player phase
banner, the hover cursor, the Rapier popup, Delete save).

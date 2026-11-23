# Manual testing

## Get the APK

Install `fe8-debug.apk` from the
[latest release](https://github.com/countDookim/Fire-Emblem-8-android/releases/tag/latest). It is rebuilt
from every master push that changes code; the release title names the commit and run number. Every build
needs your ROM, imported on first start.

| | Build | Date | Report |
|---|---|---|---|
| Latest APK | 0.1.852 (`5ee1049`) | 2026-11-23 | — |
| Last owner test | 0.1.852 (`5ee1049`) | 2026-11-23 | [issue #7](https://github.com/countDookim/Fire-Emblem-8-android/issues/7) |
| Last full pass | 0.1.622 (`22cfc9d`) | 2026-11-12 | HANDOFF "Device status" |

Items are removed from this page once the owner confirms them; the history is in git and in
[HANDOFF](../HANDOFF.md) "Device status".

## 1. Test now

All of these are in 0.1.852.

- **D-17 level-up panel background:** level a unit up (Eirika usually does within the first fights, see
  section 3). Expected: the stat panel's frame and colours are there on its first frame, every time.
  Also note any other menu that opens without its background or loads it late, and what you did just
  before. (Reported on 0.1.793 in [issue #6](https://github.com/countDookim/Fire-Emblem-8-android/issues/6);
  fixed for the level-up panel only.)
- **APP-icon2 launcher icon:** import (or re-import) the ROM. Expected: no "Add to home screen?" dialog
  and no "Add to home screen" button; the app-drawer icon stays the default gold shield and sword on dark
  blue; Android's recents screen shows Eirika's portrait as the app icon.
- **Miss and critical hit:** not seen yet, because every attack so far has hit. Expected: a miss shows the
  MISS sprite above the defender and no HP loss. A critical hit deals triple damage. It has no crit
  flash or screen shake yet (known gap).

## 2. Open bugs (no fix yet)

Reported in [issue #7](https://github.com/countDookim/Fire-Emblem-8-android/issues/7) on 0.1.852. No
retest needed until a fix is announced.

- **D-18 kill teleport:** when Seth killed a bandit, his sprite turned grey and briefly appeared on the
  bandit's tile before snapping back to his own.
- **D-19 battle box background:** when a bandit attacked Eirika, the HP box had no background.
- **D-20 after Eirika dies:** there is no game over yet (known gap), but afterwards the enemy AI stops and
  menus and text use wrong palettes.

## 3. Prologue turn 1 walkthrough

The main path to play for each new build. Start New Game, or resume the Prologue from its suspend save,
and play to the turn-1 map: Eirika near the castle gate, three red enemies to the right.

1. **Map windows:** the chapter goal window at the top right, the terrain window at the bottom right, and
   the unit window (face and HP) at the top left when the cursor is on a unit.
2. **Select:** A on Eirika shows blue move squares with a red attack-range ring. The path arrow follows the
   cursor, with the move sound. B cancels.
3. **Move:** A on a tile makes her walk the arrow path, then the unit menu opens: Item and Wait, plus
   Attack (listed first) when an enemy is in reach. B in the unit menu puts her back on her start tile,
   still selected. Wait greys her out.
4. **Attack:** choose Attack next to an enemy.
   - **Weapon list:** a framed list with a hover bar (icon, name, uses). Only the highlighted weapon's red
     squares stay, Eirika's face blinks on the right, and an item panel shows Atk, Hit, Crit and Avoid.
   - **Forecast:** A on a weapon puts the red cursor on the enemy. A forecast window slides in with both
     names, HP, damage, hit and crit, weapon icons, triangle arrows and a swaying x2 mark. B slides it out
     and reopens the weapon list.
   - **Map battle:** A on the target. A two-row box with both names and HP appears over a coloured
     gradient. The attacker lunges and steps back each round, and the target flashes white on a hit with
     the hit sound while the HP drains. A unit at 0 HP fades out; its tile is empty afterwards and stays
     empty after End.
   - **Exp bar:** after the fight, a player unit that gained exp gets an exp bar that fills with a ticking
     sound. At 100 the level-up panel slides in with the portrait and the gained stats.
   - **Afterwards:** HP and weapon uses are updated and the attacker is greyed out.
5. **End:** A on an empty tile opens the map menu (Unit, Status, Guide, Options, Suspend, End); choose End.
   - **Enemy phase:** the "Enemy Phase" banner, then each Soldier walks next to Eirika, with footstep
     sounds panned by screen position, and attacks through the same map battle.
   - **Between enemies:** a short pause after each one (the map fade; the tiles do not change).
   - **Next turn:** then the turn-2 "Player Phase" banner, and Eirika is no longer grey.
6. **Suspend and resume:** close the app on the map and reopen it. The map resumes where it was.

## 4. Known gaps (not bugs)

Retest these only when a change to them is announced.

- **Combat:** no battle animations (M6-G); no NO DAMAGE text (EP-24b), crit flash or screen shake (EP-23b),
  weapon-broke or item-drop popups (M6-G, M6-H).
- **Commands and screens:**
  - The Item command only beeps.
  - The map menu's Unit, Status, Guide, Options and Suspend do nothing, and neither do R, L and Start on
    the map.
  - There is no help box, inventory or status screen (M6-E, M6-F, M6-H).
  - Selecting an enemy shows no range.
- **Prologue flow:**
  - The boss never moves (EP-11).
  - After the last enemy, the ending dialogue plays and the map stays: no save menu or Chapter 1 yet
    (PC-3 to PC-6).
  - Eirika dying gives no game over (PC-7, PC-8).
- **Map events and rules:** no staff range, traps, after-move or tutorial events, dialogue during the
  enemy phase, or records (M6-C, M6-E, M6-H). The enemy and green phase banners are missing (D12-2).
- **Opening and title:**
  - Idling on the title replays the opening instead of the class showcase (D-15, M6-G).
  - The chapter intro's closing sound-effect fade is missing.
  - There are no world-map node icons.
  - New Game always uses Normal difficulty.
- **Popups:** only the Rapier item popup is confirmed; the other branches are low priority (D09-3).

## 5. Confirmed earlier (report any regression)

- **App:** boot, ROM import, controls (touch and gamepad), GPU rendering, suspend and resume, Delete save.
- **Opening:**
  - The opening monologue and its Start skip; the world-map exposition and title card.
  - The castle scene with its audio (no stutter).
  - All cutscene skips fade to black.
  - Title idle does not hang.
- **Map:**
  - The player phase banner and the hover cursor.
  - The Rapier popup.
  - Unit select and deselect.
  - Enemy-phase movement and attacks.
  - Exp and level-up sounds with no lingering beep.

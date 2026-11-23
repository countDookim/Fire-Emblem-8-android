# Manual testing

## Get the APK

Install `fe8-debug.apk` from the
[latest release](https://github.com/countDookim/Fire-Emblem-8-android/releases/tag/latest). It is rebuilt
from every master push that changes code; the release title names the commit and run number. Every build
needs your ROM, imported on first start.

| | Build | Date | Report |
|---|---|---|---|
| Latest APK | 0.1.856 (`50943a9`) | 2026-11-23 | — |
| Last owner test | 0.1.852 (`5ee1049`) | 2026-11-23 | [issue #7 retest](https://github.com/countDookim/Fire-Emblem-8-android/issues/7#issuecomment-7113627348) |
| Last full pass | 0.1.622 (`22cfc9d`) | 2026-11-12 | HANDOFF "Device status" |

Items are removed from this page once the owner confirms them; the history is in git and in
[HANDOFF](../HANDOFF.md) "Device status".

## 1. Test now

Fixes for four bugs from [issue #7](https://github.com/countDookim/Fire-Emblem-8-android/issues/7) are in
**0.1.856 (`50943a9`) and later**. 0.1.852 does not have them. Play the Prologue's first turns.

- **D-18 kill teleport:** kill an enemy with a player unit (Seth can on turn 1). Expected: after the fight
  the attacker stays on his own tile and greys out there; the dead enemy fades out and never reappears.
  Also check that after a fight where both units survive, both stay visible.
- **D-19 battle box background:** let an enemy attack Eirika (End turn 1). Expected: the HP box has its
  framed background, as when the player attacks.
- **D-21 level-up numbers:** level a unit up. Expected: the stat numbers on the level-up panel have the
  normal text colours (before, they used the dialogue font's colours after a battle quote).
- **D-22 boss name:** attack the boss after his battle quote. Expected: his name in the HP box has the
  normal text colours.
- **D-20 after Eirika dies (check only):** the D-21/D-22 fix also resets the text after a death quote, so
  the wrong menu and text colours may be gone. There is still no game over, and the enemy AI may still
  stop; note what you see.

## 2. Open bugs (no fix yet)

- **D-20 after Eirika dies:** there is no game over yet (known gap), but on 0.1.852 the enemy AI stopped
  afterwards and menus and text used wrong palettes. See the check above.

## 3. Known gaps (not bugs)

Retest these only when a change to them is announced.

- **Combat:** no battle animations (M6-G); no NO DAMAGE text (EP-24b), crit flash or screen shake (EP-23b),
  weapon-broke or item-drop popups (M6-G, M6-H). Crit damage and misses themselves work.
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
  - World-map node icons are missing.
  - New Game always uses Normal difficulty.
- **Popups:** only the Rapier item popup is confirmed; the other branches are low priority (D09-3).

## 4. Confirmed earlier (report any regression)

- **App:** boot, ROM import, controls (touch and gamepad), GPU rendering, suspend and resume, Delete save.
- **Opening:**
  - The opening monologue and its Start skip; the world-map exposition and title card.
  - The castle scene with its audio (no stutter).
  - All cutscene skips fade to black.
  - Title idle does not hang.
- **Prologue turn 1 (0.1.852):**
  - Map windows, unit select and deselect, move range and path arrow, the unit menu, Wait.
  - Attack: weapon list, forecast, map battle with HP box, hits, misses and crits, kills, exp bar and
    the level-up panel.
  - End, the enemy phase (movement and attacks), the turn-2 banner, suspend and resume.
  - The step-by-step walkthrough is in git history (commit 948965e) if a regression needs it.
- **Map:** the player phase banner, the hover cursor, the Rapier popup, exp and level-up sounds with no
  lingering beep.
- **App icon:** no home-screen shortcut, default launcher icon, Eirika's portrait in recents.

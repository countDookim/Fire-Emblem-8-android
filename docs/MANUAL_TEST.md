# Manual testing

## Get the APK

Install `fe8-debug.apk` from the
[latest release](https://github.com/countDookim/Fire-Emblem-8-android/releases/tag/latest). It is rebuilt
from every master push that changes code; the release title names the commit and run number. Every build
needs your ROM, imported on first start.

| | Build | Date | Report |
|---|---|---|---|
| Latest APK | 0.1.889 (`271bd27`) | 2026-11-25 | — |
| Last owner test | 0.1.889 (`271bd27`) | 2026-11-25 | PC-8 (game over) & PC-6 (Ch1 opening) confirmed OK |
| Last full pass | 0.1.622 (`22cfc9d`) | 2026-11-12 | HANDOFF "Device status" |

Items are removed from this page once the owner confirms them; the history is in git and in
[HANDOFF](../HANDOFF.md) "Device status".

## 1. Test now

Everything up to 0.1.868 was tested earlier; new items carry their build.

- **Prologue boss AI & enemy-phase events (`EP-11` / `EP-11-hooks` + `D-23`, pending next APK after `0.1.889`):**
  1. Defeat the two Soldiers in the Prologue: the one-enemy-left event & dialogue now plays.
  2. End Turn 2: O'Neill's turn event & dialogue plays, changing his AI so he moves toward you on Enemy Phase 2 and attacks when a player unit ends in range.

## 2. Open bugs (no fix yet)

- **D-23 (0.1.886, owner 11-25): fixed in 0.1.889, device check pending.** Every enemy that attacked in an enemy phase
  and survived never acted again (an unported AI "go to start" command). In 0.1.889: in the
  Prologue, let an enemy attack Eirika and survive, end turn 2 next to it, and it should attack again.
  Until then, to test game over: attack the boss with Eirika until his counterattack kills her.
- **D-24 (found 11-25): fixed after 0.1.889 (pending next APK), device check pending.** Map battles no longer crash on `TODO()` in `MapAnimInfoBox` when a unit's HP bar needs its one-segment minimum (`curHp = 1` at `maxHp >= 45`) or the HP overflow glyph (`>= 100` HP); both transcribed branches still need capture verification (`EP-21b`).

## 3. Known gaps (not bugs)

Retest these only when a change to them is announced.

- **Combat:** no battle animations (M6-G); no NO DAMAGE text (EP-24b), crit flash or screen shake (EP-23b),
  weapon-broke or item-drop popups (M6-G, M6-H). Crit damage and misses themselves work.
- **Commands and screens:**
  - The Item command only beeps (item menus: M6-F, M6-H).
  - The map menu's Unit, Status, Guide, Options and Suspend do nothing, and neither do R, L and Start on
    the map.
  - There is no help box, inventory or status screen (M6-E, M6-F, M6-H).
  - Selecting an enemy shows no range (PP-enemy); the info windows closing then is normal.
- **Chapter 1 and saves:**
  - Selecting a unit and opening its menu crashes ("EvCheck05_LOCA is not ported yet", EP-35b).
  - Power-on always resumes the suspend save (Chapter 1's first player phase after the Prologue): there is
    no title menu yet to choose Continue and a slot (PC-11).
  - Battle animations in the Chapter 1 opening are skipped (M6-G).
- **Map events and rules:** no staff range, traps, after-move or tutorial events, dialogue during the
  enemy phase, or records (M6-C, M6-E, M6-H). The enemy and green phase banners are missing (D12-2).
- **Opening and title:**
  - Idling on the title replays the opening instead of the class showcase (BA-reel-inv, after M6-G).
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
- **Prologue end to Chapter 1 (0.1.868 / PC-6 0.1.889):** the save menu after the ending, saving to slot 1, the
  Chapter 1 title card and opening event (green unit dies and fades out, Seth and Eirika stop in their
  correct positions), the first player phase.
- **Map:** the player phase banner, the hover cursor, the Rapier popup, exp and level-up sounds with no
  lingering beep.
- **Bug fixes (0.1.856):** after a kill the attacker stays on his tile (D-18); the HP box has its
  background when an enemy attacks (D-19); level-up numbers (D-21), the boss's name (D-22) and the menus after
  Eirika's death (D-20) have the normal colours.
- **App icon:** no home-screen shortcut, default launcher icon, Eirika's portrait in recents.
- **Game over (PC-8, 0.1.889):** Eirika's death fades to the game over screen and returns to the title/main menu on A (idle timeout PC-cap2c untested).

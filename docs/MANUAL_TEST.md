# Manual testing

## Get the APK

Install `fe8-debug.apk` from the
[latest release](https://github.com/countDookim/Fire-Emblem-8-android/releases/tag/latest). It is rebuilt
from every master push that changes code; the release title names the commit and run number. Every build
needs your ROM, imported on first start.

| | Build | Date | Report |
|---|---|---|---|
| Latest APK | 0.1.889 (`271bd27`) | 2026-11-25 | — |
| Last owner test | 0.1.868 (`dd96da1`) | 2026-11-24 | Issue #8: save menu to Chapter 1 OK |
| Last full pass | 0.1.622 (`22cfc9d`) | 2026-11-12 | HANDOFF "Device status" |

Items are removed from this page once the owner confirms them; the history is in git and in
[HANDOFF](../HANDOFF.md) "Device status".

## 1. Test now

Everything up to 0.1.868 was tested earlier; new items carry their build.

- **Game over (PC-8).** Build: 0.1.886 (`848cd63`).
  1. Start the Prologue (or resume it) and let Eirika die: end turns with her next to the enemies, or
     leave her in reach of the boss's soldiers and do not move her away.
  2. Expected: after her last hit the screen fades to black (the map music fades first), then the game
     over screen shows a scrolling fog with the game over text and the game over song. Wait or press A.
  3. Expected: pressing A fades the screen out and returns to the title; the title music starts again.
     Without a press the screen should end by itself after about 25 seconds (idle timeout, not yet
     verified against a capture: PC-cap2c).
  - Known limits: the death comes from the real battle, but the battle animations are off (M6-G); after
    the title, the game resumes the last suspend save (start of that player phase), because there is no
    title menu yet (PC-11); the fog's blend can differ from the original by a
    shade in single frames (not visible in play).

- **Chapter 1 opening (PC-6).** Build: 0.1.889 (`271bd27`).
  1. Play (or fast-forward with A) the Prologue to its end, take save slot 1 in the save menu, and
     watch the Chapter 1 opening play through (text pages need A; the whole opening takes a few minutes).
  2. Expected: enemy soldiers walk in, two green units walk in and one of them is removed, the other
     is killed and its sprite fades out (about half a second) before it is gone, then the two
     player units walk in from the bottom right and each stops on its own tile, both visible.
  3. Expected: when the event ends the first player phase starts at turn 1, and both player units are
     on the map and can be moved (do not open a unit's menu, EP-35b crashes there).
  - Changed by this build: at the end of the opening one player unit used to be hidden and moved onto
    the other's tile (a unit check was missing); the killed green unit used to vanish at once, without
    the fade.

## 2. Open bugs (no fix yet)

- **D-23 (0.1.886, owner 11-25): fixed in 0.1.889, device check pending.** Every enemy that attacked in an enemy phase
  and survived never acted again (an unported AI "go to start" command). In 0.1.889: in the
  Prologue, let an enemy attack Eirika and survive, end turn 2 next to it, and it should attack again.
  Until then, to test game over: attack the boss with Eirika until his counterattack kills her.
- **D-24 (found 11-25, not yet seen on a device):** a map battle can crash the app when a unit's HP bar
  needs its one-segment minimum or the HP overflow glyph (two unported functions). Likelier now that enemies
  keep fighting (D-23 fix). Report the HP values if it happens.

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
- **Prologue flow:**
  - The boss never moves or attacks, even with a unit next to him (EP-11).
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
- **Prologue end to Chapter 1 (0.1.868):** the save menu after the ending, saving to slot 1, the
  Chapter 1 title card and opening event, the first player phase.
- **Map:** the player phase banner, the hover cursor, the Rapier popup, exp and level-up sounds with no
  lingering beep.
- **Bug fixes (0.1.856):** after a kill the attacker stays on his tile (D-18); the HP box has its
  background when an enemy attacks (D-19); level-up numbers (D-21), the boss's name (D-22) and the menus after
  Eirika's death (D-20) have the normal colours.
- **App icon:** no home-screen shortcut, default launcher icon, Eirika's portrait in recents.

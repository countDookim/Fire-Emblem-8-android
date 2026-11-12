# Roadmap

Owner-approved 2026-11-11 after a review of the whole forward plan. It replaces the brief's single
milestone 6 ("remaining systems") with player-visible milestones, and the "≤ 20 min, by source file"
task rule with capture-first tasks. Open rows live in [TASKS](../TASKS.md); this file holds the
milestones, the task rules and why they changed.

## Why the plan changed

- Nothing was planned past the Prologue opening; only four rows were `todo`.
- Tasks were scoped by source file. wm4–wm7 each ported a whole file and then found the Prologue
  never runs large parts of it (camera opcodes 83/84/86–89, the scroll manager, GmPalFade, spline
  movers, automu Type0). That code is written but unverified and uncounted.
- Verified work was not reaching the phone: the monologue and wm3–wm7 are standalone, with one large
  integration waiting at the end.
- Captures were not planned, so tasks ended "blocked: no reference run reaches it".

## Owner decisions (2026-11-11)

- **Scope:** link-cable play (all `sio_*`, 434 functions) and the debug menus (`uidebug.c`,
  `bmdebug.c`, `bmcommanddbg.c`, `mapanim_debug.c`, 194 functions) are out of scope. The README shows
  them separately and leaves them out of the percentage (task PROG-1). A function with a retail
  caller stays in scope.
- **Priority:** two lanes in parallel. Lane A finishes and wires the opening (M6-A); Lane B is the
  player phase (M6-B).
- **Battle animations** come after the Prologue is playable (M6-G). Until then combat resolves
  instantly, listed as a stand-in in MANUAL_TEST.
- **Scoping:** capture-first (rules below).
- **2026-11-12:** lanes after M6-A: Lane A = M6-C → M6-D → M6-E, Lane B = M6-B then M6-F. The Easy-mode
  tutorial belongs to M6-E (rows PC-cap3, PC-10).

## Task rules

1. Four kinds of task:
   - **CAP**: a new capture in the ROM repo. Old runs stay byte-identical; pack, update the
     manifest, publish, refresh assets.
   - **INV**: an inventory test of one capture window (which procs and functions run, and when).
     No port claim.
   - **PORT**: one behaviour in one capture window, at most about 8 functions from one decomp file.
     The acceptance names the fields compared. Every tag is break-checked (CLAUDE.md rule 4).
   - **WIRE**: connect into `Fe8Boot`/`GameControl`, with a headless end-to-end test, an allocation
     test, a MANUAL_TEST entry and an APK announcement.
2. Write only functions the window runs. An untaken branch inside a ported function is transcribed
   and marked `(not verified: …)`. A whole function or file that no capture runs is not written
   until a CAP row for it exists.
3. A sub-lane starts CAP → INV. The INV result fixes the function lists of the PORT rows after it;
   correct those rows when it lands.
4. Every milestone ends with a WIRE task and a device check.
5. TASKS.md holds open rows only, with a status of at most 15 words. Details go to
   `docs/plans/<lane>.md`. A finished row is deleted and its ID added to the Done line.
6. When a lane has fewer than 5 open rows, break the next milestone below into rows.

## Milestones

Each ends in something the owner can test on the phone. Function counts are decomp totals for the
main files, not all of them reachable.

| ID | Milestone | Exit on device | Main decomp files (functions) |
|---|---|---|---|
| M6-A | Opening wired (D-11) | New Game → monologue → world map → chapter title → castle; Start skips | ported already; `chapterintrofx.c` 41, `chapter_title.c` 11, `eventscr_gmap.c` handlers |
| M6-B | Player phase | Select a unit, see range and arrow, move, Wait, End turn | `playerphase.c` 40, `bmidoten.c` 24, `bmpatharrowdisp.c` 18, `uimenu.c` 44, `player_interface.c` 55, part of `bmmenu.c` |
| M6-C | Map combat, no animation | Attack: target, forecast, damage, exp/level, death, weapon uses | `uiselecttarget.c` 18, `bksel.c` 31, rest of `bmbattle.c` (84), part of `bmtarget.c` |
| M6-D | Enemy phase and AI (Prologue) | Enemies move and attack; phases cycle; enemy banner (D12-2) | `bmphase.c` 6, `cp_phase/order/decide/perform` 48, used parts of `cp_script/utility/battle` |
| M6-E | Prologue completable | Win → ending event → save → Chapter 1 map; game over works | `eventcall.c` 45, `savemenu.c` 66, `savedraw.c` 37, `difficultymenu.c` 21, `gameoverbgfx.c` 11 |
| M6-F | Info screens | R stat screen, help boxes, unit list, status, options, minimap | `statscreen.c` 93, `helpbox.c` 71, `unitlistscreen.c` 40, `uiconfig.c` 22, `minimap.c` 34 |
| M6-G | Battle animation core (melee) | Seth/Valter and melee fights animate (issue #5 item 2) | `banim-ekr*`/`efx*` core, about 400 of 1,495 |
| M6-H | Chapters 1–4 systems, as first needed | Items, trade, rescue, visit, talk, shops, fog, staves, reinforcements | `bmitemuse.c` 57, `bmtrade.c` 44, `bmusemind.c` 38, `bmshop.c` 81, `mapanim*` 358, spell efx per spell |
| M6-I | Prep screen and free-roam world map | Preparations; world-map travel, skirmishes | `prep_*` 452, rest of `worldmap_*` (about 480) |
| M6-J | Late game and extras | Promotion, arena, supports, endings, sound room, remaining spell/dragon animations | `classchg*`, `bmarena.c`, `uisupport.c`, `ending_*`, `soundroom.c`, rest of battle_anim |
| M7 | Modding surface | Mod loader, debug menu (debug builds), MODDING.md current | starts with audit M7-0 |

M6-C to M6-F have rows in TASKS since 2026-11-12; M6-G to M6-J have starter rows (CAP/INV) only. Call chains,
capture plans and open points: [M6C-J-breakdown](M6C-J-breakdown.md). Lanes from 2026-11-12 (owner-approved):
Lane A carries the main line M6-C → M6-D → M6-E; Lane B finishes M6-B, then M6-F. The Easy-mode tutorial
(PC-cap3, PC-10) is part of M6-E, at its end; until then Easy plays without tutorials (stand-in). Earlier milestone numbering: M1–M5 and
"M6 part 1" are done (TASKS Done line; row details in
[TASKS-archive-2026-11-11](TASKS-archive-2026-11-11.md)).

Pace check, not a promise: about 42 verified functions a day over the first 18 days; about 6,970
remain in scope. The pace depends on captures.

## Open user-report issues

- **#4 starting the game:** fixed in `1b207b8`/`7b1dcfd`; the owner has since played to the Prologue
  map. Needs the owner's confirmation to close.
- **#5 Prologue cutscene:** item 1 (audio stutter) resolved as D-07; item 2 (Seth/Valter animation)
  → M6-G; item 3 (riders at the map edge) confirmed solved earlier; item 4 (Rapier popup) → D-09
  device check pending; item 5 (player phase) → M6-B to M6-D.

## Modding audit (M7-0, 2026-11-11)

[M7-modding](M7-modding.md) checks every "Modding groundwork" bullet of the brief against the code. Main
finding: the extracted JSON/Tiled/PNG/MIDI files exist, but no production code reads them; the game reads the
imported ROM by address (about 76 files in `game/`). Fourteen bullets are partial, the debug menu is missing, and
"all data in JSON, none hardcoded" is contradicted. Nine owner decisions (D1–D9) and 23 proposed M7 rows are in
that file. **D1 is decided (owner, 2026-11-11): option A** — the ROM stays the vanilla source and mods override
per table in M7 ([ADR 0015](../decisions/0015-rom-base-mod-overrides.md)); M6 ports are unaffected. D2–D9 are open.

## Soon (owner request, 2026-11-11)

`APP-1`: a **Delete save** button in the launcher, so a fresh New Game no longer needs "clear app data" in
Android settings. It deletes the save file and keeps the imported ROM. Do it before further lane work that
needs device re-tests.

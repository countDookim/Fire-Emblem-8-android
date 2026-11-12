# Task backlog

Open rows only. Milestones, task rules and the reasons: [ROADMAP](plans/ROADMAP.md) (owner-approved 2026-11-11).

- Kinds: **CAP** capture (ROM repo) · **INV** inventory test, no port claim · **PORT** one behaviour in one
  capture window, ≤ ~8 functions · **WIRE** connect into the game + headless test + MANUAL_TEST.
- Port only functions the capture window runs. `Ported from` + break-checked `@VerifiesPort` (bare function
  names), else `Transcribed from … (not verified: why)`.
- Claim before starting (CLAUDE.md rule 10); take the first `todo` whose deps are done, in either lane.
- Status ≤ 15 words; details go to the lane's notes file. Delete a finished row and add its ID to Done.
- Fewer than 5 open rows in a lane: break the next ROADMAP milestone into rows.

## Lane A: main line (M6-C → M6-D → M6-E)

Lane A's opening (M6-A) is done; the lane now carries the main line. Notes, call chains and capture plans:
[M6C-J-breakdown](plans/M6C-J-breakdown.md). Start state: `prologue.sav` (turn-1 suspend). Function lists are from the
decomp call chains; each INV row corrects the PORT rows after it.

### M6-C map combat, no animation

Captures: `ram_trace_prologue_attack` (cap3, animations on; f1365 unit menu, f1400 weapon list, f1430 target select and
forecast, f1460 confirm, f2010..2038 results), `..._attack_noanim` (f1595..2124, two B cancels) and `..._kill`
(f1623..2600). Function lists below are the MC-0 inventory's (M6C-J-breakdown).

| ID | Kind | Task | Acceptance | Deps | Status |
|---|---|---|---|---|---|
| MC-3b | PORT | Weapon pick and cancel, bmmenu.c: `WeaponSelectMenu_Selected`, `sub_80832C8`, `BallistaRangeMenu_SwitchOut`, `ItemMenu_ButtonBPressed`; `EquipUnitItemSlot`, `ClearBg0Bg1` | Equip order and unit menu back after B (noanim f1730) | MC-3a | todo |
| MC-4 | PORT | Item panel, menuitempanel.c: `ForceMenuItemPanel`, `UpdateMenuItemPanel`, `MenuItemPanelProcIdle`; bmitem.c `DrawItemMenuLine`; `BattleGenerateUiStats` numbers; face start/end if not ported (`EndMenuItemPanel` never runs) | Panel tilemap and text buffers at cap3 f1400..1430, noanim f1630..1659 | MC-0 | todo |
| MC-5 | PORT | Target selection proc, uiselecttarget.c: `NewTargetSelection`, `TargetSelection_Loop`, `_HandleMoveInput`, `_HandleSelectInput`, `_GetRealCursorPosition`, `EndTargetSelection` | Proc fields and cursor per step noanim f1660..1819, B path f1700; switching never runs: transcribed | MC-1 | todo |
| MC-6 | PORT | Attack select handlers, bmmenu.c: `AttackMapSelect_SwitchIn`/`_Select`/`_Cancel`/`_End`, `sub_8022E38`, `sub_8022E54`; `ChangeActiveUnitFacing`; proc `gProcScr_0859B630` | `gActionData` at confirm, facing; cancel lands on the weapon list (noanim f1704) | MC-5 | todo |
| MC-8b | PORT | Hit checks, bmbattle.c: `BattleCheckBraveEffect`, `BattleCheckSureShot`, `BattleCheckPierce`, `BattleCheckGreatShield`, `BattleCheckPetrify`, `BattleGenerateHitTriangleAttack` | Branches no capture takes stay `not verified` | MC-8a | todo |
| MC-10 | PORT | Forecast window setup, bksel.c: `NewBattleForecast`, `BattleForecast_Init`, `InitBattleForecastBattleStats`, `BattleForecastHitCountUpdate`, `GetBattleForecastPanelSide`, `UpdateBattleForecastContents`, `CloseBattleForecast`, `BattleForecast_OnEnd` | Forecast proc fields per step noanim f1660..1704 | MC-9 | todo |
| MC-11 | PORT | Forecast drawing, bksel.c (standard layout only): `InitBattleForecastIconPaletteBuffer`, `InitBattleForecastLabels`, `PutBattleForecastUnitName`, `PutBattleForecastItemName`, `DrawBattleForecastContents`/`Standard`, `GetFactionBattleForecastFramePalette`, `InitBattleForecastFramePalettes` | BG tilemaps and palettes at the dumps; `Extended`, `PutBattleForecastTilemaps` never run: unwritten | MC-10 | todo |
| MC-12a | PORT | Forecast loops, bksel.c: `BattleForecast_OnNewBattle`, `_LoopSlideIn`, `_LoopDisplay`, `_LoopSlideOut` (cancel only), `MapEventEngineExists_`, `TriggerBattleForcastToturialEvent` | Proc state per step noanim f1661..1704 | MC-10 | todo |
| MC-12b | PORT | Forecast sprites, bksel.c: `PutBattleForecastWeaponTriangleArrows`, `PutBattleForecastMultipliers`, `UpdateBattleForecastEffectivenessPalettes` | OAM at full dumps; help box (R) left to M6-F | MC-12a | todo |
| MC-13 | PORT | Combat action, bmmind.c: `ApplyUnitAction` combat branch, `ActionCombat`, `sProcScr_CombatAction` script, `BATTLE_GOTO1_IfNobodyIsDead`, `BATTLE_HandleItemDrop`, `BATTLE_HandleCombatDeaths`, `StoreRNStateToActionStruct`; `BeginBattleAnimations` a hook | Proc tree and script position per step after confirm (noanim f1824..2076); death-fade functions never run: unwritten | MC-7, PP-11 | todo |
| MC-14 | PORT | Exp, bmbattle.c: `BattleApplyExpGains`, `GetBattleUnitExpGain`, `GetUnitRoundExp`, `GetUnitExpLevel`, `CanBattleUnitGainLevels`, `GetUnitKillExpBonus`, `ModifyUnitSpecialExp`; kill run adds `GetUnitPowerLevel`, `GetUnitClassKillExpBonus`, `GetUnitExpMultiplier` | Exp fields of both battle units (cap3 and noanim 0→10; kill: Seth 0→3) | MC-7 | todo |
| MC-15 | PORT | Write-back, bmbattle.c: `BattleApplyGameStateUpdates`, `BattleApplyUnitUpdates`, `UpdateUnitFromBattle`, `GetBattleUnitUpdatedWeaponExp`, `HasBattleUnitGainedWeaponLevel`, `BattleApplyBallistaUpdates` (no-ballista path) | Unit HP, exp, uses, weapon exp, state at noanim f2074, kill f1999/f2549, cap3 f2028 | MC-14 | todo |
| MC-16 | PORT | Death, bmmind.c: `KillUnitOnCombatDeath`, `DropRescueOnDeath` (no-rescue path); bmunit.c `UnitKill`; the fade (`MU_StartDeathFade`) belongs to C14-ma, `BATTLE_PostCombatDeathFades` never runs | Kill run f1999: dead unit struct cleared, unit map and sprite removal | MC-13 | todo |
| MC-17 | PORT | Level-up: only `CheckBattleUnitLevelUp` (no-level-up result) runs; the rest unwritten until a capture has a level-up | `CheckBattleUnitLevelUp` result equals the captures; stats after the first level-up (EP-cap2, else add a CAP) | MC-15, EP-cap2 | todo |
| MC-w | WIRE | Attack in `Fe8Boot`: unit menu → weapon → target and forecast → battle resolves at once → exp, death; replay cap3 and MC-cap2 inputs headless | Final unit HP/exp/uses/state equal the captures (timing differs: animation stand-in); 0 B/frame; MANUAL_TEST; device check | PP-17, MC-1..16 | todo |

### M6-D enemy phase and AI (Prologue)

Captures: `ram_trace_prologue_cancel_end` f1510..1650 (End, phase switch, enemy banner, first decision and walk), plus
EP-cap1/2.

| ID | Kind | Task | Acceptance | Deps | Status |
|---|---|---|---|---|---|
| EP-cap1 | CAP | `ram_trace_prologue_enemy_phase`: animations off; End on turn 1; the whole enemy phase to turn-2 player idle; per-frame RAM; watches + `gAiState`, `gAiDecision`, red units, battle structs; `tracecall` on the AI score functions if RAM does not show them | Run published | MC-cap1 | todo |
| EP-cap2 | CAP | `ram_trace_prologue_clear`: animations off; whole Prologue to the start of the ending scene (later enemy phases, both Normal-mode events, boss kill); per-step watches; RAM windows from a scout run | Run published; notes list the windows and whether a level-up occurs | EP-cap1 | todo |
| EP-0 | INV | Enemy phase in cancel_end f1510..1650 and EP-cap1: procs per step; AI script commands and the decision per enemy | `EnemyPhaseInventoryTest`; EP-1..EP-11 lists corrected | — | todo |
| EP-1 | PORT | Phase start (bm.c label 9 path): bmunit.c `TickActiveFactionTurn`, `ClearActiveFactionGrayedStates`; the four start-of-phase displays ending with empty target lists; `sub_8015434` | Unit state bits, turn/faction and proc tree per step f1510..1597 | EP-0 | todo |
| EP-2 | PORT | AI order: cp_phase.c `AiPhaseInit`; cp_order.c `CpOrderMain`, `BuildAiUnitList`, `SortAiUnitList`, `GetUnitAiPriority`, `GetUnitBattleAiPriority`, `CpOrderFunc_BeginDecide` | `gAiState` unit list and order at f1597 | EP-0 | todo |
| EP-3 | PORT | AI decide, cp_decide.c: `CpDecide_Main`, `CpDecide_Suspend`, `AiClearDecision`, `AiSetDecision`, `AiUpdateDecision`, `AiDecideMain`, `DecideScriptA`, `DecideScriptB` | `gAiDecision` per enemy (with EP-4..7) | EP-2 | todo |
| EP-4 | PORT | AI script interpreter, cp_script.c: `AiTryExecScriptA`/`B`, `AiScript_Exec`, the commands and unit predicates the Prologue enemies run | AI script position per decision; commands not run are not written | EP-3 | todo |
| EP-5 | PORT | AI combat search, cp_battle.c: `AiAttemptOffensiveAction`, `AiAttemptCombatWithinMovement`, `AiFloodMovementAndRange`, `AiSimulateBestBattleAgainstTarget`, `AiSimulateBattleAgainstTargetAtPosition`, `AiGetCombatPositionScore`, `AiIsBadFight` | Decision target, position and item for every enemy action in EP-cap1 | EP-4, MC-9 | todo |
| EP-6 | PORT | AI combat score, cp_battle.c: `AiComputeCombatScore` and its score components (12 functions, two rows) | Scores equal the `tracecall` return values, or decisions equal in EP-cap1 and EP-cap2 if not traced | EP-5 | todo |
| EP-7 | PORT | AI movement helpers, cp_utility.c: `AiTryMoveTowards`, `AiFindTargetInReachByFunc`, `FillMovementAndRangeMapForItem`, `AiMakeMoveRangeUnitPowerMaps` and the counters the window runs | Decision position; movement and range maps at the dumps | EP-4 | todo |
| EP-8 | PORT | AI perform, movement: cp_perform.c `CpPerform_UpdateMapMusic`, `_MoveCameraOntoUnit`, `_BeginUnitMovement`, `_MoveCameraOntoTarget`, `AiRefreshMap`, `StartAiTargetCursor`, `AiTargetCursor_Main` | MU, camera and cursor per step f1599..1650 | EP-3 | todo |
| EP-9 | PORT | AI perform, action: cp_perform.c `CpPerform_PerformAction`, `AiStartCombatAction`, `CpPerform_WaitAction`, `CpPerform_Cleanup`, `CpPerform_EquipBest` and the wait action if run | `gActionData`, battle result and unit state after each enemy action in EP-cap1 | EP-8, MC-13, MC-15 | todo |
| EP-10 | PORT | Phase end to turn 2: turn counter, blue units un-greyed, the turn events (they end at once in Normal mode), player phase restart | `gPlaySt` turn/faction and unit states at the end of EP-cap1 | EP-1, EP-9 | todo |
| EP-11 | PORT | Prologue Normal-mode events in EP-cap2: one enemy left (misc event, PP-11 notes) and the boss's flag-gated turn event; new opcodes only | Event engine state per step in both windows | EP-cap2 | todo |
| D12-2 | CAP+PORT | Enemy-phase intro capture with per-scanline blend; extend `PhaseIntroDifferentialTest` | Enemy name graphics/palette and per-line blend equal the capture | — | todo |
| EP-w | WIRE | Enemy phase in `Fe8Boot`: End → enemies move and attack → turn 2; replay EP-cap1 inputs headless | Unit positions and HP at turn 2 equal the capture; 0 B/frame; MANUAL_TEST; device check | MC-w, EP-1..10 | todo |

### M6-E Prologue completable

| ID | Kind | Task | Acceptance | Deps | Status |
|---|---|---|---|---|---|
| PC-cap1 | CAP | `ram_trace_prologue_end`: EP-cap2 inputs continued through the ending scene, the post-chapter save menu (save to a slot) and the Chapter 1 opening to turn 1 | Run published | EP-cap2 | todo |
| PC-cap2 | CAP | `ram_trace_prologue_gameover`: animations off; Eirika dies; game over screen; back to the title | Run published | EP-cap1 | todo |
| PC-0 | INV | PC-cap1 and PC-cap2 windows: procs per step, event opcodes run; also whether `ram_trace_newgame`/`menu_screens` cover the New Game difficulty and slot menus densely enough | `PrologueEndInventoryTest`; PC-1..PC-14 corrected; a CAP row added if the menus need one | PC-cap1, PC-cap2 | todo |
| PC-1 | PORT | Win condition, eventinfo.c: the boss-defeat misc event check, `ShouldCallEndEvent`, `MaybeCallEndEvent`, `CallEndEvent` | Event engine start step and flags in EP-cap2/PC-cap1 | PC-0, MC-16 | todo |
| PC-2 | PORT | Ending scene: opcodes it runs that are not verified yet (music, background, flags, `MNC2`) | Event state per step; `gPlaySt` after `MNC2` | PC-1 | todo |
| PC-3 | PORT | Chapter end, gamecontrol.c: `GameControl_PostChapterSwitch`, `GameControl_ChapterSwitch` (RN advance, chapter record, rankings), `GameControl_CallPostChapterSaveMenu`; battle-map teardown as run | GameControl proc position, RNG, `gPlaySt` and unit fields at the save menu | PC-2 | todo |
| PC-4 | PORT | Post-chapter save menu, savemenu.c 66 + savedraw.c 37: only the path the capture takes (init and graphics, slot drawing, cursor/input, write, exit); split into ≤ 8-function rows by PC-0 | Proc fields per step, tilemaps/OAM at dumps, the written save block | PC-3 | todo |
| PC-5 | PORT | Chapter 1 start: world-map wrapper with the skip flag set, `StartBattleMap` for chapter 1, chapter intro for a second chapter | Proc tree per step up to the Chapter 1 beginning scene | PC-4 | todo |
| PC-6 | PORT | Chapter 1 beginning scene: opcodes and unit loads not verified yet; split by PC-0 | Event state per step; units and map at turn 1 | PC-5 | todo |
| PC-7 | PORT | Game over trigger: lord-death misc event, event.c `CallGameOverEvent`, eventcall.c `EventCallGameOverExt`, `GameOver_FadeOutCurrentBgm`, `PostGameOverHandler` | Proc tree and event state per step in PC-cap2 | PC-0, MC-16 | todo |
| PC-8 | PORT | Game over screen, gameoverbgfx.c 11 (two rows: init, HBlank and scroll; fade loops and end); return to the title | Proc fields per step; LCD/palette at dumps | PC-7 | todo |
| PC-9 | PORT | New Game difficulty menu, difficultymenu.c 21 (three rows) and the save menu's New Game path | Menu state per step; chosen difficulty in `gPlaySt`; replaces the fixed Normal stand-in | PC-0 | todo |
| PC-cap3 | CAP | `ram_trace_newgame_easy`: Easy New Game through the tutorial Prologue (forced inputs, tutorial messages, scripted battle) | Run published | — | todo |
| PC-10 | INV+PORT | Easy-mode tutorial: tutorial event scripts, forced-input branches of playerphase.c, menu overrides; rows fixed by an INV of PC-cap3 | Per the INV | PC-cap3, EP-w | todo; until then Easy plays without tutorials (stand-in) |
| PC-w | WIRE | Prologue completable in `Fe8Boot`: win → ending → save → Chapter 1 turn 1; lord death → game over → title; replay PC-cap1 and PC-cap2 inputs | Save block and Chapter 1 state equal PC-cap1; 0 B/frame; MANUAL_TEST; device check | EP-w, PC-1..9 | todo |

## Lane B: player phase (M6-B)

Notes: [M6B-player-phase](plans/M6B-player-phase.md) (captures, key frames, proc inventory). Start state: `prologue.sav` (turn-1 suspend).

M6-B rows are done (see Done); the owner's device check of PP-17 closes the milestone.

### Lane B next: M6-F info screens

Start after PP-17. All captures start from `prologue.sav`. PORT rows here are groups; each INV splits its group into
rows of at most about 8 functions. Notes: [M6C-J-breakdown](plans/M6C-J-breakdown.md).

| ID | Kind | Task | Acceptance | Deps | Status |
|---|---|---|---|---|---|
| IS-cap1 | CAP | `ram_trace_prologue_statscreen`: L to cycle units; R on Eirika; pages left and right; up/down to Seth; R help across fields; B; R on an enemy | Run published | — | todo |
| IS-0 | INV | Stat-screen and help-box windows of IS-cap1: procs per step, pages and transitions seen | Inventory test; IS-1..IS-3 split into rows | IS-cap1 | todo |
| IS-1 | PORT | Stat screen, statscreen.c 93: open/close and backgrounds, the three pages, page slide, unit switch, sprites (about 12 rows) | Proc fields per step; tilemaps, palettes and OAM at dumps | IS-0 | todo |
| IS-2 | PORT | Help boxes, helpbox.c 71: box open/move/close, text, item and stat info (about 9 rows); also the forecast and item-list help | Same; extra windows need IS-cap2 | IS-0 | todo |
| IS-3 | PORT | Map idle keys, playerphase.c: `PlayerPhase_MainIdle` R/L branches, `CanShowUnitStatScreen`, `TrySwitchViewedUnit`, `TrySetCursorOn` | Cursor, camera and proc tree per step | IS-0 | todo |
| IS-cap2 | CAP | `ram_trace_prologue_mapmenu_screens`: map menu Unit (sort, page), Status, Options (change two settings), Guide; R help in the item list and forecast; Start minimap (open, move, close) | Run published | — | todo |
| IS-4 | INV | IS-cap2 windows per screen | Inventory test; IS-5..IS-9 split into rows | IS-cap2 | todo |
| IS-5 | PORT | Unit list, unitlistscreen.c 40 (about 5 rows) | Per step and at dumps, as IS-1 | IS-4 | todo |
| IS-6 | PORT | Chapter status, uichapterstatus.c 21 (about 3 rows) | Same | IS-4 | todo |
| IS-7 | PORT | Options, uiconfig.c 22 (about 3 rows); changed settings take effect where ports read them | Same; `gPlaySt` config after the changes | IS-4 | todo |
| IS-8 | PORT | Guide, bmguide.c 29 (about 4 rows; PP-12 left hooks) | Same | IS-4 | todo |
| IS-9 | PORT | Minimap, minimap.c 34 (about 5 rows) | Same | IS-4 | todo |
| IS-w | WIRE | Info screens in `Fe8Boot`; replay IS-cap1 and IS-cap2 inputs headless | Final state equals the captures; 0 B/frame; MANUAL_TEST; device check | PP-17, IS-1..9 | todo |

## Later milestones (starter rows)

Each sub-lane starts CAP → INV; its INV adds the PORT rows (ROADMAP rules 3 and 6). Nothing past the INV is fixed.
Sub-lane contents and file counts: [M6C-J-breakdown](plans/M6C-J-breakdown.md).

| ID | Kind | Task | Acceptance | Deps | Status |
|---|---|---|---|---|---|
| BA-0 | INV | M6-G: battle-animation windows that exist (`ram_trace_prologue_battle` f14401..15035, `ram_trace_prologue_attack` f1464..2010): ekr/efx procs per step, animation-script commands used | Inventory test; function list for the melee core (about 400) and the PORT rows | — | todo |
| BA-cap1 | CAP | M6-G: per-step LCD, palette and OAM for both battle windows (as `ram_trace_prologue_wm_lcd`), if BA-0 finds the dumps too sparse | Run published | BA-0 | todo |
| BA-1 | PORT | M6-G groups, split by BA-0: start transition, setup from the hit array, script driver, main loop, screen furniture, hit effects, sound, death, exp and level-up, ending | Per step: proc fields, LCD, palette, OAM | BA-0, PC-w | todo |
| BA-w | WIRE | M6-G: animations-on battles animate (scripted Seth/Valter and player attacks); replaces the instant stand-in | Battle windows equal the captures frame by frame; device check | BA-1 | todo |
| C14-0 | INV | M6-H scout: chapters 1–4 (fixtures `chapter1.sav`, `chapter2.sav`; procs watch only): unported procs each chapter first needs | Ordered sub-lane list; CAP rows per sub-lane | PC-w | todo |
| C14-ma | INV+PORT | M6-H: map battle animation over the animations-off windows of MC-cap1/2: procs and functions listed in M6C-J-breakdown (`ProcScr_MapAnimBattle`, `_MapBattleInfoBox`, `_MapAnimExpBar`, `_MapAnimDefaultItemEffect`, Mu hit flash and death fade, `ProcScr_ApProc` miss; mapanim_lvup.c never runs) | Per step: procs, OAM, tilemaps; replaces the instant stand-in when animations are off | MC-w | todo |
| C14-1 | CAP+INV | M6-H sub-lanes in C14-0's order: items, trade, rescue family, visit/seize, talk/recruit and green phase, heal staves, doors/chests/steal, shops, reinforcements, world-map intros of chapters 2–4 | One capture and inventory per sub-lane; PORT and WIRE rows added | C14-0 | todo |
| PW-0 | CAP+INV | M6-I: preparations (fixture `chapter6.sav`): main menu, pick units, items/convoy, view map, save | Captures and inventories; PORT rows per screen (prep_*.c, 452) | C14-1 | todo |
| PW-1 | CAP+INV | M6-I: free-roam world map (fixture `complete100.sav` or a mid-game save): movement, UI, base shops, skirmishes, Tower/Ruins | Captures and inventories; PORT rows (rest of worldmap_*.c, about 480) | PW-0 | todo |
| LG-0 | CAP+INV | M6-J, one sub-lane each: promotion, arena, supports, remaining spell animations, dragons and final boss, route split, dance/summon/ballista/status staves/traps, endings, sound room, extras | Captures and inventories; PORT rows per sub-lane | PW-1 | todo |
| M7 | — | Modding surface: 23 proposed rows in [M7-modding](plans/M7-modding.md) | Rows added here after the owner decides D2–D9 | owner | waiting for D2–D9 |

## Other open rows

| ID | Kind | Task | Acceptance | Deps | Status |
|---|---|---|---|---|---|
| CAP-hard1 | CAP | Hard-mode New Game run reaching autolevelled loads (unblocks old M6-13) | Run published; then tag `UnitAutolevelRealistic`, `UnitApplyBonusLevels`, `UnitAutolevelPenalty` | — | todo |
| CAP-collide1 | CAP | Run where a unit load lands on an occupied tile (unblocks old M6-14) | Run published; then verify `AiGetUnitClosestValidPosition`, `AdjustNewUnitPosition` | — | todo |
| TEST-2 | INV | `WalkingAllocationTest` borderline on the owner PC: 1–2 of 26 allocation-free windows (needs 3), 4 of 6 local runs on 11-11 failed; CI passes | Find the allocating call (allocation profiler on the walking window) or show it is GC/JIT noise; fix or adjust with the reason | — | todo |
| TEST-3 | INV | `tools/tests/test_kmp_common.py` is pytest-style, so CI's `unittest discover` never runs it; locally it fails on `ItemInfo.kt:272` (`toByteArray(Charsets.US_ASCII)` in commonMain, since 11-09) | CI runs the check; the offender uses a common API; other pytest-only test files found | — | todo |
| D09-3 | PORT | Remaining item-popup branches (gold, full inventory, red/root, unit-name/number) | Each branch against a capture that reaches it, else not written | — | low priority; Rapier popup confirmed on device 11-11 |

Owner-PC sessions also continue [READABILITY](READABILITY.md) (rule 11).

## Done

Row details, including what each task left transcribed: [archive](plans/TASKS-archive-2026-11-11.md) and `git log`.

- **M1–M5** complete. **M6 part 1** (Prologue opening to turn 1, suspend/resume): M6-01..12, 15..19, 21..28,
  30..39, 41..43, 45, 46 done; M6-20, 29, 40, 44 done as far as captures allow.
- **Lane A complete 2026-11-12** (opening wired end to end; device check pending). D11-fix1 (`12dad72`; WorldMapBootTest input + two-pass allocation check). D11-wm10 (`1df2b1f`; world-map unit/dot sprites drawn and verified), D11-wm12 (`bb98b26`; D11-wm9 stand-ins connected or relabelled), D11-wm11 (world-map HBlank gradient verified 98/98 dumps and wired; per-line palette writes in the renderer; no step sounds in the Prologue world map), D11-ci5 (`aa1ce42`; chapter intro wired, proc tree vs capture 9325..9830 through the boot; title-only intro left for chapter transitions), D11-wm9 (`c01ca46`; world map wired: New Game → monologue → world map → castle → player phase; units invisible → D11-wm10; device check pending), D11-wm8e (unit/dot opcodes through the VM 8101..9281, 43 functions; BiosMath.arcTan2 fixed; standing-sprite OAM unchecked), D11-wm8f (`b52da30`; WM exit 9271..9313, 10 functions; ResetFaces restore runs in the first event OnEnd; EndWM transcribed), Lane A gaps (`f0a21ec`; PATT allocation removed; joined VM run 4907..8110 with all handlers real; Event1D_TalkContinue verified), D11-wm8b (`f019973`; text opcodes C6/C7/1D/B6 through the VM 5100..5495; C4 at 5496 joins with wm8d in wm8f; InitSpriteTalk palette transcribed), D11-wm8d (`de2ac46`; C4/C5 through the VM 5701..7781; per-step blend not compared), D11-wm8c (`a4e60b5`; B6/B8/B9/BA through the VM 5433..8110; C4/C5/1C/1D test stand-ins; B5/B7 not run), D11-wm3f (route proc verified per step; drawing/transition transcribed: no open paths in the Prologue; GmapRm route-flag hook unconnected → D11-wm9), D11-wire-b (`c86e437`; monologue wired in Fe8Boot, skip via boot verified; device check pending; sound-on allocation fixed in `f0a21ec`; post-monologue BGM fades → D11-wm9), D11-wm8a (WM event host 4907..5100 through the VM; 13 functions; AF/B0 arg fix; line-fade Init, Event13 transcribed), D11-wm4d (border blend/OAM/palette + unit-fade palettes per step; 14 functions; border/pulse End and unit OAM transcribed), D11-wire-a (skip sound `Sound_FadeOutBGM(1)` verified at 1505/2375/4405; `fadeOutSe` stand-in, no visible effect), D11-cap1 (`ram_trace_prologue_wm_lcd`, ROM `8a14ce3`, release published locally; per-step LCD/pal/OAM 5500–8450), D13-2 (`351feac`; skip verified to the fade-in f10069; unit-load/move opcodes test stand-ins; device check pending), D13-1 (`2eee36a`; fade + talk block on Start; device check pending), D11-ci3c (`5146b83`; main path to f9872; `ChapterIntro_End` still transcribed), D13-cap1 (`ram_trace_prologue_cutscene_skip`, ROM `bd66e15`), D11-ci3b (fog, decal, Bg2Scroll, timer; to f9708), D11-wm3e (`ec9883b`; show transition only, hide not captured; wiring in D11-wm9), D11-ci3 (`2c121c6`; light effects), D11-ci2 (`7f92921`; title hooks `getPlayChapterId`/`getNextUnclearedNode` stand-ins until M6-I), D11-ci4 (`e3ef72b`), D11-wm3d (`1f883a6`), D11-cap2 (`ram_trace_newgame_intro`, ROM `eb263d1`), D11-wm3c, D11-ci1 (`89d837d`), D11-wm3b (`f6b08f2`), D11-ci0 (`ChapterIntroProcInventoryTest`), D07-audio, D07-trace, D09-1, D09-1b, D09-2, D11-slides, D11-fades, D11-init, D11-flare,
  D11-skip, D11-proc, D11-worldmap, D11-wm1, D11-wm2, D12-1. D11-wm3..wm7 (+ wm4a–c, wm6b–d): standalone
  ports verified as far as the Prologue runs them; the rest is in lane A.
- M4-02, M4-12, M5-56, 56b, 57..61.
- **Lane B (M6-B code-complete 2026-11-12; owner device check pending):** PP-17 (`1d694df`, `bac180d`, `407635b`; player phase wired in Fe8Boot: select, range, arrow, move, unit menu, Wait, cancels, map menu End; enemy AI an M6-D stand-in that ends at once; Attack/Item and info screens stand-ins), PP-11 (`4ea8e0f`; Wait action + post-action event check, 14 tags in one window; event registry, traps, suspend save hooks), PP-18 (`25d77db`; MMB label-1 jump, status cells NONE path, Burst display; status icons transcribed), CAP-status1 (`ram_trace_prologue_status_ui`, ROM `5c99df4`), PP-13 (`8c0fbaf`; berserk CP phase/order, phase-switch event, banner start; CpDecide M6-D stand-in), PP-6 (`eb02acd`; arrow sprites + graphics; mode 1 unverified; PutSprite still untagged), PP-8 (`6d7d6b7`; move walk; GenerateBestMovementScript cross-checked only; MuStepSe not done), PP-12 (`da3702a`; map menu availability, Guide draw, End; other item effects not written; bmguide.c hooks; Fe8Boot wiring in PP-17), PP-16 (`e76a49d`; goal window + green text; slide-out and two-line goals transcribed), PP-7 (`b951640`; RangeDisplayIdle/CanMoveActiveUnitTo/BackToMove; CancelAction not run; unit-menu B unported), PP-5 (`ce24e79`; re-route branch and `ResetPathArrow` transcribed; best-path hook until PP-8), PP-10 (`12f5003`; Attack/Item availability, EffectWait; target count hook until bmtarget.c, menu not started by PlayerPhase yet), PP-4 (range squares; hook in PlayerPhase until PP-17; menu-started stretch of attack run unverified), PP-15 (`6dcb288`+; unit mini-box, HP bar; status/Burst stand-ins → CAP-status1), PP-3 (`unitSelectEnabled` off until PP-17; `MainIdle`/`DisplayUnitEffectRange` partial), PP-14 (`5f3a81b`; terrain window; unit/goal windows hooks, their config gating moves into `InitPlayerPhaseInterface` with PP-15/16), PP-0a (hover cursor, `fe2f778` + fix `c18263a`; owner device check pending), PP-2 (`caa2f1f`; ballista branch, `MapSetInRange`, single-slot reach transcribed), PP-9 (`ff84b6f`; `DisplayFrozenUiHand` still a hook), PP-1 (`8da6b33`; `GenerateUnitMovementMap` itself not run in the window), PP-0 (`PlayerPhaseProcInventoryTest`, `8036f44`), PP-cap1 (`ram_trace_prologue_select_move`, ROM `c3ad5d4`), PP-cap2 (`ram_trace_prologue_cancel_end`, ROM `45f35cf`), PP-cap3 (`ram_trace_prologue_attack`, ROM `07a6ca9`, animations on).
- **Tests:** TEST-1 (`2103612`, `a0d69c4`, `407a17f`; full run 4.5 → 3.3 min, 806 tests; [TEST-SPEEDUP](plans/TEST-SPEEDUP.md)).
- **M6-C (Lane A):** MC-3a (Attack opens the weapon list, verified per frame on noanim f1595..1658; rows, face and panel MC-4; pick and B stand-ins MC-3b), MC-9 (`BattleGenerateSimulation` forecast verified on cap3, noanim, kill), MC-7 + MC-8a (`BattleGenerateReal` rounds and hits verified on cap3, noanim, kill; exp masked until MC-14; `BattleCheck*` transcribed, MC-8b), MC-1 + MC-2 (attack target list verified on cap3, noanim, kill; Attack offered in the unit menu, choosing it beeps until MC-3a), MC-cap1 (`ram_trace_prologue_attack_noanim`), MC-cap2 (`ram_trace_prologue_kill`; ROM `assets-7b70e8f`), MC-0 (`MapCombatInventoryTest`; traced function lists in [M6C-J-breakdown](plans/M6C-J-breakdown.md)).
- **Device fixes 2026-11-12:** D14-cap1 (`ram_trace_prologue_wm_skip`), D15-0 (`ram_trace_title_idle_reel`; ROM `assets-536a641`), D14-1 + D15-1 (`d349bd9`; WM-skip crash on a stale portrait proc + instant black; class reel skipped until M6-G; device check pending).
- **App:** APP-1 (launcher Delete save, `e39c730`; owner-confirmed on 0.1.590).
- **Alignment:** PROG-1 (`b459cf3`), UNV-1, ALN-1 (`3f85702`), ALN-2, STAND-1, STAND-2, M7-0 ([M7-modding](plans/M7-modding.md): 23 proposed rows; D1 decided = option A, ADR 0015; D2–D9 open).

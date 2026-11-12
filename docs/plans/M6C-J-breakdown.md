# M6-C to M6-J: task breakdown notes

Written 2026-11-12 (owner PC) when Lane A finished. Rows are in [TASKS](../TASKS.md); milestones and task rules in
[ROADMAP](ROADMAP.md). This file holds what the rows are based on: the decomp call chains, the captures each
milestone needs, and what is not known yet.

**How exact the rows are.** M6-C, M6-D and M6-E name functions read from the decomp call chains below; their INV
rows still correct the lists (ROADMAP rule 3). M6-F names files and behaviours; its PORT rows are groups that the
INV splits into rows of at most about 8 functions. M6-G to M6-J are sub-lane starters (CAP, INV, one grouped PORT
row, WIRE): nothing past the INV is fixed. Function counts are decomp totals per file, not all reachable.

**Lanes (proposed).** Lane A, now empty, takes the main line M6-C → M6-D → M6-E. Lane B finishes M6-B and then
takes M6-F, which needs only the turn-1 save and no combat. M6-G to M6-J follow in ROADMAP order.

## M6-C map combat, no animation

Exit: Attack with target, forecast, damage, exp/level, death, weapon uses. The battle itself resolves at once
until M6-G (animations on) and the map battle animation of M6-H (animations off); both are stand-ins.

Call chain (decomp):

1. Unit menu Attack: `UnitActionMenu_Attack` → `StartUnitWeaponSelect` (bmmenu.c) starts `gWeaponSelectMenuDef`,
   the unit face and the item panel (`ForceMenuItemPanel`, menuitempanel.c). Menu rows:
   `WeaponSelectMenu_IsAvailable` (uses `MakeTargetListForWeapon`), `_Draw` (`DrawItemMenuLine`), `_SwitchIn`
   (`UpdateMenuItemPanel`, standing reach range, `DisplayMoveRangeGraphics(2)`).
2. Weapon chosen: `WeaponSelectMenu_Selected` → `EquipUnitItemSlot`, `MakeTargetListForWeapon` (bmtarget.c:
   `ForEachUnitInRange`, `AddUnitToTargetListIfNotAllied`, `TryAddTrapsToTargetList`; uiselecttarget.c `InitTargets`,
   `AddTarget`), `NewTargetSelection(&gSelectInfo_Attack)`.
3. Target select: `TargetSelection_Loop` with the handlers `AttackMapSelect_SwitchIn` (`ChangeActiveUnitFacing`,
   `BattleGenerateSimulation`, `UpdateBattleForecastContents`), `_Select` (fills `gActionData`, ends BKSEL),
   `_Cancel` (`gProcScr_0859B630` back to the weapon menu), `_End` (`CloseBattleForecast`). Forecast window: bksel.c.
4. Action: `ApplyUnitAction` → `ActionCombat` (bmmind.c) → `BattleGenerateReal` → blocking `sProcScr_CombatAction`:
   `BeginBattleAnimations`, sleep, `BattleApplyGameStateUpdates`, death branch (`BATTLE_PostCombatDeathFades`, 32
   frames, `BATTLE_DeleteLinkedMOVEUNIT`), `BATTLE_HandleItemDrop`, `BATTLE_HandleCombatDeaths`.

State on 11-12: bmbattle.c 26 verified (stat computation, scripted battle), 20 transcribed (`BattleGenerateReal`,
unit updates), 64 unwritten (hit rolls, round order, exp, level-up). bksel.c, uiselecttarget.c, bmtarget.c,
bmmind.c, menuitempanel.c: nothing written.

Captures:

- Have: `ram_trace_prologue_attack` (PP-cap3, animations on): weapon menu f1365, weapon list f1400, target select
  and forecast f1430, confirm f1460, battle screen f1496..1989, results f2010..2038 (exp 0→10, no death, no
  level-up). The menu, target, forecast, battle-generation and result ports can be checked against it as it is.
- MC-cap1: the same inputs with animations off, plus one B out of target select and one out of the weapon menu.
  Gives the cancel paths and the animations-off timeline (map battle animation procs, for M6-H).
- MC-cap2: a kill (Seth), for death handling and kill exp.
- A level-up: the first run that has one. EP-cap2 (full Prologue clear) is expected to; if it has none, add a CAP.
- Not reachable on turn 1: switching between targets (each reachable tile touches one enemy). Stays transcribed.

How animations get turned off for a capture is open: Options-menu inputs inside the run (also useful for M6-F) or
a new fixture save. MC-cap1 decides and the later captures reuse it.

## M6-D enemy phase and AI (Prologue)

Exit: enemies move and attack; phases cycle; enemy banner.

Call chain: `gProc_BMapMain` label 3: `BmMain_ChangePhase`, `BmMain_SuspendBeforePhase`, phase intro,
`TickActiveFactionTurn`, four blocking displays (status decay, terrain heal, poison, gorgon egg; each ends at once
with no targets), `sub_8015434`, `BmMain_StartPhase` → `gProcScr_CpPhase` (`AiPhaseInit`) → `gProcScr_CpOrder`
(`CpOrderMain`: `BuildAiUnitList`, `SortAiUnitList`, `CpOrderFunc_BeginDecide`) → `gProcScr_CpDecide`
(`CpDecide_Main` → `AiDecideMain` → `DecideScriptA/B` → cp_script.c commands → cp_battle.c / cp_utility.c) →
`gProcScr_CpPerform` (camera, `CpPerform_BeginUnitMovement`, `AiStartCombatAction` → `ApplyUnitAction`, the M6-C
battle code, `HandlePostActionTraps`, `RunPotentialWaitEvents`, cleanup).

State: bm.c phase functions, the berserk phase/order (PP-13) and the player banner are verified. cp_decide.c,
cp_script.c, cp_battle.c, cp_perform.c and nearly all of cp_utility.c: nothing written.

Captures:

- Have: `ram_trace_prologue_cancel_end` f1510..1650: End, phase switch, enemy banner, first decision, first enemy
  walk, target cursor. It stops before the first enemy battle.
- EP-cap1: one whole enemy phase with animations off, to the turn-2 player phase. Adds `gAiState`/`gAiDecision`
  and red-unit watches. `tracecall` (ROM repo README) can record AI score return values where RAM does not show them.
- EP-cap2: full Prologue clear with animations off: later enemy phases, the two Normal-mode events (one enemy
  left; the boss's flag-gated turn event), the boss kill, probably a level-up, the start of the ending scene.
  Per-step watches for the whole run; RAM windows are picked after a scout run.
- D12-2 (per-scanline blend of the enemy banner) keeps its own capture row.

Prologue events seen in `prologue-eventinfo.h`: the Turn 1 (red) and Turn 2/3 (blue) events only call tutorial
scripts, so in Normal mode they end at once. Tutorials are Easy only (see M6-E).

## M6-E Prologue completable

Exit: win → ending event → save → Chapter 1 map; game over works.

Call chain: boss death → misc event `DefeatBoss` → `CallEndEvent` (eventinfo.c) → ending scene script (victory
music, background, eleven flags, `MNC2`) → `gProcScr_GameControl` label POST_NORMAL_CHAPTER:
`GameControl_PostChapterSwitch`, `GameControl_ChapterSwitch` (RN advance, chapter time/turn record, rankings),
`GameControl_CallPostChapterSaveMenu` (savemenu.c, savedraw.c) → label EXEC_BM: world map wrapper (the Chapter 1
world-map script sets `SKIPWN`, so no map is shown), `StartBattleMap`, chapter intro, Chapter 1 beginning scene.
Game over: `CauseGameOverIfLordDies` → `CallGameOverEvent` → eventcall.c → gameoverbgfx.c → title.

Captures:

- PC-cap1: EP-cap2's inputs continued through the ending scene, the save menu and the Chapter 1 opening to turn 1.
- PC-cap2: Eirika dies (animations off) → game over screen → title.
- New Game menus: `ram_trace_newgame` and `menu_screens` already pass the difficulty and slot menus; PC-0 checks
  whether their dumps are dense enough or a new capture is needed.

Open point for the owner: once the real difficulty menu is in, Easy is selectable, and Easy runs the Prologue
tutorial (15 tutorial scripts, forced inputs in playerphase.c, a scripted battle). Rows PC-cap3/PC-15 cover it at
the end of M6-E. Until they are done, Easy would play without the tutorial; that has to be listed as a stand-in.
Hard needs CAP-hard1 (autolevel), already an open row.

## M6-F info screens

Start state for all captures: `prologue.sav` (turn 1). Sub-lanes, each CAP → INV → PORT:

| Sub-lane | Decomp files (functions) | Capture content |
|---|---|---|
| Stat screen | statscreen.c 93 | R on Eirika, three pages both directions, up/down to Seth, B; R on an enemy |
| Help boxes | helpbox.c 71 | R in the stat screen (move across fields), in the item list, in the forecast |
| Map-menu screens | unitlistscreen.c 40, uichapterstatus.c 21, uiconfig.c 22, bmguide.c 29 | Map menu: Unit (sort, page), Status, Options (change two settings), Guide |
| Minimap | minimap.c 34 | Start on the map: open, move, close |
| Map idle keys | playerphase.c (`PlayerPhase_MainIdle` R/L branches, `TrySwitchViewedUnit`, `CanShowUnitStatScreen`) | L to cycle units, R on a unit; taken from the stat-screen capture |

Options must take effect where a port reads them (animation setting, text speed, window colour).

## M6-G battle animation core (melee)

About 400 of 1,495 functions. Windows that exist: `ram_trace_prologue_battle` (scripted Seth/Valter, f14401..15035),
`ram_trace_prologue_battle_end`, `ram_trace_prologue_attack` f1464..2010 (Eirika/Soldier). BA-0 lists the ekr/efx
procs and animation-script commands these windows run; that fixes the function list. Likely groups (files):
start transition (banim-ekrbattlestarting.c 13, banim-ekrbattleintro.c 19), setup from the hit array
(banim-battleparse.c 6, banim-ekrmain.c 25), script driver (animedrv.c 11, banim-ekrcmd.c 20), main loop
(banim-ekrbattle.c 48), screen furniture (banim-ekrdispup.c 19, banim-ekrgauge.c 17, banim-ekrutils.c 36), hit
effects (banim-efxhit.c 24, banim-efxflash*.c 49, banim-efxhpbar.c 20, banim-efxbattle.c 14, banim-efxutils.c 26),
sound (banim-efxsound.c 24), death (banim-efxdeath.c 15), exp and level-up (banim-ekrlvup.c 25, banim-efxlvup.c
32, banim-ekrpopup.c 25), ending (banim-ekrbattleending.c 9). A per-step LCD/palette/OAM capture (as
`ram_trace_prologue_wm_lcd`) is likely needed: BA-cap1.

## M6-H chapters 1–4 systems, as first needed

C14-0 is a scout: play chapters 1 to 4 (fixtures `chapter1.sav`, `chapter2.sav`; procs watch only) and list which
unported procs each chapter first needs. That sets the order of the sub-lanes: map battle animation (mapanim*.c;
the animations-off windows of MC-cap1/2 already exist), items (bmitemuse.c 57, bmusemind.c 38), trade (bmtrade.c
44), rescue family (bmmenu.c, bmmind.c, bmtarget.c), visit/seize, talk and recruit with the green phase, heal
staves, doors/chests/keys/steal, shops (bmshop.c 81), reinforcements and turn events, world-map intros of chapters
2 to 4 (path opening, node icons; the parts D11-wm3f left transcribed), fog (first needed later; capture exists
for chapter 6).

## M6-I preparations and free-roam world map

prep_*.c 452 functions, rest of worldmap_*.c about 480. Fixtures: `chapter6.sav`, `complete100.sav`. Sub-lanes:
prep main menu, pick units, items/convoy/supply, view map and reposition, save; free-roam movement and UI, base
menu shops, skirmishes and monster spawns, Tower/Ruins and retreat.

## M6-J late game and extras

Promotion (classchg*.c 65, banim-ekrclasschg.c 30), arena (bmarena.c 22, banim-ekrarena.c 19), supports
(uisupport.c 62, bmreliance.c 24), remaining spell animations (banim-efxmagic-*.c, about 700), dragons and the
final boss (banim-ekrdragon*.c, about 150), route split, dance/summon/ballista/status staves/traps, endings
(ending_credits.c 20, ending_details.c 54), sound room (soundroom.c 52), extras (bonusclaim*.c 32, opinfo.c 49,
Creature Campaign).

## M7

Not broken down here: [M7-modding](M7-modding.md) has 23 proposed rows; they wait for owner decisions D2–D9.

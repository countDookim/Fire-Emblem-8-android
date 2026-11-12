# M6-C to M6-J: task breakdown notes

Written 2026-11-12 (owner PC) when Lane A finished. Rows are in [TASKS](../TASKS.md); milestones and task rules in
[ROADMAP](ROADMAP.md). This file holds what the rows are based on: the decomp call chains, the captures each
milestone needs, and what is not known yet.

**How exact the rows are.** M6-C, M6-D and M6-E name functions read from the decomp call chains below; their INV
rows still correct the lists (ROADMAP rule 3). M6-F names files and behaviours; its PORT rows are groups that the
INV splits into rows of at most about 8 functions. M6-G to M6-J are sub-lane starters (CAP, INV, one grouped PORT
row, WIRE): nothing past the INV is fixed. Function counts are decomp totals per file, not all reachable.

**Lanes (owner-approved 2026-11-12).** Lane A, now empty, takes the main line M6-C → M6-D → M6-E. Lane B finishes M6-B and then
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
- MC-cap1 (done, `ram_trace_prologue_attack_noanim`; Options inputs inside the run): the same inputs with animations off, plus one B out of target select and one out of the weapon menu.
  Gives the cancel paths and the animations-off timeline (map battle animation procs, for M6-H).
- MC-cap2 (done, `ram_trace_prologue_kill`; Seth kills on turn 1, no idle UI at the end): a kill (Seth), for death handling and kill exp.
- A level-up: the first run that has one. EP-cap2 (full Prologue clear) is expected to; if it has none, add a CAP.
- Not reachable on turn 1: switching between targets (each reachable tile touches one enemy). Stays transcribed.

Animations are turned off by Options-menu inputs inside the run (map menu on the empty tile: A, DOWN x3, A, RIGHT x2,
B; `gPlaySt.config.animationType` 0 → 1); later captures reuse the prefix.

### MC-0 inventory (2026-11-12)

Test: `MapCombatInventoryTest` (procs per window, asserted row by row). Functions: a scratch build of the
harness's `tracecall` hooks (scratch only, no ROM-repo change) logged every entry of about 700 functions of
bmmenu.c, uiselecttarget.c, bksel.c, menuitempanel.c, bmtarget.c, bmmind.c, bmbattle.c, bmphase.c, mu.c, ap.c and
mapanim*.c over `..._attack_noanim` f1590..2124 and `..._kill` f1620..2600 (cap3: bmmind/bmbattle/bksel/uiselect/
bmtarget/mapanim over f1360..2070 to compare). Frames below are the noanim run; the kill run has the same shape (frames in its header). "not run" = traced in all three runs and never entered.

Procs per window (script symbol; parent is `gProcScr_PlayerPhase` / tree 3 unless stated):

| Window | noanim (cap3 frames differ) | Procs |
|---|---|---|
| unit menu | f1595.. | `sProc_Menu` + 3 `sProc_MenuItem`, `ProcScr_GreenTextColor`, `sProcScr_MoveLimitView(+Change)` |
| weapon list | f1630..1659 (A), f1704..1729, f1760..1789 | `sProc_Menu` + item, `gProcCmd_MenuItemPanel`, `gProcScr_E_FACE` (+`gProcScr_0859124C`, `gProcScr_FaceBlink`) |
| target select | f1660..1699, f1790..1819 | `gProcScr_TargetSelection`, `gProcScr_BKSEL` (forecast), `sProcScr_MoveLimitView` |
| cancel (B f1700) | f1700..1703 | `gProcScr_0859B630` (camera wait, then weapon list restarts f1704); BKSEL ends f1704 |
| cancel (B f1730) | f1730 | weapon list gone, unit menu restarted (`ItemMenu_ButtonBPressed`) |
| combat | f1824..2074 | `sProcScr_CombatAction` (child of `gProcScr_PlayerPhase`), `ProcScr_MapAnimBattle` f1825..2072, `ProcScr_Mu` f1825..2079 |
| map anim | f1834..1990 | `ProcScr_MapBattleInfoBox`; per round `ProcScr_CallDelayedArg` + `ProcScr_MapAnimDefaultItemEffect` (child of MapAnimBattle), `ProcScr_MuHitFlash`, `ProcScr_PalFade` (children of Mu); miss: `ProcScr_ApProc` (kill run f1805..1832) |
| death (kill run) | f1891..1921 | `ProcScr_MuDeathFade` (+`MuHitFlash`, `PalFade`), `ProcScr_Mu` of the dead unit ends f1922 |
| exp bar | f1993..2060 | `ProcScr_MapAnimExpBar` |
| write-back, idle | f2074 .. f2084 | `ProcScr_StdEventEngine` f2076..2078, `gProcScr_SideWindowMaker` f2083, Goal/Terrain/MinimugBox f2084 |

cap3 instead has `ProcScr_ekrBattleStarting`, `gProc_ekrBattle`, `ProcScr_ekrGauge`, `gProc_ekrDispUP`,
`ProcScr_efx*` and `ProcScr_ekrBattleEnding` (M6-G); its `sProcScr_CombatAction` is f1464..2028 and the write-back
f2028. The kill run ends with no idle UI: after Eirika's attack both units are done and `gProcScr_PlayerPhase` ends
f2558 (`gProcScr_BerserkCpPhase`, `ProcScr_PhaseIntro` f2569).

Findings that change the rows:

- **Options Animation Off sets `gBattleStats.config` MAPANIMS** (`GetBattleAnimPreconfType` runs 4 times per
  `BeginBattleAnimations`; `BeginBattleMapAnims` starts `ProcScr_MapAnimBattle`). Then
  `BATTLE_GOTO1_IfNobodyIsDead` always jumps to label 1: `BATTLE_PostCombatDeathFades` and
  `BATTLE_DeleteLinkedMOVEUNIT` **never run** in the kill run. The death fade is the map animation's
  (`MapAnim_DisplayDeathFade` → `MU_StartDeathFade`, `MuDeathFade_OnLoop` x32, `EndMu`); both functions run only in
  the animations-on path, with a death, which no capture has.
- Units are written back once, at the end (`BattleApplyGameStateUpdates` f2074 noanim, f1999 and f2549 kill, f2028
  cap3): HP, exp, uses, weapon exp, the dead unit's struct. During the animation the unit structs do not change;
  the map animation reads `gBattleHitArray` (hit records identical to the forecast's).
- `BattleApplyExpGains` runs inside `BattleGenerateReal` (f1824), not at write-back. `CheckBattleUnitLevelUp` runs
  (no level-up); `GetStatIncrease`, `CheckBattleUnitStatCaps`, `UnitLevelUp`, `GetAutoleveledStatIncrease`,
  `ApplyUnitPromotion`, `GenerateBattleUnitStatGainsComparatively` never run.
- The weapon list's panel calls `BattleGenerateUiStats` (6 calls over 3 openings) and so `InitBattleUnit`, `SetBattleUnitWeapon`,
  `ComputeBattleUnitStats` and the compute chain, before any forecast.
- Forecast loop: `BattleForecast_LoopSlideOut` runs only on cancel (f1700..1703); confirm ends BKSEL at once
  (`Proc_EndEach` in `AttackMapSelect_Select`, `BattleForecast_OnEnd`). Save option = standard forecast:
  `DrawBattleForecastContentsExtended` never runs. `PutBattleForecastTilemaps`, `StartBattleForecastHelpBox` and the
  help-box populators never run. Target switching never runs (`TargetSelection_HandleMoveInput` runs 68 times
  without a change); `LinkTargets`, `GetLinkedTargetsFar`, `NewTargetSelection_Specialized` never run.
- The weapon item's switch-out is `BallistaRangeMenu_SwitchOut` (`HideMoveRangeGraphics`); `ItemMenu_ButtonBPressed`
  is the weapon list's cancel (restarts the unit menu).

Corrected PORT rows (functions that the windows enter; "ported" = already has a `Ported from` tag; the first
frame is the noanim run):

| Row | Functions run | Not run: keep transcribed / unwritten |
|---|---|---|
| MC-1 | `InitTargets`, `AddTarget`, `GetSelectTargetCount`, `GetFurthestTargetDistance`, `GetLinkedTargets`, `GetLinkedTargetsNear` (f1660) | `GetTarget` (never entered), `LinkTargets`, `GetLinkedTargetsFar` |
| MC-2 | `MakeTargetListForWeapon`, `ForEachUnitInRange`, `AddUnitToTargetListIfNotAllied`, `TryAddTrapsToTargetList` (f1630 per weapon, 1660), `AreUnitsAllied`, `IsSameAllegiance` | the other 60 `Make*TargetList`s (unit-menu availability calls `ForEachAdjacentUnit`, Trade/Rescue/Take/Talk/Support lists: PP-10) |
| MC-3a | `UnitActionMenu_Attack` (f1630), `StartUnitWeaponSelect`, `WeaponSelectMenu_IsAvailable` (x5 per open), `_Draw`, `_SwitchIn`, `sub_80832C4`; `DisplayUnitStandingAttackRange`, `HideMoveRangeGraphicsWrapper` (unit-menu item hooks) | `StartUnitBallistaSelect`, `BallistaRangeMenu_*` other than SwitchOut |
| MC-3b | `WeaponSelectMenu_Selected` (f1660), `sub_80832C8`, `EquipUnitItemSlot`, `ClearBg0Bg1`, `BallistaRangeMenu_SwitchOut`, `ItemMenu_ButtonBPressed` (cancel f1730) | |
| MC-4 | `ForceMenuItemPanel`, `UpdateMenuItemPanel`, `MenuItemPanelProcIdle` (f1630..), `DrawItemMenuLine`, `BattleGenerateUiStats` (panel numbers), `StartFace`, `SetFaceBlinkControlById`, `GetUnitPortraitId`, `EndFaceById` (face; check whether faces are ported) | `EndMenuItemPanel` |
| MC-5 | `NewTargetSelection`, `TargetSelection_Loop`, `_HandleMoveInput`, `_HandleSelectInput`, `_GetRealCursorPosition`, `EndTargetSelection` (all ran in f1660..1819) | `NewTargetSelection_Specialized`, `sub_804FBBC`, `sub_804FBDC`, `RefreshBMapGraphics` step of the proc script (the loop never ends by itself) |
| MC-6 | `AttackMapSelect_SwitchIn`, `_Select`, `_Cancel`, `_End`, `sub_8022E38`, `sub_8022E54`, `ChangeActiveUnitFacing`; proc `gProcScr_0859B630` | `StartBattleForecastHelpBox`; the `uid == 0` obstacle path (`InitObstacleBattleUnit`), `BattleGenerateBallistaSimulation` |
| MC-7 | `BattleGenerateReal`, `BattleGenerateRealInternal` (f1824), `BattleGenerate`, `ClearBattleHits`, `BattleUnwind`, `BattleGetBattleUnitOrder`, `BattleGetFollowUpOrder`, `BattleGenerateRoundHits`, `GetBattleUnitHitCount` (9 calls each = 3 per generate) | `BattleGenerateBallistaReal`, `BattleGenerateArena`, `BattleUnwindScripted`, `SetScriptedBattle` |
| MC-8a | `BattleGenerateHit`, `BattleGenerateHitAttributes`, `BattleRoll1RN`, `BattleRoll2RN`, `BattleUpdateBattleStats`, `BattleGenerateHitEffects`, `GetItemAfterUse` | `BattleGenerateHitScriptedDamage`, `BattleApplyReaverEffect` |
| MC-8b | `BattleCheckBraveEffect`, `BattleCheckSureShot`, `BattleCheckPierce`, `BattleCheckGreatShield`, `BattleCheckPetrify`, `BattleGenerateHitTriangleAttack` (all entered; the effect branches taken by none stay `not verified`) | `BattleCheckTriangleAttack`, `BattleCheckSilencer` |
| MC-9 | `BattleGenerateSimulation` (f1660, f1790), `BattleGenerateSimulationInternal`, plus the shared MC-7/MC-8 chain | `BattleGenerateBallistaSimulation` |
| MC-10 | `NewBattleForecast` (f1660, as `onInit`), `BattleForecast_Init`, `InitBattleForecastBattleStats`, `BattleForecastHitCountUpdate`, `GetBattleForecastPanelSide`, `UpdateBattleForecastContents`, `CloseBattleForecast`, `BattleForecast_OnEnd` | |
| MC-11 | `InitBattleForecastIconPaletteBuffer`, `InitBattleForecastLabels`, `PutBattleForecastUnitName`, `PutBattleForecastItemName`, `DrawBattleForecastContents`, `DrawBattleForecastContentsStandard`, `GetFactionBattleForecastFramePalette`, `InitBattleForecastFramePalettes` | `DrawBattleForecastContentsExtended`, `PutBattleForecastTilemaps` |
| MC-12a | `BattleForecast_OnNewBattle`, `_LoopSlideIn` (f1661..1668), `_LoopDisplay` (every frame to f1699), `_LoopSlideOut` (cancel only), `MapEventEngineExists_`, `TriggerBattleForcastToturialEvent` | |
| MC-12b | `PutBattleForecastWeaponTriangleArrows`, `PutBattleForecastMultipliers`, `UpdateBattleForecastEffectivenessPalettes` (every display frame) | `StartBattleForecastHelpBox`, `GetBkselHelpBoxMsg`, `HbPopulate_BkselWTriEff*` (M6-F) |
| MC-13 | `ApplyUnitAction` combat branch, `ActionCombat` (f1824), the `sProcScr_CombatAction` script, `BATTLE_GOTO1_IfNobodyIsDead` (always label 1: map anims set), `BATTLE_HandleItemDrop`, `BATTLE_HandleCombatDeaths`, `StoreRNStateToActionStruct`, `BeginBattleAnimations` + `GetBattleAnimPreconfType` as hooks | `BATTLE_PostCombatDeathFades`, `BATTLE_DeleteLinkedMOVEUNIT`, `DidUnitDie`, `LoadRNStateFromActionStruct`, all other `Action*` |
| MC-14 | `BattleApplyExpGains` (inside `BattleGenerateReal`), `GetBattleUnitExpGain`, `GetUnitRoundExp`, `GetUnitExpLevel`, `CanBattleUnitGainLevels`, `GetUnitKillExpBonus`, `ModifyUnitSpecialExp`; kill run only: `GetUnitPowerLevel`, `GetUnitClassKillExpBonus`, `GetUnitExpMultiplier` | `BattleApplyItemExpGains`, `GetBattleUnitStaffExp`, `BattleApplyMiscActionExpGains` |
| MC-15 | `BattleApplyGameStateUpdates`, `BattleApplyUnitUpdates`, `UpdateUnitFromBattle` (x2), `GetBattleUnitUpdatedWeaponExp`, `HasBattleUnitGainedWeaponLevel`, `BattleApplyBallistaUpdates` (no-ballista path), `DidBattleUnitBreakWeapon` | `UpdateUnitDuringBattle`, `UpdateActorFromBattle`, `UpdateObstacleFromBattle`, `BattleApplyItemEffect` |
| MC-16 | `KillUnitOnCombatDeath` (f1999, f2549), `DropRescueOnDeath` (no-rescue path), `UnitKill` (f1999) | `BATTLE_PostCombatDeathFades`, `BATTLE_DeleteLinkedMOVEUNIT`, `KillUnitOnArenaDeathMaybe`; the visible fade moved to C14-ma (`MU_StartDeathFade`, `MuDeathFade_OnLoop`, `EndMu` run in the map animation) |
| MC-17 | `CheckBattleUnitLevelUp` (no-level-up result) | everything else: `GetStatIncrease`, `GetAutoleveledStatIncrease`, `CheckBattleUnitStatCaps`, `UnitLevelUp`, `GenerateBattleUnitStatGainsComparatively`, `ApplyUnitPromotion`; stays unwritten until a capture has a level-up |

Already ported (`Ported from` found in the code repo) and entered by these windows: `ApplyUnitAction`, `BattleGenerate`,
`InitBattleUnit`, `SetBattleUnitWeapon`, `SetBattleUnitTerrainBonusesAuto`, `ComputeBattleUnit*` (stats, attack,
speed, hit, avoid, crit, dodge, defense, support bonuses, effective stats, silencer), `BattleUpdateBattleStats`,
`BattleGenerateHitEffects`, `BattleApplyWeaponTriangleEffect`, `BattleUnitTarget*`, `GetUnitWeaponReachBits`,
`DisplayMoveRangeGraphics`, `HideMoveRangeGraphics`, `EnsureCameraOntoPosition`, `PutMapCursor`, `LockGame`,
`UnlockGame`, `UnitBeginAction`, the MU functions (`StartMu`, `Mu_OnLoop`, `PutMu`, `EndMu`, ...).
Port rows must not re-claim them; MC-7/8/9 only add the rest of the chain.

Not covered by any MC row:

- `ProcScr_StdEventEngine` f2076..2078 (and kill run f2001..2003, f2551..2553) and `gProcScr_SideWindowMaker`
  after the write-back: the post-action event check and the player-phase UI restart (PP-11/PP-12 area; check there).
- `sProc_DelayedBMapDispResume` (bmio.c, cap3 only f2010..2011).
- `BattleIsTriangleAttack` runs 9 times at the map animation's start (f1828), not in hit generation: M6-H.
- The kill run's turn end after the last unit (`gProcScr_PlayerPhase` ends f2558, berserk phase, phase intro) is M6-D.
- `sub_802C334` (clears a bmbattle static) runs in `UnitBeginAction`, not in combat.

Map battle animation procs seen (all animations-off windows; belong to M6-H C14-ma): script
`ProcScr_MapAnimBattle` (mapanim_api.c: `MapAnim_PrepareBattleTalk`, `MapAnim_MoveCameraOntoSubject`,
`MapAnim_CallBattleQuoteEvents`, `SetBattleMuPalette`, `SetupBattleMOVEUNITs`, `MapAnim_InitInfoBox`, per round
`MapAnim_PrepareNextBattleRound`, `MapAnim_DisplayRoundAnim`, `MapAnim_ShowPoisonEffectIfAny`, then
`ProcScr_MapAnimEnd`: `MapAnim_DisplayDeathQuote`, `MapAnim_DisplayDeathFade`, `EndMapAnimInfoWindow`,
`MapAnim_StoleItemPopup`, `MapAnim_DisplayExpBar`, `DisplayWpnBrokePopup`, `DisplayWRankUpPopup`,
`MapAnim_Cleanup`); setup `BeginBattleMapAnims`, `SetupMapBattleAnim`, `SetupMapAnimSpellData`,
`MakeBattleMOVEUNIT`, `SetBattleAnimFacing`, `GetFacingDirection`, `HideUnitSprite`; `ProcScr_MapAnimDefaultItemEffect`
(mapanim_spellassoc.c: `MapAnim_BeginSubjectFastAnim`, `MapAnim_MoveSubjectsTowardsTarget`/`AwayFromTarget` x4,
`MapAnim_MoveCameraOnTarget`, `MapAnim_BeginRoundSpecificAnims`, `RegisterMapHpChangeAnim`,
`MapAnim_WaitForHPToEndChangingMaybe`); `ProcScr_MapBattleInfoBox` (mapanim_infobox.c: `StartMapAnimInfoWindow`,
`ProcMapInfoBox_OnDraw`, `MapInfoBoxShake`, `sub_807BCA8`, `sub_807BB10`, `DisplayBattleInfoBox`, scanline gradient
`StartManimFrameGradientScanlineEffect`); `ProcScr_MapAnimExpBar` (mapanim_expbar.c: `ProcMAExpBar_*`,
`DrawMAExpBar`; `ProcMAExpBar_LevelUpIfPossible` runs, no level-up); Mu side (mu.c): `StartMuSpeedUpAnim`,
`MuSlowDownAnimFreezeFunc`, `StartMuHitFlash`, `MuFlashFadeFrom_RestorePal`, `MU_StartDeathFade`,
`MuDeathFade_OnLoop`; miss: `ProcScr_ApProc` (`APProc_Create`/`_OnUpdate`/`_OnEnd`, `MapAnim_BeginMISSAnim`);
generic: `ProcScr_CallDelayedArg`, `ProcScr_PalFade` (bmlib.c). Round windows (noanim): f1851..1889, f1897..1937,
f1945..1983. Never run: `ProcScr_MapAnimEnd` level-up screens (`StartManimLevelUp` and the whole mapanim_lvup.c /
mapanim_lvupfx.c), poison/egg/steal/summon/dance, `MapAnim_BeginNODAMAGEAnim`, the spell-assoc scripts.

### MC-7/MC-8a result (2026-11-12)

`BattleGenerateRealDifferentialTest` seeds the unit table, `gPlaySt`, the terrain, both battle units,
`gBattleStats`, `gActionData` (who attacks whom) and the RNG state from the dump before the call and compares
`gBattleActor`, `gBattleTarget`, `gBattleStats`, `gBattleHitArray` (7 hits on sentinels), `gBattleHitIterator`
and the RNG state with the dump after it. Four cases pass: cap3 f1462 -> f1464 (the call straddles f1463, which
holds a half-written hit array), noanim f1823 -> f1824, kill f1713 -> f1714 and f2293 -> f2294. Verified
(`Ported from`): `BattleGenerateReal`, `BattleGenerateRealInternal`, `ClearBattleHits`, `BattleUnwind`,
`BattleGetBattleUnitOrder`, `BattleGetFollowUpOrder`, `BattleGenerateRoundHits`, `GetBattleUnitHitCount`,
`BattleGenerateHit`, `BattleGenerateHitAttributes`, `BattleRoll1RN`, `BattleRoll2RN`, `GetItemAfterUse`
(`BattleGenerate`, `BattleUpdateBattleStats`, `BattleGenerateHitEffects` were verified before). Masked: `unit.exp`
(+0x09) and `expGain` (+0x6E) of both battle units, which `BattleApplyExpGains` writes (MC-14: the hook
`BattleGenerate.applyExpGains` stays empty; `PidStats*` save statistics are not ported). Not covered by any
capture and noted in the KDoc: the brave second strike and the no-weapon return of `BattleGenerateRoundHits`, a
battle ending in the actor's own round, and the petrify ending. Left for MC-8b (transcribed, `not verified`):
`BattleCheckBraveEffect`, `BattleCheckSureShot`, `BattleCheckPierce`, `BattleCheckGreatShield`,
`BattleCheckSilencer`, `BattleCheckPetrify`, `BattleGenerateHitTriangleAttack`; `BattleCheckTriangleAttack` is
the hook `BattleGenerate.checkTriangleAttack` (false). Rolls go through `Mechanics.battleRolls` (hit roll for
`BattleRoll2RN`, crit roll for the crit rate, skill roll for every other `BattleRoll1RN`). `BattleGenerateSimulation`
(MC-9) can reuse `battleGenerate` as is.

### MC-9 result (2026-11-13)

`BattleGenerateSimulationDifferentialTest` seeds the unit table, `gPlaySt`, terrain, both battle units,
`gBattleStats` (and the RNG) from the dump before `AttackMapSelect_SwitchIn`'s call and compares both battle
units, `gBattleStats`, the 7 hits with the iterator and the RNG state (a forecast must not advance it) with the
dump after. Five cases pass: cap3 f1430 -> f1431, noanim f1660 -> f1661 and f1790 -> f1791, kill f1680 -> f1681
and Eirika f2250 -> f2260 (that stretch is dumped every 10 frames and the weapon choice reorders the items in
between, so her unit table and item slot come from the after dump, which the forecast does not change; the
f2230 -> f2231 step of the plan is a `BattleGenerateUiStats` call, not a forecast). The active unit is
`gActiveUnit`; the target is the unit `gBattleTarget` holds after (the target selection proc is not ported).
Verified (`Ported from`): `BattleGenerateSimulation`, `BattleGenerateSimulationInternal`; the rest of the chain
is MC-7/MC-8a code. Not covered: an explicit actor position (the AI's call, EP-5) and the ballista simulation.

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

Easy mode (owner-approved 2026-11-12: part of M6-E): once the real difficulty menu is in, Easy is selectable, and Easy runs the Prologue
tutorial (15 tutorial scripts, forced inputs in playerphase.c, a scripted battle). Rows PC-cap3/PC-10 cover it at
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

### MC-3a result

`WeaponSelectMenuDifferentialTest` replays `ram_trace_prologue_attack_noanim` f1595..f1658 (unit menu start, A on
Attack f1630, weapon list to f1658) against the per-frame dumps: menu and item procs, `gBmMapMovement`/`gBmMapRange`,
the range view proc and flags, BG0..BG3 tilemaps, icon tables and palette. Ported and break-checked:
`UnitActionMenu_Attack`, `StartUnitWeaponSelect`, `WeaponSelectMenu_IsAvailable`, `WeaponSelectMenu_SwitchIn`,
`DisplayUnitStandingAttackRange`, `HideMoveRangeGraphicsWrapper`, `StartOrphanMenu`, `GenerateUnitStandingReachRange`
(not in the row; the windows run it), `GetUnitWeaponReachBits` (slot branch added). Left transcribed:
`WeaponSelectMenu_Draw` (its row is `DrawItemMenuLine`, MC-4) and `sub_80832C4` (returns 0, no state). Hooks until
MC-4: face, item panel (`ForceMenuItemPanel`, `UpdateMenuItemPanel`), `DrawItemMenuLine`; BG0/BG1 tiles of the item
rows and panel are excluded from the compare. Findings: the dump of f1631 is taken mid-frame (the weapon list's start
frame outruns a VBlank, the item panel numbers); from there the game's frame k is the dump k + 1, so the test
compares f1631.. with the dump after. The weapon list shows only slot 1 (slot 0 is a weapon the unit cannot use), so
the no-target answer of `IsAvailable` is not verified. Stand-ins until MC-3b: weapon choice (refusal sound) and B
(`ItemMenu_ButtonBPressed`, reopens the unit menu).

## M7

Not broken down here: [M7-modding](M7-modding.md) has 23 proposed rows; they wait for owner decisions D2–D9.

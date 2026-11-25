# M6-C to M6-J: task breakdown notes

Written 2026-11-12 (owner PC) when Lane A finished. Rows are in [TASKS](../TASKS.md); milestones and task rules in
[ROADMAP](ROADMAP.md). This file holds what the rows are based on: the decomp call chains, the captures each
milestone needs, and what is not known yet.

**Current scopes (2026-11-15):** [M6 task scopes](M6-task-scopes.md) and TASKS supersede the broad
planning groups below. M6-D/E/F now have smaller inventories and candidate function groups; M6-G..J
have scenario-sized CAP/INV rows. A source shortlist is not captured coverage: each INV must fix its
PORT windows/functions and WIRE dependencies before coding. Function counts below are source totals,
not all reachable. Dated results are historical; use HANDOFF for current implementation status.

**Lanes (owner-approved 2026-11-12).** Lane A takes the main line M6-C → M6-D → M6-E. Lane B finishes M6-B and then
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

### MC-8b result (2026-11-13)

Transcriptions of `BattleCheckBraveEffect`, `BattleCheckSureShot`, `BattleCheckPierce`,
`BattleCheckGreatShield`, `BattleCheckSilencer`, `BattleCheckPetrify` and
`BattleGenerateHitTriangleAttack` re-read line by line against `bmbattle.c`: no mismatch. Only the
not-triggered paths are entered, and a wrong one changes the captured data, checked by mutation in
`BattleGenerateRealDifferentialTest` (cap3 f1464, which rolls the RNG per hit): brave always true
(hit count and exp bytes differ; also fails the simulation test), the class test of sure shot, pierce and
great shield always true (each rolls an RN per hit and shifts the RNG and later hits) and petrify always
zeroing the damage. Those five are `Ported from`, with KDoc naming the branch no capture takes (brave
weapon, sniper, wyvern knight, general defender, Monster stone); the simulation test cannot see the
sniper, wyvern and general mutations because a forecast rolls nothing. `BattleCheckSilencer` stays
Transcribed: it runs only after a successful crit roll and no capture has a crit, so always-true changes
nothing. `BattleGenerateHitTriangleAttack` stays Transcribed: no capture attacker has a triangle attack
class attribute, so its first check returns every time and no mutation of the rest changes data.
`BattleCheckTriangleAttack` stays the stand-in `checkTriangleAttack` (always false; stand-in until MC-8c, which transcribes it): its map scan (`gBmMapUnit`, unit status and class of the neighbours) would be unreachable for the
same reason, so it could not be break-checked.

### MC-8c result (2026-11-13)

`BattleCheckTriangleAttack` is transcribed (`BattleGenerate.battleCheckTriangleAttack`, `not verified`: no capture has a triangle attack) and replaces the stand-in hook. It scans the defender's 4 neighbours in `gBmMapUnit` (`BattleGenerate.bmMap`, with `unitTable` for `GetUnit`, both set in `Fe8Boot`), skipping other armies, sleep / petrify / status 13, female wyvern knights and units without a shared `CA_TRIANGLEATTACK_ANY` bit; two or more is a triangle attack. Neighbours off the map count as empty (the C reads the map border); `gBattleStats.taUnitA/B` are not kept (battle animation, M6-H).

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

### EP-cap1 result

Run `ram_trace_prologue_enemy_phase` (ROM repo; copy in `.assets/reference/out/`). Inputs: the Options prefix of
`ram_trace_prologue_attack_noanim`, then A f1480 (map menu), UP f1505 (wraps to End), A f1525 (End), A f2700 (one
dialogue page waits for input). Per-frame RAM f1480..2895, full dumps every 10f. Watches: the standard `WATCHES`,
`MC_BATTLE_WATCHES` minus `unit1`/`target`, `blue` (Seth, Eirika), `red` (all 50 `gUnitArrayRed` entries),
`aistate` (`gAiState`, 0x90), `aidecision` (`gAiDecision`, 0xC). Size 476 MB.

Frame windows (frame N = after step N):

| Window | Frames |
|---|---|
| End chosen, `gProcScr_PlayerPhase` gone | f1525 |
| Berserk order/phase procs, faction 0 -> 0x80 | f1526..1528, f1529 |
| Enemy phase banner (`ProcScr_PhaseIntro`) | f1535..1606 |
| `gProcScr_CpPhase`/`CpOrder`/`CpDecide` start; `gAiState.units` = 0x81, 0x82, 0x83 | f1612 |
| Red 0 (0x81): processed, no decision | f1613 |
| Soldier 1 (0x82): decision f1614, walk (MU) f1614..1695, `gProcScr_AiTargetCursor` f1648..1695 | f1614..1695 |
| Soldier 1 battle: `sProcScr_CombatAction` f1696..2013, strikes f1723..1867, death fade f1875..1906, exp bar f1909..1999 | f1696..2013 |
| Soldier 1 write-back (HP 22 -> 0, struct cleared) | f2012 |
| Soldier 2 (0x83): decision f2039, walk f2039..2128, target cursor f2081..2128 | f2039..2128 |
| Soldier 2 battle: `CombatAction` f2129..2402, strikes f2156..2255, death fade f2263..2294, exp bar f2297..2388 | f2129..2402 |
| Soldier 2 write-back (HP 20 -> 0, struct cleared; Eirika HP 16 -> 7) | f2401 |
| Event engine + dialogue (`gProcScr_Talk`; waits for A f2644..2702), BMXFADE, Cp procs gone | f2403..2746 |
| Green phase (faction 0x40, no units), `UpdateTraps` | f2751..2766 |
| Turn 2: faction 0, turn 2, `StdEventEngine` (ends at once) | f2768..2771 |
| Turn-2 banner | f2774..2845 |
| Idle `gProcScr_PlayerPhase` (position 16); map UI procs f2854 | f2851 (run ends f2895) |

Who acts: red 0 (`gUnitArrayRed[0]`, id 0x81, at (10,8)) does nothing and never moves. Soldier 1 (`Red[1]`, 0x82) and
Soldier 2 (`Red[2]`, 0x83) each move to (5,5) next to Eirika and attack her; both die to her counterattack
(Soldier 1: Eirika takes no damage; Soldier 2: Eirika HP 16 -> 7). Seth is not attacked. No level-up: Eirika ends level 1, exp 0 -> 65 (f2895), so MC-17 still needs EP-cap2.

`gAiDecision`: yes for the two attackers. It is all zero before f1614; f1614 = action 1 (attack), unit 0x82, move
(5,5), target 2 (Eirika), item slot 0, `actionPerformed` 1; cleared f2038; f2039 = the same for 0x83. Red 0 never
changes it (its decision is "no action", flagged processed with the AI_PROCESSED state bit at f1613). `gAiState`: the
unit list is filled f1612 and `unitIt` (bytes 0x74..) steps 0x0203AA05 -> 06 -> 07 per unit; `orderState` (0x78) is 1 from
f1612 and `decideState` (0x79) is 2 while a decision is performed (f1614..2037, f2039..2741).

Scores are NOT in RAM per frame (the candidate scores live in a stack struct inside one frame), so the run has
`tracecall aicombatscore 0x0803E178` (`AiComputeCombatScore`, registered after step 1600): 2 calls, f1614 (Soldier 1,
`AiCombatSimulationSt` at IWRAM 0x03007C90) and f2039 (Soldier 2, 0x03007CAC). `calls.txt` has r0; the `_entry` and
`_exit` IWRAM dumps hold the struct (`score` u32 at +8). Both exits have score 6. The function is called once per
enemy attack, not once per candidate tile, so per-candidate scores are not captured.

Prologue events seen in `prologue-eventinfo.h`: the Turn 1 (red) and Turn 2/3 (blue) events only call tutorial
scripts, so in Normal mode they end at once. Tutorials are Easy only (see M6-E).


### EP-cap2 result (2026-11-14)

Run `ram_trace_prologue_clear` (ROM repo release `assets-93b13fa`; copy in `.assets/reference/out/`). Inputs: all of
EP-cap1, then turn 2: RIGHT f2870, A f2900, UP f2930, A f2960 (End); A f3320, A f3700 (two dialogue pages of the
boss's turn event); turn 3: UP f4000, A f4020 (select Seth), RIGHT f4050/4058/4066/4074, DOWN f4082/4090 (cursor on
(8,6)), A f4130 (move), A f4210 (Attack), A f4240 (weapon), A f4270 (target); A f4440, A f4720 (boss battle and death
dialogue pages). Timeline: turn 2 nobody acts; the boss's flag-gated turn event f2964..3722 (boss `ai1`/`ai2` 6/3 ->
0/0 at f3719); enemy phase 2 banner f3726..3803, the boss's decision f3804 is a move to (9,6) with no attack
(`AiTryMoveTowards` runs, `AiComputeCombatScore` is not called), walk f3804..3831, turn 3 at f3877; Seth attacks from
(8,6): pre-battle event f4278..4461, CombatAction f4275..4891 (hit, counter miss, hit), death event f4636..4741, death
fade f4742..4773, exp bar f4776..4879, write-back f4892 (boss removed, Seth exp 0 -> 45); the ending scene's event
engine starts f4894; run ends f5000. Level-up: none (MC-17 needs MC-cap3). Per-frame RAM (full dump every 10f) in
f2955..3010, f3250..3262, f3316..3330, f3680..3730, f3795..3880, f3955..3970, f4175..4215, f4266..4300, f4436..4470,
f4480..4650, f4700..4960; RAM every 30f f1260..2850, every 10f elsewhere to f5000. Watches as EP-cap1;
`tracecall aicombatscore` records only the two EP-cap1 calls. Size 358 MB (1.3 MB compressed).

### MC-cap3 result (2026-11-14)

Run `ram_trace_prologue_levelup` (ROM repo release `assets-8051ed8`; copy in `.assets/reference/out/`). Inputs: EP-cap2
to the turn-3 idle map (f3961), then turn 3: A f4000, A f4040, A f4120, A f4160, A f4200 (Eirika uses a Vulnerary,
HP 7 -> 16, write-back f4204); RIGHT f4400, A f4430, UP f4460, A f4490 (End); A f4900 (boss dialogue page); turn 4:
LEFT f5280, A f5310, UP f5340, A f5370 (End); turn 5: A f5880, A f5920, A f5990, A f6020, A f6050 (Eirika attacks
the boss); A f6220 (death dialogue page). Seth never acts. Enemy phase 3: the boss decides f4575 to attack Eirika from
(5,5), write-back f5124 (boss HP 13, Eirika exp 76); enemy phase 4: decision f5455, write-back f5714 (boss HP 3,
Eirika HP 6, exp 87). Turn 5: kill strike f6082..6104, death event f6128..6241, death fade f6242..6273, exp bar
f6276..6746 with the level-up procs f6413..6746 (stat screen f6519..6729), write-back f6760; ending scene event
engine f6762, run ends f6870. Level-up: Eirika level 1 -> 2, exp 87 -> 67 (80 for the kill), max HP 16 -> 17, skl
8 -> 9, def 3 -> 4, lck 5 -> 6 (pow, spd, res unchanged). Per-frame RAM (full dump every 10f) in f4196..4210,
f4385..4395, f4570..4700, f4895..5130, f5450..5720, f6050..6830; RAM every 30f elsewhere. Watches as EP-cap2;
`tracecall aicombatscore` records four calls (f1614, f2039, f4575, f5455). Size 568 MB (1.6 MB compressed). The
boss's hit rolls decide the outcome: changing any input may change them.
### MC-17 result (2026-11-15)

Test: `LevelUpDifferentialTest` over `ram_trace_prologue_levelup`. The whole kill-strike battle runs in the step f6054 (the last full dump before it is f6050; the watches show no change in the battle units, unit table or RNG between f6050 and f6053). Seeded from the f6050 dump: `BattleGenerateReal` leaves `gBattleActor` and `gBattleTarget` (level 2, exp 67, `expGain` 80, `changeHP`/`changeSkl`/`changeDef`/`changeLck` 1, the rest 0) and the RNG state as the f6054 watches. The state right before the growth rolls is the same battle run with Eirika's exp 0 (no level-up); `CheckBattleUnitLevelUp` alone on her with 167 exp gives the f6054 unit and RNG state. Write-back: the f6750 dump through `BattleApplyGameStateUpdates` gives Eirika's unit struct of f6760 (dump and `blue` watch; level 2, exp 67, max HP 17, skl 9, def 4, lck 6).
Ported: `CheckBattleUnitLevelUp` (level-up branch, seven growth rolls in order), `GetStatIncrease` (moved from Transcribed in `UnitLoader`), `CheckBattleUnitStatCaps` (private; caps run, none reached). `UpdateUnitFromBattle` was already Ported (MC-15); now also seen with changed stats.
Transcribed: none new. Not seen (KDoc): exp disabled at levels 10 and 20, `US_GROWTH_BOOST`, the all-zero re-roll, a stat at its cap, a promoted unit. The growth boost mutation (always +5) survives: the captured rolls pass either way.
Stand-ins: none new. The level-up display (map level-up popup and stat screen procs f6413..6746, `ProcMAExpBar_LevelUpIfPossible` level-up branch) has no TASKS row (EP-26 covers the no-level-up path only and names MC-17 for the branch).
Wired: `BattleApplyExpGains` calls the real check in every `BattleGenerate` (`Fe8Boot` sets `unitTable`, so caps read the table unit).

### EP-13 result (2026-11-15)

Test: `MapAnimStartDifferentialTest`. The captures' `sProcScr_CombatAction` start: Seth on the boss (`ram_trace_prologue_clear`, `ProcScr_MapAnimBattle` born f4275) and the two Soldiers (`ram_trace_prologue_enemy_phase`, f1697 and f2130; f1696 and f2129 are the combat proc's own start, the animation proc comes a step later). Seeded from the next dump (f4280, f1700, f2130) with the battle units, `gBattleStats` and the first hit; `BeginBattleAnimations` runs in a tree 2 proc. Compared at the start step and the two after: the animation proc (script position, sleep timer, mark, `gBmSt.lock`), both `ProcScr_Mu` procs (unit, slot, cam_b, state, hidden_b, class, facing, step sound clock, move clock, position, offset) and their `sMuConfig` (slot, palette, chr), and `gManimSt` (actor count, round pointer, special script, hp_changing, kind, both actors' unit, battle unit, hp_max, hp_cur) plus the target's hidden map sprite handle.
Ported (new `MapAnimApi`, `ManimSt`): `BeginBattleMapAnims`, `SetupMapAnimSpellData`, `SetupMapBattleAnim`, `MakeBattleMOVEUNIT`, `SetBattleAnimFacing`, `SetupBattleMOVEUNITs`. `Mu.setMuFacing` is checked too.
Transcribed: `sub_807B4D0` (only effect is the sprite-animation layer, a display hook, in no compared value; its mutations survive), `BeginBattleAnimations` (BG 2 clear, backdrop, map renders are display; `EndAllMus` finds no MOVEUNIT in the seed). The special script field is 0 in every capture (no break-check).
Not written, fail loudly: dance/refresh battles, the event battle script, the `sub_8055BC4` true branch (EP-14/15), obstacle and petrified MOVEUNITs; the triangle attack code is present but unseen.
Stand-ins: `restOfAnimation` (EP-17..26: replaced by ending the MOVEUNITs, showing the target sprite and unlocking), camera, battle talk reset, the `GetSpellAssocStructPtr` scan (EP-16), AP layer and `SetDefaultColorEffects` (display).
Wired: `PlayerPhaseWiring` sets `CombatAction.hooks.beginBattleAnimations` (player and enemy phase). A player sees the attacker and target MOVEUNIT sprites (facing each other) for about 3 frames with the target's map sprite hidden, then the result applies as before; no info box, rounds, hit flash or exp bar. Battles and turns still finish (Fe8BootTest, PlayerPhaseBootTest, AttackFlowReplayTest, Cp tests green).

### EP-16 result (2026-11-15)

Test: `MapAnimStartDifferentialTest` (EP-13), extended by name only: the lookups feed the actor count (`gManimSt.actorCount`, hence the Mu procs and animation proc present), the special script (`gManimSt.specialProcScr`) and the facing of both MOVEUNITs, all compared at cap f4275..4277 and the Soldiers f1697, f2130. The weapon-hit path only: no spell in the Prologue.
Ported (new `SpellAssoc`, game/bm): `GetSpellAssocCharCount`, `GetSpellAssocMapAnimProcScript`, `GetSpellAssocFacing`. Break-checked: count offset (+2 -> +3: "step 4275 MOVEUNIT 0 facing ==> expected: <1> but was: <11>", the second actor gone), facing offset (+0xD -> +0xC: "step 4275 MOVEUNIT 1 facing ==> expected: <0> but was: <2>"), proc script offset (+8 -> +4: "f4280 gManimSt specialProcScr ==> expected: <0> but was: <65535>").
Not seen: which entry an item selects. Every captured weapon has the same default entry, so a lookup one entry off (item index +1, scan skipping the first entry) survives, and so does a scan that always returns the first entry (parent review); `GetSpellAssocStructPtr` is therefore Transcribed. No captured value distinguishes entries until a battle with a magic weapon.
Transcribed: `GetSpellAssocStructPtr` (above), `GetSpellAssocReturnBool` (caller in the info box, EP-20) and `GetSpellAssocFlashColor` (hit flash, EP-25): the results reach no captured value yet.
Stand-ins: none new; the EP-13 `spellAssocEntry` scan is gone and `MapAnimApi` calls `SpellAssoc`. Nothing visible changes in game.

### EP-11a result (2026-11-15)

Test: `BossTurnEventDifferentialTest` over `ram_trace_prologue_clear`. Start: `RunPhaseSwitchEvents` seeded from the step 2963 flags and `gActiveEventRegistry` and the step 2964 `gPlaySt` (`SwitchPhases` turns the faction earlier in the same step), compared with step 2964: the `CallEvent` script and exec type (the engine proc's start and exec type), the 0x40 registry bytes (the earlier misc event's entry is replaced: one entry, flag 8, count 1), the flags watch and the tutorial queue fields. AI change: the event engine of step 3718 (parked at the TUTORIAL check before SETVAL slot 1, CHAI, EVBIT and END) loaded into a real `EventEngine` with `EventScrCore` and the new command, one frame run; engine fields, slots 1..13, slot queue, flags and all 50 red units compared with step 3719 (boss `ai1`/`ai_a_pc`/`ai2`/`ai_b_pc` 6/1/3/1 to 0/0/0/0; the only unit whose AI bytes change). No release stub is hit in that frame.
Ported: `ClearActiveEventRegistry`, `RegisterEventActivation` (new `ActiveEventRegistry`, owned by `EventInfos.registry`; the four registry/tutorial hooks of `EventInfoHooks` are gone), `RunTutorialEvent` (nothing-queued path seen; the queued branch is not, a break there survives; the decomp returns false, the old hook note was wrong), `Event39_ChangeAiScript` (CHAI form), `ChangeAiForCharacter`, `ChangeUnitAi` (new `EventChangeAi`). Mutations, all failing a captured-value assertion: clear no-op (`gActiveEventRegistry` bytes, count 2 vs 1), register without the flag (`gActiveEventRegistry`, flag word 0 vs 8), `&&` to `||` in `RunTutorialEvent` (`CallEvent` list has an extra call), `ai_a_pc` not reset (`red unit 0 byte 43 got 01 want 00`), `ai2` stored wrong (`red unit 0 byte 44 got 07 want 00`), character compare inverted (`red unit 0 byte 42 got 06 want 00` and the other three AI bytes).
Transcribed: `CheckTutorialEvent` (no capture reaches it; EP-35), `sub_80832D4` (constant 0; its caller `sub_8015434` is written without the call). Not seen (KDoc): CHAI_AT, the hidden or dead skip, invalid AI values and the `AI_B_0C` flag; the registry full case.
Stand-ins: none new. Talk, faces, text, UI restore, BGM and polls of the event stay EP-11b..h (the seed of step 3718 is already past them); `PidStatsAddFavval` EP-27; `GetEventTriggerId` is still the hook default 0 in `Fe8Boot`.
Wired: `Fe8Boot` registers `EventChangeAi` (the real opcode 0x39 replaces the release stub) and `EventInfos` keeps the registry. In game: when the boss's turn event runs (`RunPhaseSwitchEvents` is wired), opcode 0x39 now changes the boss's `ai1`/`ai2` and resets the script positions instead of being skipped. No boot test plays turn 2's enemy phase, so the full boss event (its talk, faces and text are stubs, EP-11b..h) was not played end to end; `Fe8BootTest`, `PlayerPhaseBootTest`, the Cp and Event tests stay green (727 tests, 0 failures). Not in `MANUAL_TEST.md`: nothing visible changes.

### EP-11b result (2026-11-15)

Test: `TalkEventDifferentialTest` runs the real `EventEngine` with `EventText` over `EventTextTalkHost` and the real talk code (`Talk`, `TalkBubble`, `TalkFace`) as `EventTextDifferentialTest` does for the first talk. Windows: the boss turn event of `ram_trace_prologue_clear` (seed step 2964; `TEXTSTART`, `TEXTSHOW` of one message at f2966 where the Talk proc appears, the wait until f3704, the wait command completes at f3705, the text end at f3706) and the Soldiers' dialogue of `ram_trace_prologue_enemy_phase` (seed step 2463, Talk proc f2465, text end f2706); the same four commands. Start state: `sTalkStateCore` and the BG tilemaps of the seed step's dump, the text speed of `gPlaySt`, the engine proc (`procs` watch) and the event slots. The message is decoded from the ROM and the host starts the Talk proc. Inputs: the captured key mask per frame (`newKeys` derived from `inputs.bin` and asserted equal to the dump at every dumped step; the game clock the same). Per step compared with the `procs` watch: the engine's `pEventCurrent`, `evStateBits`, `evStallTimer`, `execType` and, through `TalkRig.compareProcs`, the counts of the Talk, skip listener, open, bubble-open, shift-clear, wait and lock procs, the Talk, wait and shift procs' script position and lock count, and each face proc's display bits, position, palette slot, face id and layer. At every dumped step (dense in both windows) the whole talk of `TalkRig.compare` (`sTalkStateCore`, texts, font, BG0 and BG1 tilemaps, face tiles at their first dump) and the event slots 1..13. At the text end the face wait proc exists (`FaceExists`). Not built: f4279..4726 (the battle quote, EP-28) and f4933..5000 (the ending scene), which are different event paths.
Ported: `GetTalkFaceHPos` (new `TalkFaceHPos`, shared by `TalkFace` and `TalkBubble`, whose duplicate tables are gone; only slots 1 and 4 are seen, the fight-event branch (4 or 26) is not), `GetTalkChoiceResult` (the captured `gEventSlots[0xC]` goes 1 to 0 at the wait end; `Talk.getTalkChoiceResult`), `IsTalkActive` (`Talk.isTalkActive`, the wait command's first test), `FaceExists` (true result only), `StartTalkExt` (no-parent path, now also in both windows; the `Proc_StartBlocking` branch is not seen).
Transcribed: `EndTalk` (not verified: the talk has already ended when the text end calls it at f3706 and f2706, so no talk proc is there to end), `TalkOpen_OnEnd` (not verified: the function has no body).
Mutations seen to fail (first assertion, boss window; the Soldiers window fails the same way at its frames): face columns of slots 1..7 plus 1 (`talk procs at f2972 ==> expected: <null> but was: <face 0 xPos want 192 got 200>`; Soldiers `f2471 ... want 48 got 56`; a change of slot 0 alone survives the captures); `getTalkChoiceResult` plus 1 (`slot 12 at f3705 ==> expected: <0> but was: <1>`); `isTalkActive` false (`pEventCurrent at f2966 ==> expected: <144634168> but was: <144634172>`); `faceExists` false (`face wait procs at f3706 ==> expected: <1> but was: <0>`); `printColor` 2 in `StartTalkExt` (`talk at f2966 ==> expected: <null> but was: <state byte 0x08 want 1 got 2>`); `activeWidth` plus 1 (`talk at f2966 ==> ... state byte 0x0E want 28 got 29`). Each mutation restored byte-identical (`cmp`), test green.
Stand-ins: the face fade-in/out (`StartFaceFadeIn`/`Out`), the music and palette procs of the event (EP-11c, EP-11g) are not modelled in the test, so the wait for the faces does not end there (it ends at f3718 in the capture). The choice proc (`SetTalkChoiceResult`) is not ported; the result stays 0. `IsBattleDeamonActive` is a host poll (EP-11h): always false in the talk hosts.
Wired: nothing new; the talk already runs in `Fe8Boot` through `EventTextTalkHost` (EP-11a wiring). `GetTalkChoiceResult` now reads `Talk` (always 0, as the stand-in did). In game nothing visible changes. Not in `MANUAL_TEST.md`.

### EP-1 result (2026-11-13)

Test: `PhaseStartEndDifferentialTest` over `ram_trace_prologue_enemy_phase` (blue/red/gplayst/gbmst/procs watches; `gPidStatsData` from the dumps f1520/f1530). New class `PhaseStartEnd` (game/bm). Ported: `ClearActiveFactionGrayedStates` (units at f1529, f2750, f2768, favour -8 of Seth and Eirika in `gPidStatsData`; the unavailable/unselectable/supply skips are not seen), the four `*Display_Init` (no display proc alive; an inverted end test leaves the proc), `sub_8015434` (phase proc five frames after the tick in the red, green and blue start). The existing `SwitchPhases`, `BmMain_ChangePhase`, `_StartPhase` (red branch new) and `_UpdateTraps` (green branch new) now match all three switches and the f2766 trap proc.
Transcribed, not verified: `TickActiveFactionTurn` (no unit has a duration: the decrements, targets and torch redraw never run; the test shows only that nothing changes), `GetPhaseAbleUnitCount` (count is 0 exactly when there is no banner; no excluded unit), `CountUnitsInState` (result only visible as the song id).
Stand-ins: the `Make*TargetList` functions and `sub_8026414` (empty list), the rest of each display script (ends the proc), the torch redraw, `PidStatsSubFavval08` hook (default none; `Fe8Boot` does not keep the statistics).
For EP-w: `Fe8Boot` now uses `PhaseStartEnd` (own `SelectTargets`; share the player phase list once the `Make*TargetList` exist), the four display scripts and the Tick; `cpPhase` is still the ends-at-once stand-in. EP-10 starts from `BmMain_UpdateTraps` hooks (`updateTraps`, `decayTraps`).

### EP-2a result (2026-11-13)

Test: `CpOrderDifferentialTest` (`aistate`, `blue`, `red`, `gplayst` watches; units and `gPlaySt` of f1611 fed to `gProcScr_CpPhase`). In `CpPhase.kt`: `phaseScript`, `orderScript`, `aiPhaseInit`, `cpOrderMain`, `cpOrderFuncBeginDecide`, `buildAiUnitList`, the priority functions and the sort. After one frame the list is 0x81, 0x82, 0x83, `unitIt` at the start, `orderState` 1, flags 1, `unk7E` 0xFF, special flags 7, and the tree is CpPhase > CpOrder > CpDecide (f1612); after the decision, `orderState` 2 and flags 0 (f2744, f2746).
Ported: `AiPhaseInit` (the resets of `orderState`, `cmdResult`, `unk84` are not seen: the capture holds those values already), `CpOrderMain`, `CpOrderFunc_BeginDecide`, `BuildAiUnitList` (no unit is skipped by status or state; the 0 terminator is not seen).
Transcribed, not verified: `GetUnitAiPriority` (all three enemies 25: Mov 5 + melee 20), `GetUnitBattleAiPriority`, `SortAiUnitList` (equal priorities: only the stability shows, `>` to `>=` fails; the direction is not seen).
Stand-ins: `gProcScr_CpDecide` (ends at once, until EP-3a), `UpdateAllPhaseHealingAIStatus`/`SetupUnitInventoryAIFlags` hooks (EP-2b; `dangerMapFilled` 1 at f1612 comes from there).
Wired: `Fe8Boot` uses `CpPhase.phaseScript` as `cpPhase`; with the stand-in decide the phase still ends and the next player phase starts. EP-w: share `gAiState` with CpDecide, set `hooks.startCpDecide`.

### EP-2b result (2026-11-13)

Test: `CpAiFlagsDifferentialTest` (`aistate`, `blue`, `red`, `gplayst` watches and `gBmMapUnit` of the dumps f1520/f1610; units and `gPlaySt` of the step before fed, `aiFlags`/`ai_counter` of every watched unit and `bestBlueMov` compared after). New class `CpUtility` (game/bm); `UnitTable` got `setAiFlags`, `aiConfig`, `aiCounter`, `setAiCounter`; `AiState.bestBlueMov` (0x85). f1526 runs through `berserkPhaseScript` (faction 0: Seth, HP 13 of 30, enters healing; both blue units get ally count 1; `bestBlueMov` 0 -> 8), f1612 through `phaseScript` (faction 0x80; nothing changes), f2018/f2723 call the healing refresh directly (the dead red units are no longer valid and keep their flags). `dangerMapFilled` is not touched here (AiInitDangerMap, EP-3b).
Ported: `SetupUnitInventoryAIFlags` (Mov, best Mov, the unusable-item skip: a Vulnerary adds no flag; no magic item or staff in the capture), `SaveNumberOfAlliedUnitsIn0To8Range` (the skip of a non-allied unit is not seen), `UpdateAllPhaseHealingAIStatus` (blue at f1526, red at f1612/f2018/f2723), `AiUpdateGetUnitIsHealing` (enter and no-enter seen; the branch of a unit already healing is not).
Transcribed, not verified: `SetupUnitStatusStaffAIFlags` (only the early return for a weapon runs), `SetupUnitHealStaffAIFlags` (only the no-flag path: range 1 weapons, no use effect on a usable item).
Stand-ins: none left of the two hooks (removed). `CpPhase` skips the setup when built without `BmMap`/`EntityBmMaps`/`UnitLoader` (the phase-change test does).
Wired: `Fe8Boot` passes `battleBmMap` and `battleEntityMaps` to `CpPhase`, so the setup reads the live `gBmMapUnit` of the port.

### EP-3a result (2026-11-13)

Test: `CpDecideDifferentialTest` (`aistate`, `aidecision`, `red`, `blue`, `gplayst`, `actiondata` watches; units and `gPlaySt` of f1611 fed to `gProcScr_CpPhase`, which starts the real `gProcScr_CpDecide`). New `CpDecide` (game/bm; `AiDecision` with the C layout, `AiState` got `decideState`, `dangerMapFilled`, `unk7C`, `combatWeightTableId`). A frame snapshot of the capture lies inside `CpDecide_Main` (the GBA needs more than a frame per unit), so the port is compared at the call of a stand-in hook with the frame that shows the same moment: Red 0 `AiUpdateNoMoveFlag` = f1612, Soldier 1 = f1613 (Red 0 marked `US_HAS_MOVED_AI`, `unitIt` 5, `decideState` 0), Soldier 1 perform start = f1614 (decision attack by 0x82, `decideState` 2, `unitIt` 6), Soldier 2 `AiUpdateNoMoveFlag` = f2037 (`decideState` 0, old decision still set), Soldier 2 script A = f2038 (decision zero, `decideState` 2), perform start = f2039 (attack by 0x83, `unitIt` 7), mid-perform = f2741, proc end = f2742 (`decideState` 0). Compared each time: `unitIt`, `orderState`, `decideState`, `flags`, `unk7C`, `combatWeightTableId`, all 11 `gAiDecision` bytes, the `US_HAS_MOVED_AI` bit of the three Red units. The two `CpDecide_Suspend` calls compare `gActionData.suspendPointType` with f2035 and f2740 (2). The call order (update, script A, script B and special items for Red 0 only, perform, suspend) is the EP-0 inventory.
Ported: `CpDecide_Main` (loop, marking, both perform starts, end; the dead/unselectable skip, a same-tile move with an action and the `combatWeightTableId` value are not seen: every `ai_config` is 0), `CpDecide_Suspend` (red case; the blue `SUSPEND_POINT_BSKPHASE` case is not seen), `AiClearDecision`, `AiSetDecision`, `AiDecideMain`. Mutations seen to fail: no `US_HAS_MOVED_AI` mark, `false` for `!actionPerformed` (the call order shows a perform for Red 0), `targetId` not cleared (f2038 byte 6), target/slot swapped in `AiSetDecision` (f1614 byte 6), `AiDecideMain` ignoring `actionPerformed` (f1614 `decideState` 4), `SUSPEND_POINT_CPPHASE` 3 (f2035), no `decideState` reset (f1613).
What is and is not verified of the decision content: the EP-4 stand-in `AiTryExecScriptA` calls `AiSetDecision` with values read from the captured bytes of f1614 and f2039, so the choice (attack Eirika from (5,5)) is NOT verified here (EP-4, EP-5). Verified for `AiSetDecision`: the packing of the seven arguments into the bytes, with the unit id from the active unit. In the capture `xMove` = `yMove` = 5 and `xTarget`, `yTarget`, `itemSlot` are 0, so a swap of the two move coordinates or of the target tile with the slot is not seen.
Transcribed, not verified: `DecideHealOrEscape` (entered for all three, only the not-healing, no `AI_UNIT_FLAG_3` path; the branches are stand-ins), `DecideScriptA`/`B` (only a try that succeeds at once; gorgon egg, berserk, ballista and the fallback scripts never run).
Stand-ins (hooks of `CpDecide`, default null = does nothing): `AiUpdateNoMoveFlag`, `AiInitDangerMap`, `DecideSpecialItems` (EP-3b), `AiTryExecScriptA`/`B` (default: succeeds, no decision), `AiExecFallbackScriptA`/`B` (EP-4), `Proc_StartBlocking(gProcScr_CpPerform)` (EP-8), `WriteSuspendSave` (EP-w), `RenderBmMap`, `RefreshUnitSprites` (EP-w, wired in `Fe8Boot`), heal and escape branches, `AiDoBerserkAction`/`Move`, `GetRiddenBallistaAt` (new row EP-12). `dangerMapFilled` (f1612..: 1) is `AiFillDangerMap` (EP-3b) and not compared; the test hook sets it.
Wired: `CpPhase` owns `decide` (`CpDecide`) and takes the shared `ActiveUnit`; `Hooks.startCpDecide` and the ends-at-once script are gone. `Fe8Boot` sets the render, sprite-refresh and suspend-save hooks; with the scripts as stand-ins every enemy is marked moved, no perform starts, and the phase still ends and turn 2 starts (`Fe8BootTest`, `PhaseChangeDifferentialTest` green). `CpOrderDifferentialTest` now reads the f1612 state at the first `AiUpdateNoMoveFlag` call (the decision loop runs in the first frame). EP-w: `CpDecide_Suspend` writes the suspend save only through the hook; check the SRAM write and `main.suspendPointType` on a device when decisions exist.

### EP-3b result (2026-11-13)

Test: `CpDecideDifferentialTest` extended (same snapshot points as EP-3a; the `RefreshUnitSprites` call stands for the start of a unit, right before `AiUpdateNoMoveFlag`). New in `CpDecide`: `aiUpdateNoMoveFlag`, `aiInitDangerMap`/`aiFillDangerMap`, `decideSpecialItems`, `aiTryDoSpecialItems`, `getSpecialItemFuncIndex`, `sub8040C5C`; the three EP-3b hooks are gone. Compared in addition: `dangerMapFilled` at f1612 (Red 0's script A call), f1613, f1614, f2037, f2038; `gBmMapOther` (15 x 10, from the full dumps) against r01612 (Red 0's map: 43 tiles of 14), r01613 (still Red 0's), r01614 (Soldier 1's: 35 tiles of 23 = 14 + 9, 8 of 14), r02038 (Soldier 2's: 36 tiles of 23); `flags` per snapshot.
Ported: `AiUpdateNoMoveFlag` (the clear case; no captured unit has the stay bit), `AiInitDangerMap` (clear and refill per unit; the already-filled return is not seen), `AiFillDangerMap` (unit filters, weapon choice with the carried-over `item`/`might`, the `(power + might) >> 1` damage and the per-tile sum; fed by the EP-7 hooks, see below). Mutations seen to fail: `AiUpdateNoMoveFlag` always sets stay (`flags` f1613 expected 1 was 3), no clear in `AiInitDangerMap` (map (0, 0) f1614 expected 14 was 28), no `>> 1` (map (0, 0) f1612 expected 14 was 28), no allied filter (f1612 expected 14 was 42; the range hook returns all tiles for non-player units).
Transcribed, not verified: `DecideSpecialItems` and `AiTryDoSpecialItems` (run once, for Red 0, and change nothing; skipping the call, or returning at the start of the latter, keeps the test green); `GetSpecialItemFuncIndex` and `sub_8040C5C` (only the -1 and non-rogue returns run; a wrong result would only trip the test's "not in the capture" check from the EP-0 trace, not a captured value).
Stand-ins: `AiCouldReachByBirdsEyeDistance` and `FillMovementAndRangeMapForItem` (EP-7) are fed from the dump (which player unit is in reach; range = the tiles of the captured map), so the reach and the movement/attack range are NOT verified here; the first player unit is assumed to be the one adding 14 (the sum matches, so the assumption holds). The special item functions and the rogue part of `sub_8040C5C` (EP-12, never entered). In `Fe8Boot` the EP-7 hooks are null, so the danger map stays empty.

### EP-4 result (2026-11-13)

Test: `CpDecideDifferentialTest` extended (same snapshot points as EP-3a/3b; new `rng` watch). The real interpreter (`CpScript`, game/bm, set into the `CpDecide` hooks by `CpPhase`) runs on the unit bytes of f1611 and the ROM script tables (`gpAi1Table`/`gpAi2Table` at 0x085A91E4/0x085A91D8, read at run time). Compared at every snapshot: `ai1`, `ai_a_pc`, `ai2`, `ai_b_pc` of the three Red units (Red 0 0 -> 1 and 0 -> 1 at f1613; Soldier 1 a-pc 0 -> 1 at f1614; Soldier 2 at f2039; `ai2` never runs), `unk7E` (0xFF), `gAiState.cmd_result` (all 0: no written command sets it), and `gRNState` at the call of the `AiAttemptOffensiveAction` stand-in (one `NextRN_N(100)` draw of command 05: f1614 for Soldier 1, f2038 for Soldier 2; Soldier 2 starts from the captured f2037 state because Soldier 1's battle, EP-9, draws too). `UnitTable` got `ai1`, `aiAPc`, `setAiAPc`, `ai2`, `aiBPc`, `setAiBPc`.
Ported: `AiTryExecScriptA`, `AiTryExecScriptB` (table, entry at pc 0, result; the 0x10 stride is not seen: every try starts at pc 0), `AiScript_Exec` (dispatch of 05, 06, 0E; `unk7E` value; the fallback replacement is not seen), `AiScriptCmd_05_DoStandardAction` (the draw, the no-list branch, the advance; a failed chance roll and the ignore list are not seen), `AiScriptCmd_06_DoNothing`, `AiScriptCmd_0E_DoNothing`.
Mutations seen to fail: no draw in command 05 (`gRNState` word 0 f1614 expected 53780 was 62625), command 06 not advancing (`ai_a_pc` of 81 f1613 expected 1 was 0), command 0E not advancing (`ai_b_pc` of 81 f1613 expected 1 was 0), advance on the wrong counter (`ai_a_pc` of 81 f1613), script B read from the `ai1` table (`ai_b_pc` of 81 f1613), `AiTryExecScriptA` returning false (`gAiDecision` byte 0 f2038 expected 0 was 1), `AiIsUnitEnemy` always false (captured target 2 of 82 is an enemy, f1614), staff try result inverted and chance roll never passing (`gAiDecision` byte 0 f2037 expected 1 was 0), the ignore-list branch always taken (same), `unk7E` set to 0 (f1613 expected 255 was 0). Survived (so not verified): `AiIsUnitEnemy` always true, the 0x10 stride, leaving the `unk7E` write out.
Transcribed: `AiIsUnitEnemy` (demoted: only the captured target is tested, and "always true" survives).
Stand-ins: `AiAttemptOffensiveAction` is fed from the capture (the decision bytes of f1614 and f2039 go into `AiSetDecision`), so the decision content (attack Eirika from (5, 5)) is NOT verified here: EP-5. `AiTryDoStaff` (null hook: no staff; EP-5). Every other command (00..04, 07..0D, 0F..1B), command 05 with an ignore list and its two list predicates, `AiExecFallbackScriptA/B`: EP-12 (`CpScript.Hooks.unportedCommand`, the `CpDecide` fallback hooks); the test fails if one is entered.
Wired: `CpPhase` owns `script` and takes the shared `RngService` (`Fe8Boot` passes the game's); `tryExecScriptA/B` are no longer null. In `Fe8Boot` the EP-5 hook is null, so every attack decision is empty, the enemy phase still ends and turn 2 starts (`Fe8BootTest`, `PhaseChangeDifferentialTest` green). A unit whose script reaches an unwritten command stays on it (the try ends, no decision).

### EP-5 result (2026-11-13)

Test: `CpDecideDifferentialTest` extended (same snapshot points). `AiAttemptOffensiveAction` now runs for real in `CpBattle` (game/bm, built by `CpPhase` when it has the map objects, wired into the `CpScript` hooks), so `gAiDecision` (target 2, move (5, 5), slot 0, action 1) at f1614 and f2039 comes from the port. Compared at the `tracecall aicombatscore` entry dumps (`call_aicombatscore_{1,2}_entry`): the `AiCombatSimulationSt` the port builds (xMove, yMove, targetId, itemSlot; padding bytes and the stale score are not compared), the whole `gBmMapRange` (the reversed range of Eirika), `gBattleActor`, `gBattleTarget`, `gBattleStats` and `gBattleHitArray` from the real `BattleGenerateSimulation` (MC-9: first non-own-tile position call, now seen: (5, 5)), and `gRNState`. The simulation starts from the `gBattle*` structs of the dump before it (the unused bytes are the previous battle's), the blue units of f1613 / f2037 (the phase start wrote their `ai_counter`) and the actor's `pMapSpriteHandle` of that frame (reallocated after Soldier 1's death).
Ported: `AiAttemptOffensiveAction` (the weapon and unit loops, the decision; not seen: `AI_FLAG_STAY` branch body, a second weapon, a tie, ballista, steal, magic unit), `AiFillReversedAttackRangeMap`, `AiSimulateBestBattleAgainstTarget` (reach and range filters, chosen tile; not seen: the other-unit filter, a tie, a choice between tiles), `AiSimulateBattleAgainstTargetAtPosition`, `AiIsBadFight`, `AiTryDoStaff` (result for a unit without a staff; not seen: silenced, a staff).
Transcribed: `AiGetCombatPositionScore` (not verified: one candidate tile per target, so only "not 0" shows; the sign of the movement and danger terms survive mutation).
Mutations seen to fail (first assertion): `AiFillReversedAttackRangeMap` min range - 2 (`gBmMapRange (4, 5) call_aicombatscore_1_entry` expected 0 was 1); no movement filter (`st.yMove at call_aicombatscore_1_entry` expected 5 was 4); no range filter (`st.xMove ...` expected 5 was 8); `yMove` never stored (`st.yMove` expected 5 was 0); battle simulated at `yMove + 1` (`battle unit 203a4ec +0x11 at call_aicombatscore_1_entry` expected 5 was 6); `AiIsBadFight` true (`gAiDecision byte 0 Soldier 2 before its decision (f2037)` expected 1 was 0); target id 1 (`st.targetId at call_aicombatscore_1_entry` expected 2 was 1); decision slot + 1 (`gAiDecision byte 7 Soldier 1 decided (f1614)` expected 0 was 1); slot of the simulation + 1 (`st.itemSlot at call_aicombatscore_1_entry` expected 0 was 1); `AiTryDoStaff` returns true (f2037 decision byte 0, as above); no enemy test (`st.xMove` expected 5 was 10: Red 0 gets a second score call); score comparison inverted, no decision set, the stay test inverted (f2037 decision byte 0). Survived (so not verified): the sign of the movement and danger terms of the position score, the other-unit filter.
Stand-ins: `AiComputeCombatScore` and its 11 components (EP-6a/6b) are fed from the exit dumps (score 6); the position components are 0; `GenerateUnitMovementMap` (the entry dump's `gBmMapMovement`) and `AiReachesByBirdsEyeDistance` (true for all: Seth has no tile) are EP-7 stand-ins, so the movement costs, the reach and the movement-limited candidate set are NOT verified; ballista, steal, magic seal, `TryRemoveUnitFromBallista` and the staff functions are EP-12 stand-ins (`CpBattle.Hooks`); `AiAttemptCombatWithinMovement`, `AiFloodMovementAndRange` and the ballista simulation are not written.
Wired: `CpPhase.battle` (null without loader and maps), `Fe8Boot` sets its simulation hook to the real `BattleGenerate`; the EP-7 hooks are null there, so no enemy is in reach, `AiAttemptOffensiveAction` decides nothing and the enemy phase ends as before (`Fe8BootTest`, `PhaseChangeDifferentialTest` green).

### EP-6a result (2026-11-13)

Test: `CpDecideDifferentialTest` extended. `AiComputeCombatScore` now runs for real in `CpBattle` (the `computeCombatScore` stand-in is gone; the test no longer reads the score from a dump). The inputs of the score call (state after the simulation) are compared at the `aicombatscore` entry dumps as in EP-5; at the perform start the best attack (`CpBattle.bestResult`, `finalResult`) is compared with the exit dumps' `AiCombatSimulationSt`: xMove, yMove, targetId, itemSlot and score (6 at both). The C writes only `score` in this function.
Reading the score: both totals are below 0, so the C returns the backup = the damage dealt component (3 x coefficient 2 = 6); `score * 40` is never taken. The values by hand (coefficient row 0, from the entry dumps): damage dealt 6 / 6, opponent low HP 13 / 13 (Eirika at 7 HP after the simulated battle), friend zone 0 / 0, target class 0 / 0 (the rank is 9 for every class, one past the 9-entry bonus list: the C reads the padding byte), turn 1, damage taken 7 / 10, danger 2 / 2, own low HP 12 / 20: totals -1 and -12.
Ported: `AiComputeCombatScore` (the backup, the clamp to 0, the coefficient row at `gAiCombatScoreCoefficientTable` read from the ROM; not seen: a positive total, `score * 40`, a row other than 0), `AiGetDamageDealtCombatScoreComponent` (the value 6 via the backup; not seen: a kill, the cap, negative damage).
Transcribed: `AiGetOpponentLowHpScoreComponent` (demoted: it shows only through a total below 0 that includes the hand-computed EP-6b inputs), `AiGetFriendZoneCombatScoreComponent` and `AiGetTargetClassCombatScoreComponent` (0 in both calls). Mutations that turn the ally test or the rank offset change the score (below), but the zone's tile scores, its cap and the class bonus values show in no captured value.
Mutations seen to fail (all `st.score at call_aicombatscore_1_exit`, expected 6): damage divisor 10 instead of 100 (was 1320), damage coefficient 1 (was 3), attack and defense swapped (was 0), kill test on 7 HP instead of 0 (was 1720); opponent low HP `20 + hp` (was 520) and its coefficient taken from the damage dealt column (was 480); no clamp to 0 (was -40); the backup replaced by 0 (was 0); damage taken added instead of subtracted (was 520). Not caught: friend zone base score 5 instead of 4, turn subtracted (the totals stay below 0). Caught but kept Transcribed (they only show the 0 contribution): friend zone ally test inverted (was 160), class bonus read at rank 0 instead of 9 (was 360).
Stand-ins: `AiGetTurnCombatScoreComponent`, `AiGetDamageTakenScoreComponent`, `AiGetDangerScoreComponent`, `AiGetLowHpScoreComponent` (EP-6b) are fed the values above (worked out by hand from the entry dumps with the decomp formulas, not captured), so the score check does NOT verify them or the signs of the turn term; `AiGetClassRank` (EP-7) is fed 9. The three position components stay the EP-5 stand-in (0).
Wired: `Fe8Boot` sets `CpBattle.battleUnits`; the score hooks are null there (nothing is in reach, so nothing is scored).

### EP-7 result (2026-11-14)

Test: `CpDecideDifferentialTest` extended; the EP-7 hooks of `CpDecide` (`couldReachByBirdsEyeDistance`, `fillMovementAndRangeMapForItem`) and `CpBattle` (`generateUnitMovementMap`, `reachesByBirdsEyeDistance`, `getClassRank`) are gone, the test feeds none of them. `CpUtility` (now with a `MovementMap`) holds the four cp_utility.c functions; `CpBattle` takes the `CpUtility`. New comparisons of `gBmMapMovement` and `gBmMapRange` with the dumps: f1612 (at Red 0's script A), f1614 (at Soldier 1's perform start), f2038 (at Soldier 2's perform start: the dump is taken after its attack search) and both `aicombatscore` entry dumps (movement added to the existing range comparison). Not compared: f1613 (the capture's range map has a later change there). Soldier 1's battle (EP-9) is not run, so at its perform start the test copies its (cleared) struct and the blue units from f2037; with it the f2038 danger map now matches in full.
Ported: `GenerateUnitMovementMap` (bmidoten.c; a wrapper over `generateUnitMovementMapExt` with `UNIT_MOV`), `FillMovementAndRangeMapForItem` (movement flood, range clear, ring around each reachable tile), `AiGetClassRank` (reads `gAiClassRankLists` from the ROM; only the end-of-list result 9 is seen because every FE8 list is empty, so the found branch is dead data; ported because dropping the count changes the captured score). Movement and range maps equal the dumps at 3 frames and 2 entry dumps.
Transcribed: `AiReachesByBirdsEyeDistance` (not verified: it decides only clear cases; a threshold 4 tiles too wide or a distance of x only keeps the test green), `AiCouldReachByBirdsEyeDistance` (see the finding below).
EP-7b resolved (2026-11-15): `ram_trace_ai_danger_calls` captures the original callback boundaries.
Red 0's `AiFillDangerMap` enters f1612 and returns f1613; its completed map includes the second
player unit's contribution on all 35 tiles. The f1613 frame dump is already inside Soldier 1's
next fill, so it is not Red 0's completed map. `CpDecideDifferentialTest` now compares all 150 tiles
against `call_danger_1_exit`, with no tile exclusion. The trace's input prefix and f1612..1614
EWRAM/IWRAM match the existing EP-cap1 capture. No production AI code or port credits changed.

### EP-29a capture limits (2026-11-15)

`ram_trace_battle_item_helpers` records 27 original calls over f4181..4274: three inventory misses,
ten item-effectiveness false results, ten unit-effectiveness false results and four covered-range
true results. `BattleItemHelpersDifferentialTest` compares their captured arguments/returns and
checks input and full RAM equality at f4180/f4274. All four helpers remain Transcribed and have no
verification tags: constant-return implementations match these cases. EP-29cap needs nondefault
original calls before verification credit; EP-29a stays partial.

Mutations seen to fail (first assertion): `GenerateUnitMovementMap` budget + 1 (`gBmMapOther (1, 1) Red 0 in script A (f1612)` expected 14 was 23); fill skips the origin tile (`gBmMapRange (4, 4) Red 0 in script A (r01612)` expected 3 was 2); minimum range - 2 (`gBmMapRange (3, 2) ... (r01612)` expected 1 was 2); range map not cleared (`gBmMapOther (0, 0) ... (f1612)` expected 14 was 23); `AiGetClassRank` without the count (`st.score at call_aicombatscore_1_exit` expected 6 was 360); `AiCouldReach` without the other unit's movement or always false (`gBmMapOther (0, 0) ... (f1612)` expected 14 was 0). Survived (so Transcribed): `AiReaches` threshold + 4, `AiReaches` x distance only.
Stand-ins: the EP-6b score components and position components (unchanged); the EP-12 hooks; the perform proc (EP-8).
Wired: `CpPhase` builds `CpBattle` with its `CpUtility`, so `Fe8Boot` needs nothing more. In game, End turn on the Prologue now makes both Soldiers decide the attack on Eirika (move (5, 5), target 2, as in the capture); the perform proc is null, so nothing is performed, the phase still ends and turn 2 starts (checked by a scratch run; `Fe8BootTest`, `PhaseChangeDifferentialTest` green).

### EP-8 result (2026-11-15)

Test: `CpPerformDifferentialTest` (+ `CpPerformSim`, `CpPerformAllocationTest`): `CpDecide` runs for one unit with `aiDecideMainFunc` replaced by the captured `gAiDecision` of the decision frame (input, not ported: for the boss that is EP-7d, `AiScriptCmd_12_MoveTowardsEnemy`; the soldiers' decision is EP-3a..EP-7 and is compared by `CpDecideDifferentialTest`), then `gProcScr_CpPerform` and `gProcScr_AiTargetCursor` run for real and `CpDecide` blocks on the perform proc as in the C. Start state (units, `gPlaySt`, `gBmSt`, `gBmMapUnit` from the unit table) and the RNG after the decision's draw are the capture's. Windows: Soldier 1 f1614..1695, Soldier 2 f2039..2128 (`ram_trace_prologue_enemy_phase`), the boss f3804..3831 (`ram_trace_prologue_clear`, no cursor). Per frame compared: `gBmSt.camera`, the `CpPerform`, `ProcScr_Mu`, `gProcScr_AiTargetCursor` procs (existence, script position, MU `state`/`facing`/`step_sound_clock`/`move_clock_q4`/`moveConfig`/`x_q4`/`y_q4`/`cam_b`, `sMuConfig[0].pc`, cursor x, y, kind, timer), `ProcScr_CamMove` existence, `gAiDecision` move tile, `gActionData` subject, move tile, move count and type, the unit's state word, `sLastCoordMapCursorDrawn` and `sLastTimeMapCursorDrawn`. Engine frame k is compared with capture step decision frame + 1 + k: the decision step's dump is a snapshot inside the frame that decides and starts the walk (Soldier 1 and the boss show the new MU before its first loop, Soldier 2 after it), so it is not compared. Inputs from outside the port: the MU's sprite palette (sprite handle table) and the game clock (`gGameClock` of the dump, `sLastTimeMapCursorDrawn` equals it at the put).
Ported (four, each in the three windows or the two with a cursor): `CpPerform_BeginUnitMovement` (reuses `PlayerPhase.unitBeginAction` (Ported) and `unitApplyWorkingMovementScript` (Transcribed, made public), `MovementMap.generateUnitMovementMap`, `generateBestMovementScript` (Transcribed), `Mu.setAutoMuMoveScript`; not seen: sprite hide, default facing (EP-8c), trap and hidden-unit cases), `CpPerform_MoveCameraOntoTarget` (attack: cursor at the target's tile; no action: none; the other actions and the ballista start are not seen), `StartAiTargetCursor`, `AiTargetCursor_Main` (not seen: A or START held).
Transcribed: `CpPerform_UpdateMapMusic` (not verified: the music restart shows in no captured value); `CpPerform_MoveCameraOntoUnit` (parent review: only its visible flag is seen; the camera call is not, since the Prologue map fits the screen and `gBmSt.camera` is (0, 0) in every window, so a camera move onto another tile survives).
Mutations seen to fail (first assertion): `isUnitVisible` not set (`gProcScr_CpPerform script position f1615` expected 5 was 8); MU gets no script (same message); `decision.xMove = action.xMove + 1` (`gAiDecision.xMove f1615` expected 5 was 6); no movement map (`MU state f1615` expected 2 was 1); no `UnitBeginAction` (`gActionData.subjectIndex f1615` expected 130 was 0); cursor pixel x * 8 (`AiTargetCursor x f1648` expected 64 was 32); cursor timer limit 44 (`gProcScr_AiTargetCursor exists f1694` expected true was false); cursor kind 0 (`AiTargetCursor kind f1648` expected 2 was 0); combat target = move tile (`AiTargetCursor x f1648` expected 64 was 80); a move-only action starts a cursor (`gProcScr_AiTargetCursor exists f3830` expected false was true); script without `PROC_WHILE(MuExistsActive)` (`gProcScr_CpPerform script position f1615` expected 5 was 7); timer starts at 1 (`AiTargetCursor timer f1648` expected 1 was 2); cursor put at (x, x) (`sLastCoordMapCursorDrawn.y f1648` expected 80 was 64). Survived (stay unverified in the KDoc): camera call of `MoveCameraOntoUnit` and of `MoveCameraOntoTarget` removed or given another tile, A/START check removed, MU started for an invisible walk, `HideUnitSprite` removed, the trapped return removed, `SetAutoMuDefaultFacing` removed. Each mutation restored byte-identical (`cmp`), test green.
Stand-ins (named hooks of `CpPerform`): `CpPerform_PerformAction`/`CpPerform_WaitAction`/`CpPerform_Cleanup` (EP-9, with `AiRefreshMap`), `CpPerform_EquipBest` (EP-9b), `RideBallista` (EP-12); with no hook the script goes on at once and ends. `Fe8Boot` sets `cleanup` to a stand-in (EP-9): `EndAllMus`, clear `US_HIDDEN`, `ShowUnitSprite`.
Wired: `Fe8Boot` builds `CpPerform` (`MovementMap`, `CamMove`, `Mu`, `PlayerPhase`, keys, rng, `MapCursor`, sprite handles, map music) and sets `CpDecide.Hooks.startCpPerform`. In game: End turn on the Prologue now shows both Soldiers (in order) walk to (5, 5) next to Eirika, the target cursor on her for 47 frames each, then each Soldier snaps back to its old tile (the move is applied by EP-9's `ApplyUnitAction`; nothing attacks). The phase still ends, turn 2 starts (`PlayerPhaseBootTest`, `Fe8BootTest`, `PhaseChangeDifferentialTest` green; a scratch run showed no MU left over and `US_HIDDEN` cleared at turn 2).

### EP-9 result (2026-11-15)

Test: `CpPerformActionDifferentialTest` on the EP-8 set-up (`CpPerformSim`: captured `gAiDecision` as input, `CpDecide` blocks on the real perform proc), now with the real combat code: `PlayerPhase.applyUnitAction` > `CombatAction.actionCombat` (MC-13), `BattleGenerate`, `BmBattle` (MC-16 death handling); the map battle animation (M6-H) is the placeholder of `CombatActionDifferentialTest` (locks the game, ends with the captured `ProcScr_MapAnimBattle`). Start state also from the capture: `gActionData` (stale `targetIndex` etc.), the battle structs; the RNG is the capture's state before the walk's tie-break draw (Soldier 2: f2038; the decision's own draw is in it). Windows: Soldier 1 f1696..2013, Soldier 2 f2129..2402 (`ram_trace_prologue_enemy_phase`), the boss f3831..3832 (`ram_trace_prologue_clear`). Per step compared: `CpPerform` and `sProcScr_CombatAction` existence and script position, `gActionData` (subject, target, move tile, move count, type, item slot), `gBattleActor`/`gBattleTarget` (all bytes except the AI script positions 0x42..0x45, which the fed decision does not advance), `gBattleStats`, the RNG, the unit table (both blue units and the three Soldiers, every byte but the map sprite handle and the AI script positions; the boss at f3832 without its inventory). After the proc ended the same unit table, `gActionData` and `gBmSt` cursor/target/display fields equal the capture at f2018, f2723 and f3832. Alignment: the capture's step f1696 / f2129 is a snapshot inside the frame that runs `BattleGenerateReal` (the original's `CpPerform@9` with the combat proc just started, the repeat not yet run), so from that frame on engine frame f is compared with step f + 1. The post-action event of the original (`RunPotentialWaitEvents`, f2014..2017 and the dialogue f2403..2723, EP-11) is not run in the test, so the end of the proc is compared by state, not by frame.
Ported: `CpPerform_PerformAction` (action 0 and 1; the other 14 fail loudly), `AiStartCombatAction` (the trap-target branch `GetTrapAt` fails loudly), `CpPerform_WaitAction`, `AiDummyAction`, `CpPerform_Cleanup`, `AiRefreshMap`. Reused, not duplicated: `ApplyUnitAction`, `ActionCombat`, `EquipUnitItemSlot` (`ItemInfo`), `MoveActiveUnit` (made public; deviation: no statistics call for a unit that was killed, which has no character, the C reads address 4), `SetCursorMapPosition`, `UpdateAllPhaseHealingAIStatus`. Not seen (KDocs): the `Proc_Goto` of `CpPerform_Cleanup` (with `CpPerform_EquipBest` still a stand-in the proc ends at the same frame), the healing update, `EndAllMus`, `ShowUnitSprite`, the entity-map refreshes and the position write of `CpPerform_WaitAction`.
Mutations seen to fail (first assertion): no combat start (`sProcScr_CombatAction exists f1697` expected true was false); `itemSlotIndex` 1 (`gActionData.itemSlotIndex f1697` expected 0 was 1); attacker not put on its move tile (`gBattleTarget+9 f1697` expected 32 was 1); no `MoveActiveUnit` in `AiRefreshMap` (`red unit 0 byte 0xc f3832` expected 2 was 1; `red unit 1 byte 0xc (end) f2018` expected 3 was 1); cursor not set (`gBmSt.playerCursor.x (end) f2018` expected 5 was 10); no `AiRefreshMap` in the cleanup (`gBmSt.playerCursor.x f3832` expected 9 was 10); the wait never ends (`the perform proc ends` expected null). Survived (stay unverified in the KDoc): the position write of `CpPerform_WaitAction` removed, `Proc_Goto` always taken, `EndAllMus`/`ShowUnitSprite` removed. Each mutation restored byte-identical (`cmp`), test green.
Stand-ins (hooks of `CpPerform`): `CpPerform_EquipBest` (EP-9b), `NewBMXFADE(1)` (EP-9c), `RenderBmMapOnBg2`, `RideBallista` (EP-12). The `Fe8Boot` cleanup stand-in of EP-8 is gone.
Wired: `Fe8Boot` passes `CpPerform` the map, entity maps, item rules, `CpUtility` (`CpPhase.utility`, now internal), render, sprite handles. In game, End turn on the Prologue: the enemy banner, then each Soldier walks to the tile next to Eirika with the target cursor on her, attacks (the map battle runs through `sProcScr_CombatAction` with no map animation, M6-H: it takes two frames), and Eirika takes 9 damage (HP 16 to 7, from the Soldier that hits) while each Soldier dies to her counter (its struct is cleared, no death fade or exp bar yet); then turn 2 starts (a scratch run: turn 2 at the same frame the phase ends, no proc left over, `PlayerPhaseBootTest` green). The dialogue that follows the second Soldier in the original (EP-11) does not show.

### EP-9b result (2026-11-15)

Test: `CpPerformActionDifferentialTest` (boss test) extended; `CpPerformSim` builds `CpEquip` (`CpPerform` now takes it, the `equipBest` hook is gone). Compared: the boss's whole unit struct (inventory slots and equipped weapon included; the exclusion EP-9 added at f3832 is removed) at f3831, f3832 and f3833, `gBmMapMovement` at f3831 and f3832 (at f3832 it is the flood of the last player unit that can reach the boss's tile, left behind by `AiEquipGetDanger`), `gBmMapOther` at f3832 (cleared: no player unit reaches the tile, total danger 0). Input: the decision's danger map (EP-3b, not built in this sim) is the capture's f3831 dump, which the equip's query then clears.
Ported: `AiCanEquip` (true branch only), `AiEquipGetFlags` (one weapon, no staff, worn-down flag or range flags not seen), `AiEquipGetDanger` (clear, floods, no non-zero danger seen), `AiIsWithinFlyingDistance` (the `>=` boundary not seen). Transcribed: `CpPerform_EquipBest` (x/y swap at its call survives), `AiEquipBestConsideringDanger`, `AiEquipBestMatch`, `sub_803EC18` (danger 0 and one item: never reach an equip). Note: the C `AiEquipGetFlags` has no return after the loop; the port returns true. `AiEquipGetDanger` uses `UnitLoader.getUnitEquippedWeapon` and `BmBattle.getUnitPower` (reused), `CpUtility.moves` for the flood.
`CpPerform_Cleanup`'s `Proc_Goto(proc, 1)` is now verified on both sides: the live boss runs the equip, the Soldiers killed in their attack skip it.
Mutations seen to fail (first assertion): no clear of `gBmMapOther` (`gBmMapOther f3832 (0, 0)` expected 0 was 14); cleanup always goes to label 1 (`gBmMapMovement f3832 (0, 0)` expected 8 was 255); `AiCanEquip` false (same message); within-distance always true (same); no movement flood (same); `AiEquipGetFlags` inverted empty test (same); `CpPerform_EquipBest` without the danger call (same); no allied check (same). Survived: within-distance `>=` to `>`, x and y swapped at the danger call. Each restored byte-identical, test green.
Stand-ins: none new. In game: no visible change (the equip changes no slot: the Soldiers die, a surviving unit with one weapon keeps it); wired through `Fe8Boot`.

### EP-9c result (2026-11-15)

Test: `BmxFadeDifferentialTest` on the `CpPerformSim` set-up of EP-9 (now with a `BgHardware`, `BmMapRender` and `BmxFade`; the display state before the fade, LCD buffer, palette and BG2, is loaded from capture step F0 - 1 as input, since the map battle and sprites change BLDCNT and the palette in the original, M6-H). The post-action event (EP-11) is not run, so the frame the fade starts in is the sync point: engine step k after it is compared with capture step F0 + k, k = 0..16. Windows: boss F0 = 3832 (`ram_trace_prologue_clear`), Soldier 1 F0 = 2018, Soldier 2 F0 = 2723 (`ram_trace_prologue_enemy_phase`). Per step: `sProcScr_BMXFADE` existence (through `DoesBMXFADEExist`), script position, `counter`, `game_lock`, `gBmSt.lock`, BLDCNT, EVA/EVB/EVY, BG2CNT, the whole `gBG2TilemapBuffer` and `gPaletteBuffer` (the palette never changes in the fade); at F0 also `gBmMapUnit`, `gBmMapFog`, `gBmMapHidden`. A fourth case compares the three pools after the decision's refresh and the perform proc's `UnitBeginAction` (the boss is off the map) with f3805 (the f3803 refresh itself shows only through them: both run in the first engine frame). `CpPerformAllocationTest` gains the 16 fade frames (zero bytes).
Ported: `NewBMXFADE`, `bmxfade_init`, `bmxfade_loop`, `Destruct6CBMXFADE`, `DoesBMXFADEExist` (new `BmxFade`, `sProcScr_BMXFADE` on tree 3), `RenderBmMapOnBg2` (BG2 buffer at F0..F0+15, BG2CNT 0x8000 tile offset until the end), `RefreshEntityBmMaps` (also the dumps of `EntityBmMapsDifferentialTest`, fog and no fog). Transcribed: `RefreshTorchlightsOnBmMap`, `RefreshMinesOnBmMap` (not verified: the Prologue has no trap, so the pool is empty in every capture); `SetAllUnitNotBackSprite` stays Transcribed (no unit has `US_BIT8`).
Mutations seen to fail (first assertion, boss window; the other two windows fail the same way at their frames): `NewBMXFADE` without `LockGame` (`gBmSt.lock f3832` expected 1 was 0); counter starts at 15 (`BMXFADE counter f3832` expected 14 was 13); blend target B on BG2 (`BLDCNT f3832` expected 14404 was 13380); coefficients swapped (`EVA f3832` expected 15 was 1); BG2 not cleared at the end (`gBG2TilemapBuffer[0] f3847` expected 0 was 32771); BG2 tile offset not reset (`BG2CNT f3847` expected 3586 was 3594); no `SetDefaultColorEffects` (`BLDCNT f3847` expected 14340 was 14404); no `UnlockGame` (`gBmSt.lock f3848` expected 0 was 1); `DoesBMXFADEExist` false (`the fade starts` expected true was false); no `PROC_END_IF_DUPLICATE` (`sProcScr_BMXFADE script position f3832` expected 6 was 5); no `Proc_Break` (`sProcScr_BMXFADE exists f3847` expected true was false); `RenderBmMapOnBg2` draws nothing (`gBG2TilemapBuffer[0] f3832` expected 32771 was 0); BG2 offset 0 (`BG2CNT f3832` expected 3594 was 3586); `RefreshEntityBmMaps` without the unit pass (`gBmMapUnit (4, 4) f3832` expected 1 was 0), fog cleared with 0 (`gBmMapFog (0, 0) f3832` expected 1 was 0), hidden or unit map not cleared (`hidden (0,-2) at ram_trace_first_phase/f19430` expected 0 was 170; `unit (0,-2) ...` the same). Survived (stay unverified in the KDoc): `InitBmBgLayers` removed (the priorities are already set), `BG_EnableSyncByMask(4)` removed (the sync mask is not compared). Each mutation restored byte-identical (`cmp`), test green.
Stand-ins: the trap pool `EntityBmMaps.Traps` (default empty, EP-36 `GetTrap`); `DoesBMXFADEExist` callers still on their own stand-ins, none in a captured window of this task (see below).
Wired: `Fe8Boot` builds `BmxFade` and sets `CpPerform.hooks.newBmxFade` and `renderBmMapOnBg2`. In game, End turn on the Prologue: after each enemy's action (Soldier 1, Soldier 2) the game is locked for 17 frames (about 0.3 s) while BG2 (the map as before) is blended into BG3 (the map after); the map tiles are the same on both sides, so nothing visible changes except the short pause before the next unit. The phase still ends, turn 2 starts (`Fe8BootTest`, `PlayerPhaseBootTest`, `PhaseChangeDifferentialTest` green). Other callers of `NewBMXFADE`/`DoesBMXFADEExist` in the decomp: `NewBMXFADE`: bmunit.c:1173 (`redrawAfterTorch`, `PhaseStartEnd.Hooks`), playerphase.c:883 (`PlayerPhase.Hooks.newBmxFade`, fog), prep_sallycursor.c, trapfx.c, bmbattle.c:2021, fogmap.c; `DoesBMXFADEExist`: event.c:74 (`EventEngineHooks.fadeExists`, wired to the screen fade, not BMXFADE), playerphase.c (five waits, `PlayerPhase.Hooks.doesBmxFadeExist`, false), bmmind.c (`CombatAction.Hooks.doesBmxFadeExist`), player_interface.c (`PlayerInterface.Hooks`), trapfx.c, prep, worldmap, sio_bat, bmdebug. None is entered inside f2018..2033, f2723..2738 or f3832..3847 except the event engine (EP-11, f2014..2017 starts before the fade), so none is wired here; replace them with `BmxFade.doesBmxFadeExist` when their own task runs.

### EP-8c result (2026-11-15)

Test: `CpPerformDifferentialTest` (EP-8 set-up, unchanged) with `HaltMu` and `Mu_OnStateDoNothing` added to its three walk tests. The MU `state`, `facing`, `x_q4`, `y_q4`, `move_clock_q4`, `step_sound_clock` and the perform proc's script position are compared per step over f3804..3831 (boss, walk end f3829), f1614..1695 and f2039..2128 (Soldiers; walk ends f1647 and f2080). The f4131..4240 window (Seth's player-phase walk, `ram_trace_prologue_clear`, `HaltMu` f4179) has no harness (it needs the player phase from the turn-3 selection) and shows the same `HaltMu` and idle handler that `PlayerPhaseMoveDifferentialTest` already compares (Eirika, f1296..1314 and f1331..1365), so it was not built; say if it is wanted as a separate row.
Ported: `HaltMu` (now public in `Mu`: `EndMuMovement`, then `MU_STATE_INACTIVE`), `Mu_OnStateDoNothing` (explicit `onStateDoNothing`, used for the INACTIVE, DISPLAY_UI and DEATHFADE states). Transcribed: `EndMuMovement` (not verified: the function has no body), `SetAutoMuDefaultFacing` (new `Mu.setAutoMuDefaultFacing`; not verified: the facing is replaced by the move script's first command before any captured frame; `SetMuDefaultFacing` stays as it was). Not written: `GetMuImgBufById` (the port keeps each MU's VRAM graphics slot in `MuProc.gfxVram`; the C function only returns the buffer address, no captured value; it belongs to the MU sprite graphics, M6-H).
Mutations seen to fail (first assertion): `HaltMu` without the state change (`gProcScr_CpPerform script position f2130` expected 10 was 5; the same in the other windows, the proc keeps waiting for the MU); the idle states run the movement handler (`MU step_sound_clock f3829` expected 25 was 26). Survived (stay Transcribed): `SetAutoMuDefaultFacing` removed from `CpPerform_BeginUnitMovement`, and `SetMuDefaultFacing` with the wrong facing (3 instead of 2). Each mutation restored byte-identical (`cmp`), test green.
Stand-ins: none left in this task; the `CpPerform.Hooks.setAutoMuDefaultFacing` hook is gone (`CpPerform` calls `Mu.setAutoMuDefaultFacing` and `Fe8Boot` and `CpPerformSim` no longer set it). `PlayerPhase.Hooks.setAutoMuDefaultFacing` is unchanged.
Wired: `Fe8Boot` (the call is inside `CpPerform`). In game nothing visible changes: the walker's final facing is set by the move script, as before.

### EP-8b result (2026-11-15)

Test: `MuStepSeDifferentialTest` on the `CpPerformSim` walks (the sim now has `Mu` with the ROM, an `M4aSound` from `Fe8Sound.create` and `SpacialSe`): the boss f3805..3829 (`ram_trace_prologue_clear`), Soldier 1 f1615..1647 and Soldier 2 f2040..2080 (`ram_trace_prologue_enemy_phase`; the capture's step 2039 is a snapshot inside the frame that starts the walk, so engine frame f is step f + 1). Per step: the `ProcScr_MuStepSe` proc (existence, script position, `song1`, `song2`, `unk60`, `x1`, `x2`). In each frame `MuStepSe_PlaySeA` plays a song, the capture's IWRAM music player for it (`gSongTable` `ms`) holds the song's header and, on every track, the external pan the sim's player has after `PlaySeSpacial` (`Screen2Pan` of x 136 or 152; songs 150 and 151). The capture has sound state only as these IWRAM structures (the dump is per frame); the sim's players start from a clean `m4aSoundInit`, so only the header and `panX` are compared, not the sequencer state the driver ticks. The Seth walk f4131..4176 has no harness (it needs the player phase from the turn-3 selection, as in EP-8c) and was not built; the same procs are in `PlayerPhaseMoveDifferentialTest`'s walks (Eirika, f1296..1314) but that test does not compare them yet.
Ported: `MuStepSe_PlaySeA`, `MuStepSe_PlaySeB`, `PlayMuStepSe`, `StartPlayMuStepSe`, `PlaySeSpacial`, `Screen2Pan` (new `SpacialSe`), `m4aMPlayPanpotControl` (new `M4aSequencer.panpotControl`). `UpdateMuStepSounds` stays Transcribed (the Foot list is the only one seen) but now reads the sound scripts from the ROM and passes only non-zero entries with the MU's screen x, which fixes BUG-mustepse. Transcribed: `MuStepSe_Init` (not verified: the proc is new when it runs, so the cleared fields are 0 either way), `IsFirstPlaythrough` (not verified: only matters with A held in a walk; no capture has it). Not written: `IsGamePlayedThrough` (bmsave-lib.c, the save library; `MuHooks.isGamePlayedThrough` stays a stand-in, M6-E; no captured value depends on it).
Mutations seen to fail (first assertion): pan scaling off by one (`SE player 4 track 0 panX (song 150, x 136) f1615` expected 12 was 13); no `panX` write (the same, expected 12 was 0); wrong player (the same, was 0); `x1` off by one (`MuStepSe x1 f1615` expected 136 was 137); `PlaySeA` plays `song2` (`SE player 0 song header f1615` expected 139936204 was 136471472); `PlaySeB` always plays (`SE player 0 song header f1616`, same values); wrong place in the sound script (`ProcScr_MuStepSe exists f1615` expected true was false); zero entries queued (`MuStepSe song2 f1615` expected 0 was 2); no display position (`MuStepSe x1 f1615` expected 136 was 0); one yield fewer in the proc (`ProcScr_MuStepSe exists f1615`); no `PlayMuStepSe` in `SetMuMoveScript` (`script position f1615` expected 4 was 2); first slot never filled (`MuStepSe song1 f1615` expected 150 was 0). Survived (stay unverified in the KDoc): the left clamp of `Screen2Pan` (x < 0 is never seen), `m4aMPlayImmInit` in `PlaySeSpacial`, the clearing in `MuStepSe_Init`. The same failures show at the Soldier 2 frames (f2040). Each mutation restored byte-identical (`cmp`), test green.
Stand-ins: `MuHooks.isGamePlayedThrough` (M6-E). The old `MuHooks.startPlayMuStepSe` hook is gone (`playSeSpacial` replaces it).
Wired: `Fe8Boot` sets `MuHooks.playSeSpacial` to `SpacialSe` over `control.sound`, so every MU walk (enemy walks and the player's, since `Mu` is shared) queues and plays its step sounds on the SE players, panned by screen x, unless sound effects are off. Not heard on a device.

### EP-14 result (2026-11-15)

Test: `BanimSetupDifferentialTest`. `PrepareBattleGraphicsMaybe` (EP-15, not written) runs the queries and leaves their results in RAM, which the dumps after the start hold: f4280 (Seth on the boss, call at f4274), f1700 and f2130 (the Soldiers, f1696 and f2129). The two battle units, `gPlaySt` and `gBmSt` come from the dump; the left-hand unit is the one `gpEkrBattleUnitLeft` holds. Compared: `gBanimIdx` and `gBanimTriAtkPalettes` and `gBanimFactionPal` per valid side, `gBanimLinkArenaFlag`, `gBanimUniquePaletteDisabled` (link arena flag or `unitColor`), `gBattleScripted` (seeded stale, cleared by the port) and the animation setting against `gPlaySt`. No call trace exists for these functions.
Ported (new `BanimSetup`, game/bm): `GetBattleAnimationId` (the unused `unit` argument dropped; the entry position goes to `animEntry`), `GetBanimFactionPalette`, `SetBattleUnscripted`, `SetBanimLinkArenaFlag`, `GetBanimLinkArenaFlag`. Break-checked: no `-1` on the index ("f4280 gBanimIdx[0] ==> expected: <24> but was: <25>"), entry offset +2 -> +6 ("expected: <24> but was: <26>"), red palette -> green ("gBanimFactionPal[0] ==> expected: <1> but was: <2>"), unscripted sets true ("gBattleScripted ==> expected: <0> but was: <1>"), set link flag stores `1 - flag` and get returns 1 (both "gBanimLinkArenaFlag ==> expected: <0> but was: <1>"), item type +1 ("gBanimIdx[0] ==> expected: <24> but was: <65535>").
Not seen: which entry is chosen. Every captured weapon matches a type entry (`0x100 | type`), none an item-specific entry, so the pass order (item entries first) and the item index comparison survive (both mutated: green). Green and purple factions. `GetBattleAnimationId` is Ported for the table read and the type match only.
Transcribed: `CheckBattleScripted` (the result only feeds the `usrdefined_enable` branch in `PrepareBattleGraphicsMaybe`; the test asserts false because the map animation ran, and a mutation to true fails that, but it is not a captured value), `FilterBattleAnimCharacterPalette` (only the NULL default is seen: default -> 1 fails "gBanimTriAtkPalettes[0] ==> expected: <0> but was: <1>"; the ballista entries are not in a capture), `GetEfxHp` (no capture calls it), `GetBattleAnimPreconfType` and `GetSoloAnimPreconfType` (the setting is OFF in all three; inverting the test for the solo setting survives because every branch then answers OFF), `IsItemDisplayedInBattle` (no item of the "item" type).
Stand-ins: none new. `CombatAction.hooks.getBattleAnimPreconfType` stays the OFF stand-in until M6-G; `MapAnimApi.battleAnimationsOn` (`sub_8055BC4`) stays until EP-15.
Wired: not wired. A new game's setting is ON (`initPlayConfig`), so the real answer would change the player phase sprite refresh. Nothing visible changes.

### EP-0 inventory (2026-11-13)

Test: `EnemyPhaseInventoryTest` (procs per window asserted row by row over `ram_trace_prologue_cancel_end`
f1510..1650 and `ram_trace_prologue_enemy_phase` f1525..2851; plus `gAiDecision`, `gAiState` and the Red units'
AI fields). Functions: **entered (traced)**. A scratch build of the harness (hook on every Thumb instruction,
logging entries of the 367 Thumb functions of cp_phase.c, cp_order.c, cp_decide.c, cp_script.c, cp_battle.c,
cp_utility.c, cp_perform.c, cp_staff.c, cp_0803E2F4.c, and of bm.c, bmphase.c, bmunit.c; then 139 callees of those files
and cpextra_80407F0.c; then bmusailment.c and bmtrick.c; no ROM-repo change, scratch in the agent cache dir) ran
the EP-cap1 inputs and the cancel_end inputs; entries per function and the frames they happen in came out of that.
Nothing is inferred except where a row says so. There is no cp_berserk.c: the berserk phase (`AiPhaseBerserkInit`,
`CpOrderBerserkInit`, `AiPhaseCleanup`, `CpOrderFunc_End`) lives in cp_phase.c/cp_order.c. The cancel_end window
f1510..1650 enters exactly the functions the enemy-phase run enters in the same frames (no difference).

Procs per window (enemy-phase run; script symbol, parent `gProc_BMapMain` unless stated):

| Window | Frames | Procs |
|---|---|---|
| berserk phase, switch | f1526..1528 | `gProcScr_BerserkCpPhase` + `gProcScr_BerserkCpOrder` (f1526); faction 0 -> 0x80 f1529 |
| phase events | f1529..1531 | `ProcScr_StdEventEngine` (ends at once) |
| banner | f1535..1606 | `gProcScr_ResetCursorPosition` f1535, `ProcScr_PhaseIntro` + BlendBox/Squares/Text/Unk |
| start-of-phase displays | f1607..1611 | none alive (the four `*Display_Init` run at f1607..1610 and end at once) |
| AI phase | f1612..2745 | `gProcScr_CpPhase` > `gProcScr_CpOrder` > `gProcScr_CpDecide` (f1612..2741) > `gProcScr_CpPerform` per acting unit |
| Soldier 1 | f1614..2017 | `ProcScr_Mu` (walk), `ProcScr_MuStepSe` x6, `gProcScr_AiTargetCursor` f1648..1694, `sProcScr_CombatAction` f1696..2012 (child of CpPerform), `ProcScr_MapAnimBattle` f1697..2010 and the map-animation procs of MC-0, `ProcScr_MuDeathFade` f1875..1905, `ProcScr_MapAnimExpBar` f1909..1998 |
| between | f2014..2038 | `ProcScr_StdEventEngine` f2014..2016, `sProcScr_BMXFADE` f2018..2033 |
| Soldier 2 | f2039..2402 | same as Soldier 1 (f2039 walk, f2081..2127 cursor, `CombatAction` f2129..2401) |
| event after the last kill | f2403..2721 | `ProcScr_StdEventEngine` + `ProcScr_EventDisplayCursor` f2403..2462, `gProcScr_Talk` f2465..2703 (`gProcScr_TalkWaitForInput` f2644..2701, face procs), `gProcScr_FaceEndIn8Frames` f2706 |
| phase end | f2723..2745 | `sProcScr_BMXFADE` f2723..2738, Cp procs end f2742..2745 |
| green phase | f2747..2766 | `BerserkCp*` f2747, empty `gProcScr_CpPhase`/`CpOrder` f2759..2761, `BerserkCp*` f2763, `gProcScr_UpdateTraps` f2766 |
| turn 2 | f2768..2851 | `StdEventEngine` f2768..2770, banner f2774..2844, `gProcScr_PlayerPhase` f2851 |

AI script commands (unit struct +0x42 `ai1`, +0x43 `ai_a_pc`, +0x44 `ai2`, +0x45 `ai_b_pc`; tables `gAi1ScriptTable`,
`gAi2ScriptTable` in cp_data.c; `gAiState` has no script pointer, only `cmd_result[8]` at +0x86 and the unit
struct holds the positions). Three red units, `gAiState.units` = 0x81, 0x82, 0x83 (filled f1612):

| Unit | ai1 / ai2 (ROM scripts) | What ran |
|---|---|---|
| Red 0 (0x81) | 6 `gAiScript_DoNothing` (NOP, GOTO_START) / 3 `AiScr_AiB_NeverMove` (NOP_0E, GOTO_START) | f1613: `AiTryExecScriptA` > `AiScript_Exec` > `AiScriptCmd_06_DoNothing` (a-pc 0 -> 1; script ends), then `AiTryExecScriptB` > `AiScriptCmd_0E_DoNothing` (b-pc 0 -> 1). No decision; `GOTO_START` (cmd 03) never runs |
| Soldier 1 (0x82) | 0 `gAiScript_ActionInRange` (ACTION(100), GOTO_START) / 0x0A (move to Eirika, else enemy) | f1613..1614: `AiScriptCmd_05_DoStandardAction` > `AiAttemptOffensiveAction`; a-pc 0 -> 1 f1614; `AiTryExecScriptB` not called, ai2 never runs |
| Soldier 2 (0x83) | 0 `gAiScript_ActionInRange` / 0x12 `AiScr_AiB_MoveToEnemyAfterOneTurn` (NOP_0E, SET_AI2 0, GOTO_START) | f2038..2039: the same as Soldier 1; a-pc 0 -> 1 f2039; ai2 never runs |

`DecideScriptA`/`B` run 3x/1x, `AiTryExecScriptA`/`B` 3x/1x, `AiScript_Exec` 4x, `AiIsUnitEnemy` 9x. Commands entered: 05, 06,
0E. Never entered: 00..04, 07..0D, 0F..1B, `AiExecFallbackScriptA/B`, the berserk commands, the unit predicates other than
`AiIsUnitEnemy`. `NextRN_N` runs 3x (f1614, f2038..2039). `gAiState.cmd_result` stays 0.

Findings that change the rows:

- **Both attackers decide in script A.** The AI2 scripts (movement towards targets) never run; `AiTryMoveTowards`,
  `AiFindTargetInReachByFunc`, `AiMakeMoveRangeUnitPowerMaps`, `AiRandomMove` and the ~35 other cp_utility.c helpers are
  never entered. EP-cap2 is the first capture that can run them (later enemy phases).
- `AiAttemptCombatWithinMovement` and `AiFloodMovementAndRange` are never entered (the decision comes from
  `AiAttemptOffensiveAction`: `AiSimulateBestBattleAgainstTarget` > `AiGetCombatPositionScore` >
  `AiComputeCombatScore`, called once per attacker; `AiSimulateBattleAgainstTargetAtPosition` > `BattleGenerateSimulation`).
- `CpDecide_Suspend` runs after each performed action (f2035, f2740) and calls `WriteSuspendSave` (SUSPEND_POINT_CPPHASE):
  the enemy phase writes the suspend save (SRAM) after every acting unit. Red 0 acts without a perform, so no save.
- `AiTryDoSpecialItems` (cpextra_80407F0.c), `GetSpecialItemFuncIndex`, `sub_8040C5C` and `DecideSpecialItems` run only for Red 0
  (f1613). `DecideHealOrEscape` runs for all three.
- `CpPerform_EquipBest` never runs (`EquipUnitItemSlot` runs once per attack, from the combat start, f1696/f2129).
- The four start-of-phase displays are only `*_Init` (f1607..1610, green f2754..2757, turn 2 f2846..2849);
  `_Display`, `_Next`, `MassEffectDisplay_*`, `FinishDamageDisplay` never run. `TrapDamageDisplay_Init`/`_Check` run f2766.
- The event that starts f2403 (dialogue, `CountRedUnits` f2014 and f2403) follows the last kill with Red 0 left alive:
  it is probably the Normal-mode one-enemy-left event (not checked against the event script); EP-11 can start from this window.
- Eirika takes no counter damage from Soldier 1 and 9 from Soldier 2; no level-up (exp 0 -> 65 across both).

Functions per file ("entered" = entered (traced), count of entries in brackets; "never" = never entered in the run;
the tag, if any, is the code repo's `Ported from` / `Transcribed from` today, by a scan that can miss other spellings):

| File | Entered | Never entered |
|---|---|---|
| cp_phase.c | `AiPhaseInit` (2), `AiPhaseBerserkInit` (3, Ported), `AiPhaseCleanup` (5, Ported) | none |
| cp_order.c | `CpOrderMain` (4), `CpOrderFunc_BeginDecide` (2), `BuildAiUnitList` (2), `SortAiUnitList` (1), `GetUnitAiPriority`, `GetUnitBattleAiPriority` (3 each, f1612 only), `CpOrderBerserkInit` (3, Ported), `CpOrderFunc_End` (5, Ported) | none |
| cp_decide.c | `CpDecide_Main` (4), `CpDecide_Suspend` (2), `AiClearDecision` (3), `AiSetDecision` (2), `AiDecideMain` (3), `DecideHealOrEscape` (3), `DecideSpecialItems` (1), `DecideScriptA` (3), `DecideScriptB` (1) | `AiUpdateDecision` |
| cp_script.c | `AiTryExecScriptA` (3), `AiTryExecScriptB` (1), `AiScript_Exec` (4), `AiIsUnitEnemy` (9), cmds `05`, `06`, `0E` | cmds 00..04, 07..0D, 0F..1B; `AiExecFallbackScriptA/B`, `AiDoBerserkAction/Move`, `AiIsUnitEnemyAndNotInScrList`, `...AndScrCharId`, `...AndScrClassId`, `AiIsUnitEnemyOrInScrList`, `AiIsUnitNonActive`, `sub_803CF60`, `sub_803CFB4`, `sub_803D124`, `sub_803D43C`, `sub_803D440` |
| cp_battle.c | `AiAttemptOffensiveAction` (2), `AiFillReversedAttackRangeMap` (3), `AiSimulateBestBattleAgainstTarget` (3), `AiGetCombatPositionScore` (2), `AiIsBadFight` (2), `AiSimulateBattleAgainstTargetAtPosition` (2), `AiComputeCombatScore` (2), 8 `AiGet*CombatScoreComponent` (2 each), 3 `AiGet*CombatPositionScoreComponent` (2 each) | `AiAttemptCombatWithinMovement`, `AiFloodMovementAndRange`, `AiAttemptBallistaCombat`, `AiSimulateBestBallistaBattleAgainstTarget`, `AiAttemptStealActionWithinMovement`, `AiAttemptStealAction_GetMovementAt` |
| cp_utility.c | `AiReachesByBirdsEyeDistance` (4), `AiCouldReachByBirdsEyeDistance` (6), `FillMovementAndRangeMapForItem` (6), `AiGetClassRank` (2), `SetupUnitInventoryAIFlags` (5), `SetupUnitStatusStaffAIFlags` (15), `SetupUnitHealStaffAIFlags` (15), `SaveNumberOfAlliedUnitsIn0To8Range` (10) | 38: `AiTryMoveTowards(NeglectWall)`, `AiFindTargetInReach*` (4), `AiFindBestAdjacentPositionByFunc`, `AiFindClosest*` (3), `AiFindPillageLocation`, `AiFindSafestReachableLocation`, `AiMakeMoveRangeUnitPowerMaps`, `AiRandomMove`, `AiCount*` (6), `AiGetUnit*`, `AiIs*`/`AiLocationIsPillageTarget`, `AiCompare`, `CharStoreAI`, `sub_803B678`, `sub_803C284..490` |
| cp_perform.c | `AiTargetCursor_Main` (94), `StartAiTargetCursor` (2), `CpPerform_UpdateMapMusic`, `_MoveCameraOntoUnit`, `_BeginUnitMovement`, `_MoveCameraOntoTarget`, `_PerformAction`, `_WaitAction`, `_Cleanup`, `AiRefreshMap`, `AiStartCombatAction`, `AiDummyAction` (2 each) | 15: `CpPerform_EquipBest`, `AiPickAction`, `AiPillageAction`, `AiTalkAction`, `AiStaffAction`, `AiUseItemAction`, `AiEscapeAction`, `AiStartEscapeAction`, `AiStartStealAction`, `AiRideBallistaAction`, `AiExitBallistaAction`, `AiDKNightmareAction`, `AiDKSummonAction`, `AiRefreshAction`, `AiWaitAndClearScreenAction` |
| cp_staff.c | `AiTryDoStaff` (2, finds no staff) | the 13 other functions (`AiStaff*`, silence, warp, safest adjacent position) |
| cp_0803E2F4.c | `AiInitDangerMap`, `AiFillDangerMap` (3 each), `UpdateAllPhaseHealingAIStatus` (7), `AiUpdateGetUnitIsHealing` (12) | 39: equip (`AiEquip*`, `AiCanEquip`), steal/dance, heal, escape, `AiTryMoveToSpecificPosition`, `AiTryActionAfterMove`, `sub_803EC18..F9C8` |
| cpextra_80407F0.c (+ `sub_80832D4`) | `AiUpdateNoMoveFlag` (3), `AiTryDoSpecialItems` (1), `GetSpecialItemFuncIndex` (1), `sub_8040C5C` (1), `sub_80832D4` (3) | `AiSpecialItem*`, `AiDecideDKSummon`, `AiDecideNightmareStaff`, `AiTryDKSummon`, `AiTryUseNightmareStaff`, `sub_8040AE0..1054`, `sub_80410C4` |
| bm.c phase path | `SwitchPhases`, `BmMain_ChangePhase`, `BmMain_SuspendBeforePhase`, `BmMain_UpdateTraps` (3 each), `BmMain_StartPhase` (4), `sub_8015434` (3), `LockGame`/`UnlockGame` (11), `StartMapSongBgm`, `GetCurrentMapMusicIndex`, `EnsureCameraOntoPosition`, `SetCursorMapPosition`, `RunPhaseSwitchEvents` (3), `CallEvent` (4) | `BmMain_ResumePlayerPhase`, `BmMain_StartIntroFx`, `CallBeginningEvents`, `GotoChapterWithoutSave`, `UndeployEveryone`, `sub_80155C4`, `GetCurrentPhase`, `GetNonActiveFaction` |
| bmphase.c | `GetPhaseAbleUnitCount` (11), `CountUnitsInState` (5), `AreUnitsAllied` (49) | `IsSameAllegiance` |
| bmunit.c (phase path) | `TickActiveFactionTurn` (3: f1607, f2754, f2846), `ClearActiveFactionGrayedStates` (3: f1529, f2750, f2768), `UnitBeginAction` (2), `UnitKill` (2), `UnitFinalizeMovement` (2), `MoveActiveUnit` (2), `CountRedUnits` (2), `CountAvailableBlueUnits` (5), `ClearCutsceneUnits` (4), `SetAllUnitNotBackSprite` (2), unit stat getters (`GetUnitCurrentHp`, `GetUnitMaxHp`, `GetUnitPower`, ...) | the other 62 bmunit.c functions |
| bmusailment.c / bmtrick.c | `StatusDecayDisplay_Init` (f1607), `TerrainHealDisplay_Init` (f1608), `PoisonDamageDisplay_Init` (f1609), `GorgonEggHatchDisplay_Init` (f1610) (each 3x), `TrapDamageDisplay_Init`/`_Check`, `DecayTraps`, `CountDownTraps`, `GenerateTrapDamageTargets`, `GenerateDisplayedTrapDamageTargets`, `ResetCountedDownTraps`, `PostTrapExecFlag` (f2766..2767), `EnableAllLightRunes`/`DisableAllLightRunes` (4, per attack), `GetTrap` | all `*Display_Display`/`_Next`/`_Watch`, `StatusHealEffect_*`, `KillAllRedUnits_*`, `ApplyHazardHealing`, `AddTrap*`, `GetTrapAt` |

Callees outside those files that the windows enter (all traced; most are Ported/Transcribed already): `GenerateUnitMovementMap`
(10), `GenerateMovementMap`, `SetWorkingMoveCosts`, `BmMapFill`, `MapAddInRange`/`MapAddInBoundedRange`, `CanUnitUseWeapon`,
`CanUnitUseStaff`, `GetItemMinRange`/`MaxRange`/`Might`/`Type`/`UseEffect`, `GenerateBestMovementScript`,
`UnitApplyWorkingMovementScript`, `SetAutoMuMoveScript`, `SetAutoMuDefaultFacing`, `StartMu`, `ApplyUnitAction`,
`BattleGenerateSimulation`, `EquipUnitItemSlot`, `TryRemoveUnitFromBallista`, `ProcessTurnSupportExp`, `NewBMXFADE`,
`RenderBmMapOnBg2`. Not entered: `GenerateMagicSealMap`, `MarkMovementMapEdges`, `GenerateUnitExtendedMovementMap`,
`GenerateExtendedMovementMapOnRange` (+NeglectWall), `GetRiddenBallistaAt`, `GetBallistaItemAt`, `GetItemUses`.

Corrected PORT rows (functions run, with the first frames; "Ported"/"Transcribed" means the code repo already has it):

| Row | Functions run | Not run: keep transcribed / unwritten |
|---|---|---|
| EP-1 | `TickActiveFactionTurn`, `ClearActiveFactionGrayedStates`, the four `*Display_Init`, `sub_8015434` (Transcribed), `GetPhaseAbleUnitCount`, `CountUnitsInState` (Transcribed); `SwitchPhases`, `BmMain_ChangePhase`, `_StartPhase`, `_SuspendBeforePhase`, `_UpdateTraps` already Ported | the displays' `_Display`/`_Next` procs, `BmMain_ResumePlayerPhase`, `BmMain_StartIntroFx`, `CallBeginningEvents` |
| EP-2a | `AiPhaseInit` (f1612), `CpOrderMain`, `CpOrderFunc_BeginDecide`, `BuildAiUnitList`, `SortAiUnitList`, `GetUnitAiPriority`, `GetUnitBattleAiPriority` | none in the two files |
| EP-2b | `SetupUnitInventoryAIFlags`, `SetupUnitStatusStaffAIFlags`, `SetupUnitHealStaffAIFlags`, `SaveNumberOfAlliedUnitsIn0To8Range`, `UpdateAllPhaseHealingAIStatus`, `AiUpdateGetUnitIsHealing` | the staff/heal/steal helpers of cp_0803E2F4.c |
| EP-3a | `CpDecide_Main`, `CpDecide_Suspend`, `AiClearDecision`, `AiSetDecision`, `AiDecideMain`, `DecideHealOrEscape`, `DecideScriptA`, `DecideScriptB` | `AiUpdateDecision` |
| EP-3b | `AiUpdateNoMoveFlag`, `AiInitDangerMap`, `AiFillDangerMap`, `DecideSpecialItems`, `AiTryDoSpecialItems`, `GetSpecialItemFuncIndex`, `sub_8040C5C` | the other special-item/DK/Nightmare functions |
| EP-4 | `AiTryExecScriptA`, `AiTryExecScriptB`, `AiScript_Exec`, `AiIsUnitEnemy`, cmds 05, 06, 0E | the other 27 commands, fallback scripts, predicates |
| EP-5 | `AiAttemptOffensiveAction`, `AiFillReversedAttackRangeMap`, `AiSimulateBestBattleAgainstTarget`, `AiSimulateBattleAgainstTargetAtPosition`, `AiGetCombatPositionScore`, `AiIsBadFight`, `AiTryDoStaff` | `AiAttemptCombatWithinMovement`, `AiFloodMovementAndRange`, ballista, steal |
| EP-6a/b | `AiComputeCombatScore`, 8 `AiGet*CombatScoreComponent`, 3 `AiGet*CombatPositionScoreComponent` | none in the file's score part |
| EP-7 | `AiReachesByBirdsEyeDistance`, `AiCouldReachByBirdsEyeDistance`, `FillMovementAndRangeMapForItem`, `AiGetClassRank`, `GenerateUnitMovementMap` | the 38 other cp_utility.c functions (movement commands) |
| EP-8 | `CpPerform_UpdateMapMusic`, `_MoveCameraOntoUnit`, `_BeginUnitMovement`, `_MoveCameraOntoTarget`, `StartAiTargetCursor`, `AiTargetCursor_Main` | |
| EP-9 | `CpPerform_PerformAction`, `AiStartCombatAction`, `CpPerform_WaitAction`, `AiDummyAction`, `CpPerform_Cleanup`, `AiRefreshMap` | `CpPerform_EquipBest` and the 14 other actions |
| EP-10 | `ProcessTurnSupportExp`, `DecayTraps`, `CountDownTraps`, `GenerateTrapDamageTargets`, `GenerateDisplayedTrapDamageTargets`, `TrapDamageDisplay_Init`/`_Check`, `ResetCountedDownTraps`, `PostTrapExecFlag`, `CountRedUnits`, `CountAvailableBlueUnits` | trap placement (`AddTrap*`), `TrapDamageDisplay_Display`/`_Next` |

### EP-0b inventory (2026-11-14)

Test: `EnemyPhaseInventory2Test` (procs per window, asserted row by row over `ram_trace_prologue_clear`, plus the boss's
AI fields and `gAiDecision` at the move-only decision). Functions: **entered (traced)**, by a scratch build of the
harness as in EP-0, scratch in the agent scratchpad, no ROM-repo change. The hook logs entries of every Thumb function
with a `T`/`t` symbol of the decomp nm file below 0x080D8000 (8526 functions; EP-0 traced 590) and the frames of each
entry. The `tracecall aicombatscore` line of the script was dropped in the scratch copy (`tracecall` replaces the
instruction hook); the `procs` watch of the scratch run is byte-identical to the published run. Function to decomp
file by the text sections of `fireemblem8.map`. ARM functions (`arm_func_start`) are not seen by the hook.
Tags: a scan of main-source Kotlin (tests excluded) for `Ported from src/<file>: <Func>` and
`Transcribed from src/<file>: <Func>` (a differently spelled tag is missed); **Ported/Transcribed here means tagged, not
verified**. A function with no tag is NEW; `*` marks NEW names that appear in main source without a tag (a stand-in or a
comment). Engine layer (no rows): `hardware.c`, `proc.c`, `ctc.c`, `time.c`, `irq.c`, `ramfunc.c`, `libagbsyscall.c`,
`m4a*.c`, `sio_main.c`, `asm/arm_call.c`, compiler runtime; the gbafe engine implements these without per-function tags;
whether one needs a tag is not decided here. The four main-loop functions `OnMain`, `OnVBlank`, `WfxUpdate`, `WfxVSync`
are entered every frame and have no row (engine loop).

Windows (EP-cap2 timeline from "EP-cap2 result"; procs from the test; script symbols, parent `gProc_BMapMain` unless stated):

| Window | Frames | Procs |
|---|---|---|
| a: boss turn event | f2964..3722 | `SwitchPhases` at f2964 starts `ProcScr_StdEventEngine` (f2964..3721; no `gProcScr_PlayerPhase`, no banner yet); `gMusicProc3Script` f2964..3012; `gProcScr_Talk` f2966..3703 (+ `gProcScr_TalkSkipListener` f2968, `TalkOpen`/`TalkBubbleOpen` f2982..2994, `gProcScr_TalkWaitForInput` f3259..3321 and f3685..3701 (the two A presses), `gProcScr_TalkShiftClear` f3327..3343 and f3496..3512); face procs `gProcScr_E_FACE` + `gProcScr_FaceBlink` + `gProcScr_0859124C` f2972..3714 with `ProcScr_PalFade` f2972..2984 and f3706..3714; `ProcScr_TemporaryLock` f2972..2980; `gProcScr_FaceEndIn8Frames` f3706..3714, `gUnknown_08591DE8` f3706..3717; event ends f3721 (boss `ai1`/`ai2` and positions 6/1/3/1 -> 0/0/0/0 at f3719) |
| b: move-only enemy phase | f3726..3877 | banner `ProcScr_PhaseIntro` (+ BlendBox, Squares, Text, Unk) f3726..3796 (the banner starts earlier); `gProcScr_CpPhase` > `CpOrder` f3803..3854, `gProcScr_CpDecide` f3803..3851, `gProcScr_CpPerform` f3804..3831 (one unit), `ProcScr_Mu` f3804..3831 with four `ProcScr_MuStepSe` pulses (f3805, 3812, 3820, 3828); no `gProcScr_AiTargetCursor`, no `CombatAction`, no map-animation proc; `sProcScr_BMXFADE` f3832..3847; berserk phase f3856..3857, empty `CpPhase`/`CpOrder` f3868..3870, berserk f3872, `gProcScr_UpdateTraps` f3875, `ProcScr_StdEventEngine` f3877 (turn 3) |
| c: Seth attacks the boss | f4130..4892 | unit walk `ProcScr_Mu` + `MuStepSe` f4131..4176; unit menu `sProc_Menu` + `sProc_MenuItem` + `gProcCmd_MenuItemPanel` + `ProcScr_GreenTextColor` + `sProcScr_MoveLimitView` f4181..4239; target selection `gProcScr_TargetSelection` + `gProcScr_BKSEL` f4240..4269; `sProcScr_CombatAction` (child of `gProcScr_PlayerPhase`) f4274..4892; `ProcScr_MapAnimBattle` f4275..4890; pre-battle quote event `ProcScr_BattleEventEngine` f4278..4460 with `gProcScr_Talk` f4279..4443 (face procs f4285..4454, `TalkWaitForInput` f4432..4441); `ProcScr_MapBattleInfoBox` f4468..4635; per round `ProcScr_MapAnimDefaultItemEffect` (f4485..4535, 4544..4581, 4590..4627) with `ProcScr_CallDelayedArg`, `ProcScr_MuHitFlash` f4491..4507 and 4596..4612, `ProcScr_ApProc` (miss) f4550..4577; death event `ProcScr_BattleEventEngine` f4636..4740 with `gProcScr_Talk` f4636..4723 (`TalkWaitForInput` f4709..4721); `ProcScr_MuDeathFade` f4742..4772; `ProcScr_MapAnimExpBar` f4776..4878; `sProcScr_CombatAction` ends f4892 |
| d: ending scene start | f4894..5000 | `gProcScr_PlayerPhase` stays alive (f4894..5000); `ProcScr_StdEventEngine` f4894..5000; `gMusicProc3Script` f4894..4944; `ProcScr_FadeToBlack` f4895..4910 and `ProcScr_FadeFromBlack` f4916..4931 (children of the event); `gProcScr_Talk` f4933..5000 (+ `TalkSkipListener` f4935, `TalkOpen`/`TalkBubbleOpen` f4949..4961); two face slots `gProcScr_E_FACE` f4939..5000 with `ProcScr_PalFade` and `ProcScr_TemporaryLock` f4939..4951 |

Sub-timeline of window c (first entries of the functions in the tables): move f4130..4180 (`UnitFinalizeMovement`
f4181), unit menu f4181..4239, weapon list f4210..4239, target selection and forecast f4240..4270, `BeginBattleAnimations`
f4274 (battle-animation setup queries, then `BeginBattleMapAnims`), pre-battle event f4278..4461, rounds f4484..4635,
death event f4636..4741, death fade f4742..4773, exp bar f4776..4879, weapon-break and rank-up checks f4880..4888,
write-back f4892 (`BattleApplyGameStateUpdates`, `UnitKill`, `KillUnitOnCombatDeath` entered 4892, as at f2012/f2401).

Findings that change rows:

- **Window a has one new opcode.** Commands run: 01 End, 02, 03 (Transcribed), 05, 08, 09, 0A, 0C, 12 (BGM), 19 (Transcribed),
  1A, 1B, 1D (text), 39 `Event39_ChangeAiScript` (`ChangeUnitAi` > `ChangeAiForCharacter`, f3719, NEW). Everything else
  (`EvCheck02_TURN`, `RunPhaseSwitchEvents`, `StartEventFromInfo`, `SearchAvailableEvent`, flags) is Ported/Transcribed.
  The rest of the window's NEW functions are the text, face and UI plumbing around the talk procs.
- **The boss's move needs the movement commands EP-0 found never entered.** f3804: `AiScriptCmd_05_DoStandardAction` >
  `AiAttemptOffensiveAction` (Ported, finds nothing: `AiComputeCombatScore` is not called), then script B runs
  `AiScriptCmd_12_MoveTowardsEnemy` > `AiTryMoveTowards` > `AiFindTargetInReachByFunc`; `AiReachesByBirdsEyeDistance`,
  `AiCouldReachByBirdsEyeDistance`, `FillMovementAndRangeMapForItem`, `GenerateUnitMovementMap`,
  `GenerateExtendedMovementMapOnRange`, `RevertMovementScript`, `AiCheckDangerAt`, `UnitHasMagicRank` are entered at
  f3803..3804. Not entered: `AiMakeMoveRangeUnitPowerMaps`, `AiRandomMove`, `AiTryMoveTowardsNeglectWall`, the other 30+ cp_utility.c
  functions. `gAiDecision` at f3804: action 0, unit 0x81, move to (9,6); the boss ends the phase at (9,6) with a-pc/b-pc 1.
- **`CpPerform_EquipBest` runs** (f3832, with `AiCanEquip`, `AiEquipBestConsideringDanger`, `AiEquipGetDanger`,
  `AiEquipGetFlags`, `AiIsWithinFlyingDistance`): EP-0 said it never runs; it does for a unit that moved without acting.
  `AiDummyAction` f3831 (action 0), then `AiRefreshMap`, `CpPerform_Cleanup`, `NewBMXFADE` f3832.
- The same phase end as EP-0 follows: `CpDecide_Suspend` f3849 (suspend save written f3850..3860), `ProcessTurnSupportExp`
  f3877, `DecayTraps` ... `PostTrapExecFlag` f3875..3876, `TrapDamageDisplay_Init`/`_Check` f3875. The four start-of-phase
  `*Display_Init` run before the banner (f3798..3801; their target-list callees `MakeTerrainHealTargetList`,
  `MakePoisonDamageTargetList`, `MakeGorgonEggHatchTargetList`, `sub_8026414` and the terrain-heal helpers are NEW).
- **Window c's battle runs through the animation-setup queries.** `BeginBattleAnimations` (f4274) calls 20 banim functions
  (`banim-ekrcmd.c`, `-battleparse.c`, `-ekrarena.c`, `-ekrbattle.c`, `-ekrhensei.c`, `-ekrdragonstatus.c`, ...) only to learn
  that no animation runs, then `BeginBattleMapAnims`. No ekr/efx proc, no animation script. All of mapanim*.c except
  `mapanim_lvup.c` (19 + 15 + 10 + 7 + 6 + 1 NEW functions in mapanim.c, mapanim_infobox.c, mapanim_spellassoc.c,
  mapanim_expbar.c, mapanim_scanline.c, mapanim_specialeffect.c) runs here; the level-up branch is entered as a check only
  (`ProcMAExpBar_LevelUpIfPossible` f4878, no level-up; `CheckBattleUnitLevelUp` runs once at f4274).
- The death event is `CheckBattleDefeatTalk` > `GetDefeatTalkEntry` > `DisplayDefeatTalkForPid` + `SetPidDefeatedFlag`
  (f4636), run through `EventEngine_CreateBattle` (Transcribed) with `Event1B`/`Event1D`/`sub_800E210`/`sub_800E640`/`Event01`
  and `Event10_ModifyEvBit` (Transcribed); the pre-battle event is `CallBattleQuoteEventsIfAny` > `GetBattleQuoteEntry` >
  `CallBattleQuoteEventInBattle` (f4278; same functions ran at f1700, f2133 with no quote).
- f4181..4240 enter the unit-menu build of the player phase: 26 `*Usability` functions of bmmenu.c, 10 target-list builders of
  bmtarget.c, `CheckForCharacterEvents`, `GetAvailableTileEventCommand` and the forecast tutorial checks are untagged. They are
  M6-B code paths (the menu was wired in PP-17); whether the menu port covers them under other names is not checked here.
- **The ending scene is started by the wait-event path, not by the end-event functions.** f4894: `RunPotentialWaitEvents` >
  `CheckForWaitEvents` > `RunWaitEvents` > `EvCheck01_AFEV` > `StartEventFromInfo` (all Ported) after `AreAnyEnemyUnitDead`;
  `ShouldCallEndEvent`, `MaybeCallEndEvent`, `CallEndEvent` and `CallGameOverEvent` are never entered in the whole run
  (PC-1 listed them). Commands run: 05, 0A, 0C, 12, 08, 1A, 23 `Event23_DisaleMapDisp`, 21 `Event21_TextBg` +
  `EventShowTextBgDirect`, 17 `Event17_Fade` (Transcribed), 03 (Transcribed), 01, 1B, 1D; plus `SetPermanentFlag`,
  `AreAnyEnemyUnitDead`, `HandlePostActionTraps`, `ChangeUnitSpritePalette`, `RefreshAllies`, `SetUnitStatus`, `LockMus`. NEW in window
  d: 51 functions, all of them already counted in a..c except `RefreshAllies`, `SetUnitStatus`, `LockMus`.
- `MuStepSe_*`, `StartPlayMuStepSe`, `PlayMuStepSe`, `PlaySeSpacial`, `Screen2Pan`, `IsFirstPlaythrough`, `IsGamePlayedThrough`
  (the step-sound chain, BUG-mustepse) are untagged in every walk of the run (also f1615, f2039).
- `PidStats*` (bmsave-bwl.c) run in every battle (f1696, f2129, f4274) and at f2964 (`PidStatsAddFavval`); `PidStatsAddExpGained`
  is the no-op stand-in of MC-14. `WriteWorldMap*`, `SaveDungeon*`, `GetConvoyItemArray`, `SramOffsetToAddr` run with each suspend
  save (f3850..3860, f4272, f2036, f2741 ...).

Counts of distinct functions entered per window (class: P = `Ported from` tag, T = `Transcribed from` tag, NEW = no tag, E = no tag and in the engine layer, see below):

| Window | Ported | Transcribed | NEW (game logic) | Engine layer |
|---|---|---|---|---|
| a: boss turn event f2964..3722 | 165 | 40 | 61 | 66 |
| b: move-only enemy phase f3726..3877 | 203 | 72 | 109 | 72 |
| c: turn 3 attack on the boss f4130..4892 | 394 | 109 | 252 | 80 |
| d: ending scene start f4894..5000 | 168 | 45 | 51 | 61 |
| union (a..d) | 507 | 144 | 336 | 90 |

NEW functions per window, by decomp file (`name@first entry frame`; `*` = the name appears in main-source Kotlin without a tag, i.e. a stand-in or a comment; functions run in several windows are listed in each):

**Window a: boss turn event f2964..3722**

| File | NEW functions |
|---|---|
| banim-ekrbattle.c | `IsBattleDeamonActive*`@2965 |
| bm.c | `OnMain*`@2964 `OnVBlank*`@2964 `ReadGameSaveCoreGfx*`@3721 |
| bmio.c | `WfxUpdate*`@2964 `WfxVSync*`@2964 `GetTextDisplaySpeed*`@2966 |
| bmlib.c | `Decompress*`@2966 `GetDataSize*`@2972 `Register2dChrMove*`@2972 `SetBlackPal`@2972 `StartTemporaryLock*`@2972 `TemporaryLock_OnLoop`@2972 `UnpackRaw*`@2972 `StartPalFadeToBlack`@3706 |
| bmsave-bwl.c | `PidStatsAddFavval`@2964 `PidStatsSubFavval08*`@2964 |
| bmsave-lib.c | `UnlockSoundRoomSong*`@3013 |
| bmtrick.c | `GetTrap*`@2964 |
| bmunit.c | `GetUnitSMSId*`@2964 |
| bmxfade.c | `DoesBMXFADEExist*`@2964 |
| cgtext.c | `CgTextExists*`@3705 `EndCgText*`@3706 |
| event.c | `EventEngine_CanStartSkip*`@2964 `_FreezeMenu*`@2964 |
| eventinfo.c | `ClearActiveEventRegistry*`@2964 `RegisterEventActivation*`@2964 `RunTutorialEvent*`@2964 |
| eventscr.c | `_WhileFace6CExists*`@3706 `Event39_ChangeAiScript`@3719 |
| eventscr3.c | `ChangeAiForCharacter`@3719 `ChangeUnitAi`@3719 |
| eventscr_utils.c | `ChangeUnitSpritePalette*`@3706 `ResetBkselPalette*`@3722 |
| face.c | `FindFreeFaceSlot*`@2972 `GetFaceDisplayBits`@2972 `GetPortraitData*`@2972 `EndFaceIn8Frames*`@3706 `EndFacePtr*`@3715 |
| fontgrp.c | `GetLang*`@2966 `InitSystemTextFont*`@2966 `Text_SetColor*`@2966 `Text_GetCursor`@3259 `ResetText*`@3721 |
| helpbox.c | `sub_808BB74*`@3706 |
| icon.c | `LoadIconPalettes*`@3721 `ResetIconGraphics_*`@3721 |
| scene.c | `IsTalkActive*`@2966 `StartTalkExt*`@2966 `GetTalkFaceHPos*`@2972 `TalkOpen_OnEnd*`@2995 `GetTalkChoiceResult*`@3705 `EndTalk*`@3706 `FaceExists*`@3706 |
| soundwrapper.c | `DeleteAll6CWaitMusicRelated*`@2964 `GetCurrentBgmSong*`@2964 `PlaySong*`@3013 |
| uimenu.c | `FreezeMenu*`@2964 `ResumeMenu*`@3722 |
| uiutils.c | `UnpackUiFrameImage`@3721 `UnpackUiFramePalette`@3721 |
| engine layer (66) | asm/arm_call.c 2, hardware.c 9, libagbsyscall.c 6, m4a.c 5, m4a_1.c 9, proc.c 22, ramfunc.c 3, tools/agbcc/lib/libc.a(memcpy.c) 1, tools/agbcc/lib/libc.a(memset.c) 1, tools/agbcc/lib/libgcc.a(_call_via_rX.c) 6, tools/agbcc/lib/libgcc.a(_modsi3.c) 1, tools/agbcc/lib/libgcc.a(_umodsi3.c) 1 |

**Window b: move-only enemy phase f3726..3877**

| File | NEW functions |
|---|---|
| bm.c | `OnVBlank*`@3726 `OnMain*`@3728 |
| bmbattle.c | `sub_802C334*`@3804 |
| bmcontainer.c | `GetConvoyItemArray`@3850 |
| bmdifficulty.c | `SaveDungeonRecords*`@3850 `SaveDungeonState*`@3850 |
| bmidoten.c | `GenerateUnitMovementMap*`@3803 `GenerateExtendedMovementMapOnRange*`@3804 `RevertMovementScript*`@3804 |
| bmio.c | `WfxVSync*`@3726 `WfxUpdate*`@3727 `IsFirstPlaythrough*`@3805 |
| bmitem.c | `GetItemUseEffect`@3803 |
| bmlib.c | `Decompress*`@3726 `PlaySeSpacial`@3805 `Register2dChrMove*`@3805 `Screen2Pan`@3805 |
| bmmap.c | `GetTerrainHealAmount*`@3799 `GetTerrainHealsStatus*`@3799 `RefreshEntityBmMaps*`@3803 `RefreshMinesOnBmMap*`@3803 `RefreshTorchlightsOnBmMap*`@3803 |
| bmreliance.c | `GetUnitSupportLevel*`@3877 `GetUnitSupporterCharacter*`@3877 `GetUnitSupporterUnit*`@3877 `GetUnitTotalSupportLevel`@3877 `ProcessTurnSupportExp*`@3877 `UnitGainSupportExp`@3877 |
| bmsave-bwl.c | `PidStatsAddSquaresMoved*`@3832 |
| bmsave-gmap.c | `WriteWorldMapNodes`@3850 `WriteWorldMapPaths`@3850 `WriteWorldMapSkirmishes`@3850 `WriteWorldMapStuff*`@3850 `WriteWorldMapUnits`@3850 |
| bmsave-lib.c | `WriteGlobalSaveInfoNoChecksum*`@3726 `IsGamePlayedThrough*`@3805 `SramOffsetToAddr`@3850 |
| bmtarget.c | `MakeTerrainHealTargetList*`@3799 `MakePoisonDamageTargetList*`@3800 `sub_8026414*`@3800 `MakeGorgonEggHatchTargetList*`@3801 |
| bmtrap.c | `GetPickTrapType*`@3832 |
| bmtrick.c | `GetTrap*`@3803 `GetTrapAt*`@3832 `CountDownTraps`@3875 `DecayTraps*`@3875 `GenerateDisplayedTrapDamageTargets`@3875 `GenerateTrapDamageTargets`@3875 `sub_802EA00`@3875 `sub_802EA1C`@3875 `PostTrapExecFlag`@3876 `ResetCountedDownTraps`@3876 |
| bmunit.c | `GetUnitCurrentHp`@3803 `GetUnitLeaderCharId*`@3803 `GetUnitSMSId*`@3803 `UnitHasMagicRank*`@3804 |
| bmusailment.c | `TrapDamageDisplay_Check`@3875 `TrapDamageDisplay_Init`@3875 |
| bmxfade.c | `NewBMXFADE*`@3832 `bmxfade_init`@3832 `bmxfade_loop`@3832 `Destruct6CBMXFADE`@3848 `DoesBMXFADEExist*`@3877 |
| cp_0803E2F4.c | `AiCheckDangerAt`@3804 `AiCanEquip`@3832 `AiEquipBestConsideringDanger`@3832 `AiEquipGetDanger`@3832 `AiEquipGetFlags`@3832 `AiIsWithinFlyingDistance`@3832 |
| cp_perform.c | `CpPerform_BeginUnitMovement`@3804 `CpPerform_MoveCameraOntoUnit`@3804 `CpPerform_UpdateMapMusic`@3804 `CpPerform_MoveCameraOntoTarget`@3830 `AiDummyAction`@3831 `CpPerform_PerformAction`@3831 `CpPerform_WaitAction`@3831 `AiRefreshMap*`@3832 `CpPerform_Cleanup`@3832 `CpPerform_EquipBest`@3832 |
| cp_script.c | `AiScriptCmd_12_MoveTowardsEnemy`@3804 |
| cp_utility.c | `AiCouldReachByBirdsEyeDistance*`@3803 `FillMovementAndRangeMapForItem*`@3803 `AiFindTargetInReachByFunc`@3804 `AiReachesByBirdsEyeDistance*`@3804 `AiTryMoveTowards*`@3804 |
| event.c | `EventEngine_CanStartSkip*`@3877 `_FreezeMenu*`@3877 |
| eventinfo.c | `sub_80832D4*`@3803 `CheckTutorialEvent*`@3832 `RunTutorialEvent*`@3859 `ClearActiveEventRegistry*`@3877 `RegisterEventActivation*`@3877 |
| mu.c | `GetMuImgBufById*`@3804 `MuStepSe_Init`@3805 `MuStepSe_PlaySeA`@3805 `PlayMuStepSe*`@3805 `SetAutoMuDefaultFacing*`@3805 `StartPlayMuStepSe*`@3805 `MuStepSe_PlaySeB`@3806 `EndMuMovement`@3829 `HaltMu*`@3829 `Mu_OnStateDoNothing`@3829 |
| phasechangefx.c | `PhaseIntroVMatchLo`@3728 `PhaseIntroVMatchMid*`@3728 |
| soundwrapper.c | `GetCurrentBgmSong*`@3726 `StartBgm*`@3797 |
| uimenu.c | `GetForceDisabledMenuItems*`@3850 `FreezeMenu*`@3877 |
| uiselecttarget.c | `GetTarget`@3875 |
| engine layer (72) | asm/arm_call.c 2, hardware.c 13, irq.c 1, libagbsyscall.c 7, m4a.c 4, m4a_1.c 6, proc.c 26, ramfunc.c 2, sio_main.c 1, tools/agbcc/lib/libc.a(memcpy.c) 1, tools/agbcc/lib/libc.a(memset.c) 1, tools/agbcc/lib/libgcc.a(_call_via_rX.c) 7, tools/agbcc/lib/libgcc.a(_umodsi3.c) 1 |

**Window c: turn 3 attack on the boss f4130..4892**

| File | NEW functions |
|---|---|
| ap.c | `APProc_Create`@4550 `APProc_OnUpdate`@4551 `APProc_OnEnd`@4578 |
| banim-battleparse.c | `GetBanimTerrainGround`@4274 `GetSpellAnimId`@4274 `ParseBattleHitToBanimCmd`@4274 `UnsetMapStaffAnim`@4274 |
| banim-ekrarena.c | `GetBattleAnimArenaFlag`@4274 `SetBanimArenaFlag`@4274 |
| banim-ekrbattle.c | `GetBanimLinkArenaFlag`@4274 `SetBanimLinkArenaFlag`@4274 `IsBattleDeamonActive*`@4285 |
| banim-ekrbattleintro.c | `PrepareBattleGraphicsMaybe`@4274 |
| banim-ekrbattlestarting.c | `sub_8055BC4`@4274 |
| banim-ekrcmd.c | `CheckBattleScripted`@4274 `FilterBattleAnimCharacterPalette`@4274 `GetBanimFactionPalette`@4274 `GetBattleAnimationId*`@4274 `GetEfxHp`@4274 `SetBattleUnscripted`@4274 |
| banim-ekrdragonstatus.c | `ResetEkrDragonStatus`@4274 |
| banim-ekrhensei.c | `CheckBanimHensei`@4274 |
| bm.c | `OnMain*`@4130 `OnVBlank*`@4130 |
| bmbattle.c | `BeginBattleAnimations*`@4274 `GetBattleAnimPreconfType*`@4274 `DidBattleUnitBreakWeapon`@4880 `HasBattleUnitGainedWeaponLevel`@4888 `BattlePrintDebugHitInfo`@4892 `BattlePrintDebugUnitInfo`@4892 |
| bmcontainer.c | `HasConvoyAccess`@4181 `GetConvoyItemArray`@4272 |
| bmdifficulty.c | `CanUnitSeize`@4181 `SaveDungeonRecords*`@4272 `SaveDungeonState*`@4272 |
| bmio.c | `WfxUpdate*`@4130 `WfxVSync*`@4130 `IsFirstPlaythrough*`@4131 `GetTextDisplaySpeed*`@4279 |
| bmitem.c | `GetUnitItemSlot`@4181 `IsItemEffectiveAgainst*`@4210 `IsUnitEffectiveAgainst`@4210 `IsItemCoveringRange*`@4240 `GetItemAwardedExp`@4888 `GetWeaponLevelFromExp`@4888 |
| bmlib.c | `PlaySeSpacial`@4131 `Register2dChrMove*`@4131 `Screen2Pan`@4131 `Decompress*`@4210 `GetDataSize*`@4210 `UnpackRaw*`@4210 `CopyString`@4211 `SetBlackPal`@4285 `StartTemporaryLock*`@4285 `TemporaryLock_OnLoop`@4285 `StartPalFadeToBlack`@4446 `PutStringCentered`@4470 `sub_8013168`@4470 `CallDelayedArg`@4485 `CallDelayedArg_OnLoop`@4486 |
| bmmenu.c | `ArenaCommandUsability`@4181 `ArmoryCommandUsability`@4181 `AttackBallistaCommandUsability`@4181 `ChestCommandUsability`@4181 `DanceCommandUsability`@4181 `DoorCommandUsability`@4181 `DropUsability`@4181 `ExitCommandUsability`@4181 `GiveUsability`@4181 `ItemSubMenu_IsTradeAvailable`@4181 `PickCommandUsability`@4181 `PlayCommandUsability`@4181 `RescueUsability`@4181 `RideCommandUsability`@4181 `SecretShopCommandUsability`@4181 `StaffCommandUsability`@4181 `StealCommandUsability`@4181 `SummonCommandUsability`@4181 `SupplyUsability`@4181 `SupportCommandUsability`@4181 `TakeUsability`@4181 `TalkCommandUsability`@4181 `UnitActionMenu_CanSeize`@4181 `VendorCommandUsability`@4181 `VisitCommandUsability`@4181 `YobimaCommandUsability`@4181 |
| bmreliance.c | `GetUnitSupporterCharacter*`@4181 `GetUnitSupporterUnit*`@4181 `InitSupportBonuses`@4210 |
| bmsave-bwl.c | `PidStatsAddBattleAmt*`@4274 `PidStatsAddFavval`@4274 `PidStatsAddWinAmt`@4274 `PidStatsRecordBattleRes*`@4274 `PidStatsAddExpGained*`@4892 `PidStatsRecordDefeatInfo*`@4892 |
| bmsave-gmap.c | `WriteWorldMapNodes`@4272 `WriteWorldMapPaths`@4272 `WriteWorldMapSkirmishes`@4272 `WriteWorldMapStuff*`@4272 `WriteWorldMapUnits`@4272 |
| bmsave-lib.c | `IsGamePlayedThrough*`@4131 `SramOffsetToAddr`@4272 `WriteGlobalSaveInfoNoChecksum*`@4274 |
| bmtarget.c | `ForEachAdjacentUnit`@4181 `MakeRescueTargetList`@4181 `MakeTakeTargetList`@4181 `MakeTalkTargetList`@4181 `MakeTargetListForSupport`@4181 `MakeTradeTargetList`@4181 `TryAddRescuedUnitToTakeTargetList`@4181 `TryAddUnitToRescueTargetList`@4181 `TryAddUnitToTalkTargetList`@4181 `TryAddUnitToTradeTargetList`@4181 |
| bmtrick.c | `GetTrap*`@4181 `DisableAllLightRunes*`@4240 `EnableAllLightRunes*`@4240 |
| bmunit.c | `GetUnitKeyItemSlotForTerrain`@4181 `GetUnitCurrentHp`@4274 `UnitHasMagicRank*`@4274 |
| bmxfade.c | `DoesBMXFADEExist*`@4279 |
| cgtext.c | `CgTextExists*`@4445 `EndCgText*`@4446 |
| event.c | `CallBattleQuoteEventInBattle`@4278 `GetEventTriggerState`@4278 `EventEngine_CanStartSkip*`@4279 |
| eventinfo.c | `RunTutorialEvent*`@4130 `StartDestSelectedEvent*`@4130 `CheckForCharacterEvents`@4181 `GetAvailableTileEventCommand`@4181 `StartAfterUnitMovedEvent*`@4181 `CheckBattleForecastTutorialEvent*`@4240 `CheckTutorialEvent*`@4240 `CallBattleQuoteEventsIfAny`@4278 `GetBattleQuoteEntry`@4278 `CheckBattleDefeatTalk`@4636 `DisplayDefeatTalkForPid`@4636 `GetDefeatTalkEntry`@4636 `SetPidDefeatedFlag`@4636 |
| eventscr.c | `_WhileFace6CExists*`@4446 |
| face.c | `GetFaceDisplayBits`@4210 `GetPortraitData*`@4210 `FindFreeFaceSlot*`@4285 `EndFaceIn8Frames*`@4446 `EndFacePtr*`@4455 |
| fontgrp.c | `Text_SetColor*`@4211 `Text_SetCursor`@4211 `GetLang*`@4275 `InitSystemTextFont*`@4275 `ResetText*`@4275 `Text_GetCursor`@4432 |
| helpbox.c | `sub_808BB74*`@4446 |
| icon.c | `LoadIconPalettes*`@4210 `ResetIconGraphics_*`@4241 |
| mapanim.c | `MapAnim_PrepareBattleTalk`@4275 `MapAnim_MoveCameraOntoSubject`@4276 `MapAnim_CallBattleQuoteEvents`@4278 `SetBattleMuPalette`@4467 `SetBattleMuPaletteByIndex`@4467 `MapAnim_InitInfoBox`@4468 `MapAnim_AdvanceBattleRound`@4484 `MapAnim_PrepareNextBattleRound`@4484 `MapAnim_DisplayRoundAnim`@4485 `MapAnim_ShowPoisonEffectIfAny`@4537 `MapAnim_DisplayDeathQuote`@4636 `MapAnim_DisplayDeathFade`@4742 `MapAnim_StoleItemPopup`@4775 `MapAnim_DisplayExpBar`@4776 `BattleUnit_ShouldDisplayWpnBroke`@4880 `DisplayWpnBrokePopup`@4880 `BattleUnit_ShouldDisplayWRankUp`@4888 `DisplayWRankUpPopup`@4888 `MapAnim_Cleanup`@4891 |
| mapanim_api.c | `BeginBattleMapAnims`@4274 `MakeBattleMOVEUNIT`@4274 `SetupMapAnimSpellData`@4274 `SetupMapBattleAnim`@4274 `SetBattleAnimFacing`@4275 `SetupBattleMOVEUNITs`@4275 `sub_807B4D0`@4275 |
| mapanim_expbar.c | `DrawMAExpBar`@4777 `ProcMAExpBar_FrameAdvance`@4777 `ProcMAExpBar_InitDisplay`@4777 `ProcMAExpBar_OnDraw`@4777 `ProcMAExpBar_PlaySound74`@4803 `ProcMAExpBar_OnIncrement`@4805 `ProcMAExpBar_LevelUpIfPossible`@4878 |
| mapanim_infobox.c | `StartMapAnimInfoWindow`@4468 `DisplayBattleInfoBox`@4470 `GetBattleInfoPalByFaction`@4470 `MapInfoBoxShake`@4470 `MapInfoBox_PrepareForShake`@4470 `PrepareMapBattleBoxNumGfx`@4470 `ProcMapInfoBox_OnDraw`@4470 `UncompMapBattleBoxNumGfx`@4470 `sub_807BA28`@4470 `sub_807BB10`@4470 `sub_807BB40`@4470 `sub_807BD54`@4470 `sub_807BCA8`@4479 `EndMapAnimInfoWindow`@4636 `ProcMapInfoBox_OnEnd`@4636 |
| mapanim_scanline.c | `InitScanline`@4470 `InitScanlineBuf`@4470 `PrepareGradientScanlineBuf`@4470 `StartManimFrameGradientScanlineEffect`@4470 `SwapScanlineBufs`@4471 `sub_8081FFC`@4471 |
| mapanim_specialeffect.c | `MapAnim_BeginMISSAnim`@4550 |
| mapanim_spellassoc.c | `MapAnimMoveUnitTowardsTargetExt`@4485 `MapAnim_BeginSubjectFastAnim`@4485 `MapAnim_GetRoundProcScript`@4485 `MapAnim_MoveSubjectsTowardsTarget`@4485 `MapAnim_MoveCameraOnTarget`@4489 `MapAnim_BeginRoundSpecificAnims`@4491 `RegisterMapHpChangeAnim`@4491 `MapAnim_WaitForHPToEndChangingMaybe`@4499 `MapAnimMoveUnitAwayFromTargetExt`@4513 `MapAnim_MoveSubjectsAwayFromTarget`@4513 |
| msg.c | `StrInsertTact*`@4211 |
| mu.c | `Mu_OnStateDoNothing`@4130 `MuStepSe_Init`@4131 `MuStepSe_PlaySeA`@4131 `PlayMuStepSe*`@4131 `StartPlayMuStepSe*`@4131 `MuStepSe_PlaySeB`@4132 `EndMuMovement`@4179 `HaltMu*`@4179 `GetMuImgBufById*`@4274 `StartMuSpeedUpAnim`@4485 `StartMuHitFlash`@4491 `MuSlowDownAnimFreezeFunc`@4506 `MuFlashFadeFrom_RestorePal`@4508 `MU_StartDeathFade*`@4742 `MuDeathFade_OnLoop`@4742 `EndMuExt*`@4773 |
| scene.c | `IsTalkActive*`@4279 `StartTalkExt*`@4279 `GetTalkFaceHPos*`@4285 `TalkOpen_OnEnd*`@4308 `GetTalkChoiceResult*`@4445 `EndTalk*`@4446 `FaceExists*`@4446 |
| spellassoc.c | `GetSpellAssocCharCount`@4274 `GetSpellAssocMapAnimProcScript`@4274 `GetSpellAssocStructPtr`@4274 `GetSpellAssocFacing`@4275 `GetSpellAssocReturnBool`@4468 `GetSpellAssocFlashColor`@4491 |
| uimenu.c | `GetForceDisabledMenuItems*`@4272 |
| uiutils.c | `UnpackUiFrameBuffered*`@4270 `UnpackUiFramePalette`@4270 `UnpackUiFrameImage`@4461 |
| engine layer (80) | asm/arm_call.c 5, hardware.c 10, irq.c 1, libagbsyscall.c 7, m4a.c 5, m4a_1.c 9, proc.c 29, ramfunc.c 3, tools/agbcc/lib/libc.a(memcpy.c) 1, tools/agbcc/lib/libgcc.a(_call_via_rX.c) 7, tools/agbcc/lib/libgcc.a(_divsi3.c) 1, tools/agbcc/lib/libgcc.a(_modsi3.c) 1, tools/agbcc/lib/libgcc.a(_umodsi3.c) 1 |

**Window d: ending scene start f4894..5000**

| File | NEW functions |
|---|---|
| banim-ekrbattle.c | `IsBattleDeamonActive*`@4895 |
| bm.c | `OnMain*`@4894 `OnVBlank*`@4894 |
| bmio.c | `WfxUpdate*`@4894 `WfxVSync*`@4894 `GetTextDisplaySpeed*`@4933 |
| bmlib.c | `Register2dChrMove*`@4905 `Decompress*`@4913 `GetDataSize*`@4939 `SetBlackPal`@4939 `StartTemporaryLock*`@4939 `TemporaryLock_OnLoop`@4939 `UnpackRaw*`@4939 |
| bmmap.c | `RefreshEntityBmMaps*`@4894 `RefreshMinesOnBmMap*`@4894 `RefreshTorchlightsOnBmMap*`@4894 |
| bmsave-lib.c | `UnlockSoundRoomSong*`@4945 |
| bmtrap.c | `GetPickTrapType*`@4894 |
| bmtrick.c | `GetTrap*`@4894 `GetTrapAt*`@4894 |
| bmunit.c | `GetUnitCurrentHp`@4894 `GetUnitSMSId*`@4894 `RefreshAllies*`@4894 `SetUnitStatus*`@4894 |
| bmxfade.c | `DoesBMXFADEExist*`@4894 |
| cgtext.c | `EndCgText*`@4912 |
| event.c | `EventEngine_CanStartSkip*`@4894 `_FreezeMenu*`@4894 |
| eventinfo.c | `CheckTutorialEvent*`@4894 `ClearActiveEventRegistry*`@4894 `RegisterEventActivation*`@4894 `RunTutorialEvent*`@4894 |
| eventscr_utils.c | `ChangeUnitSpritePalette*`@4912 |
| face.c | `FindFreeFaceSlot*`@4939 `GetFaceDisplayBits`@4939 `GetPortraitData*`@4939 |
| fontgrp.c | `Text_SetColor*`@4912 `GetLang*`@4933 `InitSystemTextFont*`@4933 |
| helpbox.c | `sub_808BB74*`@4912 |
| mu.c | `Mu_OnStateDoNothing`@4894 `LockMus*`@4912 |
| scene.c | `EndTalk*`@4912 `IsTalkActive*`@4933 `StartTalkExt*`@4933 `GetTalkFaceHPos*`@4939 `TalkOpen_OnEnd*`@4962 |
| soundwrapper.c | `DeleteAll6CWaitMusicRelated*`@4894 `GetCurrentBgmSong*`@4894 `PlaySong*`@4945 |
| uimenu.c | `FreezeMenu*`@4894 |
| engine layer (61) | asm/arm_call.c 2, hardware.c 9, libagbsyscall.c 6, m4a.c 5, m4a_1.c 6, proc.c 21, ramfunc.c 3, tools/agbcc/lib/libc.a(memcpy.c) 1, tools/agbcc/lib/libc.a(memset.c) 1, tools/agbcc/lib/libgcc.a(_call_via_rX.c) 5, tools/agbcc/lib/libgcc.a(_modsi3.c) 1, tools/agbcc/lib/libgcc.a(_umodsi3.c) 1 |

Ported and Transcribed functions the windows enter (union a..d, by file; engine-layer files left out):

| File | Ported | Transcribed |
|---|---|---|
| agb_sram.c | `WriteAndVerifySramFast` `WriteSramFast` |  |
| ap.c |  | `AP_Create` `AP_Delete` `AP_Display` `AP_ExecDummyFrame` `AP_ExecFrame` `AP_Find` `AP_Init` `AP_LoadDefinition` `AP_QueueObjGraphics` `AP_QueueObjRotScale` `AP_SwitchAnimation` `AP_Update` |
| bksel.c | `BattleForecastHitCountUpdate` `BattleForecast_Init` `BattleForecast_LoopDisplay` `BattleForecast_LoopSlideIn` `BattleForecast_OnNewBattle` `CloseBattleForecast` `DrawBattleForecastContents` `DrawBattleForecastContentsStandard` `GetBattleForecastPanelSide` `GetFactionBattleForecastFramePalette` `InitBattleForecastBattleStats` `InitBattleForecastFramePalettes` `InitBattleForecastIconPaletteBuffer` `InitBattleForecastLabels` `MapEventEngineExists_` `NewBattleForecast` `PutBattleForecastItemName` `PutBattleForecastMultipliers` `PutBattleForecastUnitName` `PutBattleForecastWeaponTriangleArrows` `UpdateBattleForecastEffectivenessPalettes` | `BattleForecast_OnEnd` `TriggerBattleForcastToturialEvent` `UpdateBattleForecastContents` |
| bm.c | `BmMain_ChangePhase` `BmMain_StartPhase` `BmMain_SuspendBeforePhase` `BmMain_UpdateTraps` `EnsureCameraOntoPosition` `GetCameraAdjustedX` `GetCameraAdjustedY` `GetCurrentMapMusicIndex` `GetGameLock` `HandleMapCursorInput` `HandleMoveCameraWithMapCursor` `HandleMoveMapCursor` `InitBmBgLayers` `LoadObjUIGfx` `LockGame` `PutMapCursor` `SetCursorMapPosition` `StartMapSongBgm` `SwitchPhases` `UnlockGame` `UpdateStatArrowSprites` `sub_8015434` |  |
| bmbattle.c | `BattleApplyExpGains` `BattleApplyGameStateUpdates` `BattleApplyUnitUpdates` `BattleApplyWeaponTriangleEffect` `BattleCheckBraveEffect` `BattleCheckGreatShield` `BattleCheckPetrify` `BattleCheckPierce` `BattleCheckSureShot` `BattleGenerate` `BattleGenerateHit` `BattleGenerateHitAttributes` `BattleGenerateHitEffects` `BattleGenerateReal` `BattleGenerateRealInternal` `BattleGenerateRoundHits` `BattleGenerateSimulation` `BattleGenerateSimulationInternal` `BattleGenerateUiStats` `BattleGetBattleUnitOrder` `BattleGetFollowUpOrder` `BattleRoll1RN` `BattleRoll2RN` `BattleUnitTargetCheckCanCounter` `BattleUnitTargetSetEquippedWeapon` `BattleUnwind` `BattleUpdateBattleStats` `CanBattleUnitGainLevels` `ClearBattleHits` `ComputeBattleUnitAttack` `ComputeBattleUnitAvoidRate` `ComputeBattleUnitCritRate` `ComputeBattleUnitDefense` `ComputeBattleUnitDodgeRate` `ComputeBattleUnitEffectiveCritRate` `ComputeBattleUnitEffectiveHitRate` `ComputeBattleUnitEffectiveStats` `ComputeBattleUnitHitRate` `ComputeBattleUnitSilencerRate` `ComputeBattleUnitSpeed` `ComputeBattleUnitStats` `ComputeBattleUnitSupportBonuses` `GetBattleUnitExpGain` `GetBattleUnitHitCount` `GetBattleUnitUpdatedWeaponExp` `GetUnitClassKillExpBonus` `GetUnitExpLevel` `GetUnitKillExpBonus` `GetUnitPowerLevel` `GetUnitRoundExp` `InitBattleUnit` `ModifyUnitSpecialExp` `SetBattleUnitTerrainBonusesAuto` `SetBattleUnitWeapon` `UpdateUnitFromBattle` | `BattleApplyBallistaUpdates` `BattleGenerateHitTriangleAttack` `BattleInitTargetCanCounter` `CheckBattleUnitLevelUp` `ComputeBattleUnitSpecialWeaponStats` `ComputeBattleUnitStatusBonuses` `ComputeBattleUnitWeaponRankBonuses` `GetUnitExpMultiplier` |
| bmcamadjust.c | `ProcFun_ResetCursorPosition` | `GetEnemyStartCursorPosition` |
| bmidoten.c | `GenerateMovementMap` `GenerateUnitStandingReachRange` `MapAddInBoundedRange` `MapAddInRange` `SetWorkingBmMap` `SetWorkingMoveCosts` | `GenerateBestMovementScript` `UnitApplyWorkingMovementScript` |
| bmio.c | `BMapDispSuspend` `BMapVSync_OnLoop` `BMapVSync_UpdateMapImgAnimations` | `BMapVSync_UpdateMapPalAnimations` |
| bmitem.c | `CanUnitUseWeaponNow` `DrawItemMenuLine` `EquipUnitItemSlot` `GetItemAfterUse` `GetItemAttributes` `GetItemCrit` `GetItemHit` `GetItemIconId` `GetItemMaxUses` `GetItemMight` `GetItemMinRange` `GetItemName` `GetItemReachBits` `GetItemType` `GetItemUses` `GetUnitWeaponReachBits` | `CanUnitUseStaff` `CanUnitUseWeapon` `GetItemDefBonus` `GetItemHpBonus` `GetItemIndex` `GetItemLckBonus` `GetItemMaxRange` `GetItemPowBonus` `GetItemResBonus` `GetItemSklBonus` `GetItemSpdBonus` `GetItemWeaponEffect` `GetItemWeight` `GetUnitEquippedWeapon` `GetUnitEquippedWeaponSlot` |
| bmlib.c | `Copy2dChr` `FadeFromBlack_OnInit` `FadeToBlack_OnInit` `FadeToCommon_OnLoop` `Interpolate` `StartLockingFadeFromBlack` `StartLockingFadeToBlack` `StringCompare` | `FadeFromCommon_OnLoop` `PalFade_OnLoop` `StartPalFade` |
| bmmap.c | `BmMapFill` `DisplayBmTile` `DisplayMovementViewTile` `RefreshUnitsOnBmMap` `RenderBmMap` `UnpackChapterMapPalette` `UpdateBmMapDisplay` | `RenderBmMapOnBg2` |
| bmmenu.c | `AttackCommandUsability` `AttackMapSelect_End` `AttackMapSelect_Select` `AttackMapSelect_SwitchIn` `BallistaRangeMenu_SwitchOut` `DisplayUnitStandingAttackRange` `HideMoveRangeGraphicsWrapper` `ItemCommandUsability` `StartUnitWeaponSelect` `UnitActionMenu_Attack` `WeaponSelectMenu_IsAvailable` `WeaponSelectMenu_Selected` `WeaponSelectMenu_SwitchIn` | `WeaponSelectMenu_Draw` |
| bmmind.c | `ActionCombat` `ApplyUnitAction` `BATTLE_GOTO1_IfNobodyIsDead` `BATTLE_HandleCombatDeaths` `BATTLE_HandleItemDrop` `KillUnitOnCombatDeath` `StoreRNStateToActionStruct` | `DropRescueOnDeath` |
| bmpatharrowdisp.c | `GetMovementScriptFromPath` |  |
| bmphase.c | `AreUnitsAllied` | `GetPhaseAbleUnitCount` `IsSameAllegiance` |
| bmreliance.c | `GetUnitSupporterCount` | `GetUnitSupportBonuses` |
| bmsave-bwl.c | `WriteChapterStats` `WritePidStats` |  |
| bmsave-lib.c | `Checksum16` `GetSaveWriteAddr` `IsSramWorking` `LoadAndVerifySoundRoomData` `ReadGlobalSaveInfo` `WriteChapterFlags` `WritePermanentFlags` `WriteSaveBlockInfo` | `WriteSupplyItems` |
| bmsave.c | `EncodeSuspendSavePackedUnit` `GetLastSuspendSaveId` `GetNextSuspendSaveId` `PopulateSaveBlockChecksum` `SramChecksum32` `WriteSuspendSave` `WriteSwappedSuspendSaveId` | `WriteTraps` |
| bmtarget.c | `AddUnitToTargetListIfNotAllied` `ForEachUnitInRange` `MakeTargetListForWeapon` `TryAddTrapsToTargetList` |  |
| bmtrap.c | `HandlePostActionTraps` |  |
| bmudisp.c | `AddUnitSprite` `ApplyUnitSpritePalettes` `GetUnitSpritePalette` `PutUnitSpriteIconsOam` `PutUnitSpritesOam` `RefreshUnitSprites` `UseUnitSprite` | `GetUnitDisplayedSpritePalette` `GetUnitSpriteHideFlag` `HideUnitSprite` `PutChapterMarkedTileIconOam` `ShowUnitSprite` `SyncUnitSpriteSheet` |
| bmunit.c | `ClearActiveFactionGrayedStates` `ClearCutsceneUnits` `CountAvailableBlueUnits` `GetCharacterData` `GetClassData` `GetUnit` `GetUnitFromCharId` `GetUnitMovementCost` `GetUnitPortraitId` `MoveActiveUnit` `UnitBeginAction` `UnitKill` | `GetUnitDefense` `GetUnitItemCount` `GetUnitLuck` `GetUnitMaxHp` `GetUnitPower` `GetUnitResistance` `GetUnitSkill` `GetUnitSpeed` `SetAllUnitNotBackSprite` `TickActiveFactionTurn` `UnitCheckStatCaps` `UnitFinalizeMovement` `UnitRemoveInvalidItems` |
| bmusailment.c | `GorgonEggHatchDisplay_Init` `PoisonDamageDisplay_Init` `StatusDecayDisplay_Init` `TerrainHealDisplay_Init` |  |
| chapterdata.c | `GetChapterEventDataPointer` `GetROMChapterStruct` |  |
| cp_0803E2F4.c | `AiFillDangerMap` `AiInitDangerMap` `AiUpdateGetUnitIsHealing` `UpdateAllPhaseHealingAIStatus` |  |
| cp_battle.c | `AiAttemptOffensiveAction` |  |
| cp_decide.c | `AiClearDecision` `AiDecideMain` `AiSetDecision` `CpDecide_Main` `CpDecide_Suspend` | `DecideHealOrEscape` `DecideScriptA` `DecideScriptB` |
| cp_order.c | `BuildAiUnitList` `CpOrderBerserkInit` `CpOrderFunc_BeginDecide` `CpOrderFunc_End` `CpOrderMain` | `GetUnitAiPriority` `GetUnitBattleAiPriority` `SortAiUnitList` |
| cp_phase.c | `AiPhaseBerserkInit` `AiPhaseCleanup` `AiPhaseInit` |  |
| cp_script.c | `AiScriptCmd_05_DoStandardAction` `AiScript_Exec` `AiTryExecScriptA` `AiTryExecScriptB` | `AiIsUnitEnemy` |
| cp_staff.c | `AiTryDoStaff` |  |
| cp_utility.c | `SaveNumberOfAlliedUnitsIn0To8Range` `SetupUnitInventoryAIFlags` | `SetupUnitHealStaffAIFlags` `SetupUnitStatusStaffAIFlags` |
| cpextra_80407F0.c | `AiUpdateNoMoveFlag` |  |
| ctc.c | `ClearSprites` `PushSpriteLayerObjects` `PutSpriteExt` | `PutSprite` |
| event.c | `EventEngine_Create` `EventEngine_OnUpdate` | `BattleEventEngineExists` `CallEvent` `CallNextQueuedEvent` `EventEngineExists` `EventEngine_CreateBattle` `EventEngine_OnEnd` `GetEventSlotCounter` |
| eventinfo.c | `CheckForWaitEvents` `EvCheck00_Always` `EvCheck01_AFEV` `EvCheck02_TURN` `GetChapterFlagBits` `GetChapterFlagBitsSize` `GetPermanentFlagBits` `GetPermanentFlagBitsSize` `RunPhaseSwitchEvents` `RunWaitEvents` `SearchAvailableEvent` `SetFlag` `SetPermanentFlag` `StartEventFromInfo` | `CheckChapterFlag` `CheckFlag` `CheckPermanentFlag` `CheckPermanentFlagFrom` `ClearChapterFlag` `ClearFlag` `SearchNextAvailableEvent` `SetChapterFlag` `sub_80832C4` `sub_80832C8` |
| eventscr.c | `Event01_End` `Event02_EvBitAndIdMod` `Event05_SetSlot` `Event08_Label` `Event09_Goto` `Event0A_Call` `Event0C_Branch` `Event12_StartBGM` `Event1A_TEXTSTART` `Event1B_TEXTSHOW` `Event1D_WaitForLockTalk` `Event21_TextBg` `Event23_DisaleMapDisp` `EventShowTextBgDirect` `sub_800E210` `sub_800E640` | `Event03_CheckEvBitOrId` `Event10_ModifyEvBit` `Event17_Fade` `Event19_Checks` |
| face.c | `EndFace` `EndFaceById` `FaceBlinkProc_GenBlinkInterval` `FaceRefreshSprite` `Face_OnIdle` `ResetFaces` `SetFaceBlinkControl` `SetFaceBlinkControlById` `StartFace` `StartFaceFadeIn` `StartFaceFadeOut` `sub_8005FD4` `sub_8005FE0` `sub_800623C` `sub_8006280` `sub_80062B8` | `Face_OnInit` `SetFaceDisplayBits` `SetupFaceGfxData` `StartFaceAuto` `sub_8006134` |
| fontgrp.c | `AddSpecialChar` `ClearText` `DrawSpecialCharGlyph` `DrawTextGlyph` `GetCharTextLen` `GetCharTextLenASCII` `GetColorLut` `GetSpecialCharChr` `GetStringTextCenteredPos` `GetStringTextLen` `GetStringTextLenASCII` `GetTextDrawDest` `GreenText_OnLoop` `InitTalkTextFont` `InitText` `InitTextDb` `InitTextFont` `PutDrawText` `PutNumber` `PutNumberExt` `PutNumberOrBlank` `PutNumberTwoChr` `PutSpecialChar` `PutText` `PutTwoSpecialChar` `SetTextFont` `SetTextFontGlyphs` `StartGreenText` `Text_DrawCharacter` `Text_DrawCharacterAscii` `Text_DrawNumber` `Text_DrawNumberOrBlank` `Text_DrawString` `Text_DrawStringASCII` `Text_InsertDrawNumberOrBlank` `Text_InsertDrawString` `Text_SetParams` | `ResetTextFont` |
| hardware.c | `BG_EnableSyncByMask` `BG_Fill` `BG_GetMapBuffer` `BG_SetPosition` `EnablePaletteSync` `FlushBackgrounds` `FlushLCDControl` `GetBackgroundTileDataOffset` `SetBackgroundTileDataOffset` `SetBlendConfig` `SetPrimaryHBlankHandler` `SyncHiOam` `UpdateHBlankHandlerState` `UpdateKeyStatus` `_UpdateKeyStatus` | `SetBlendBackdropA` `SetBlendBackdropB` `SetObjAffine` `SetSecondaryHBlankHandler` `SyncLoOam` |
| icon.c | `DrawIcon` `GetIconGfxIndex` `GetIconTileIndex` `LoadIconPalette` `ResetIconGraphics` | `GetIconGfxTileIndex` |
| m4a.c | `CgbModVol` `CgbSound` `FadeOutBody` `MPlayFadeOut` `MPlayStart` `MidiKeyToCgbFreq` `MidiKeyToFreq` `TrkVolPitSet` `m4aMPlayImmInit` `m4aMPlayStop` `m4aSongNumStart` `m4aSongNumStop` `m4aSoundMode` |  |
| m4a_1.c | `ChnVolSetAsm` `MPlayMain` `RealClearChain` `TrackStop` `m4aSoundVSync` `ply_endtie` `ply_fine` `ply_note` `ply_pan` `ply_pend` `ply_tempo` `ply_voice` `ply_vol` | `ply_keysh` |
| mapanim_api.c | `GetFacingDirection` |  |
| menuitempanel.c | `ForceMenuItemPanel` `MenuItemPanelProcIdle` `UpdateMenuItemPanel` |  |
| msg.c | `GetStringFromIndex` `SetMsgTerminator` |  |
| mu.c | `EndMu` `GetDefaultMuConfig` `GetMuAnimForJid` `GetMuDisplayPosition` `GetMuImg` `GetMuQ4MovementSpeed` `MU_OnEnd` `MuExistsActive` `Mu_OnLoop` `Mu_OnStateMovement` `PutMu` `RunMuMoveScript` `SetAutoMuMoveScript` `SetMuMoveScript` `StartMu` `StartMuInternal` | `EndAllMus` `ResetMuAnims` `SetMuDefaultFacing` `SetMuFacing` `UpdateMuStepSounds` |
| phasechangefx.c | `PhaseIntroBlendBox_InLoop` `PhaseIntroBlendBox_Init` `PhaseIntroBlendBox_OutLoop` `PhaseIntroClearText` `PhaseIntroSquares_InLoop` `PhaseIntroSquares_Init` `PhaseIntroSquares_OutLoop` `PhaseIntroText_InLoop` `PhaseIntroText_OutLoop` `PhaseIntroText_PutText` `PhaseIntroUnk_Init` `PhaseIntroUnk_Loop` `PhaseIntro_EndIfNoUnits` `PhaseIntro_InitDisp` `PhaseIntro_InitGraphics` `PhaseIntro_WaitForEnd` | `PhaseIntroInitText` `PhaseIntroVMatchHi` |
| playerphase.c | `CanMoveActiveUnitTo` `DisplayMoveRangeGraphics` `GetPlayerSelectKind` `HandlePlayerCursorMovement` `HideMoveRangeGraphics` `MoveLimitViewChange_OnInit` `MoveLimitViewChange_OnLoop` `MoveLimitView_OnEnd` `MoveLimitView_OnInit` `MoveLimitView_OnLoop` `PlayerPhase_ApplyUnitMovement` `PlayerPhase_DisplayUnitMovement` `PlayerPhase_PrepareAction` `PlayerPhase_RangeDisplayIdle` `PlayerPhase_WaitForUnitMovement` `RunPotentialWaitEvents` |  |
| proc.c | `Proc_BlockEachMarked` `Proc_Break` `Proc_End` `Proc_Find` `Proc_ForEach` `Proc_Goto` `Proc_Run` `Proc_Start` `Proc_StartBlocking` | `Proc_SetEndCb` |
| rng.c | `AdvanceGetLCGRNValue` `NextRN` | `NextRN_100` `NextRN_N` `Roll1RN` `Roll2RN` `StoreRNState` |
| scene.c | `ClearPutTalkText` `ClearTalkBubble` `ClearTalkFaceRefs` `InitTalk` `IsTalkFaceMoving` `PutTalkBubble` `PutTalkBubbleTail` `PutTalkBubbleTm` `SetInitTalkTextFont` `SetTalkFaceDisp` `SetTalkFaceLayer` `StartOpenTalkBubble` `StartTalkMsg` `StartTalkOpen` `StartTalkWaitForInput` `TalkBubbleOpen_OnIdle` `TalkHasCorrectBubble` `TalkInterpret` `TalkLoadFace` `TalkOpen_OnIdle` `TalkOpen_PutTalkBubble` `TalkPrepNextChar` `TalkShiftClear_OnIdle` `TalkShiftClear_OnInit` `TalkWaitForInput_OnIdle` `Talk_OnEnd` `Talk_OnIdle` `Talk_OnInit` | `CheckTalkFlag` `GetStrTalkLen` `InitTalkTextWin` `IsTalkLocked` `SetActiveTalkFace` `SetTalkFaceMouthMove` `SetTalkFaceNoMouthMove` `TalkBgSync` `TalkOpen_InitBlend` `TalkSkipListener_OnIdle` |
| soundwrapper.c | `DelaySong_OnLoop` `Sound_FadeOutBGM` `StartBgmExt` `StartSongDelayed` `sub_80028FC` | `StartOrChangeBgm` |
| time.c | `CheckCanKeyComboReset` `GetGameClock` `IncrementGameClock` |  |
| uidebug.c | `AreAnyEnemyUnitDead` |  |
| uimenu.c | `ApplyMenuCursorVScroll` `DrawMenuItemHover` `EndMenu` `GetMenuCursorPosition` `MenuAlwaysEnabled` `Menu_OnIdle` `Menu_OnInit` `ProcessMenuDpadInput` `ProcessMenuSelectInput` `RedrawMenu` `StartMenuAt` `StartMenuCore` `StartOrphanMenu` `StartOrphanMenuAdjusted` `StartSemiCenteredOrphanMenu` | `OverriddenMenuAvailability` `OverriddenMenuSelected` |
| uiselecttarget.c | `AddTarget` `EndTargetSelection` `GetFurthestTargetDistance` `GetLinkedTargets` `GetLinkedTargetsNear` `GetSelectTargetCount` `InitTargets` `NewTargetSelection` `TargetSelection_GetRealCursorPosition` `TargetSelection_HandleSelectInput` `TargetSelection_Loop` | `TargetSelection_HandleMoveInput` |
| uiutils.c | `ClearBg0Bg1` `ClearUiFrame` `DisplayUiHand` `DrawUiFrame` `DrawUiFrame2` `DrawUiItemHoverExt` `LoadUiFrameGraphics` |  |
| unit_facing.c | `ChangeActiveUnitFacing` |  |
| worldmap_path.c |  | `GetBattleMapKind` |

Corrected and new PORT rows (all in `docs/TASKS.md`; every NEW function of the table above is in exactly one row; checked by
script against the trace):

| Window | Rows |
|---|---|
| a: boss turn event | EP-11a (opcode 39, event start), EP-11b..h (talk, face, text, UI restore, BGM, polls), EP-27 (`PidStatsAddFavval`) |
| b: move-only phase | EP-7d, EP-7c (movement commands and helpers), EP-7/EP-8/EP-9 (windows added), EP-8b, EP-8c (Mu), EP-9b (equip), EP-9c (fade, map refresh), EP-1b (display targets), EP-3c, EP-3d (suspend save), EP-10a..c (traps, counts, support exp), D12-2 (banner per scanline) |
| c: boss battle | EP-13..EP-16 (setup, banim queries, spell lookups), EP-17..EP-19 (flow, popups), EP-20..EP-22 (info box, scanline), EP-23..EP-25 (round effects, miss, Mu effects), EP-26 (exp bar), EP-27 (stats), EP-28 (quote and defeat events), EP-29..EP-36 (unit-menu build and battle helpers); `C14-ma` is now the umbrella that re-verifies EP-13..26 on MC-cap1/2 |
| d: ending scene start | PC-1 (corrected: wait-event path), PC-2 (opcode list), PC-2a (3 functions), EP-11b..h and EP-29 (shared plumbing) |

Counts of NEW functions per row group: 336 distinct NEW game-logic functions (union of a..d) = 4 main-loop functions without a
row + 332 in rows. Engine-layer functions: 90, no rows.

## M6-E Prologue completable

Exit: win → ending event → save → Chapter 1 map; game over works.

Observed chain (EP-0b): boss death → wait-event path (`RunPotentialWaitEvents` / `RunWaitEvents`)
→ ending scene script (victory
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

### BA-0 result

`BattleAnimationWindowInventoryTest` (procs watch spans plus EWRAM dumps; steps are 1-based). Last-alive steps:

| | `ram_trace_prologue_attack` (Eirika/Soldier) | `ram_trace_prologue_battle` (scripted Seth/Valter) |
|---|---|---|
| Map -> battle start | `ProcScr_ekrBattleStarting` f1464..1495 (window start) | `ProcScr_ScriptBattleDeamon` f14430, `ekrBattleStarting` f14431..14466 |
| Furniture appears | `ekrGauge`/`ekrDispUP` f1480, window/base/kakudai f1482 | f14448 / f14450..14451 |
| Rounds begin | `gProc_ekrBattle` f1496..1988; `efxWeaponIcon`/`efxHPBarColorChange` f1505..1984 | `ekrBattle` f14467..15034; icon procs f14648..15030 |
| Rounds | 3 hits: `efxHPBar` f1577..1611, f1659..1692, f1784..1818 | round 1 miss (`EfxAvoid` f14730..14763, `efxDamageMojiEffect`, `ekrsubAnimeEmulator`); round 2 hit `efxHPBar` f14890..14926 |
| HP | `gEkrGaugeHp` low half 0x16->0xf (f1579..1592), high half 0x10->7 (f1661..1677), low 0xf->8 (f1786..1799); `gBanimDoneFlag` f1819 | `gEkrGaugeHp` high half 0x1e->0xd (f14892..14924); `gBanimDoneFlag` f14995 |
| Battle -> map | `ekrBattleEnding` f1989, `gEkrBattleEndFlag` f1989, `sProc_DelayedBMapDispResume` f2010 | `ekrBattleEnding` + `gEkrBattleEndFlag` f15035 (trace runs to f15059) |

`gBattleActor`/`gBattleTarget` curHP hold the precomputed final HP for the whole window (7/8 and 13/34), so the
per-round value comes only from `gEkrGaugeHp`. The scripted window also runs `ProcScr_BattleEventEngine`
(`gProcScr_Talk` quote, f14479..14630) inside `ekrBattle`; no `efx*Death`/`lvup`/exp proc runs in either window.

Proc groups to rows: BA-i1 `ekrBattleStarting`, `ekrUnitKakudai`, `ekrBaseAppear`, `EkrBaseKaiten`,
`ekrWindowAppear`, `ekrBattleDeamon`, `ScriptBattleDeamon`; BA-i2/BA-i3 `ekrBattle`, `ekrsubAnimeEmulator`,
`ekrChienCHR`; BA-i4 `efxHPBar`, `efxStatusUnit`; BA-i5 `ekrGauge`, `ekrDispUP`, `ekrNamewinAppear`,
`efxWeaponIcon`, `efxHPBarColorChange`; BA-i6 `EfxHitQuake`, `efxQuake`, `efxFlashUnit`, `efxFlashHPBar`,
`efxFlashBG`, `efxNormalEffect(BG)`, `efxDamageMojiEffect(OBJ)`, `EfxAvoid` (sound: no proc; needs the PSG/audio
data); BA-i8 `ekrBattleEnding`, `DelayedBMapDispResume`. BA-i7: no proc observed, so the exp bar is not in these
windows (BA-cap1 or a new CAP must supply it).

Gaps for BA-cap1: no per-step LCD/palette/OAM/VRAM (full dumps only every 10th step, `r` dumps are EWRAM + IWRAM
only); no function-entry trace, so function lists and branches are still unknown; the anim script commands of
`ekrsubAnimeEmulator` (`ekrcmd`) are not logged; no audio/PSG writes; the hit array contents beyond
`gBattleHitIterator` and the OAM/BG/transition buffers (map -> battle fade) are not isolated.

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

### MC-3b result

`WeaponSelectMenuDifferentialTest` now also runs A on the weapon (frame 1659 = dump 1660, noanim) and B in the
weapon list (frame 1729 = dump 1730). The choice is compared on the active unit's whole struct (item order after
`EquipUnitItemSlot`), `itemSlotIndex`, BG0/BG1 (`ClearBg0Bg1`), the range squares that stay, and the menu's end.
B is seeded from the dump of f1703 and the weapon list is started at the frame the dump has it (the cancel proc
`gProcScr_0859B630` is MC-6); it is compared with the restarted unit menu, the BG2 clear and the hidden range.
Ported and break-checked: `WeaponSelectMenu_Selected`, `EquipUnitItemSlot`, `ClearBg0Bg1`,
`BallistaRangeMenu_SwitchOut` (the ending-menu branch; the hide branch is not verified), `ItemMenu_ButtonBPressed`.
`sub_80832C8` stays transcribed (returns 0). Findings: from f1631 the dump k + 1 is the game's frame k and so are
its inputs (`inputs[k]`); the menu x of the restart is clamped in the capture, so the cursor x is not
distinguished; `ResetTextFont` is a hook (no port). Stand-in until MC-5: `NewTargetSelection`; on a device the
weapon is equipped and the player is back in the unit menu (range hidden).

### MC-4a result

`WeaponSelectMenuDifferentialTest` now draws the weapon rows with the ported `DrawItemMenuLine` and compares BG0
without the row exclusion (only the item panel's box stays excluded), the text tiles of VRAM (the unit menu and
weapon strips and the special characters, in the `f` dumps 1640, 1650, 1660, 1710, 1720, 1730), the icon tiles, and
`DrawnIconLookupTable`/`IconGFXIDLookupTable` at f1630 and f1657. The f1703 restart is run too (the dump resets the
icons there; the test does that for the unported proc). Ported and break-checked: `DrawItemMenuLine`, `GetItemUses`,
`DrawIcon`, `GetIconTileIndex`, `GetIconGfxIndex`, `Text_SetParams`, `PutSpecialChar`, `PutNumberExt`, `PutNumber`,
`PutNumberOrBlank`, `GetSpecialCharChr`, `AddSpecialChar`, `DrawSpecialCharGlyph` (new in `TextSystem`);
`GetItemName`, `Text_DrawString` and `PutText` ran already. Transcribed: `ResetTextFont` (the window starts after its
caller; wired to `ResetTextFont` of `PlayerPhase_ApplyUnitMovement` and of the B cancel). Not verified: the grey
row (the list shows only usable rows), `PutNumberOrBlank`'s dash branch, unbreakable uses. Findings:
`DrawnIconLookupTable` entries are 4 bytes (`References`, `Index`, 2 unused; the table is 0x380 bytes), not 2 as
`ResetIconGraphics` assumed; the rows draw on the menu's first frame, after the item panel's `InitText` calls (72 tile
slots), so the test advances `chr_counter` by 72 in the panel hook and skips those slots and the panel's icon 112
(MC-4b).

### MC-4b result

`WeaponSelectMenuDifferentialTest` now runs the real item panel and face. Added to the compare: the panel proc (unit,
x, y, icon palette, weapon slot, `draw_arrow`, its six `struct Text`s), `gBattleActor` and `gBattleTarget` (all 128
bytes), BG0/BG1 without the panel box exclusion, all text tiles of VRAM (the panel's strips included; the 72-slot
chr-counter workaround is gone) and icon 112; the face proc (display bits, x, y, `oam2`, portrait, slot, layer), its
blink child (control, interval, script position), `gLCGRNValue`, the face's upper 12x6 tiles and its OBJ palette
(from the dump after the start frame). Ported and break-checked (one mutation each, all fail): `ForceMenuItemPanel`,
`UpdateMenuItemPanel`, `MenuItemPanelProcIdle` (a `>=` draws arrows with equal stats; the dump has none), `BattleGenerateUiStats`
(two mutations: the no-weapon dashes, the equip of the slot), `Text_DrawNumber`, `Text_InsertDrawNumberOrBlank`,
`Text_DrawNumberOrBlank`, `GetUnitPortraitId`, `StartFace` and `SetFaceBlinkControl` (both were Transcribed),
`SetFaceBlinkControlById`, `EndFace`, `EndFaceById`. Stand-in: the panel's staff/item description layout
(`UpdateMenuItemPanel` for a staff or item; the weapon list never shows one). Not verified inside ported ones: the
stat arrows, the dash branch of `Text_DrawNumberOrBlank`, the world map terrain of `BattleGenerateUiStats`, the
Lyon portrait and class-default branches, `StartFace` flags 12 and 13. `EndMenuItemPanel` is not written (never runs).
Findings: the dump of f1706 repeats f1705 (a frame that outran a VBlank); the face tree (root 5) did not run in it, so
the cancel window skips tree 5 at f1705; the OBJ palette reaches the dump a VBlank after the start frame; face tile
rows 6 and 7 of the sheet change in the cancel window's dumps (other sprites' tiles), so only rows 0 to 5 are compared;
`IsUnitEffectiveAgainst` reads the target class, which `BattleGenerateUiStats` clears (the original reads BIOS bytes;
now it returns false for a null class). Wired in `PlayerPhaseWiring`: the face on slot 0 of the talk scene's faces,
the panel with the forecast's arrow sprites, `EndFaceById` on the menu's `ENDFACE`. Sprite OAM of the face is not
compared.

### MC-5 result

`TargetSelectionDifferentialTest` replays `ram_trace_prologue_attack_noanim` twice: the selection started by A on
the weapon (game frame 1659, dump 1660) with B at 1699 (the cancel), and the one started at 1789 with A at 1819
(confirm). Seeded from the dump of the start frame (units after the equip), the list made by the ported
`MakeTargetListForWeapon`, then per frame: the proc (existence, flags, current target), the `gBmSt` camera and cursor
fields, the game lock, `sLastCoordMapCursorDrawn`/`sLastTimeMapCursorDrawn` (the `PutMapCursor` kind 2 draw, newly
observed) and the target list with its links. Ported and break-checked: `NewTargetSelection`, `TargetSelection_Loop`,
`TargetSelection_HandleSelectInput`, `TargetSelection_GetRealCursorPosition`, `EndTargetSelection`. Transcribed:
`TargetSelection_HandleMoveInput` (no capture presses the d-pad and the list has one target, so no mutation changes
captured data), the frozen branch, the R branch, `keys.newKeys = 0` (the proc's first run is a frame after its start).
Not written: `NewTargetSelection_Specialized`, `sub_804FBBC`, `sub_804FBDC`, `LinkTargets` stays as in MC-1.
Findings: the proc first runs (and puts the cursor) one frame after it starts; the dump 3 frames after each start
(f1662, f1792) shows the cursor time one frame old (mid-frame dump, the forecast's frame), not compared. The selection
callbacks stay stand-ins: until MC-6 `AttackMapSelect_*` are replaced by A and B ending the selection and reopening the
unit menu (range hidden); the forecast is MC-10. The camera never moves in the capture.

### MC-6 result

`TargetSelectionDifferentialTest` now runs the real `AttackMapSelect` (the callbacks of `gSelectInfo_Attack`) on the two
MC-5 windows: B at game frame 1699 continued to 1703 (the cancel proc and the weapon list restart) and A at 1819.
Per frame it also compares `gWorkingMovementScript` (the facing command, 07 04 at dump 1660), `gActionData` (target
0x82, action 2 at dump 1820, item slot, `xOther`/`yOther`), BG2 (`AttackMapSelect_End` clears it), the camera, the lock
(B: End unlocks, the cancel proc locks, the weapon list locks, the proc unlocks) and the cancel proc's existence.
The restart is `WeaponSelectMenuDifferentialTest`'s cancel window, which now calls the ported `sub_8022E54`
(`UnitActionMenu.unitActionMenuAttackFromProc`) instead of its start stub. Ported and break-checked:
`AttackMapSelect_SwitchIn`, `_Select`, `_Cancel`, `_End`, `sub_8022E54`, `ChangeActiveUnitFacing`,
`GetFacingDirection` (mapanim_api.c, needed by the facing; new in `UnitFacing`); the proc `gProcScr_0859B630` is
`AttackMapSelect.cancelScript`. Transcribed: `sub_8022E38` (the map fits the screen, the camera never moves). Inside the ported ones (not verified): the event-engine-running returns, the wall or
trap target (`uid == 0`), the R help. Findings: the weapon list reopens at game frame 1703, the frame the forecast
proc `gProcScr_BKSEL` (MC-10, modelled in the test by the dumps) is gone; `NewBattleForecast`,
`BattleGenerateSimulation` + `UpdateBattleForecastContents` (SwitchIn), `Proc_EndEach(BKSEL)`/`CloseBattleForecast`
and `StartBattleForecastHelpBox` are hooks, stand-in until MC-10. On a device A on a target sets the combat action and
`ApplyUnitAction` ends the unit's turn like Wait (stand-in until MC-11: no battle).

### MC-10 result

`BattleForecast` (new, `fe8.game.bm`) is the forecast window proc `gProcScr_BKSEL`; `AttackMapSelect` now takes it
instead of the MC-10 hooks (`newBattleForecast`, `updateForecast`, `closeForecast`, `forecastExists` are gone;
`startForecastHelp` stays, stand-in until MC-12b). `TargetSelectionDifferentialTest` runs it on the two MC-5 windows
(f1659..1704 with B, f1789..1820 with A) and compares per frame: the proc's existence and script position, `frameKind`,
`ready`, frame count, x, y, side, slide counter, hit counts and effectiveness flags (not at the first dump after the
start, where the proc's fields still hold stale bytes), `gBattleActor`, `gBattleTarget`, `gBattleStats` and
`gBmMapMovement` (filled with -1). Excluded: BG tiles, palettes and text the forecast draws (MC-11, MC-12).
Ported and break-checked: `NewBattleForecast`, `BattleForecast_Init`, `InitBattleForecastBattleStats`,
`BattleForecastHitCountUpdate`, `GetBattleForecastPanelSide`, `CloseBattleForecast`. Transcribed:
`UpdateBattleForecastContents` (its one call comes before the proc is ready), `BattleForecast_OnEnd` (drawing only),
`TriggerBattleForcastToturialEvent`. The loop steps (`OnNewBattle`, `LoopSlideIn`, `LoopDisplay`, `LoopSlideOut`,
`DrawBattleForecastContents`) are "Partially ported": their fields and counters are verified here, the drawing is
hooks, so MC-12a and MC-11 still tag them. Findings: the forecast's set-up frame is heavy (two VBlanks), so the dump 3
frames after each start repeats the previous one (first selection) or holds the half-run frame (second), and the game
runs one frame behind the dump index after it; the test skips one engine run at start + 2. The first display
frame is dump start + 7; cancel (B at 1699) goes to label 1 and the slide-out ends the proc at 1704; A ends it at once
(1820). The ballista and obstacle forecasts and the cap3 and kill runs are not covered.

### MC-12b result

The display now puts the forecast's sprites. `PutBattleForecastWeaponTriangleArrows`, `PutBattleForecastMultipliers`,
`UpdateBattleForecastEffectivenessPalettes` and `UpdateStatArrowSprites` (bm.c, the arrow frames) are Ported;
`hooks.putDisplay` is gone. `BattleForecast` takes the sprite lists and the game clock. The full dumps of the two
noanim windows hold the sprites in their OAM (f1670, f1680, f1690; f1800, f1810): per dump an x2 mark (tile 0x2E8)
and two arrows (tiles 0x4C..0x4F), the actor's up and the target's down, so the Prologue fight does have a weapon
triangle. `TargetSelectionDifferentialTest` compares, per full dump, the forecast's OAM entries (by tile, in OAM
order) and, per dump, palettes 3 and 4 (the MC-11 exclusion is gone). The dumps of the closing frame (1700, 1820)
hold the frame before's OAM and are skipped. Break-checked (all fail): arrow frame divisor, arrow x, arrow direction
(target), arrow direction (actor), multiplier x, sway angle, palette 3 and 4 step 0. Not verified (no mutation changes
captured data): the flash's non-zero steps (nothing is effective in the windows), the target's multiplier, the panel's
y. The VRAM tile graphic of the mark was already compared in MC-11. Help box (`StartBattleForecastHelpBox`) stays M6-F.
Not covered: cap3 and kill runs were not checked for other arrows.

### MC-12a result

`BattleForecast` loops now write BG 0 and 1: `BattleForecast_LoopSlideIn` and `_LoopSlideOut` (one private
`slideStep`, they differ in the offset table: 6, 8, 9, 10 and 9, 8, 6, 0) clear both tilemaps, copy the panel's
columns from the scratch tilemaps and flag sync; `_OnNewBattle`, `_LoopDisplay`, `MapEventEngineExists_` are
Ported. `TargetSelectionDifferentialTest` (`ram_trace_prologue_attack_noanim`, windows f1659..1704 and
f1789..1820) now compares the BG 0 and 1 tilemaps and the BG 0 and 1 offsets per dump from start + 2, through
the slide in, the display and (first window) the slide out to dump 1703 (1704 is the weapon list reopening). At
A (second window) the menu proc's `MENU_ACT_CLEAR` clears BG 0 and 1; the test does that itself. Break-checked:
x (left branch), slide-in table, slide-out table (two entries), BG 1 destination, BG fill value, frame count,
`MapEventEngineExists_` always true; all fail. Not verified (the panel is on the left in both windows): the
right-side branches (x = 20, copy to column 30 - offset). `PutBattleForecastTilemaps` is Transcribed (only a
contents redraw calls it); `TriggerBattleForcastToturialEvent` stays Transcribed, its hook `tutorialPending`
is a stand-in until PC-10. The sprite work and help box stay MC-12b / M6-F. `eventEngineExists` is wired
in `PlayerPhaseWiring`.

### MC-11 result

`BattleForecast` now draws the standard panel (hooks `initDrawing`, `drawContents`, `initFramePalettes`,
`resetTextFont`, `unpackUiFrame` are gone). `TargetSelectionDifferentialTest` also compares, from the first complete
dump after each start (the dump 3 frames after the start is a repeat or half run, see MC-10): the two scratch
tilemaps `gUiTmScratchA` and `B` (EWRAM, 0x500 bytes each, seeded from the start dump because the panel leaves the
rest stale), `gBkselPals`, the label and name `struct Text`s, palettes 1, 2, 0x12 (and 3 until the display's flash),
BG 1's vertical offset, and in the dumps that have VRAM (`f`, every 10 frames: f1670..1700, f1800..1820) the text
tiles (0x06001000 + slot * 0x40, every slot of the font counter), the 4x2 panel tile graphic at 0x06015D00 and
the icon tiles. Excluded: the BG 0 and 1 tilemaps (written by the slide steps, MC-12a), the display's palette
flash and sprites (palettes 3 and 4 from the first display frame, MC-12b) and palette 5 (the range squares').
Ported and break-checked (one mutation each, all fail): `InitBattleForecastIconPaletteBuffer`,
`InitBattleForecastLabels`, `PutBattleForecastUnitName`, `PutBattleForecastItemName`,
`DrawBattleForecastContents` (standard branch), `DrawBattleForecastContentsStandard`,
`GetFactionBattleForecastFramePalette`, `InitBattleForecastFramePalettes`, and with them the text helpers
`InitTextDb`, `PutNumberTwoChr`, `PutTwoSpecialChar`, `PutDrawText` (new in `TextSystem`), `Copy2dChr` (private in
`BattleForecast`), and `GetStringTextCenteredPos` and `Text_InsertDrawString`, which were Transcribed and are now
Ported. Inside ported ones (not verified): the 100 and dash branches of `PutNumberTwoChr`, the no-weapon target
branch, the Monster Stone, the purple (wall or trap) frame. `BattleForecast_OnEnd` stays Transcribed (it restores
the window frame, whose palette equals the forecast's blue one in the capture). Findings: `TileMap_FillRect` fills
`width + 1` by `height + 1` entries (its asm counters run to -1); `BattleForecast_Init` loads the panel graphic
and palette 0x12 (OBJ palette 2) too; palette 5 changes every few frames by itself. Not covered: cap3, the kill
run, extended layout.

### MC-13 result (2026-11-13)

`CombatActionDifferentialTest` seeds the unit table, `gPlaySt`, `gBmSt`, terrain, both battle units, `gBattleStats`,
`gActionData` and the RNG from `ram_trace_prologue_attack_noanim` f1823 and runs `PlayerPhase.applyUnitAction` (a
test proc in tree 2 calls it) -> `ActionCombat` -> `BattleGenerateReal` -> `sProcScr_CombatAction` through f2076.
The trees run as `OnMain` does: tree 2 is skipped while the game is locked (this is why the real combat proc
stays at command 2 from f1825 to f2073: the map animation's `LockGame`, not the proc script, holds it).
The map animation is a test placeholder that locks the game and unlocks it when the dump's
`ProcScr_MapAnimBattle` is gone; its BG/OAM/palette/sprite writes are M6-H and not compared. Dump 1824 holds
`BeginBattleAnimations` half run and is skipped. Compared per dump from 1825: the combat proc and its script
position, the blocked parent's lock count, both battle units (but `unit.exp` and `expGain`, MC-14), `gBattleStats`,
the RNG; at the end both unit table entries after the write-back (HP, weapon uses and exp of the weapon; every byte
but `unit.exp`), the unit ids `BATTLE_HandleItemDrop` stores in the proc (+0x64/+0x66, s16 sign-extended) as the
arguments of the death hooks. `StoreRNStateToActionStruct` runs in the suspend save: f1820 (stores the pre-battle
state, equal to the old bytes) and f2079 (the post-battle state); the test checks both against the dumps with a
filled buffer (`BmSuspendSave` now calls it). Verified: `ApplyUnitAction` (combat branch), `ActionCombat`,
`BATTLE_GOTO1_IfNobodyIsDead`, `BATTLE_HandleItemDrop`, `BATTLE_HandleCombatDeaths`, `StoreRNStateToActionStruct`.
Combat does not set `US_HAS_MOVED` (the unit turns grey through `MoveActiveUnit`'s `US_UNSELECTABLE`).
Transcribed (not verified): the item drop branch, the Nightmare, wall/trap and ballista paths (fail loudly).
Not written: `BATTLE_PostCombatDeathFades`, `BATTLE_DeleteLinkedMOVEUNIT` (script entries fail loudly).
Stand-ins: `beginBattleAnimations` (sets `MAPANIMS`, no animation) and `getBattleAnimPreconfType` (M6-H);
`itemGotPopup` (M6-H; no capture drops an item);
The write-back `BmBattle.battleApplyGameStateUpdates` runs (tagged Ported since MC-15; this
test checks its result too). `pidStatsAddExpGained` is a no-op stand-in since MC-14 (the exp now flows to it).

**MC-14 result.** `BattleApplyExpGains` now runs inside `BattleGenerateReal` (`BattleGenerate.battleApplyExpGains`,
replacing the `applyExpGains` hook), and `BattleGenerateRealDifferentialTest` and `CombatActionDifferentialTest` no
longer mask `unit.exp` (+0x09), `expGain` (+0x6E) or the write-back exp: cap3 and noanim 0 -> 10, kill run Seth 0 -> 3
all match. Verified, each break-checked against the captures: `BattleApplyExpGains`, `GetBattleUnitExpGain`,
`GetUnitRoundExp`, `GetUnitExpLevel`, `CanBattleUnitGainLevels`, `GetUnitKillExpBonus`, `GetUnitPowerLevel`,
`GetUnitClassKillExpBonus`, `ModifyUnitSpecialExp` (a mutation that adds 1 to the result). Caveats: the kill bonus is
clamped to 0 in the captured kills, so only a large change to `GetUnitClassKillExpBonus` shows (thief, boss and
`ENTOUMBED` branches are not told apart); `GetUnitExpMultiplier` is Transcribed (no assassin, and the bonus it
multiplies is 0). Level-up: no capture crosses 100 exp, so `CheckBattleUnitLevelUp` stays the hook
`checkBattleUnitLevelUp` (stand-in until MC-17: level and exp bookkeeping only, no stat gains, RNG not advanced). `pidStatsAddExpGained`
(save statistics, no plan row) is a no-op stand-in. Not written: `BattleApplyItemExpGains`, `GetBattleUnitStaffExp`,
`BattleApplyMiscActionExpGains`.

**MC-15 result.** `BattleApplyGameStateUpdates`, `BattleApplyUnitUpdates`, `UpdateUnitFromBattle` and
`GetBattleUnitUpdatedWeaponExp` are Ported and verified by the new `BattleWriteBackDifferentialTest`: it seeds the
unit table and both battle units from the dump before the write-back (the frame is found as the first dump whose unit
entries change) and compares the whole unit entries of actor and target with the dump after it, at noanim f2074,
kill f1999 and f2549 and cap3 f2028. Break-checked (mutate, test fails, restore): dropping the unit updates, the
actor's weapon store, exp +1, weapon exp gain +1. The dead unit of the kill run is compared since MC-16. `BattleApplyBallistaUpdates` stays Transcribed (no ballista, and the
no-ballista path writes nothing). Not written: `HasBattleUnitGainedWeaponLevel` and `DidBattleUnitBreakWeapon`
(the map animation's level-up and break popups, M6-H), `UpdateUnitDuringBattle`, `UpdateActorFromBattle`,
`UpdateObstacleFromBattle`. `PidStatsAddExpGained` is still the no-op stand-in.

**MC-16 result.** `KillUnitOnCombatDeath` (`BmBattle`) and `UnitKill`
(`UnitTable.unitKill`) are Ported; `DropRescueOnDeath` (`BmBattle`) is Transcribed (not verified: a break check cannot change captured data); the hooks `killUnitOnCombatDeath` and `dropRescueOnDeath` and the wiring's
dead-and-hidden stand-in are gone (`BATTLE_HandleCombatDeaths` calls them directly). What the dumps show: at the
write-back frame (kill f1999, f2549) the dead enemy's `pCharacterData` is 0 and nothing else of the struct changes
beyond the write-back; `gBmMapUnit` and the sprite handles still hold it. It leaves the unit map at the next
`RefreshEntityBmMaps` (f2005) and the sprite list at the next `RefreshUnitSprites` (f2008), both already ported (a
unit without a character is skipped). `BattleWriteBackDifferentialTest` now compares the dead unit's whole struct
after the write-back plus the death handling, and a second test replays both refreshes on the killed table and
compares `gBmMapUnit` and the handle array with those dumps. Transcribed (not verified): the blue branch of
`UnitKill` (no player unit dies in the captures); `DropRescueOnDeath` with a rescuer does nothing (stand-in until C14-1: no drop, rescue link left; `DeathDropAnim`,
`UnitGetDeathDropLocation`, `UnitDrop` not written). Stand-ins: `pidStatsRecordDefeatInfo` (save statistics, no-op),
`InitUnitsupports` (not called, supports lane M6-J), the death fade (M6-H; the unit vanishes at once).

**MC-w1 result.** `AttackFlowReplayTest` boots `Fe8Boot` from the Prologue resume save, replays the
`ram_trace_prologue_attack_noanim` inputs relative to the first idle `PlayerPhase` frame (the Options prefix, the unit
menu, Attack, weapon list, both B cancels, the confirm) and stops when `sProcScr_CombatAction` has appeared and ended
with the phase back at the idle command (the map animation is a stand-in, so frame numbers differ). Compared with the
capture's f2124: every byte of every unit (HP, exp, level, items and uses, state bits; the sprite pointer
+0x3C..0x3F skipped) and the next 4 RNG draws. All equal on the first run, no wiring bug found; the test fails when
compared with f1820, so it is not vacuous. Not covered: the kill path and allocation over the flow (MC-w2).

**MC-w2 result.** `KillFlowReplayTest` replays `ram_trace_prologue_kill` the way MC-w1 does (shared helper
`AttackReplay`): inputs relative to the idle frame, stop when Seth's `sProcScr_CombatAction` has ended with the phase
idle, compare with f2020 (after the f1999 write-back, before the cursor input of f2029): the whole unit table (Seth's
kill exp, uses and state, the dead enemy's struct) and the next 4 RNG draws all equal; no wiring bug. `gBmMapUnit` is
not compared (the boot's map is private). Allocation: `PlayerPhaseAllocationTest` gained an attack-flow case (weapon
list, target select, forecast shown, 100 frames after the battle): all frames allocate 0 B. Found, not fixed: the unit's
walk (MU proc, f1560..1595 of the noanim run) allocates 32 B per frame, which the existing select/move windows also
skip; the window is left out. MANUAL_TEST step 3 now lists the attack flow and its stand-ins.
TEST-2 (11-13) found it: bisecting `GameLoop.onMain` per proc showed `gProcScr_PlayerPhase`, whose
`playerPhaseWaitForUnitMovement` calls `Mu.muExistsActive`; `for (c in configs)` over a `List` allocated an
`ArrayList.Itr` (32 B) each frame. An index loop fixes it (walk frames 0 B; a sprite frame shown for the first time
still allocates 48 B once per boot). `WalkingAllocationTest` went from 1-2 to 8 of 26 free windows, threshold now 5.
TEST-4 (11-13): the opening-walk windows are dirty from two things. A load waiting for a free MU slot
(`EventUnitLoads` callback each frame) called `canStartMu` = `mu.configs.any {}`, a 32 B iterator per frame for
~35 frames; now an index loop. The 48 B / 96 B spikes at sprite-frame changes are the one-time `objectAt` cache fill
per boot (confirmed: each coincided with a miss); documented, not changed. Windows stay 8 of 26 (a first-show miss dirties a 10-frame window), threshold 5 kept.

## M7

[M7-modding](M7-modding.md) now includes accepted extensive-modding scope (ADR 0016), implementation
proposals and design inventories M7-24..32. Runtime/API/storage/packaging choices remain open.

## MC-18i display inventory (2026-11-15)

MC-18i inventory — original `ram_trace_prologue_levelup`, after-step numbering

The procs watch records f6413..6746 and the test asserts exact relevant proc lifetimes and parentage. The exp-bar proc is child of map battle and blocks on ManimLevelUp f6413..6745; both exp bar and battle remain at f6746. `ProcMAExpBar_LevelUpIfPossible` calls `StartManimLevelUp`, whose blocking child actor_id is 0. No function-entry watch for these routines is present: call timing below is inferred from proc cursor, fields, source script and proc presence, not directly recorded function entries. Uninitialized fields before f6519 must not be treated as values.

Cursor: window initialization/dim and music child f6414; announcement AP f6431..6500; ending at f6501; restore music f6502..6517; main-screen initialization f6519; scroll in f6521..6532 (-144 to -48 in steps of 8); gain labels f6563, 6584, 6605, 6626, 6647 (next_stat_num 1, 2, 4, 6, 7); gain scan ends f6669; gain-label/color children end f6728; scroll out f6730..6741; cleanup cursor f6742; level-up ends after f6745. Face is live f6519..6741. Label parent f6519..6728; color child f6520..6728. AP lifetimes include short and long overlapping instances; see test rows. Gain selection is only this capture; missing stats and loss cases are not covered.

Dump timing: EWRAM proc snapshots agree with watches throughout f6519..6745. BG0/BG1 buffers and palette RAM are available per step. VRAM, hardware palette, OAM and IO snapshots occur every ten steps. Source/proc analysis supplies tentative attribution. Audio timing requires another capture.

Candidate corrected subsets, observed path only:
- MC-18a f6413..6518: `StartManimLevelUp`, `InitManimLevelUpWindow`, `ManimLevelUp_DimBgm`, `ManimLevelUp_StartLevelUpText`, `ManimLevelUp_EndLevelUpText`, `ManimLevelUp_RestoreBgm`. Check child/actor, AP lifecycle, window IO and OAM at full snapshots; sound/volume require new instrumentation to directly verify.
- MC-18b f6519, 6563..6669: `PutManimLevelUpFrame`, `PutManimLevelUpStat`, `GetManimLevelUpStatGain`, `GetManimLevelUpBaseStat`; compare original EWRAM BG tilemaps and source unit/battle fields using runtime data; VRAM only at fNNNNN snapshots. Non-gain and magic-label alternatives need separate captures.
- MC-18c f6519..6728: `StartManimLevelUpStatGainLabels`, `StartManimLevelUpStatGainLabelAnim`, `EndManimLevelUpStatGainLabels`, `ManimLevelUpStatGainLabel_Finish`; compare child/AP lifetimes, label proc fields and available full snapshots; loss path and prep-item helper unobserved.
- MC-18d f6519..6745: `ManimLevelUp_InitMainScreen`, `ManimLevelUp_ScrollIn`, `ManimLevelUp_PutStatGainLabels`, `ManimLevelUp_ScrollOut`, `ClearManimLevelUpWindow`, `ManimLevelUp_Clear`; panel fields each step; EWRAM buffers each step and IO/OAM only full snapshots. ResetDialogueScreen executes both via script and end callback; distinguish source calls from recorded direct entry.
- MC-18e f6520..6728: `ManimLevelUpLabelColor_Init`, `ManimLevelUpLabelColor_Loop`; color proc fields and palette buffer per-step EWRAM; hardware palette full snapshots only.
- MC-18f start at f6413 through f6746 and beyond for resume: `ProcMAExpBar_LevelUpIfPossible` plus `StartManimLevelUp` boundary; compare blocking parent/child and continuation. Confirm actual resume using later frames before asserting its precise timing.

## EP-11g music query result (2026-11-15)

EP-11g evidence and follow-ups
Capture ram_trace_prologue_clear: at f2963 a song is playing; f2964 has a delayed music proc with counter 48 and a new song, with no prior pending proc to cancel. Proc is present through f3012 and gone f3013. f3797 calls StartBgm from StartMapSongBgm for the already-playing song; no transition or speed is distinguishable. No SRAM or sound-room save watch exists in this capture. Current test covers the f3797 map caller and two captured current-song values; it does not reconstruct the f2964 event or compare the delayed proc each step. Existing PlaySong omits sound-room unlocking; SoundWrapper is not wired to BmSaveLib. No new implementation/credit for cancellation, StartBgm, PlaySong, or unlocking.
Recommend CAP: transition while a delayed song proc already exists, with proc fields per step, to distinguish cancellation; separate StartBgm transition with a different song, observing delay; SRAM/sound-room watch surrounding a new unlock, repeated unlock, invalid checksum and special pair unlocks, plus player state for PlaySong. Wire SoundWrapper to save service only after observing the save behavior.


`EventMusicDifferentialTest`: `GetCurrentBgmSong` returning 0 fails against the original song field; restored test passes. No credit for other helpers.

## PC-9i menu inventory (2026-11-21)

PC-9i inventory, 2026-11-21. Source: pinned fireemblem8u src/difficultymenu.c,
savemenu.c, savedraw.c and include/savemenu.h. This is original-state evidence,
not port verification. All callback statements below are inferences from proc
script cursors and source, not per-step callback watches.

ram_trace_newgame: RAM snapshots every step 955–1230 (EWRAM, IWRAM); f962,
f1020, f1100, f1225 also have full video regions. SRAM snapshots 1128–1145,
1212–1216 and 1230; no SRAM snapshot before 1128. inputs: A 964–965,
Down 1030–1031, A 1060–1061, A 1130–1131, A 1200–1201, A 1230.
No per-step proc watch: proc state is decoded from the EWRAM snapshots, not
function-entry trace. ProcScr_SaveMenu slot 2 persists 955–1211; ProcScr_savedraw
slot 3 and gProcScr_SaveDrawCursor slot 4 persist through 1210. Difficulty child
slot 5 exists 982–1087, sprite child slot 1 984–1087. Difficulty cursor commands
(index = offset/8) are 4 at 982–983, 5 at 984–985, 7 at 986–1002,
10 at 1003–1060, 12 at 1061–1070, 14 at 1071–1087. Save-menu cursor:
15 at 955–963, 16 at 964, 19 at 965–981, 23 at 982–1088,
24 at 1089–1097, 26 at 1098–1115, 31 at 1116, 32 at 1117–1132,
37 at 1133, 39 at 1134, 41 at 1135, 42 at 1136–1173,
82 at 1174–1175, 84 at 1176–1209, 91 at 1210, 93 at 1211.

PC-9a: 981–1003 entry/init/fade and 1061–1089 confirm/end. Inferred
NewNewGameDifficultySelect, DifficultySelect_Init, InitDifficultySelectScreen,
DifficultySelect_OnEnd. RAM has BG tilemap buffers throughout, full VRAM/pal/OAM
only f1020 and f1100 within these subwindows. Lifecycle is observed through
parent/child and cursor progression, not directly through a callback trace.
PC-9b: input loop cursor 10: selection 0 through 1029, 1 at 1030 onward;
selection 1 confirmed 1060, save menu difficulty becomes 1. Source implies
DifficultySelect_Loop_KeyHandler, DrawDifficultyModeText, sub_80AC034 and
sub_80AC680 on this movement. Initial DrawDifficultyModeText/sub_80AC034
inferred at init. sub_80AC1BC/sub_80AC22C appear in source but are not present
in this proc script; no evidence of calls here. No wrap, up, B, hard or direct
selection 0 confirmation in dense run.
PC-9c: DrawDifficultySprite_Init, DrawDifficultySprites_Loop,
DrawDifficultyMenuCursorMaybe, sub_80AC4F8 and StartDrawDifficultyMenuSprites
inferred from child script/source 984–1087; sub_80AC680 movement at 1030,
cursor fields evolve thereafter. RAM state is dense but full OAM only f1020:
no post-movement OAM sequence; no claim of OAM animation coverage.
PC-9d: SaveMenu_ResetLcdFormDifficulty at entry and restore,
SaveMenu_ReloadScreenFormDifficulty, SaveMenu_PostDifficultHandler,
SaveMenu_SetDifficultyChoice inferred from scripts/input and difficulty 1;
slot 0 selected by A at 1130. Source dispatch supports SaveMenuWriteNewGame,
then SaveMenu_NewGame; SRAM changes 1130–1132; existing
NewGameSaveReferenceTest compares this write. Record controller/tutorial choice 1,
non-hard, slot 0 only. Other save callbacks incl. SaveMenu_SaveSlotSelectLoop,
SaveMenuSlotSelDrawSprite and savedraw's SaveDraw_Init/SaveDraw_Loop_Main and
SaveDrawCursor_Init/SaveDrawCursor_Loop have separate ownership; do not fold
all save menu work into PC-9d. Screen reload 1089–1115 and slot 1116–1132
are separate subwindows, not a complete save-menu inventory.

menu_screens: only m0962, m1024, m1086, m1087 (full EWRAM/IWRAM/VRAM,
palette/OAM/IO and RGB), independent timeline: A 963–964, A 1025–1026;
initial difficulty 0 at m1024; slot menu m1086–1087. This is direct choice 0,
but no intervening snapshots or SRAM, no per-step proc watch. Do not equate its
m1086 with dense f1086. At m1024 script state is difficulty loop; m1086/1087
save slot loop. These are useful graphics snapshots, not dense lifecycle evidence.

CAP9 plan, no new capture made: starting from documented clean SRAM, capture
full per-step proc-array watch plus inputs and RAM; full video regions/OAM each
step from difficulty entry through return and slot screen, SRAM before/after
write. Exercise Down and Up including both wraps, idle/confirm during cursor
motion, confirm each of 0/1/2 in independent reset runs, B cancellation and
re-entry, and A/Start dispatch. Capture slot movement/empty and occupied slots,
back and overwrite-confirm/cancel with known initial saves, then confirm each
branch and retain before/after SRAM and game-control handoff. Maintain distinct
scenarios for occupied-slot prompts and other save-menu callers; do not infer
coverage of branches from the source or a single clean slot. Capture replayable
setup, input scripts, manifests, and full RAM/video/SRAM around boundaries.

## EP-11f2 UI helper follow-up (2026-11-21)

EP-11f2 evidence: event.c EventEngine_OnEnd -> ReadGameSaveCoreGfx (event type 5); bm.c ReadGameSaveCoreGfx -> ResetText; fontgrp.c ResetText -> InitTextFont -> InitSystemTextFont, ApplyPalette(Pal_Text, palid), color 0 zero. Capture f3720->3721 color 14 tested through production TalkScene host, full bank compared. Other event-end mid-frame VRAM timing exclusions remain as existing tests. Face fade uses default layout; other SetupFaceGfxData layouts remain unsupported. No new port tags or mutation checks.

SpacialSe readability review, decomp src/bmlib.c Screen2Pan and PlaySeSpacial and include/soundwrapper.h PlaySoundEffect: class KDoc describes spatial pan and MU footsteps consistent with callers; Screen2Pan KDoc matches both clamps and integer range, capture limits correctly stated; PlaySeSpacial KDoc matches macro, player selection, immediate init and pan control, and states capture limit. Sound disabled only skips starting the effect; init and pan still run, matching decomp. No comments/names changes warranted.

## PC-cap1 ending and Chapter 1 capture (2026-11-21)

Published private release `assets-620a70f`, run `ram_trace_prologue_end`. Preserves the first 5,000
input frames of the clear run; representative EWRAM/IWRAM checkpoints match its prefix. Slot 0 save
is bracketed by s09799/s09802 (write f9800..9802). Save-menu proc f9444..9862; chapter intro starts
f9872; Chapter 1 opening event ends f25426; first idle around f25510, final checkpoint f25580 is
chapter 1, player faction, turn 1, PlayerPhase command 16, no event engine. Last input is A f25400.
Per-step procs; RAM every ten frames after f5000 plus dense windows 5001..5100, 9350..10100,
10200..10500 and 25380..25580; graphics every 100 frames and transition boundaries; SRAM9350..10100.
Callbacks remain inferred from source/cursors, not directly instrumented. Only this dialogue/slot path
is covered. Archive verification retained 51,552 baseline members' contents (root provenance append
excepted), and checked all 10,839 new entries. Audio remains excluded with the known clear-run divergence;
this is not verified audio coverage. PC-0/PC-4i/PC-6i are now available; no Android chapter-completion claim.

## PC-0 inventory (2026-11-21)

Test: `PrologueEndInventoryTest` (`testing/src/test/kotlin/fe8/testing/PrologueEndInventoryTest.kt`) over
`ram_trace_prologue_end` (`PC-cap1`). Covers ONLY the Prologue ending event (`f4894..9440`), `GameControl`
post-chapter switch (`f9441..9444`, resume `f9863`), and Chapter 1 start (`f9864..10298`). Save-menu
internals (`gProcScr_SaveMenuPostChapter` `f9444..9862`) are excluded (owned by `PC-4i` / `PC-4a..4g`).

Capture notes and watch alignment:
- `procs` and all per-step watches in `PC-cap1` use 1-based step indexing (`firstStep = 1`), matching the
  step number `N` of `fNNNNN`/`rNNNNN` snapshots (`r04893`, `r04894`, `f09441`, `r09442`, `r09443`, `f09444`,
  `f09863`, `r09864..r09871`, `f09872`).
- `calls.txt` holds only the two `EP-cap1` `AiComputeCombatScore` calls (`f1614`, `f2039`), and `states.txt`
  is empty. No function-entry trace exists for `f4894..10298`; callbacks below are inferred from proc
  script cursors, watches, dense RAM, and pinned `fireemblem8u` source.

Active proc windows (`EnemyPhaseInventoryTest.rows` over `procs` watch, 1-based steps):

| Window | Frames | Key procs and transitions |
|---|---|---|
| Ending start (`PC-1`, `PC-2`, `PC-2a`) | `f4894..5000` | `ProcScr_StdEventEngine` and `gMusicProc3Script` start `f4894` (`gProcScr_PlayerPhase` cursor `33 -> 35`); `ProcScr_FadeToBlack` `f4895..4910`; `ProcScr_Mu` locked `f4912` (`EV_STATE_GFXLOCKED`); `ProcScr_FadeFromBlack` `f4916..4931`; `gProcScr_Talk` `f4933..5000+`; `gProcScr_E_FACE` x2 `f4939..5000+` |
| Ending dialogue | `f4933..9403` | `ProcScr_StdEventEngine` stays alive unbroken `f4894..9440` with no save-menu proc before `f9444` |
| Ending tail + switch out (`PC-2`, `PC-3`) | `f9406..9444` | `ProcScr_FadeToBlack` `f9406..9421`; faces end `f9422`; `REMA`/`ENUT`x11/`MNC2(1)` `f9424` (`EventScr_08592170` `f9424..9437`, `Mu` unlocked); `EventScr_08592114` `f9438`; `gProc_MapTask` restarts `f9431..9439` (`LOMA(0xFFFF)`); `sProc_BMVSync` and `gProc_MapTask` end `f9440`; `StdEventEngine`/`BMapMain`/`PlayerPhase` gone `f9441`; `GameControl` `81` -> `69` (`f9442`) -> `71` (`f9443`) -> `76` (`f9444`, `gProcScr_SaveMenuPostChapter` starts) |
| Save menu (excluded) | `f9444..9862` | Owned by `PC-4i` / `PC-4a..4g` |
| Chapter 1 start (`PC-5`) | `f9863..10298` | `GameControl@76` unlocked `f9863` -> `52` (`f9864`, `LGAMECTRL_EXEC_BM_EXT`, `ProcScr_WorldMapWrapper` skipped) -> `53` (`f9865..9869`, `StartBattleMap`) -> `54` (`f9870`, `gProc_BMapMain@1`, `gProc_MapTask@9`, `sProc_BMVSync@3`); `gProcScr_ChapterIntro` + 6 children `f9872..10296` (skip at `A` `f10200`); `gProc_BMapMain@12` starts Chapter 1 `ProcScr_StdEventEngine@5` at `f10298` |

Observed values at transitions (`PC-cap1`):
- `f4893 -> f4894`: `gProcScr_PlayerPhase` cursor `33 -> 35`, `ProcScr_StdEventEngine` starts (`pEventCurrent = 0x089EF164` `EventScr_Prologue_EndingScene`, `evStateBits = 0x0002`), `gPermanentFlagBits[3]` `0x00 -> 0x80` (`EVFLAG_HIDE_BLINKING_ICON` = `0x84`), `gChapterFlagBits[0]` `0xC3 -> 0xE7` (`EVFLAG_WIN` = `3`, `EVFLAG_DEFEAT_ALL` = `6`).
- `f4911 -> f4912`: `ProcScr_Mu` lock count `0 -> 1` (`LockMus`), `evStateBits` `0x0102 -> 0x1102` (`EV_STATE_GFXLOCKED`).
- `f9423 -> f9424`: `ProcScr_Mu` lock count `1 -> 0` (`ReleaseMus`), `gPlaySt[0x4A]` `0x00 -> 0x04` (`save_menu_type = 2`), `GameControl` `nextAction` `4 -> 2` (`GAME_ACTION_USR_SKIPPED`), `nextChapter` `0 -> 1`, `pEventCurrent` -> `EventScr_08592170` (`evStateBits = 0x0D12`). Note: all 11 `ENUT` flags (`0xE0, 0xE1, 0xB7, 0xB4, 0xB5, 0xDC, 0xB9, 0xC2, 0xC3, 0xE7, 0xC9`) are already set in `prologue.sav` (`gPermanentFlagBits` unchanged at `f9424`).
- `f9443 -> f9444`: `gPlaySt.chapterIndex` `0 -> 1`, `gRNState` `(60569, 20783, 3607)` -> `(35448, 26949, 25418)` (3 `NextRN` draws), `gGmMonsterRnState` (`f09444.iwram.bin` `0x0300534E`) equals new RNG state, `gChapterStats[0]` (`f09444.ewram.bin` `0x0203ECF4`) = `0x00360180` (chapter `0`, turn `3`, time `54`), `gPlaySt.unk_2C_04` = `0x50` and `unk_30` rankings populated, blue units (Seth `13 -> 30` HP, Eirika `7 -> 16` HP) reset with `state = 0x09` (`US_HIDDEN | US_NOT_DEPLOYED`).
- `f9864..9870`: `GameControl` jumps to `LGAMECTRL_EXEC_BM_EXT` (no `ProcScr_WorldMapWrapper`), `StartBattleMap` runs `f9865..9869` (`faction = 0x40`, `chapterTurnNumber = 0`, `gChapterFlagBits` cleared at `f9865`; `time_chapter_started = 10739` and `US_NOT_DEPLOYED` cleared to `state = 0x01` at `f9870`; `sTrapPool` at `0x0203A614` stays all zero).

Boundaries, observed functions, and codebase status (`Ported` / `Transcribed` / `Unported`):

- **`PC-1`** (`f4893 -> f4894`, `r04893 -> r04894`):
  - Already `Ported`: `RunPotentialWaitEvents` (`PlayerPhase`), `CheckForWaitEvents`, `RunWaitEvents`, `EvCheck01_AFEV`, `StartEventFromInfo`, `SetFlag`, `SetPermanentFlag` (`EventInfo`, `Fe8EventFlags`), `EventEngine_Create` (`EventEngine`).
  - Currently `Transcribed`: `CallEvent` (`EventEngine`, empty-queue branch).
  - Never entered (leave unwritten): `ShouldCallEndEvent`, `MaybeCallEndEvent`, `CallEndEvent`.
  - Work: differential verification test on `f4893 -> f4894` (and `CallEvent` empty-queue verification).
- **`PC-2`** (`f4894..5000` start, `f9406..9438` tail):
  - Already `Ported`: `Event01_End`, `Event02_EvBitAndIdMod` (`ENUT`), `Event05_SetSlot`, `Event08_Label`, `Event0A_Call`, `Event0C_Branch`, `Event19_Checks`, `Event12_StartBGM`, `Event1A_TEXTSTART` (`REMOVEPORTRAITS`), `Event1B_TEXTSHOW` (`TEXTSHOW`/`REMA`), `Event1D_WaitForLockTalk`, `Event21_TextBg`, `Event23_DisaleMapDisp`, `Event25_ChangeMap` (`LOMA`), `SetNextGameActionId` (`GameControl`), `Sound_FadeOutBGM` (`SoundWrapper`).
  - Currently `Transcribed`: `Event03_CheckEvBitOrId`, `Event06_SlotOperation`, `Event17_Fade`.
  - `Unported` (3 new functions): `Event2A_MoveToChapter` (`src/eventscr.c`, `EVSUBCMD_MNC2` branch), `SetNextChapterId` (`src/gamecontrol.c`), `DeleteAll6CWaitMusicRelated` (`src/soundwrapper.c`).
- **`PC-2a`** (`f4894`, `f4911 -> f4912`, `f9423 -> f9424`):
  - Already `Ported`: `RefreshAllies` (`UnitTable`), `SetUnitStatus` (`UnitTable`, status `0` path).
  - `Unported` (2 new functions): `LockMus`, `ReleaseMus` (`src/mu.c`, `Proc_BlockEachMarked`/`Proc_UnblockEachMarked` on `PROC_MARK_MU`).
- **`PC-3`** (`f9439..9444`, resume `f9863`) — covers >8 non-trivial unported/transcribed functions across 5 source files; split into 3 sub-tasks (`<= 8` functions each):
  - **`PC-3a`** (Prologue teardown & `GameControl` post-chapter switch, `f9439..9444` & `f9863`):
    - `EventEngine_OnEnd` (`src/event.c`, `EV_EXEC_UNK5` + `EV_STATE_CHANGEGM` branch, `Transcribed`), `EndAllMus` (`src/mu.c`, `Transcribed`), `EndBMapMain` (`src/bmio.c`, `Transcribed`), `GameControlHandlePostNormalOrExtraChapter`, `GameControl_PostChapterSwitch` (`Partially ported`), `GameControl_RestoreChapterId` (`Transcribed`), `GameControl_ChapterSwitch` (`Unported`), `GameControl_CallPostChapterSaveMenu` (`Unported`, handoff to `Make6C_SaveMenuPostChapter`), `StoreRNState` (`RngService`, `Transcribed` -> `gGmMonsterRnState`). (`GameControl_RememberChapterId` and `NextRN` are already `Ported`.)
  - **`PC-3b`** (Chapter stats & chapter-change unit cleanup, `f9443 -> f9444`):
    - `src/bmsave-bwl.c` (`Unported`): `RegisterChapterTimeAndTurnCount`, `GetNextChapterStatsSlot`, `GetChapterStats` (`GetGameClock` already `Ported`).
    - `src/bmio.c` (`Unported`): `ChapterChangeUnitCleanup` (clears phantoms/green/red, heals and resets blue units, sets `US_HIDDEN | US_NOT_DEPLOYED`).
  - **`PC-3c`** (Chapter rankings & party gold sum, `f9443 -> f9444`; split further if gold helpers exceed 8):
    - `src/gamerankings.c` (`Unported`): `ComputeChapterRankings`, `GetChapterTacticsRank`, `GetChapterSurvivalRank`, `GetChapterDeathCount`, `GetChapterFundsRank`, `GetChapterCombatRank`, `GetChapterWinPerc`, `GetChapterExpRank`, `GetOverallRank` (`PidStatsGetTotal*` already `Transcribed` in `PidStats`).
    - `src/bmitem.c` (`Unported`): `GetPartyTotalGoldValue` (plus `GetConvoyItemCostSum`, `GetUnitItemCostSum`; consider `PC-3d` for `GetPartyTotalGoldValue` + `GetChapterFundsRank`/`GetChapterCombatRank`/`GetChapterExpRank` if keeping strict `<= 8` total functions per row).
- **`PC-5`** (`f9863..10298`):
  - Correction to plan: `ProcScr_WorldMapWrapper` is NOT entered (`MNC2(1)` sets `save_menu_type = 2`, so `GameCtrl_CheckNewGameAndBranch` jumps to `LGAMECTRL_EXEC_BM_EXT` at `f9864`).
  - Already `Ported`: `GameControl_RememberChapterId` (`GameControl`), `StartBattleMap`, `ClearBattleMapState`, `StartBMapMain` (`BattleMapStart`), `ChapterIntro` main effects (`ChapterIntroMainPathTest`/`ChapterIntroDifferentialTest`).
  - Currently `Transcribed`: `GameCtrl_CheckNewGameAndBranch` (`GameControl`), `BmMain_StartIntroFx` (`BMapMain`), `gProcScr_ChapterIntro` script/skip tail (`ChapterIntro_BeginFadeOut`, `ChapterIntro_BeginFastFadeToMap`, `ChapterIntro_End`), `ClearTraps` (`MapTraps`).
  - `Unported` (2 small `StartBattleMap` hooks): `ClearTemporaryUnits` (`src/bmunit.c`), `ResetMenuOverrides` (`src/uimenu.c`). (`InitMapObstacles` and `LoadChapterTraps` remain M6-H stand-ins since Chapter 1 has no traps/obstacles in `sTrapPool`.)
  - Ends at `f10298` (`gProc_BMapMain@12` starting Chapter 1 `ProcScr_StdEventEngine@5`, boundary of `PC-6i-load`/`PC-6i-event`).

## PC-4i inventory (2026-11-21)

Test: `SaveMenuInventoryTest` (`testing/src/test/kotlin/fe8/testing/SaveMenuInventoryTest.kt`) over
`ram_trace_prologue_end` (`PC-cap1`), covering ONLY `gProcScr_SaveMenuPostChapter` (`f9444..9862`)
and its exit back to `gProcScr_GameControl` (`f9863`).

Capture notes:
- Steps are 1-based (`run.inputs[step - 1]` is the key mask for step `step`). The only non-zero
  inputs in `9444..9863` are `A_BUTTON` (`0x0001`) at `f9600` (open confirm prompt) and `f9800`
  (confirm save to slot `0`). No `DPAD`, `B_BUTTON`, or `R_BUTTON` input occurs.
- SRAM snapshots `s09350..s10100.sram.bin` cover the entire window every frame; dense EWRAM/IWRAM
  snapshots `r09445..r09862` and boundary graphics dumps `f09444`, `f09494`, `f09600`, `f09799`,
  `f09800`, `f09802`, `f09863` exist. No function-entry trace covers `f9444..9863`; callbacks are
  inferred from proc script cursors, watches, dense RAM/SRAM, and pinned `fireemblem8u` source.

Active proc windows (`EnemyPhaseInventoryTest.rows` over `procs` watch, `f9444..9863`):

| Proc row | Steps | Notes |
|---|---|---|
| `gProcScr_GameControl <- tree 3` | `9444-9863` | `scrCur = 76`, `lockCnt = 1` (`9444..9862`) -> `0` (`9863`, `nextAction = 2`) |
| `gProcScr_SaveMenuPostChapter <- gProcScr_GameControl` | `9444-9862` | Entry `9444..9461`, select loop `9494..9802`, slot scroll `9803..9843`, fade-out `9844..9861`, exit `9862` |
| `ProcScr_savedraw <- gProcScr_SaveMenuPostChapter` | `9459-9862` | Started by `SaveMenu_InitScreen`; ended by `PostSaveMenuHandler` |
| `ProcScr_ApProc <- tree 3` | `9459-9814` | Suspend bookmark sprite (`sus_slot_cur = 0`), deleted when `unk_44 != 0x100` |
| `gProcScr_SaveDrawCursor <- ProcScr_savedraw` | `9459-9862` | Started by `SaveDraw_Init`; active every frame (`SaveDrawProc.unk_29 == 1`) |
| `ProcScr_BmFadeIN <- tree 4` | `9461-9492` | `NewFadeIn(4)` from `PL_SAVEMENU_SAVE_SLOT_SEL_FADEIN` |
| `ProcScr_BmFadeOUT <- tree 4` | `9845-9860` | `NewFadeOut(8)` from `PL_SAVEMENU_EXIT_FADE` (label 17) |

Observed subwindows, RAM/SRAM timing, and hardware transitions (`PC-cap1`):
- **Entry (`f9444..9461` -> `f9494`)**:
  - `f9444`: `Make6C_SaveMenuPostChapter` starts `gProcScr_SaveMenuPostChapter`; `SaveMenuInit` sets
    `jump_label = 5` (`PL_SAVEMENU_SAVE_SLOT_SEL`), `sus_slot = 0` (`ReadLastGameSaveId() == 0`),
    `main_options = main_sel_bitfile = 0x40` (`MAIN_MENU_OPTION_INVALID`), `unk_2f = 0xDC`;
    `SaveMenu_SetLcdChapterIdx` sets `gSaveMenuRTextData = (0, -1, -1)` and LCD/blend state, pausing
    at `scrCur = 5`. (`gPlaySt.save_menu_type == 2` and `chapterIndex == CHAPTER_L_1`, so the world
    map / Frelia chapter-override branches are NOT taken.)
  - `f9445..9458`: `SaveMenu_InitScreen` (`scrCur = 6`) spans 14 frames (VBlank fires mid-call):
    `f9450` sets `cursor_config = 0`, `cursor_slot = 0xFF`, `unk_44 = 0x100`; `f9451..9455` fill
    slot metadata (`chapter_idx = [0, 7, 6]`, `unk_3a = [1, 3, 3]`, `played_time = [0, 268746, 148852]`);
    `f9458` sets `sus_slot_cur = 0`.
  - `f9459`: `SaveMenu_InitScreen` calls `StartSaveDraw` (`ProcScr_savedraw` -> `SaveDraw_Init` starts
    `gProcScr_SaveDrawCursor`, `ProcScr_ApProc`, clears `gUnknown_020007E0`, installs
    `sHBlankHandler1 = HBlank_BgVerticalScroll` `0x08014CC5`, runs `SaveDraw_ScrollFogBG`).
  - `f9461..9493`: `SaveMenuDirectlySelectSlotOnPrepScreen` (`!(gBmSt.gameStateBits & BM_FLAG_PREPSCREEN)`)
    jumps to `PL_SAVEMENU_SAVE_SLOT_SEL_FADEIN` (`scrCur = 13`), running `NewFadeIn(4)` (`f9461..9492`).
    `SaveMenu_SaveSlotSelectLoop` (`scrCur = 16`) begins at `f9494`.
- **Idle & slot movement (`f9494..9802`)**:
  - `sus_slot` stays `0` throughout `f9444..9862`; no `DPAD`/`B`/`R` input occurs, so
    `SaveMenuTryMoveSaveSlotCursor`, `SaveMenuModifySaveSlot`, `LoadSaveMenuInfo`, and `sub_80AB514`
    never run. `SaveMenuWaitHelpBoxAnim` returns `false` every frame (`ctrl_timer == 0`).
- **Confirm prompt (`f9600`)**:
  - `A` at `f9600` calls `ExecSaveMenuMiscOption` (`MAIN_MENU_OPTION_INVALID`, `cursor_config == 0`):
    sets `cursor_config = 1`, `SaveMenuDrawSubSelBox(proc, 1)` writes 10 non-zero tile entries to
    `gBG1TilemapBuffer` row 17 cols 7..16 (`r09599` all `0` -> `f09600` `0x4080..0x4092`), and
    `SaveMenuHandleHelpBox` returns early (`MAIN_MENU_OPTION_INVALID` has no help box).
- **Confirm & 3-frame SRAM write (`f9800..9802`)**:
  - `s09350 == s09799`: `s09799` has valid game saves in slots 0..2 (`slot 0`: `chapterIndex = 0`,
    `time_saved = 0`, `SaveBlockInfo[0].checksum32 = 0x0EF3180F`) and valid suspend saves in slots
    `3` & `4` (`SAVEBLOCK_KIND_SUSPEND = 1`).
  - `A` at `f9800` calls `ExecSaveMenuMiscOption` (`cursor_config == 1`) -> `WriteGameSave(0)`,
    spanning 3 frames (`f9800..9802`):
    - `s09799 -> s09800`: `gPlaySt.time_saved` updates `5140 -> 10669`; 94 SRAM bytes change
      (`0x0094..0x00B3` invalidating suspend slots `3` & `4` to `SAVEBLOCK_KIND_INVALID = 0xFF`;
      `0x3FC4..0x3FDA` and `0x3FF0..0x4057` writing `GAME0` `PlaySt` with `chapterIndex = 1`,
      `time_saved = 10669`, and first packed units).
    - `s09800 -> s09801`: 45 SRAM bytes change (`0x473C..0x4743`, `0x480F..0x482C`, `0x4841`,
      `0x4C70..0x4C72`, `0x4D33..0x4D40`, `0x4D5D..0x4D73` in `GAME0` body).
    - `s09801 -> s09802`: 4 SRAM bytes change (`0x0070..0x0073`: `SaveBlockInfo[0].checksum32`
      `0x0EF3180F -> 0x5652A716`); `s09802 == s10100`.
    - `f9802`: `SaveMenuDrawSubSelBox(proc, 0)` clears `gBG1TilemapBuffer` row 17 cols 7..16 to `0`
      and sets `cursor_config = 0`; `Proc_Goto(proc, PL_SAVEMENU_SLOT_SELECTED)` sets `scrCur = 17`.
  - **Existing writer coverage**: `BmGameSave.writeGameSave` (`game/.../save/BmGameSave.kt`)
    already ports `WriteGameSave` and every callee (`InvalidateSuspendSave`, `WriteGameSavePackedUnit`,
    `Read/WriteGlobalSaveInfo`, `WriteGMMonsterRnState`, `WriteSupplyItems`, `WritePidStats`,
    `WriteChapterStats`, `WriteBonusContentClaimFlags`, `WritePermanentFlags`, `WriteWorldMapStuff`,
    `SaveDungeonRecords`, `WriteSaveBlockInfo`, `WriteLastGameSaveId`). No new `bmsave` writer
    functions are needed for the slot 0 write.
- **Post-write slot box scroll & metadata refresh (`f9803..9844`)**:
  - `f9804`: `SaveMenuRegisterSlotSelected` (`scrCur = 21`, `jump_label = 6`).
  - `f9805..9843`: `SaveMenuWaitSlotBoxScrolling` (`scrCur = 22`). `SetObjAffine(0, ...)` flips slot 0
    (`gOam` affine 0 `pd`: `256` -> `2048` at `f9812` -> `256` at `f9822`). At `scroll_cnt == 8`
    (`f9812..9814`): `SaveMenuInitSaveSlotData(0)` updates `chapter_idx[0]` `0 -> 1`,
    `played_time[0]` `0 -> 10669`, `gPlayStOptionBits[0]` animation bit `0 -> 2`;
    `SaveMenuInitSaveSlotData(4)` sees suspend save invalid and sets `unk_44 = 0xF0`;
    `PutChapterTitleGfx` and `SaveMenuInitSlotPalette(0)` refresh slot 0. At `f9815`, `sub_80AB05C`
    deletes `ProcScr_ApProc` (`approc = 0`) and `sub_80AACBC` steps `unk_44` (`0xE0..0x00` over
    `f9815..9829`, rotating `gOam` affine 3) until `sus_slot_cur = 0xFF` at `f9830`.
  - `f9838..9843`: `scroll_cnt == 0x20` runs `InitSaveMenuChoice` (and `IsExtra*` SRAM scans across
    frames), then `main_sel_bitfile == MAIN_MENU_OPTION_INVALID` jumps to `PL_SAVEMENU_EXIT_FADE`
    (`scrCur = 27` at `f9844`).
- **Exit (`f9844..9863`)**:
  - `f9845..9860`: `NewFadeOut(8)` / `ProcScr_BmFadeOUT` (`scrCur = 29`).
  - `f9862`: `PostSaveMenuHandler` (`scrCur = 32`, `main_sel_bitfile = 0x40`) calls `Proc_End(savedraw)`
    (`SaveDraw_OnEnd` -> `EndBgVerticalScroll`), calls `SetPrimaryHBlankHandler(0)`, and returns early.
  - `f9863`: save menu procs gone, `sHBlankHandler1 == 0`, `GameControl` unlocked at `scrCur = 76`
    with `nextAction = 2` (`GAME_ACTION_USR_SKIPPED`).

Existing save-menu / render code already in repo:
- `WriteGameSave` + all `bmsave` sub-calls (`BmGameSave.kt`, `BmSaveLib.kt`).
- `PostSaveMenuHandler` (`fe8.game.savemenu.PostSaveMenu`; `Resume`/`Restart` verified, `MAIN_MENU_OPTION_INVALID` branch transcribed/ready to verify on `f9862->9863`).
- `SaveDraw_ScrollFogBG`, `GetBgVerticalScrollBuffer`, `FlipBgVerticalScroll`, `HBlank_BgVerticalScroll`, `BG_SetPosition` (`SaveDraw.kt`, `BgVerticalScroll.kt`, `LcdControl.kt`).
- `sub_80895B4`, `PutChapterTitleGfx` (`ChapterTitle.kt`; `sub_80895B4` non-`8` config bits transcribed).
- `NewFadeIn`, `FadeInExists` (`ArchivedPaletteFade.kt`; `NewFadeOut`, `FadeOutExists` unported).

Corrected `PC-4a..4g` breakdown (splitting rows that exceed `<= 8` unported/transcribed functions):

| Row | Window (`PC-cap1`) | Functions run (unported / transcribed) | Already ported / not run in `PC-cap1` |
|---|---|---|---|
| `PC-4a1` (was `PC-4a` part 1) | `f9444`, `f9461..9493` | `Make6C_SaveMenuPostChapter`, `SaveMenuInit`, `SaveMenu_SetLcdChapterIdx`, `SaveMenuDirectlySelectSlotOnPrepScreen`, `InitSaveMenuHelpTextSt`, `ReadLastGameSaveId` (6) | Already `Ported`: `NewFadeIn`, `FadeInExists`. Not run: WM/Frelia chapter branches in `SetLcdChapterIdx`, prep-screen false branch (`NewFadeIn(8)`). |
| `PC-4a2` (was `PC-4a` part 2) | `f9445..9459` | `SaveMenu_InitScreen`, `SaveMenuPutChapterTitle`, `SaveMenuCopyPalette`, `SaveMenuInitSubBoxText`, `PutChapterTitleBG` (5) | Already `Ported`: `PutChapterTitleGfx`, `SetObjAffine`. |
| `PC-4b` | `f9459..9862` | `StartSaveDraw`, `SaveDraw_Init`, `SaveDraw_OnEnd`, `sub_80AB548`, `StartBgVerticalScroll`, `SetBgVerticalScrollPosition`, `sub_8014EF4`, `EndBgVerticalScroll` (8) | Already `Ported`: `SaveDraw_ScrollFogBG`, `HBlank_BgVerticalScroll`, `GetBgVerticalScrollBuffer`, `FlipBgVerticalScroll`. |
| `PC-4c1` (was `PC-4c` part 1) | `f9459..9862` | `SaveDraw_Loop_Main`, `sub_80AB05C`, `sub_80AADE0`, `sub_80AA7EC`, `sub_80AAE90` (early exit), `sub_80AAF6C` (early exit), `BitfileToIndex` (7) | Not run: `sub_80AABC4`, `sub_80AAC40`, `sub_80AB514`, main/extra menu branches, `cursor_config != 0` help-box branch. |
| `PC-4c2` (was `PC-4c` part 2) | `f9459..9862` | `sub_80AACBC`, `sub_80AA7AC`, `sub_80AB56C` (3; particle RNG uses `AdvanceGetLCGRNValue`) | Already `Ported`: `SaveDraw_ScrollFogBG`. |
| `PC-4d` | `f9459..9862` | `StartSaveDrawCursor`, `SaveDrawCursor_Init`, `SaveDrawCursor_Loop`, `sub_80AB4F4` (4) | Not run in `PC-cap1` (need slot-nav CAP): `SaveMenuTryMoveSaveSlotCursor`, `SaveMenuModifySaveSlot`, `sub_80AB514`. Moved to `PC-4e2`: `InitSaveMenuChoice`. |
| `PC-4e1` (was `PC-4e` part 1) | `f9494..9802` | `SaveMenu_SaveSlotSelectLoop`, `SaveMenuWaitHelpBoxAnim`, `ExecSaveMenuMiscOption`, `SaveMenuHandleHelpBox`, `SaveMenuDrawSubSelBox`, `SaveMenuDrawSubSelBoxExt` (6) | Already `Ported`: `WriteGameSave` (`BmGameSave`). Not run: `DPAD`/`B`/`R`, `LoadSaveMenuInfo`, non-`INVALID` options, `cursor_config == 2` cancel. |
| `PC-4e2` (was `PC-4e` part 2) | `f9803..9861` | `SaveMenuRegisterSlotSelected`, `SaveMenuWaitSlotBoxScrolling`, `InitSaveMenuChoice`, `AddMainMenuOption`, `AddExtraMenuOption`, `NewFadeOut`, `FadeOutExists` (+ `bmsave-lib` `IsExtra*` availability checks if not stubbed) | Already `Ported`: `PutChapterTitleGfx`, `LoadBonusContentData`. Not run: `scroll_cnt == 0x30` branch, `MAIN_MENU_OPTION_NEW_GAME` branch. |
| `PC-4f` | `f9450..9458`, `f9812..9814` | `SaveMenuInitSaveSlotData`, `SaveMenuInitSlotPalette`, `SaveDrawSetDifficultSlotPalette`, `GetChapterTitleExtra`, `IsGameNotFirstChapter`, `LoadSavedEid8A`, `ReadGameSavePlaySt`, `ReadSuspendSavePlaySt` (8) | Already `Ported`: `IsSaveValid`, `IsValidSuspendSave`, `sub_80895B4` (verify new `config` bits). Not run: `LoadSavedWMStuff`, `GetChapterIndexOnWmNode` (`save_menu_type != 4`), `PLAY_FLAG_HARD` palette pulse. |
| `PC-4g` | `f9862 -> 9863` | Verification only (`PostSaveMenuHandler` `MAIN_MENU_OPTION_INVALID` branch on `f9862 -> f9863`) | Already `Ported`: `PostSaveMenuHandler` (`PostSaveMenu.kt`). |

PC-4f result (2026-11-22): `PostChapterSaveMenuDifferentialTest` (`fe8.game.savemenu.SaveSlotData`, `BmSaveLib`, `ChapterTitle.getChapterTitleExtra`). Slot data runs `f9450..9458` and, after the slot 0 write, `f9814..9815` (the option-bit copy and the suspend slot 4 call land in `f9815`; `unk_44 = 0xF0` is already stepped to `0xE0` by `sub_80AACBC` in that frame). Capture shows `IsGameNotFirstChapter((PlaySt *)slot)` (decomp `BUG?`) reads BIOS bytes and answers true for slots 0..2, so it is Transcribed with a stand-in `PlaySt`. Not run: empty-slot branch, `LoadSavedWMStuff`/`GetChapterIndexOnWmNode`, `SaveDrawSetDifficultSlotPalette` (no `PLAY_FLAG_HARD` slot). Slot palette color 1 of bank `0x1A` is rewritten by the palette cycle (`PC-4c2`) and skipped at `f9815`.


## PC-6i-event inventory (2026-11-22)

Test: `Chapter1OpeningEventInventoryTest` (4 tests, `testing/src/test/kotlin/fe8/testing/`) over
`ram_trace_prologue_end` (`PC-cap1`), `f10298..f25580`. Dialogue/event branches only; map changes, unit
loads and movement (`Event2C_LoadUnits`, `Event30_ENUN`, `Event2F_MoveUnit`) are owned by `PC-6i-load`.
No function-entry trace exists, so entered handlers are inferred from `pEventCurrent` (`proc+0x38`; note
`+0x30` = `pEventStart`, `+0x34` = `pEventIdk`, the current CALL target) sampled once per frame, with the
opcode ids decoded from the runtime ROM (`opcode = hword >> 8`, `length = (hword >> 4) & 0xF` halfwords).
Handlers that run and return inside one frame (e.g. the `CALL` targets `EventScr_CallOnTutorialMode`,
`EventScr_MoveUnitS2ToLeader`) leave no cursor sample.

Windows (`ProcScr_StdEventEngine`, `execType` cutscene, `evStateBits` `0x0002` throughout unless noted):
- `f10298..25418`: `EventScr_Ch1_BeginningScene` (`0x089EF848`, `0x1E0` bytes) started by `gProc_BMapMain@12`
  (`CallBeginningEvents`, PROC_CALL_2); `evStateBits` `0x0002`. Engine absent `f10297`.
- Shared sub-scripts via `CALL` (`pEventIdk`): `EventScr_SetBackground` `f10497..10533` (fade out/in,
  `GFXLOCKED` `0x1000` set `f10514`), `Event_TextWithBG` `f10534..13006` (`gProcScr_Talk`, text 0x929-0x92A
  block) and `EventScr_TextShowWithFadeIn` `f13007..13048`; repeated at `f13164..13200`, `f16272..16309`
  (text/dialog `..20205`), `f20206..20247`. Each `EventScr_SetBackground`/`TextShowWithFadeIn` pair holds
  the engine lock count 1 during `ProcScr_FadeToBlack`/`FadeFromBlack` (`f10497`, `f13007`, `f13032`) and
  returns to the main script cursor (`f13049`: `+0x38`).
- Script battle `f15085..15525` (`FIGHT`: `Event3F_ScriptBattle`, lock 1, `ProcScr_ScriptBattleDeamon`);
  `gProcScr_FaceEndIn8Frames` (`REMA`) `f16006..16018` and `f25406..25418` (lock 1).
- End: `ENDA` at `f25419` with `execType` cutscene switches to `EventScr_08592114` (`pEventIdk`;
  `evStateBits 0x0092` = `NOSKIP|NOFADE|0002`); its `CHECK_EVBIT(10)`/`CALL(EventScr_08592140)` path takes
  the `NOFADE` branch (no fade); `ENDA` -> `EVC_END` -> `EventEngine_OnEnd` (`EV_EXEC_UNK5`, no
  `CHANGEGM`) at `f25422`: engine gone, `gBmSt.lock` `1 -> 0`.
- `f25423..25426`: `CallNextQueuedEvent` starts the second engine for `EventScr_Ch1_Turn1Player` (start =
  `0x089EFA28`, right after the script); `gBmSt.lock` `1`, gone `f25426` (lock `0`).
- `BMapMain` `12 -> 17` at `f25423` (`CallBeginningEvents` done, `UndeployEveryone`,
  `UnsetEventId_0x84`, `BmMain_ChangePhase`: `faction 0x40 -> 0x00`, `turn 0 -> 1`); `ProcScr_PhaseIntro`
  `f25429..25500` (outside this INV's scope); `gProcScr_PlayerPhase@5` `f25507`, `@16` (idle) from `f25510`
  with `BMapMain@31` and no event engine through `f25580`.

Flags (only changes in the window, `flags` watch): `gChapterFlagBits[0]` bit 0 (`EVFLAG_BATTLE_QUOTES`)
set `f15085` / cleared `f15526` (`ENUT`/`ENUF` around the `FIGHT`); `gChapterFlagBits[1]` bit 2
(`EVFLAG_TMP(11)`) set `f25419` (`ENUT` near the end); `gPermanentFlagBits[3]` bit 7
(`EVFLAG_HIDE_BLINKING_ICON`) cleared `f25423` (`UndeployEveryone`/`UnsetEventId_0x84`).

Entered handlers (status by `Ported from`/`Transcribed from` in `game/`; main script linear decode up to the
last sampled cursor `+0x1B4`):
- `Ported`: `Event02_EvBitAndIdMod`, `Event05_SetSlot`, `Event07_SlotQueueOperations`, `Event0A_Call`,
  `Event0C_Branch`, `Event0E_STAL`, `Event12_StartBGM`, `Event1A_TEXTSTART`, `Event1B_TEXTSHOW`,
  `Event1D_WaitForLockTalk`, `Event21_TextBg`, `Event22_ClearScreen`, `Event3B_DisplayCursor`,
  `Event3F_ScriptBattle`, `Event01_End`, `Event08_Label`, `Event09_Goto`, `EventEngine_OnUpdate`,
  `sub_800E640`, `UnlockGame`, `ClearCutsceneUnits`, `ReadGameSaveCoreGfx`, `UnpackChapterMapPalette`,
  `BmMain_ChangePhase`.
- `Transcribed`: `Event03_CheckEvBitOrId`, `Event06_SlotOperation`, `Event17_Fade`, `Event19_Checks`,
  `Event34_MessWithUnitState` (`DISA`/`KILL`/`DISA_IF` subcodes), `EventEngine_OnEnd`, `CallNextQueuedEvent`,
  `CallBeginningEvents`, `SetEventId_0x84`, `UndeployEveryone`, `UnsetEventId_0x84`, `EndTalk`, `EndCgText`,
  `sub_808BB74`, `ResetBkselPalette`, `ResumeMenu`, `ChangeUnitSpritePalette`.
- No `Ported from` tag (host-interface only / stand-in): `UpdatePrevDeployStates` (stand-in in `BMapMain`),
  `gProcScr_TalkSkipListener` proc body, `ProcScr_EventDisplayCursor` proc body.
- No Unported opcode handler: every opcode id of the main script has a ported or transcribed handler.

PORT rows: `PC-6e-a` (verify Transcribed script-core opcodes: `Event03`, `Event06`, `Event17`, `Event19`,
`Event34`), `PC-6e-b` (beginning-event tail in `BMapMain`/`EventEngine`: `CallBeginningEvents`,
`SetEventId_0x84`, `UpdatePrevDeployStates`, `UndeployEveryone`, `UnsetEventId_0x84`,
`EventEngine_OnEnd` `EV_EXEC_UNK5` path, `CallNextQueuedEvent`), `PC-6e-c` (event teardown helpers `EndTalk`,
`EndCgText`, `sub_808BB74`, `ResetBkselPalette`, `ResumeMenu`, `ChangeUnitSpritePalette` and the skip-listener
/ `EventDisplayCursor` proc bodies).

Corrections from PC-6e-b (2026-11-24, `Chapter1BeginningTailDifferentialTest`):
- `CallNextQueuedEvent` does not start the Turn 1 event: the call queue is empty `f25416..25430`. The
  Turn 1 engine at `f25423` comes from `BmMain_ChangePhase` -> `RunPhaseSwitchEvents`. Its proc dump
  there shows the new `pEventStart` (`0x089EFA28`) but the old engine's `idk`/`cur`/`evStateBits`/`execType`
  (unexplained).
- `BMapMain` stays at cursor 12 during the event because `GameLoop` skips tree 2 while `gBmSt.lock` != 0.
- `f25421` is a mid-step sample (`mainLoopEnded` 0, engine listed, lock 1); `f25422` is that step's end.
- `UpdatePrevDeployStates` (`fe8.game.bm.UnitDeployState`) is not wired in `Fe8Boot` yet
  (`BMapMain.hooks.updatePrevDeployStates` no-op): PC-6 WIRE.

Results from PC-6e-c (2026-11-25, `Chapter1TeardownDifferentialTest`):
- None of the non-talk teardown target procs (`gProcScr_CgTextMain`, all five box-dialogue procs
  `gProcScr_BoxDialogue` / `ProcScr_TalkBoxIdle` / `ProcScr_MergeBoxDialogue` /
  `ProcScr_BoxDialogueDrawTextExt` / `gUnknown_08A01800`, `gProcScr_BKSEL`, `sProc_Menu` /
  `sProc_MenuMain`) ever exists in `PC-cap1` (`f10297..25580`), `B`/`START` is never pressed
  there, and every `gProcScr_Talk` ends on its own (`f13005`, `f15005`, `f16004`, `f20204`, `f25404`)
  before `REMA` (`f13006`, `f15006`, `f16005`, `f20205`, `f25405`) and before `EventEngine_OnEnd`
  at `f25422`. Per rule 4, `EndTalk`, `EndCgText`, `sub_808BB74`, `ResetBkselPalette`, and
  `ResumeMenu` remain `Transcribed`.
- `EventEngineProc.mapSpritePalIdOverride` (`+0x44`) is 0 throughout `f10298..25423`, and
  `gPaletteBuffer` OBJ banks 27..31 at `f25420..25422` (and every non-`FIGHT` dump in
  `f10297..25580`) already hold the standard `gPal_LightRune` / `gPal_MapSprite` palettes, so
  `ChangeUnitSpritePalette` stays `Transcribed`. The tail teardown at `f25420 -> f25422` runs the
  real hooks and verifies `gActiveFont` reset (`sTalkFont` -> `gDefaultFont`), `sModifiedPalette`
  set to 1, `gBmSt.lock` -> 0, unchanged `gPaletteBuffer`, and ended event/talk/skip/face-wait
  procs. (`Fe8Boot.endEventTalk` also wired to call `endTalk`/`endCgText`/`endBoxDialogue`.)
- Extracted `gProcScr_TalkSkipListener` into `fe8.game.scene.TalkSkipListener` (`TalkScene` and
  `TalkRig` share it); `TalkSkipListener_OnIdle` upgraded to `Partially ported` against `PC-cap1`
  `f10534..10820` (`assertTalkWindow1`: proc lifecycle, `A` setting `instantScroll = 1` at
  `f10600`, `TalkWaitForInput` guard at `f10800`) and `f25390..25405` (`assertTalkWindow5Tail`:
  guard at `f25400`, `Talk_OnEnd` ending `TalkSkipListener` at `f25404`). Unexercised branches
  remain `shiftClearAll`/`shiftClear` guards with non-zero keys, `TALK_FLAG_NOSKIP`/`NOFAST`, and
  `B`/`START`/`DPAD_ANY`.
- Verified `ProcScr_EventDisplayCursor` (`createProcScrEventDisplayCursor`, `EventDisplayCursor_Loop`,
  `PutMapCursor`) across all five Chapter 1 opening cursor windows (`10436..10495`, `13103..13162`,
  `15558..15617`, `16211..16270`, `20269..20328`) and hardware `OAM[0..3]` at `f15600` and `f20300`.


## PC-6i-load inventory (2026-11-22)

Test: `Chapter1LoadInventoryTest` (4 tests, `testing/src/test/kotlin/fe8/testing/`, 0 skipped) over
`ram_trace_prologue_end` (`PC-cap1`), `f10298..f25580`. Method as `PC-6i-event`: no function-entry trace, so
windows come from `EnemyPhaseInventoryTest.rows` (proc lifetimes), the event engine cursor (`proc+0x38`) and
the `blue`/`red`/`rng` watches plus `ewram` regions (`gUnitArrayGreen`, `gBmMapUnit`/`Other`/`Terrain`/
`Hidden`, trap pool). Unit checks are structural (`pChar`/`pClass` pointers, `xPos`/`yPos`, `state`,
slot occupancy, RNG draw frames); stats are not embedded (rule 3).

Map changes: none. `gBmMapTerrain`, `gBmMapHidden` and the trap pool equal their `f10297` values at every
checked frame; the map is 15x10 throughout. The only map writes are `gBmMapUnit` (unit ids) and `gBmMapOther`
(MU reservations, cleared by `Event2C`/`Event2F`/`Event30` via `BmMapFill(gBmMapOther, 0)`).

Windows (cursor offsets are from `EventScr_Ch1_BeginningScene`):
- `f10297 -> f10298`: pre-event `UpdatePrevDeployStates`/player unit hide: blue slots 0/1 (Seth/Eirika) `xPos`
  to -1.
- W1 `f10298..10378` enemy `Event2C_LoadUnits` (+0x04) then `Event30_ENUN` (+0x0C): 7 red units (slot 0
  Breguet, slots 1-6 generic) load throttled by `CanStartMu` (`pCallback` = `EventLoadUnitWithMovement`):
  2 at `f10299`, 4 `f10300`, 5 `f10347`, 6 `f10352`, 7 `f10358`; `UnitAutolevel` draws RNG only on
  `f10299/10300/10347/10352/10358`. `ProcScr_MuCtrl`/`ProcScr_Mu` per unit (`f10299..10376`),
  `ProcScr_MuStepSe` bursts; `ENUN` leaves `+0x0C` at `f10378`. `gBmMapUnit` fills at `f10346..10360`.
- W2 `f13049..13103` NPC load (+0x38, green slots 0/1: cavalier and the Frelian unit), ENUN (+0x40), `DISA`
  (+0x4C -> +0x54) of green slot 0; green slot 1 waits on slot 0 (`MuCtr` dependency) until `f13059`.
- W3a `f15049..15084` Breguet `Event2F_MoveUnit` (+0x7C -> +0x80), tile (2,5) -> (2,3), speed 0 (16 Q4/f).
- W3b `f15526..15558` `KILL`/`DISA_IF` (+0xC8 -> +0xD0) after `FIGHT`: `MU_StartDeathFade`,
  `ProcScr_MuHitFlash`, `ProcScr_PalFade`; green slot 1 `curHp` 0, `state` bit set `f15530`, slot cleared
  by `f15560`.
- W4 `f16019..16153` seven `Event2F_MoveUnit` (+0xF4..+0x154) with `Event30_ENUN` (+0x15C -> +0x160); MU speed
  24 Q4/f; 2-leg REDAs; BestMove tie-break draws on `f16022`/`f16040`; red end tiles equal at `f16155`
  and `f25580`.
- W5 `f20249..20268` ally `Event2C_LoadUnits` (+0x18C), blue slots 0/1 at (14,9) then settle (13,8)/(12,9);
  draw at `f20250`, `ProcScr_MuCtrl`/`Mu` gone `f20267`.
- W6 `f25421..25580`: engine end, `CallNextQueuedEvent`, `UndeployEveryone` (see `PC-6i-event`); no unit or
  map changes after `f20270` (`gBmMapUnit` identical `f20270..25580`).

Functions entered (decomp chains; `Ported` / `Transcribed` per `Ported from`/`Transcribed from`):
- `Event2C_LoadUnits` -> `GetLoadUnitsAmount`, `GetUnitDefinitionFromEventScr` (both `Ported`),
  `BmMapFill` (`Ported`), `EventLoadUnitWithMovement` (`Transcribed`) -> `ModifyMoveUnitFlag`,
  `TryPrepareEventUnitMovement` (`CanStartMu`), `ShouldUNITBeLoaded`, `IsUnitSlotAvailable` (all
  `Transcribed`) -> `LoadUnit_800F704` (`Transcribed`) -> `LoadUnit`, `UnitLoadStatsFromChracter`,
  `UnitLoadSupports`, `UnitAutolevel`, `UnitAutolevelCore`, `GetAutoleveledStatIncrease` (`Ported`),
  `GenUnitDefinitionFinalPosition`, `MoveUnit_`/`MoveUnitExt` (`Transcribed`).
  `Event2C` itself is `Ported` (subcode 3 `GetChapterEnemyUnitDefinitions` throws, not in this window).
- `MuCtr_StartDefinedMove`, `MuCtr_InitDefinedMove`, `sub_807A0E4`, `sub_807A194`, `sub_807A294`,
  `MuCtr_ExecREDA_807A358`, `AdjustNewUnitPosition`, `sub_807A644`, `MuCtr_OnEnd`, `MuCtrExists`,
  `SetMuMaxWalkSpeed_`: all `Transcribed` (`MuCtrl.kt`, not verified); `sub_807A300`/`sub_807A324`,
  `CopyEventMoveREDAs`/`ClearEventMoveBuffer`: no `Ported from` tag (Kotlin counterparts only).
- `StartMu`/`StartMuInternal`, `RunMuMoveScript`, `Mu_OnStateMovement`, `GetMuQ4MovementSpeed`, `Mu_OnLoop`,
  `MU_OnEnd`/`EndMu`, `PlayMuStepSe`, `StartPlayMuStepSe`, `MuStepSe_PlaySeA/B`, `MU_StartDeathFade`,
  `MuDeathFade_OnLoop`, `StartMuHitFlash`: `Ported` (`Mu.kt`); `MuStepSe_Init`, `UpdateMuStepSounds`,
  `StartMuExt`/`MuExists`: `Transcribed`.
- `Event2F_MoveUnit`, `Event30_ENUN`: `Ported` but verified for end tiles only; `Event30` also runs
  `RefreshEntityBmMaps`, `RefreshUnitSprites`, `RenderBmMap` (`Ported`). `Event34` (`DISA`/`KILL`/`DISA_IF`) is
  owned by `PC-6e-a`; `ClearUnit` `Ported` (`PC-6l-a`); `UnitKill` `Ported`.
- `PC-6l-a` W1 (`f10298..10378`, `Chapter1EnemyLoadDifferentialTest`): `ClearUnit` `Ported`;
  `EventLoadUnitWithMovement`, `ModifyMoveUnitFlag`, `TryPrepareEventUnitMovement`, `ShouldUNITBeLoaded`,
  `IsUnitSlotAvailable`, `LoadUnit_800F704`, and `sub_800F8A8` `Partially ported` (VBlank frames
  `10299/10300` overrun `Event2C_LoadUnits` and `10347/10352/10358` overrun `gBmSt.mainLoopEnded == 0`;
  `r10298` `gUnitArrayRed[0..6]` holds dirty Prologue bytes so `ClearUnit` is exercised; Ch1 applies
  `easyModeLevelMalus == 0` / bonus 0; `sub_800F8A8` takes `MuCtr_StartDefinedMove` with `MoveUnit_`/`MoveUnitExt`
  unentered); `GenUnitDefinitionFinalPosition` stays `Transcribed` (mutation to `(0, 0)` survives W1).
- `PC-6l-b` W1/W2/W4 (`f10298..10378`, `f13048..13103`, `f16018..16153`, `Chapter1MuCtrlDifferentialTest`):
  extracted `sub_807A300` and `sub_807A324` from `sub_807A294` in `MuCtrl.kt`; `MuCtr_StartDefinedMove` and
  `sub_807A300` upgraded to `Ported`; `MuCtr_InitDefinedMove`, `sub_807A0E4`, `sub_807A194`, `sub_807A294`,
  `sub_807A324`, `MuCtr_ExecREDA_807A358`, `MuCtr_OnEnd`, and `IsMuActive` upgraded to `Partially ported` per
  rule 4 break-checks (every `timer > 0` leg across `PC-cap1` has `delayFrames == 0` and `a == 0`, so `sub_807A0E4`
  never waits at `timer != 0`; W2 `green[1]` waits on `green[0]` via `sub_807A300`/`sub_807A324` across
  `f13050..13058` and unblocks on leg transition `0 -> 1` at `f13059`; W4 has 7 single-leg `MOVE` walks at
  speeds 16/24 Q4/f with 4-slot throttle and `BestMove` tie-breaks at `f16022`/`f16040`; `fastWalkB`, `A_BUTTON`,
  flags `2/4/8`, and `AdjustNewUnitPosition` on occupied tiles remain unexercised).

PORT rows (each one window, <= 8 functions; verify-or-port against the `f`-windows above):
`PC-6l-a` W1 load path (`EventLoadUnitWithMovement`, `ModifyMoveUnitFlag`, `TryPrepareEventUnitMovement`,
`ShouldUNITBeLoaded`, `IsUnitSlotAvailable`, `LoadUnit_800F704`, `GenUnitDefinitionFinalPosition`,
`ClearUnit`), `PC-6l-b` W1/W2/W4 `MuCtrl` start and leg runner (`MuCtr_StartDefinedMove`,
`MuCtr_InitDefinedMove`, `sub_807A0E4`, `sub_807A194`, `sub_807A294`, `MuCtr_ExecREDA_807A358`,
`MuCtr_OnEnd`, `sub_807A300`/`sub_807A324`), `PC-6l-c` W1/W4/W5 position and path helpers
(`AdjustNewUnitPosition`, `sub_807A644`, `MoveUnit_`, `MoveUnitExt`, `SetMuMaxWalkSpeed_`, `MuCtrExists`,
`CopyEventMoveREDAs`, `ClearEventMoveBuffer`), `PC-6l-d` W1/W4 MU step sound and start (`StartMuExt`,
`MuExists`, `MuStepSe_Init`, `UpdateMuStepSounds`, `PlayMuStepSe` start/foot-class), `PC-6l-e` W3a/W4
`Event2F_MoveUnit`/`Event30_ENUN` full state (`gBmMapUnit`/`Other` per frame, not just end tiles) and W2/W3b `KILL`
fade (`UnitKill`, `MU_StartDeathFade`, `MuDeathFade_OnLoop`, `StartMuHitFlash`).

### BA-i2 result

`BattleHitParseInventoryTest` (EWRAM dumps per step; no function-entry trace exists, so "observed" below means
inferred from state before/after one step, not logged calls). Windows:

| | `ram_trace_prologue_attack` | `ram_trace_prologue_battle` |
|---|---|---|
| Hit array final | f1430 (f1463 is mid-rewrite: only hit 0 kept, then final again) | f14430 |
| Parse step (`gAnimRoundData`, `gEfxHpLut`, `gEkrInitialHitSide`, `gBanimValid`, `gEkrGaugeHp` all 0 before) | f1464 | f14431 |
| `ekrBattle` proc (`gpProcEkrBattle`) -> anims | proc f1496, `gAnims[0..3]` set f1497 | proc f14467, anims f14468 |
| Hits | 3: BEGIN 7, RETALIATION 9, FOLLOWUP 7, then END | 2: BEGIN+MISS, RETALIATION 17, then END |
| `gAnimRoundData` (L,R per hit) | (6,0) (0,6) (6,0), rest 0xFFFF | (4,9) (0,6), rest 0xFFFF |
| `gEfxHpLut` | 0x16 0x10 | 0xf 7 | 8 (rest 0xFFFF) | 0x22 0x1e | 0xFFFF 0xd |

Both fights are `EKR_DISTANCE_CLOSE`, `gBanimPositionIsEnemy` = (1,0), so `gEkrInitialHitSide` = POS_R, triangle
units NULL, `gEfxHpLutOff` 0; outputs are read-only to the window end. Actor setup: `gAnims` are four consecutive
0x48 pool entries (`0x02028f78`), state ENABLED, y 0x58, x 0x5C (left pair) / 0x94 (right pair), `currentRoundType` 6
(`ANIM_ROUND_TAKING_HIT_CLOSE`, the `InitMainAnims` CLOSE arm); `gEkrBgPosition` 0 (the `gEkrInitPosReal != 0` arm).
`state2` is rewritten over the next frames (0x2400 -> 0x0400, 0x4500 later), not asserted.

Functions run (inferred, branch in brackets): `PrepareBattleGraphicsMaybe` -> `ParseBattleHitToBanimCmd`
[not PROMOTION, not REFRESH; no Runesword/axe far remap; per hit: non-crit non-silencer arm with
`UnitHasMagicRank` false, MISS arm in the scripted window only; no TATTACK, no DEVIL; HP chain via `GetEfxHp`];
`ekrBattle_Init` [`gEkrInitPosReal != 0`, ShouldCallBattleQuote; arena flag false -> timer 0x1E] ->
`InitMainAnims` [CLOSE arm] -> `InitBattleAnimFrame` -> `InitLeftAnim`, `InitRightAnim` (both `gBanimValid`) ->
`AnimCreate` x4, `RegisterEkrDragonStatusType`, `InitEkrDragonStatus`. Not run: PROMOTION/REFRESH returns, FAR and
FARFAR arms, crit/silencer, devil, triangle attack, dragon.

Existing Kotlin: `BanimPrepare.parseBattleHitToBanimCmd` (and `sub8055BC4`, `GetEfxHp`) is ported and verified by
`BanimPrepareDifferentialTest` at three map-animation attacks (state after the call; same function and variables).
The two animated windows add no new branch for it, only an extra check (rebuilt array, the CLOSE arm in an
animation-on run). Nothing covers `ekrBattle_Init`, `InitMainAnims` and the Anim init (no Kotlin `Anim`/`AnimCreate`
user in the battle path), and `gAnims` structures need `anime.c` (`AnimCreate`, `AnimDelete`, pool) first.

Proposed rows:

| ID | Kind | Task | Acceptance | Deps | Status |
|---|---|---|---|---|---|
| BA-p2a | PORT | Anim pool and `AnimCreate` for the two animated windows (anime.c: `AnimCreate`, `AnimInit`/pool helpers it runs, <= 8 functions) | `gAnims` structs 0x48 stride, f1497 and f14468 Anim bytes (state, x, y, timer, oam2Base, priority, round type) equal the dumps | BA-i2 | todo |
| BA-p2b | PORT | `InitMainAnims`, `InitBattleAnimFrame`, `InitLeftAnim`, `InitRightAnim` (CLOSE arm only) | `gAnims[0..3]` and `gEkrXPos*/YPos*Real/Base`, `gEfxHpLutOff` equal the dumps at f1497 and f14468; first-frame `state2` compare; mutation checked | BA-p2a | todo |
| BA-p2c | PORT | `ekrBattle_Init` (+ `RegisterEkrDragonStatusType`, `InitEkrDragonStatus`, `ShouldCallBattleQuote`) | `gEkrBgPosition`, `gCtrlC01Blocking`, proc timer/is_quote/unk58 at f1496 and f14467 equal the dumps; the scripted quote branch (Talk f14480) vs the attack's | BA-p2b | todo |
| BA-cap2 | CAP | Parse variants: crit, far/runesword, devil, triangle, REFRESH, PROMOTION battle-animation windows | Hit array and `gAnimRoundData`/`gEfxHpLut` dumps at the parse step per variant | BA-i2 | todo |

### BA-i3 result

`BattleAnimCommandInventoryTest` (no function-entry trace: events inferred from `Anim::pScrCurrent` movement between
per-step EWRAM dumps, words decoded at runtime from the same dump or the ROM). Window: `ram_trace_prologue_attack`,
anims set f1497, waiting loop f1499..f1536, round 1 (right pair, `gAnims[2,3]`, Eirika/Soldier first hit) f1537..f1630
(`efxHPBar` f1577..1611 inside it); the left pair (`gAnims[0,1]`) takes the hit and starts its own round at f1622.
Struct offsets match `include/anime.h` (state2 0x0C, state3 0x10, round type 0x12, queue size 0x14, queue 0x15,
`pScrCurrent` 0x20). Anims 0/2 run RAM scripts (`gBanimScr*` buffers), anims 1/3 ROM default scripts first.

| Frames | Observed | Handler |
|---|---|---|
| f1497..1498 | pool entries ENABLED, state2 `0x400 + idx*0x100` | `AnimCreate` / `InitLeftAnim`/`InitRightAnim` (BA-i2) |
| f1499..1536 | anims 0/2: FRAME, `C01` loop (2-step period; `pScrCurrent = pScrStart`); anims 1/3: STOP word, state2 STOP bit | `AnimUpdateAll` -> `AnimInterpret` (FRAME, STOP, COMMAND arms); `BattleAIS_ExecCommands` `ANIM_CMD_WAIT_01` pre-battle arm |
| f1537 | all four state3 = C01_BLOCKING_IN_BATTLE; right pair round type 0 (HIT_CLOSE), left pair 6; scripts switched (jump) | `ekrBattleTriggerNewRoundStart` (NEW_ROUND_START + STOP) then `BattleAIS_ExecCommands` NEW_ROUND_START arm: `GetAnimNextRoundType`, `SwitchAISFrameDataFromBARoundType`, `AnimScrAdvance` |
| f1538..1539 | `C03` (wait attack start), `C07` (attack start, plain arm: no SURE_SHOT/GREAT_SHIELD/PIERCE) | `ExecCommands` `ANIM_CMD_WAIT_03`, `0x07` |
| f1545, 1573, 1578, 1626 | `C43`, `C24`, `C1f`, `C34` | `EfxPlaySEwithCmdCtrl` (sound group) |
| f1574 | `C04`: state3 -> 0x0d (TAKE_BACK_ENABLE, HIT_EFFECT_APPLIED) | `ANIM_CMD_WAIT_04`; the miss branch (`CheckRoundMiss`) not taken |
| f1577 | `C1a` same step `efxHPBar`, `EfxHitQuake`, `efxNormalEffect`, `efxFlashUnit` start | `0x1A`: `StartBattleAnimHitEffectsDefault`, `NewEfxNormalEffect` (non-silencer arm) |
| f1588..1610, f1611 | `C01` on 0 (hold), 2, 3; released at f1611, state3 cleared | `ANIM_CMD_WAIT_01` in-battle arm, `CheckEkrHitDone` |
| f1621..1622 | `C06` on 2/3 queues NEXT_ROUND_START on the left pair; left scripts switch to round 0 | `0x06`, NEXT_ROUND_START arm |
| f1630 | `C0d` on 2/3: round type 0 -> 6 (script switch to TAKING_HIT_CLOSE standing) | `0x0D`, `ANIM_ROUND_HIT_CLOSE` arm |

Instruction kinds reached: FRAME and COMMAND only (plus STOP/loop reset); no MOVE, WAIT, END, forced sprite or pointer
instruction. Command ids: `01 03 04 06 07 0d 1a 1f 24 34 43` (`0d` seen as queue byte plus script switch; `42` appears later, left pair f1655, outside the asserted window). The queue
always drains inside the step (`commandQueueSize` 0 at every dump); wait ids (01, 03, 04, 07 here) hold `pScrCurrent`.

Not reached in this round: `C02`/`C18` (dodge), `C05` and spells, `C0x08..0c` crit/pierce, `C13`, `C14`/`C15` quake
commands, `C2d`/`C2e`.. skill effects, `C47`/`C4e`, layer commands `0x71`/`0x72`, `AnimInterpret` END/MOVE/WAIT/PTR
arms, `Anim_8005334`, the miss/`0x0D` MISS/FAR arms, `NewEkrChienCHR`. Gaps: wait-time (hold) frames carry no queue
history after a drain (same id twice in a row looks like one event); `AnimDisplayPrivate` output needs OAM dumps (the
dumps' `oam.bin` exist only every 10th step); the sound commands only have the EWRAM-visible state, not PSG output.

Existing Kotlin: `gbafe.anim` has `Anim`/`AnimPool` (`AnimCreate`, `AnimInsert`); `AnimInterpret`, `AnimUpdateAll`,
`BattleAIS_ExecCommands`, `AnimScrAdvance` and `SwitchAISFrameDataFromBARoundType` have no Kotlin user.

Proposed rows:

| ID | Kind | Task | Acceptance | Deps | Status |
|---|---|---|---|---|---|
| BA-p3a | PORT | `AnimInterpret` (FRAME, STOP, COMMAND incl. wait-id `pScrCurrent--`, LOOP/END/WAIT/MOVE as non-captured arms) + `AnimUpdateAll` timer loop | f1499..f1536 anims 0..3 `pScrCurrent`/`timer`/`state2`/queue equal the dumps per step, driven from f1498; mutated FRAME or COMMAND arm fails | BA-i3 | todo |
| BA-p3b | PORT | `BattleAIS_ExecCommands` skeleton: queue drain, `end_exec_loop` masks, FRAME arm with `GetAISLayerId`, `C03`/`C04`/`C07`/`C01` (pre-battle and in-battle arms) | state3/state2/`pScrCurrent` of the four anims f1537..f1611 equal the dumps | BA-p3a | todo |
| BA-p3c | PORT | `C1a`/`C06`/`C0d` arms + `GetAnimNextRoundType`, `SwitchAISFrameDataFromBARoundType`, `AnimScrAdvance` | f1537, f1621..1622 and f1630 script pointers, round types and nextRoundId equal the dumps | BA-p3b | todo |
| BA-p3d | PORT | `ekrBattleTriggerNewRoundStart` + NEW_ROUND_START / NEXT_ROUND_START arms | f1537 state3 and round types, `gBanimDoneFlag` reset, proc timer 0x1E gate | BA-p3b | todo |
| BA-p3e | WIRE | `EfxPlaySEwithCmdCtrl` sound ids `1f 24 34 42 43` behind the audio interface | needs a PSG/audio capture (BA-cap1); until then only the queue ids are asserted | BA-cap1 | todo |

### BA-i4 result

`BattleRoundHpInventoryTest` (no function-entry trace: inferred from `gEkrGaugeHp`, `gEfxHpLutOff`, `gEkrHitNow`,
`gEkrHpBarCount`, `gBanimDoneFlag`, the live `ProcEkrBattle`/`ProcEfxHpBar` fields and the `ekrBattle`
`proc_idleCb` pointer mapped to a decomp name via the nm file at runtime). Window `ram_trace_prologue_attack`,
`ekrBattle` f1496..1988; hits: right hits left (22 -> 15), left hits right (16 -> 7), right hits left (15 -> 8).
`gEkrGaugeHp` = (left, right) halfwords. Every step is dumped; one step (f1582, f1789) shows the bar proc unadvanced.

| Frames | Observed | Handler |
|---|---|---|
| f1496..1497 | `proc_idleCb` = `ekrBattle_Init` then 0 (script runs `ekrBattleMain`), timer 0x1E | `ekrBattle_Init`, `ekrBattleMain` (BA-p2c) |
| f1499..1506 | idle chain: PrepareDragonIntro f1499, ExecDragonIntro f1500, PostEkrDragonIntro f1503, SetFlashingEffect f1504, ExecTriangleAtk f1505, TriggerNewRoundStart f1506; `ekrBattleMain` took the `is_quote == false && unk58 == false` arm at timer 0x1F (no namewin/pre-event) | `ekrBattleMain` -> `ekrBattlePrepareDragonIntro` (no dragon: `ExecDragonIntro` passes) -> `ekrBattlePostEkrDragonIntro` -> `ekrBattleSetFlashingEffect` (no flash) -> `ekrBattleExecTriangleAtk` (no triangle) |
| f1506..1536 | `ekrBattleTriggerNewRoundStart`, proc timer counts to 0x1F | `++timer <= 0x1E` early return |
| f1537 | idle `ekrBattle_80503EC` (state3 / STOP set on all four anims, `gBanimDoneFlag` both 0: BA-i3) | `ekrBattleTriggerNewRoundStart` tail (both `gBanimValid` arms) |
| f1537..1539 | `ekrBattle_80503EC` -> `ekrBattle_StartPromotion` -> `ekrBattleInRoundIdle` (one step each) | `ekrBattle_80503EC`, `ekrBattle_StartPromotion` (not-promotion arm) |
| f1539..1828 | idle stays `ekrBattleInRoundIdle` through all 3 hits; no second `TriggerNewRoundStart`, no `ArenaContinueBattle`/`ParseBattleHitToBanimCmd` re-run | `ekrBattleInRoundIdle` `EKR_DISTANCE_CLOSE`, `done[0]+done[1] != 2` return path; later rounds start via anim script `C06` (BA-i3) |
| f1577 / f1659 / f1784 | `efxHPBar` created in the step of `C1a`: `gEkrHpBarCount` 0 -> 1, `gEkrHitNow[side]` = 1, `this`/`cur` = HP before, `next` = HP after, `diff` -1, timer/timer2/finished 0 | `NewEfxHpBar` (non-GREAT_SHIELD, count 0); `GetEfxHp(off*2+pos)` from `gEfxHpLut`, side = the side taking the hit |
| first decrement f1579 / f1661 / f1786 | every second proc run: `timer` toggles 0/1, `cur` and `gEkrGaugeHp[side]` -1 together; 7, 9 and 7 steps; spacing 2 steps, 3 across the unadvanced step (f1581 -> 1584, f1788 -> 1791); `timer2` +1 per run | `EfxHpBar_DeclineToDeath` (`++timer == 2` arm) |
| f1592 / f1677 / f1799 | `cur == next`: `finished` = 1, gauge at final value (15, 7, 8), timer2 14 / 18 / 14 | `EfxHpBar_DeclineToDeath` (`cur == next` arm) |
| f1609 / f1690 / f1816 | timer2 stuck at 0x1E and `finished`: `gEfxHpLutOff[side]` +1, `gEkrHitNow[side]` = 0, proc script advances next step; `next` > 0 so neither death arm (`NewEfxDead`/`NewEfxDeadEvent`) runs | `EfxHpBar_DeclineToDeath` (timer2 gate, `next != 0`), `Proc_Break` |
| f1610 / f1691 / f1817 | script index 3, `cur` = 1 (`gEfxBgSemaphore` and `gEfxSpellAnimExists` 0, so no wait; `CheckRound2` arms with 16/20 not taken) | `EfxHpBar_MoveCameraOnEnd` (`cur = 1` arm) |
| f1611 / f1692 / f1818 | script index 4, timer 1 == `cur`: `gEkrHpBarCount` -> 0; proc gone the next step | `EfxHpBar_WaitCameraMove` (`timer == cur` arm; `cur - 4` arm not reached since cur 1) |
| f1819, f1828 | `gBanimDoneFlag[0]` = 1 (f1819), `[1]` = 1 (f1828); idle -> `ekrBattleOnBattleEnd` f1829 | set by the anim scripts (`ANIM_CMD` end; BA-i3 gap), `ekrBattleInRoundIdle` `== 2` arm with `GetBattleAnimArenaFlag() == 0` -> `ret = 1` |
| f1829..1988 | idle chain `ekrBattleOnBattleEnd` f1829, `ekrBattle_8050600` f1830 (count 0, no dead event, namewin gone), `ekrBattle_WaitForPostBattleAct` f1831, `ekrBattleExecExpGain` f1861, `_80508F0` f1862, `_8050940` f1875, `ekrBattleWaitExpBarIdle` f1886, `ekrBattlePostExpBarIdle` f1897, `_8050AB8` f1928, `ekrBattleLvupHanlder` f1941, `ekrNewEkrPopup` f1982, `WaitForPopup` f1983, `PostPopup` f1984, `TriggerDragonStatusFinished` f1985, `PostDragonStatusEffect` f1988, ended f1989 | post-battle chain (BA-i7/BA-i8 territory); each is entered once, branch inside not resolved here |

Not reached: `NewEfxHpBar` early return (GREAT_SHIELD or count != 0), `EfxHpBar_DeclineToDeath` death arms, `gEfxHpLutOff`
past the end, `EfxHpBar_MoveCameraOnEnd` `CheckRound2` arms (16/20) and `NewEfxFarAttackWithDistance`,
`EfxHpBar_WaitCameraMove` `cur - 4` arm, `NewEfxHpBarResire*`, `NewEfxHpBarLive`, `NewEfxNoDamage*`, `NewEfxStatusCHG`,
arena arms of `ekrBattleInRoundIdle` (`ArenaSetResult`, speedup, the `val == 2` redo-round path), MONOCOMBAT and
PROMOTION distance arms, `ekrBattle_HandlePreEventMaybe`/quote arm, `ekrBattleExecDragonIntro` dragon arms,
`ekrBattleExecTriangleAtk` active arm. The scripted window (`ram_trace_prologue_battle`) adds the quote arm of
`ekrBattleMain` (`NewEkrWindowAppear`/`NewEkrNamewinAppear`, `ekrBattle_HandlePreEventMaybe`) and the miss round (no
`efxHPBar`; `EfxAvoid`); its idle chain is not inventoried here.

Gaps: the unadvanced steps (f1582, f1789) are unexplained (frame skip or proc-tree run gate; the cause needs a
proc-run trace); `gEkrHpBarCount`/`gBanimDoneFlag` writers outside `efxHPBar` are seen only as state; post-battle idle
functions (f1829..1988) are single-step transitions with unknown inner branches (exp/levelup/popup state not dumped
beyond EWRAM); `gEkrHPs`-style values are not exposed apart from `gEkrGaugeHp`.

Existing Kotlin: `EkrBattleInit.kt` (`ekrBattle_Init`, `RegisterEkrDragonStatusType`, `ShouldCallBattleQuote`),
`BanimSetup.kt` (`GetEfxHp`), `BanimPrepare.kt` (hit parse writes `gEfxHpLut`/`gEkrGaugeHp`). No Kotlin for `ProcEkrBattle`
callbacks after `ekrBattle_Init`, `InBattleMainRoutine`/`MainUpdateEkrBattle`, `ProcEfxHpBar` or `CheckEkrHitDone`.

Proposed rows:

| ID | Kind | Task | Acceptance | Deps | Status |
|---|---|---|---|---|---|
| BA-p4a | PORT | `NewEfxHpBar`, `EfxHpBar_DeclineToDeath` (non-death arm; death arms as non-captured), `CheckEkrHitDone`, `CheckEkrHitNow` | f1577..f1609 / f1659..f1690 / f1784..f1816 per-step `gEkrGaugeHp`, `gEfxHpLutOff`, `gEkrHitNow`, `gEkrHpBarCount`, proc `timer/cur/timer2/finished/this/next/diff` equal the dumps (driven from the start step; the unadvanced f1582/f1789 steps are skipped by the driver); mutated step size or timer gate fails | BA-i4, BA-p2b | todo |
| BA-p4b | PORT | `EfxHpBar_MoveCameraOnEnd` (`cur = 1` arm), `EfxHpBar_WaitCameraMove`, `ProcScr_efxHPBar` script order | f1609..f1612, f1690..f1693, f1816..f1819 script index, `cur`, `timer`, `gEkrHpBarCount` equal the dumps; `CheckRound2` arms left as non-captured | BA-p4a | todo |
| BA-p4c | PORT | `ekrBattleMain` (quote-free arm), `ekrBattlePrepareDragonIntro`, `ekrBattleExecDragonIntro`/`PostEkrDragonIntro` (no-dragon arms), `ekrBattleSetFlashingEffect`, `ekrBattleExecTriangleAtk` (inactive arms) | `proc_idleCb` identity and `timer` per step f1496..f1506 equal the dumps | BA-p2c | todo |
| BA-p4d | PORT | `ekrBattleTriggerNewRoundStart` tail, `ekrBattle_80503EC`, `ekrBattle_StartPromotion` (non-promotion), `ekrBattleInRoundIdle` (CLOSE, non-arena: wait and `== 2 -> ekrBattleOnBattleEnd` arms), `ekrBattleOnBattleEnd`, `ekrBattle_8050600` | idle identity per step f1506..f1831, `gBanimDoneFlag` driven from the dump flags f1819/f1828, `gCtrlC01Blocking`, proc timer equal the dumps | BA-p4c, BA-p3d | todo |
| BA-i4b | INV | Post-battle idle chain `ekrBattle_WaitForPostBattleAct` .. `PostDragonStatusEffect` (f1831..1989): branches without exp/level-up procs | per-function branch table from the dumps (exp gain 0, no lvup, popup) | BA-i4 | todo |
| BA-cap3 | CAP | Proc-run trace (function entries of `ekrBattle`/`efxHPBar` callbacks) incl. the unadvanced steps f1582/f1789, plus a death-hit and a GREAT_SHIELD/`CheckRound2` hit window | Entry log per step; death/far-attack windows for `EfxHpBar_DeclineToDeath` death arms | BA-i4 | todo |

### BA-i4b result

`PostBattleIdleInventoryTest` (same capture and method as BA-i4; no function-entry trace, so each dump step lists the
`proc_idleCb` that runs in that step: steps listed = calls). The window did have an exp gain (right unit +10, left 0,
previous exp (-1, 0), no level-up; `gBanimExpGain`/`gBanimExpPrevious` are `s16[2]`), so the exp-bar functions run;
the "no exp / level-up *procs*" arms are the `ekrNewEkrPopup` jumps. `gBanimLinkArenaFlag` 0, `gEkrDebugModeMaybe` 0,
`gEkrInitPosReal` 1 and `gEkrInitialHitSide` 1 throughout. No `ekrLvup*` or popup proc exists in the window.

| Function | Frames (calls) | Branch taken | Not exercised |
|---|---|---|---|
| `ekrBattleOnBattleEnd` | f1829 (1) | `speedup = 0`, cb `_8050600` | none |
| `ekrBattle_8050600` | f1830 (1) | `gEkrHpBarCount`/`gEkrDeadEventExist` 0 and namewin gone: passes in one call; `timer = 0`; `CheckEkrDragonDead` false; `pos` = R (gain L 0) equals `gEkrInitPosReal`, so `speedup` stays 0, no `NewEfxFarAttackWithDistance` (inferred: no proc, `speedup` 0) | the three early returns, dragon-dead return, `pos != gEkrInitPosReal` arm and far attack |
| `ekrBattle_WaitForPostBattleAct` | f1831..1860 (30), timer 0..29 | `++timer < 0x1E` return x29; 30th call: arena flag != 1 and `gain[L] != -gain[R]` -> `ekrBattleExecExpGain` | the `ekrNewEkrPopup` arm (link arena or zero-sum gain) |
| `ekrBattleExecExpGain` | f1861 (1) | draws the exp bar (VRAM/BG/window side effects, not dumped), default priority arm (`GetBanimDragonStatusType` not DRACO_ZOMBIE/DEMON_KING), `gain[L] == 0` -> uses `prev[R]`; `timer = 0` | DRACO_ZOMBIE/DEMON_KING priority arm, `gain[L] != 0` arm |
| `ekrBattle_80508F0` | f1862..1874 (13), timer 0..12 | `++timer > 12` false x12 (window animates), true on the 13th: `timer = 0` | none |
| `ekrBattle_8050940` | f1875..1885 (11), timer 0..10 | 11th call `++timer > 10`: `gain[L] == 0`, `gain[R] != 0` arm: `timer = prev[R]` (0), `end = prev[R] + gain[R]` (10); two sound starts | `gain[L] != 0` arm; neither-gain fall-through |
| `ekrBattleWaitExpBarIdle` | f1886..1896 (11), timer 0..10, end 10 | redraws the number bar each call; `++timer > end` true on 11th: `timer = 0` | none (exp 100 wrap `DivRem` arm untested: values < 100) |
| `ekrBattlePostExpBarIdle` | f1897..1927 (31), timer 0..30 | `timer == 0` song stop on the first call; `++timer > 30` true on the 31st | none |
| `ekrBattle_8050AB8` | f1928..1940 (13), timer 0..12 | window shrink x12, 13th call `timer = 0` | none |
| `ekrBattleLvupHanlder` | f1941..1981 (41), timer 0..40 | `++timer == 0x18` at the 24th call: `c = prev[R] + gain[R]` = 10 < 100, no `NewEkrLvlupFan`; 41st call `timer = 41 > 0x28`: BG clear, default priority arm, `c < 100` -> `ekrNewEkrPopup` | `c >= 100` (fan, `ekrBattle_ExecEkrLvup`, `WaitEkrLvupIdle`), `gain[L] != 0` arm, dragon priority arm |
| `ekrNewEkrPopup` | f1982 (1) | `NewEkrPopup`; `gEkrPopupEnded` 0 before, 1 at f1983; no popup proc seen | popup content/animation (BA-i7) |
| `ekrBattle_WaitForPopup` | f1983 (1) | `CheckEkrPopupEnded` true on the first call: `DeleteAnimsOnPopup` | the wait-return arm |
| `ekrBattle_PostPopup` | f1984 (1) | `EndProcEfxWeaponIcon`, `EndEfxHPBarColorChange` (both procs gone at f1985), `side = gEkrInitialHitSide` (1), `counter = 0` | none |
| `ekrBattle_TriggerDragonStatusFinished` | f1985..1987 (3) | call 1: side 1 -> gAnims[2], default arm, `side = 0`, `counter = 1`; call 2: side 0 -> gAnims[0], default arm, `side = 1`, `counter = 2`; call 3: `counter == 2` -> `PostDragonStatusEffect` | DEMON_KING/DRACO_ZOMBIE/MYRRH arms, `SetEkrDragonExit`, `ekrBattle_WaitEkrDragonEndIdle` |
| `ekrBattle_PostDragonStatusEffect` | f1988 (1) | `gEkrBattleEndFlag` 0 -> 1, debug mode 0: `NewEkrNamewinAppear(2, 7, 0)` (`gEkrNamewinAppearExist` 0 -> 1, `ProcScr_ekrNamewinAppear` appears f1989), `EkrRestoreBGM`, cb `nullsub_69` | `gEkrDebugModeMaybe != 0` arm |
| `nullsub_69` | not observed | `ekrBattle` is gone at f1989 (`ProcScr_ekrBattleEnding` appears the same step; who ends the proc is BA-i8) | n/a |

Gaps: the draw side effects of `ekrBattleExecExpGain`, `ekrBattleWaitExpBarIdle`, the window calls and the sounds are in
VRAM/IO/audio, not in the EWRAM dumps, so a port can only be verified on the control state (callback, timer, `end`,
`side`, `counter`, the globals above, the proc set); `ekrBattle_8050600` internals (`CheckEkrDragonDead`, `speedup`)
are inferred from state; the exp-bar draw and popup content belong to BA-i7, `ProcScr_ekrBattleEnding` to BA-i8.

Proposed rows:

| ID | Kind | Task | Acceptance | Deps | Status |
|---|---|---|---|---|---|
| BA-p5a | PORT | `ekrBattle_WaitForPostBattleAct` (`OnBattleEnd`/`8050600` done in BA-p4d) (ExecExpGain arm), `ekrBattleExecExpGain` control state only (default priority arm, `gain[L] == 0`; draw calls Transcribed behind a hook) | f1829..1861 `proc_idleCb` identity, `timer`, `speedup` equal the dumps (driven from f1829 with the dump globals); mutated gate (`< 0x1E`) fails | BA-p4d | todo |
| BA-p5b | PORT | `ekrBattle_80508F0`, `ekrBattle_8050940` (`gain[R]` arm), `ekrBattleWaitExpBarIdle`, `ekrBattlePostExpBarIdle`, `ekrBattle_8050AB8` (window/draw/sound calls behind hooks) | f1862..1940 callback identity, `timer`, `end` per step equal the dumps; exp-bar draw not asserted (not dumped) | BA-p5a | todo |
| BA-p5c | PORT | `ekrBattleLvupHanlder` (`c < 100` arm), `ekrNewEkrPopup`, `ekrBattle_WaitForPopup` (ended arm), `ekrBattle_PostPopup`, `ekrBattle_TriggerDragonStatusFinished` (default arms), `ekrBattle_PostDragonStatusEffect` (debug-mode 0 arm) | f1941..1989 callback, `timer`, `side`, `counter`, `gEkrBattleEndFlag` equal the dumps; weapon-icon/HP-bar-color procs ended at f1985, namewin proc started f1989 (hooks) | BA-p5b | todo |
| BA-cap4 | CAP | Post-battle windows to exercise the arms above: exp gain on the left unit, level-up (`c >= 100`, `ekrBattle_ExecEkrLvup`/`WaitEkrLvupIdle`), zero exp (popup arm of `WaitForPostBattleAct`), dragon status arm (DEMON_KING/DRACO_ZOMBIE/MYRRH, `WaitEkrDragonEndIdle`) | Capture + inventory per arm; overlaps BA-level-inv/BA-i7 | BA-i4b | todo |

### BA-i8 result

`BattleEndingInventoryTest` (same capture and method as BA-i4b: `ram_trace_prologue_attack`, no function-entry trace,
dump = state after the step), f1989..2038. `ProcScr_ekrBattleEnding` is a script proc of eight `PROC_REPEAT`s (script
cursor n + 1 after the n-th repeat command read; NAME is command 0). A repeat command is read and its callback called in
the same step; after `Proc_Break` the callback pointer reads 0 and the cursor still names the command that broke.
`gBanimBG` 0, `gBanimLinkArenaFlag` 0, `gEkrBattleEndFlag` 1, `gEkrBmLocation` (8, 6, 8, 5) throughout. The window
ends where the map procs are back and stable (f2038; the proc set is unchanged to f2070, end of the capture).

| Function | Frames (calls) | Branch taken | Not exercised |
|---|---|---|---|
| `NewEkrbattleending` | f1989 (1) | `ProcScr_ekrBattleEnding` appears, `timer` 0, callback `_80560F0` set (same step as `PostDragonStatusEffect`; `ekrBattle` gone) | none |
| `ekrBattleEnding_80560F0` | f1990 (1) | `gBanimBG == 0`: `Proc_Break`, timer stays 0 | dragon-status arms, fade path (`Interpolate`, `EfxPalBlackInOut`, 9 calls) |
| `ekrBattleEnding_8056170` | f1991 (1) | `gBanimBG == 0` break | `UnpackChapterMapGraphics`/fade/`RenderBmMap` path, dragon arms |
| `ekrBattleEnding_80561C8` | f1992 (1) | `gBanimBG == 0` break | `EfxChapterMapFadeOUT` loop (9 calls), dragon arms |
| `ekrBattleEnding_8056228` | f1993 (1) | `timer = 0`, `x1 = x2 = 136`, `y1 = y2 = 96` from `gEkrBmLocation`; `AnimClearAll`, `NewEkrUnitKakudai`, `NewEkrBaseKaiten`, `NewEkrWindowAppear`, `NewEkrBaseAppear` (4 procs appear, `gEkrWindowAppearExist` 0 -> 1) | none |
| `ekrBattleEnding_8056288` | f1994..2009 (13 calls; timer 1 shown f1994..1997, then +1 per step to 13) | `++timer > 0xC` false x12; 13th call (f2009): `EndEkrGauge` (`ProcScr_ekrGauge`, `EkrBaseKaiten`, `ekrWindowAppear`, `ekrBaseAppear` gone), BG init and window registers (not dumped) | none |
| `ekrBattleEnding_8056310` | f2010 (1) | `timer = 0`, `terminator = 15`; `BMapDispResume_FromBattleDelayed` starts `ProcScr_Mu` and `sProc_DelayedBMapDispResume`; arena flag != 1 -> `UnpackChapterMapPalette`; link-arena flag 0 -> no fog | arena (`GetBattleAnimArenaFlag == 1`) and link-arena arms |
| `sProc_DelayedBMapDispResume` | f2010, f2011 present, gone f2012 | `PROC_SLEEP(0)` then `BMapDispResume` (not dumped) | `BMapDispResume` internals (suspend-point arms) |
| `ekrBattleEnding_8056390` | f2012..2026 (15 calls), timer 1..14, 0 on the 15th | `timer != terminator` increments; box `Interpolate` from (136, 96) to the screen; BG2 tilemap cleared; at `timer == terminator`: `timer = 0`, window box reset, palette sync; `ProcScr_ekrUnitKakudai` gone f2012 | none |
| `ekrBattleEnding_8056484` | f2027 (1) | `EndEkrBattleDeamon` (`gProc_ekrBattleDeamon` gone), `RefreshBMapDisplay_FromBattle` (VRAM/IO only) | none |
| (script end) | f2028 | `PROC_END`: `ProcScr_ekrBattleEnding` gone; `sProcScr_CombatAction` gone f2029 | n/a |

Other proc changes in the window (map side): `ProcScr_efxStatusUnit` (one of two) ends f1995, `gProc_ekrDispUP` f2001,
`ProcScr_ekrNamewinAppear` f2002 (`gEkrNamewinAppearExist` 1 -> 0), `ProcScr_StdEventEngine` runs f2030..2032,
`ProcScr_Mu` ends f2034, `gProcScr_SideWindowMaker` f2037, then the terrain/unit/goal displays and
`ProcScr_GreenTextColor` start f2038. Final set: `gProcScr_GameControl`, `sProc_BMVSync`, `gProc_BMapMain`,
`gProc_MapTask`, `gProcScr_PlayerPhase` and those four.

Gaps: everything the ending functions draw (palette fades, BG/window/blend registers, tilemap clears, OAM/unit sprite
sheet, `BMapDispResume`) is VRAM/IO/palette, not in the EWRAM dumps, so a port verifies only control state (callback,
cursor, timer, terminator, `x1..y2`, proc set, flags). The three steps where the `8056288` timer does not advance
(f1995..1997) and the idle step f2011 cannot be attributed without a proc-run entry trace (BA-cap3 style); the port
must reproduce the timer values per step, so BA-p8a drives the dump steps and treats those steps as no-run. The
`gBanimBG != 0` fade arms of `_80560F0`/`_8056170`/`_80561C8`, dragon-status arms and arena arms have no capture
(BA-cap4 style CAP).

Proposed rows:

| ID | Kind | Task | Acceptance | Deps | Status |
|---|---|---|---|---|---|
| BA-p8a | PORT | `NewEkrbattleending`, `ekrBattleEnding_80560F0`/`_8056170`/`_80561C8` (`gBanimBG == 0` arm), `_8056228`, `_8056288` (draw/proc-start calls behind hooks) | f1989..2009 callback, cursor, `timer`, `x1..y2`, proc-start hook calls equal the dumps; mutated `> 0xC` gate fails | BA-p5c | todo |
| BA-p8b | PORT | `ekrBattleEnding_8056310` (non-arena, non-link arm), `_8056390`, `_8056484`, `sProc_DelayedBMapDispResume` order (restore calls behind hooks) | f2010..2028 callback, cursor, `timer`, `terminator`, `gProc_ekrBattleDeamon` end, delayed proc lifetime equal the dumps | BA-p8a | todo |


### BA-i1 result

`BattleEntryInventoryTest` (same method as BA-i8; no function-entry trace, dump = state after the step). Windows:
`ram_trace_prologue_attack` f1463..1500 (Eirika/Soldier, primary; `gEkrBmLocation` (8, 6, 8, 5)) and
`ram_trace_prologue_battle` f14429..14469 (scripted Seth/Valter, `gEkrBmLocation` (8, 5, 7, 5)); both: `gEkrDebugModeMaybe`
0, `gBanimBG` 0, `gEkrDistanceType` CLOSE, `gBanimValid` (1, 1), `gEkrInitPosReal` 1. The entry procs are all in
`PROC_TREE_3`. Every proc is first read some steps after it is started: the furniture procs (started in one
`InitBattleScreen` call) show their start values for 3 (attack) or 5 (scripted) steps before the first call, and
`ekrBaStart_ExecEkrBattle6C` shows timer 1 for 3 / 5 steps; these steps are no-run steps for the port (the likely cause
is `InitBattleScreen`'s heavy calls spilling over frames; unattributable without a proc-run trace, BA-cap3 style).
`PrepareBattleGraphicsMaybe`/`BeginAnimsOnBattleAnimations` ran in the step before the first dump (all entry globals are
0 at f1463 and set at f1464; f14430..14431 scripted), so only their combined output is observed, not this proc group.

| Function | Frames (calls) | Branch taken | Not exercised |
|---|---|---|---|
| `NewEkrBattleDeamon` | f1464 (1); scripted f14431 (1) | `gBattleDeamonActive` 0 -> 1, `gpProcEkrBattleDeamon` = the proc address; same step as `NewEkrBattleStarting` (`LockGame` not dumped) | none |
| `nullsub_35` (`gProc_ekrBattleDeamon` repeat, cursor 3) | f1465..1500 / f14432..14469 every step | callback stays set the whole window (proc ends in BA-i8 `_8056484`, f2027) | `ekrBattleDeamon_Destructor` (BA-i8 window) |
| `NewEkrBattleStarting` | f1464 (1); f14431 (1) | `ProcScr_ekrBattleStarting` started; attack dump already shows `InitScreen` run (cursor 2), scripted shows it set and unrun | arena (`BeginAnimsOnBattle_Arena`) and hensei arms, `BeginAnimsOnBattleAnimations`/`MainUpdate_8055C68` bodies (no entry trace) |
| `ekrBaStart_InitScreen` | f1464 / f14432 (1) | `timer` 0, `terminator` 15, `x1 = x2 = (loc[0] + loc[2]) * 8 + 8` (136 / 128), `y1 = y2 = (loc[1] + loc[3]) * 8 + 8` (96 / 88); BG2 clear, blend, WIN0 setup (not dumped) | none |
| `ekrBaStart_SreenFailIn` | f1465..1479 / f14433..14447 (15) | timer 1..15, then the `timer == terminator` arm: `InitOam`, `BMapDispSuspend`, palette fade, `EndAllMus` (`ProcScr_Mu` gone the same step), `Proc_Break` | none; WIN0 box interpolation not observable |
| `ekrBaStart_InitBattleScreen` | set f1480..1481 (run by f1482); scripted f14448..14450 | `gEkrDebugModeMaybe == 0`: `ProcScr_ekrGauge` + `gProc_ekrDispUP` appear f1480 / f14448; CLOSE: switch is a no-op; then `NewEkrUnitKakudai(0)`, `NewEkrBaseKaiten(0)`, `NewEkrWindowAppear(0, 0xB)`, `NewEkrNamewinAppear(0, 0xB, 0)`, `NewEkrBaseAppear(0, 0xB)`; timer 0 | debug mode, MONOCOMBAT (`EkrGauge_Set4C/50`), PROMOTION |
| `ekrBaStart_ExecEkrBattle6C` | f1483..1496 / f14452..14467 (12 calls over 14 / 16 steps) | timer 1..11, 12th call `++timer > 0xB` with `gBanimBG == 0`: `NewEkrBattle` + `Proc_End` (`gProc_ekrBattle` appears f1496 / f14467, `ProcScr_ekrBattleStarting` gone, cursor never passes 5) | `Proc_Break` arm (`gBanimBG != 0` or dragon status) and `ekrBaStart_8055FE8`, `_8056024`, `_8056078` (never run) |
| `NewEkrUnitKakudai` | f1482 / f14450 (1) | type 0, `valid_l = valid_r = 1` (CLOSE arm) | PROMOTION, normal-vs-dragon `valid_l` arm |
| `UnitKakudaiPrepareAnimScript` | f1485 / f14456 (1) | box `x1 = loc[0] * 16 + 8`, `y1 = loc[1] * 16 + 8`, `x2`/`y2` from `loc[2]`/`loc[3]` ((136,104,136,88) / (136,88,120,88)); `left_pos` 92, `right_pos` 148 (`gEkrInitPosReal` 1: left reduced); timer 0, terminator 11; image decompress/DMA, palette (not dumped) | `gEkrBattleEndFlag` arm (BA-i8 window), petrify and REFRESH palette arms |
| `UnitKakudaiMain` | f1486..1497 / f14457..14468 (12) | timer 1..11 (type 0: zoom 0x250 -> 0x100), 12th call `timer >= terminator` -> `Proc_Break` (f1497); `AnimDisplay` sprites (OAM) not dumped | type != 0 arm (ending, BA-i8) |
| `UnitKakudaiEndNop` | f1499 / f14470 (1) | `Proc_Break`; proc gone f1500 / f14471 (one idle step f1498 / f14469 before it) | none |
| `NewEkrBaseKaiten` / `EkrBaseKaitenMain` | f1482 / f14451; Main timer 1..11 f1485..1495, 12th call f1496 / f14467 | CLOSE arm, type 0: box `(x1, x2, y1, y2)` = `((loc[0] + loc[2]) * 8 + 8, 0x78, (loc[1] + loc[3]) * 8 + 8, 0x68)`, terminator 11; 12th call: `AnimDelete`, `Proc_Break`; gone f1497 / f14468; anim script id and anim position are in the anim array, not read | FAR/FARFAR (two procs), MONOCOMBAT, type 1 |
| `NewEkrWindowAppear` / `EkrWindowAppearMain` | f1482 / f14451; Main timer 1..11, 12th call f1496 / f14467 | type 0, terminator 11, `ymax` 0x39, `gEkrWindowAppearExist` 0 -> 1 -> 0 at the 12th call; `EkrGauge_Clr323A` and `EkrGauge_SetInitFlag` (not dumped); gone f1497 / f14468 | type 1 arm (scripted f14470.., f14633.., and the endings f1993..2009, f15039..; BA-i8/BA-i2 windows) |
| `NewEkrNamewinAppear` / `EkrNamewinAppearDelay` / `EkrNamewinAppearMain` | f1482 / f14451; Delay 1 call (delay 0), Main timer 1..11 f1486..1496, 12th call f1497 / f14468 | type 0, terminator 11, `ymax_name` -49, `gEkrNamewinAppearExist` 0 -> 1 -> 0 at the 12th call (one step after the window flag); gone f1499 / f14470 | delay > 0, type 1 (scripted f14470) and type 2 (`EndEkrDispUP`, ending f15035) |
| `NewEkrBaseAppear` / `EkrBaseAppearMain` | f1482 / f14451; Main timer 1..11, 12th call f1496 / f14467 | type 0, terminator 11, `gProcEkrBaseAppearExist` 0 -> 1 -> 0 at the 12th call; BG2 scroll (not dumped); gone f1497 / f14468 | type 1 arm (endings) |
| `ScriptBattleDeamon` (eventscr.c) | scripted f14430..14469+ (every step, never breaks in the capture) | proc created by the event engine one step before `ekrBattleStarting`; `lock` (0x64) 1, parent = `evtproc` (0x58) = `ProcScr_StdEventEngine`; condition `lock == GetGameLock()` false throughout | Break arm (`EventBattleReloadBmStatus`, `Proc_SetMark`): the capture ends before `EndEkrBattleDeamon` (f15059 < ~f15085) |

Proc set changes (attack): f1464 +`ekrBattleStarting`, `sProcScr_CombatAction`, `gProc_ekrBattleDeamon`; f1479 -`ProcScr_Mu`;
f1480 +`ekrGauge`, `gProc_ekrDispUP`; f1482 +`ekrNamewinAppear`, `ekrWindowAppear`, `EkrBaseKaiten`, `ekrUnitKakudai`,
`ekrBaseAppear`; f1496 +`gProc_ekrBattle`, `gMusicProc3Script` -`ekrBattleStarting`; f1497 -`ekrWindowAppear`, `EkrBaseKaiten`,
`ekrBaseAppear`; f1499 -`ekrNamewinAppear`; f1500 -`ekrUnitKakudai`. Scripted: the same shape with `ProcScr_ScriptBattleDeamon` +
`ProcScr_Mu` at f14430, starting + deamon f14431, `ProcScr_Mu` gone f14447, gauge/dispUP f14448, kakudai f14450, the other four
f14451, ekrBattle f14467, three furniture procs gone f14468.

BA-cap1 evidence: control state (callbacks, cursor, timers, boxes, flags, proc set) is complete for both windows, so the
PORT rows below need no new capture. Still missing, and needed only for visual correctness (not these rows): per-step
LCD registers (WIN0 box, blend, BG2 scroll, BG enable), palette (map-fade `EfxPalBlackInOut`) and OAM for the kakudai
sprites and the base-kaiten anim; no capture of non-CLOSE distances (FAR/FARFAR/MONOCOMBAT/PROMOTION), banim BG
(`gBanimBG != 0`, the `_8055FE8/_8056024/_8056078` fades), arena, hensei, debug mode or dragon status. BA-cap1 stays
conditional on the owner wanting those pixels; the no-run steps are unattributable without BA-cap3.

Proposed rows:

| ID | Kind | Task | Acceptance | Deps | Status |
|---|---|---|---|---|---|
| BA-p1a | PORT | `NewEkrBattleDeamon`, `nullsub_35`, `NewEkrBattleStarting`, `ekrBaStart_InitScreen`, `_SreenFailIn`, `_InitBattleScreen` (debug 0, CLOSE arm), `_ExecEkrBattle6C` (`gBanimBG == 0` arm); draws, `NewEkr*` starts behind hooks | f1464..1496 and f14431..14467 callback, cursor, timer, `x1..y2`, `gBattleDeamonActive`, proc-start hook order equal the dumps (no-run steps skipped); mutated `> 0xB` gate fails | BA-i1 | todo |
| BA-p1b | PORT | `NewEkrWindowAppear`, `EkrWindowAppearMain`, `NewEkrNamewinAppear`, `EkrNamewinAppearDelay`/`Main`, `NewEkrBaseAppear`, `EkrBaseAppearMain` (type 0; register writes behind hooks) | timer, terminator, `ymax`, exist flags and end step equal the dumps in both windows; mutated `>= terminator` fails | BA-i1 | todo |
| BA-p1c | PORT | `NewEkrBaseKaiten`, `EkrBaseKaitenMain` (CLOSE, type 0), `NewEkrUnitKakudai` (CLOSE), `UnitKakudaiPrepareAnimScript`, `UnitKakudaiMain`, `UnitKakudaiEndNop`, `ScriptBattleDeamon` (lock-false arm) | box, `left_pos`/`right_pos`, `valid_l/r`, timer, callback/cursor and gone steps equal the dumps; sprite/anim draws behind hooks | BA-i1 | todo |

### BA-i5 result

`BattlePanelsInventoryTest` (same method as BA-i8: `ram_trace_prologue_attack`, no function-entry trace, dump = state after
the step), f1478..1993; the appearance of `ekrGauge`/`ekrDispUP` (f1480..1500) is BA-i1's, here only their per-step state is
inventoried. The four procs are plain `PROC_REPEAT` procs whose callback never breaks in the window (`ekrGaugeMain` and
`ekrDispUPMain` cursor 2, `sub_8054F10` cursor 4, `EfxHPBarColorChangeMain` cursor 3), so every dump step is one call except
three whole-screen no-run steps (f1582, f1752, f1789: no proc field and no HP changes; BA-cap3 already lists f1582/f1789).
Inputs constant over the window: `gEkrDistanceType` CLOSE, `gBanimMaxHP` (22, 16), `gBanimWtaBonus` (-15, +15),
`gBanimEffectiveness` (0, 0), `gEkrGaugeHit/Dmg/Crt` fixed, `valid` 0, hide flags `unk4C/unk50` 0 on both procs, `asnyc` 0.
`gEkrGaugeHp` is a pair of halfwords (side 0 low, side 1 high). `NamewinAppear` (f1482..1499) stays BA-p1b's.

| Function | Frames (calls) | Branch taken | Not exercised |
|---|---|---|---|
| `NewEkrGauge` / `NewEkrDispUP` | f1480 (1 each); `gpProcEkrGauge`/`gpProcEkrDispUP` = proc address while alive | `gEkrGaugeHpBak` = (-1, -1) f1480..1482; `valid`, `battle_init`/`sync`, `unk4C/50`, `asnyc` 0; VRAM/palette/icon loads and `ModDec` buffers (not dumped) | `gEkrGaugeHp > 0x50` purple palette; `EkrGauge_Set4C50`/`EkrDispUpSet*` |
| `EkrGauge_Clr323A` / `EkrDispUP_SetPositionUnsync` (via `EkrWindowAppearMain`/`EkrNamewinAppearMain`, BA-p1b) | f1482..1495 (y per step) | gauge `y` 57 -> 0 (57,57,57,57,56,53,50,46,41,34,27,19,10,0), dispup `y` -49 -> 0 (-49 x5, -48,-46,-43,-39,-35,-30,-24,-17,-9, 0 at f1496); `battle_init` 0, `sync` 0 | none |
| `EkrGauge_SetInitFlag`, `SyncEkrDispUP`/`EkrDispUP_SetPositionSync` (callers in BA-p1b/BA-i6) | gauge `battle_init` 1 f1496..1992; dispup `sync` 1 f1497..1988, 0 again f1989 | `x = y = 0` except 24 steps with `y = x = +-3` (f1578..1803, three hits: 1578 -3, 1583 +3, 1586 -3 ...), identical on gauge and dispup; `gEkrBg0QuakeVec` itself reads (0, 0) at every dump step | `Setxy323A` callers for magic/dragon/triangle |
| `ekrGaugeMain` | f1480..1993 (1/step) | `battle_init == 0` arm (BG0 panel redraw) f1480..1495 and again f1993; `1` arm after; `hp_changed`: `gEkrGaugeHpBak` copies `gEkrGaugeHp` the same step it changes (22 -> 15 side 0 f1579..1592, 16 -> 7 side 1 f1661..1677, 15 -> 8 side 0 f1786..1799, drops of 1 every 2..3 steps) and at the first run f1483; `CheckEkrHitNow` rotate-scale arm for side 0 f1577..1608, side 1 f1659..1689, side 0 f1784..1815; single HP bar row only (max HP <= 0x28), `gBanimWtaBonus` arrow arm both signs | `valid == 1` return; `unk4C/50` hide; HP > 0x50; second bar row (`uVar8 != 0`); FAR/FARFAR/MONOCOMBAT x offsets |
| `EkrGauge_Setup44` | f1862 (`unk44` 0x400, from `EkrGauge_Setup44(1)` in the exp window) and f1982 (0) | value `val * 0x400` | `Setup44(2)` (level-up) |
| `ekrDispUPMain` | f1480..1993 (1/step) | `sync == 0` arm (draws BG0 panel) f1480..1496, then returns at `sync != 0` through f1988; `sync == 0` again f1989..1993 (arm runs again) | `asnyc == 1` return, `unk4C/50` hide |
| `NewEfxWeaponIcon` / `sub_8054F10` | started f1505 (counters 0), then 1 call/step to f1984 (479 steps, 3 no-run) | eff1 = eff2 = 0: only `EfxAdvanceFrameLut` + `EnablePaletteSync`; frame `unk4C` 0..16, cycle 32 steps (wraps f1538, 1570, 1603, 1635, ...); `invalid` 0 | `eff != 0` white flash arms (`LoadIconPalette`, `EfxPalWhiteInOut`), `Disable/EnableEfxWeaponIcon` |
| `EndProcEfxWeaponIcon` | f1985 (proc gone, `gpProcEfxWeaponIcon` 0) | proc was still on its repeat callback; `sub_8054F78` (end callback) palette restore not dumped | none |
| `NewEfxHpBarColorChange` / `EfxHPBarColorChangeMain` | started f1505, 1 call/step to f1984 | both HP halves <= 80: `EfxDecodeSplitedPalette` arms for both sides; `timer1/frame1` LUT cycle 36 steps -> `unk54` 0..5, `timer2/frame2` cycle 80 steps -> `unk58` 0..4 (unused here); `disabled` 0 | HP > 80 purple arm (`unk58` used), `Disable/EnableEfxHpBarColorChange` |
| `EndEfxHPBarColorChange` | f1985 (proc gone; `gpProcEfxHpBarColorChange` keeps the stale address) | `Proc_End` | none |
| `EfxAdvanceFrameLut` | 2 x 1 call/step f1506..1984 icon, colour | `timer == 0`: take the entry, `timer = dur - 1`, `frame++`; else `timer--`; `-2` restart arm taken (icon 14, colour1 13, colour2 5 times); `frame` reads 1 after a restart | `-1`, `-3`, `-4`, `-5`, `-6` return arms |

Gaps: everything drawn (BG0 name/weapon/terrain panel tiles, HP gauge and hit/dmg/crit digits, OAM of bars and icons, VRAM
`RegisterDataMove` copies, palette banks 0xB/0xC and the 0x1D/0x1E icon palettes, window/blend registers) is VRAM/OAM/palette
and not in the EWRAM dumps (BA-cap1 evidence), so a port verifies control state only and the pixel result of the panels and
bars stays unverified. The text drawing of names and weapon names (`EfxPrepareScreenFx`, `EkrDispUP`) runs at entry before the
first dump. `ModDec`/`gEkrGaugeDecoder` (EWRAM) were not compared; `sub_8050E40`, `sub_8071068` and `sub_8051238` write only
palette/OAM tables. The no-run steps f1582/f1752/f1789 need BA-cap3 to be attributed (a port drives the dump steps and skips
them). No capture of HP > 0x50, max HP > 0x28, effectiveness flash (eff != 0), FAR/FARFAR/MONOCOMBAT gauges, or the scripted
Seth/Valter window was inventoried (optional `ram_trace_prologue_battle` f14448..15035 not run: same procs, same arms expected).

Proposed rows:

| ID | Kind | Task | Acceptance | Deps | Status |
|---|---|---|---|---|---|
| BA-p9a | PORT | `NewEkrGauge` (control state; draws, palette, icon loads behind hooks), `EkrGauge_Setup44`, `EkrGauge_Clr4C50`, `EkrGauge_Clr323A`, `EkrGauge_SetInitFlag`/`ClrInitFlag`, `DisableEkrGauge`, `EndEkrGauge` | f1480 proc fields, `gEkrGaugeHpBak` (-1, -1), `unk44` f1862/f1982, `battle_init` f1496/f1993 equal the dumps; mutated `val * 0x400` fails | BA-i5 | todo |
| BA-p9b | PORT | `ekrGaugeMain` (control: `battle_init`/`valid`/hide arms, `hp_changed` and `gEkrGaugeHpBak` copy, x/y selection for CLOSE, rotate-scale arm select; OAM/VRAM/BG0 writes behind hooks), `EkrGauge_Setxy323A` | f1480..1993 `gEkrGaugeHpBak`, `x`/`y` per step (incl. 24 quake steps), hook call order per step equal the dumps; mutated `hp_changed` fails | BA-p9a | todo |
| BA-p9c | PORT | `NewEkrDispUP`, `EkrDispUpClear4C50`, `EkrDispUP_SetPositionSync`/`Unsync`, `SyncEkrDispUP`/`UnsyncEkrDispUP`/`UnAsyncEkrDispUP`, `ekrDispUPMain` (sync/asnyc early returns; panel tilemap copy behind a hook) | f1480..1993 `sync`, `y`, hook calls (only while `sync == 0`) equal the dumps | BA-i5 | todo |
| BA-p9d | PORT | `NewEfxWeaponIcon`, `sub_8054F10` (eff = 0 arm), `EndProcEfxWeaponIcon`, `NewEfxHpBarColorChange`, `EfxHPBarColorChangeMain` (HP <= 80 arm), `EndEfxHPBarColorChange`, `EfxAdvanceFrameLut` (LUT data read from the ROM loader) | f1505..1985 `timer`, `frame`, value fields, gone step and `gp*` pointers equal the dumps (no-run steps skipped); mutated `dur - 1` fails | BA-i5 | todo |

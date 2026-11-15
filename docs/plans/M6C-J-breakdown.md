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
Finding (open, EP-7b): at f1612 the captured danger map of Red 0 (10, 8) holds only the first player unit's damage (14). The port, with the formula of the ROM's code (checked in the Thumb bytes: `|dx| + |dy| <= UNIT_MOV(a) + UNIT_MOV(b) + maxRange`), also adds the second unit's 9 on its 35 tiles, and the dump's movement and range maps are that second unit's flood, which the port reproduces exactly. For Soldier 1 and 2 the map matches in full (14 / 23). Checked the mid-function theory (snapshot inside `AiFillDangerMap` after the second unit's flood, before its add): it does not hold, because r01613 still lacks the 9 and r01614 is already Soldier 1's map, so no complete map of Red 0 exists. The test skips only those 35 tiles of Red 0's f1612/f1613 danger map (EP-7b) and compares every other tile exactly.
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

Not broken down here: [M7-modding](M7-modding.md) has 23 proposed rows; they wait for owner decisions D2–D9.

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

### EP-1 result (2026-11-13)

Test: `PhaseStartEndDifferentialTest` over `ram_trace_prologue_enemy_phase` (blue/red/gplayst/gbmst/procs watches; `gPidStatsData` from the dumps f1520/f1530). New class `PhaseStartEnd` (game/bm). Ported: `ClearActiveFactionGrayedStates` (units at f1529, f2750, f2768, favour -8 of Seth and Eirika in `gPidStatsData`; the unavailable/unselectable/supply skips are not seen), the four `*Display_Init` (no display proc alive; an inverted end test leaves the proc), `sub_8015434` (phase proc five frames after the tick in the red, green and blue start). The existing `SwitchPhases`, `BmMain_ChangePhase`, `_StartPhase` (red branch new) and `_UpdateTraps` (green branch new) now match all three switches and the f2766 trap proc.
Transcribed, not verified: `TickActiveFactionTurn` (no unit has a duration: the decrements, targets and torch redraw never run; the test shows only that nothing changes), `GetPhaseAbleUnitCount` (count is 0 exactly when there is no banner; no excluded unit), `CountUnitsInState` (result only visible as the song id).
Stand-ins: the `Make*TargetList` functions and `sub_8026414` (empty list), the rest of each display script (ends the proc), the torch redraw, `PidStatsSubFavval08` hook (default none; `Fe8Boot` does not keep the statistics).
For EP-w: `Fe8Boot` now uses `PhaseStartEnd` (own `SelectTargets`; share the player phase list once the `Make*TargetList` exist), the four display scripts and the Tick; `cpPhase` is still the ends-at-once stand-in. EP-10 starts from `BmMain_UpdateTraps` hooks (`updateTraps`, `decayTraps`).

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

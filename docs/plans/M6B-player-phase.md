# M6-B player phase: notes

Lane B of [ROADMAP](ROADMAP.md); rows in [TASKS](../TASKS.md). Start state: `prologue.sav` (turn-1 suspend).

## Captures

### `ram_trace_prologue_select_move` (PP-cap1, ROM repo `c3ad5d4`)

Resume (A f1002, A f1104), then select Eirika, move 2 tiles right, Wait. Per-step watches gplayst, gbmst, rng,
evglobals, flags, procs, actiondata, `unit1` (Eirika, `gUnitArrayBlue[1]` 0x0202BE94, 0x48); per-frame RAM
f1255..1420; full dumps every 10f f1260..1420. Seth is `gUnitArrayBlue[0]` at (4,4).

| Event | Frame |
|---|---|
| PlayerPhase proc up / map UI up | f1169 / f1172 |
| LEFT: cursor (5,5) → (4,5), onto Eirika | f1250 |
| A: select (unit state 0x1); MoveLimitView proc | f1260; f1262 |
| Range built (ViewChange proc ends) | f1268 |
| RIGHT, RIGHT: cursor (5,5), (6,5) | f1275, f1283 |
| A: confirm; MoveLimitView removed; MU walk | f1295; f1296..f1314 |
| Unit at (6,5); unit menu (Item, Wait) opens | f1314 |
| DOWN: cursor to Wait; A: Wait | f1340; f1355 |
| StdEventEngine f1356..1359; unit state 0x42 (greyed); Mu removed | f1360 |
| SideWindowMaker; idle UI procs (Goal/Terrain/UnitDisplay) back | f1363; f1364 |

The resume cursor starts at (5,5), not on Eirika. The menu offers only Item and Wait (no enemy in range).

### `ram_trace_prologue_cancel_end` (PP-cap2, ROM repo `45f35cf`)

Same start, watches and `unit1`; per-frame RAM f1255..1650, full dumps every 10f. Cancel select, cancel move,
map menu End, phase change, enemy phase intro, first AI action.

| Event | Frame |
|---|---|
| LEFT onto Eirika; A select; range procs | f1250; f1260; f1262..1268 |
| B: cancel select (state 0x0, range proc removed); idle procs back | f1290; f1291 |
| A select; RIGHT, RIGHT; A confirm; MU walk | f1310; f1330, f1340; f1355; f1355..1374 |
| Unit at (6,5), unit menu | f1374 |
| B: menu closes; unit back at (4,5), state 0x1; range + path arrow | f1400; f1401; f1403..1409 |
| B: back to idle (cursor (4,5)); idle procs back | f1425; f1426 |
| RIGHT to (5,5); A: map menu (Unit Status Guide Options Suspend End) | f1445; f1465 |
| UP: cursor wraps Unit → End; A: End (PlayerPhase/menu procs end) | f1490; f1510 |
| BerserkCpOrder/CpPhase procs; gPlaySt faction 0 → 0x80; StdEventEngine | f1511..1513; f1514; f1514..1517 |
| PhaseIntro (enemy banner) f1520..1591; CpDecide/CpOrder/CpPhase; enemy MU + CpPerform | f1597; f1599 |
| AiTargetCursor; run ends (first enemy battle would start ~f1690) | f1633; f1650 |

The enemy phase banner here is per frame (no per-scanline blend), so D12-2 still needs its own capture.

## Proc inventory (PP-0)

Inventory only, no port claim. Test: `testing/src/test/kotlin/fe8/testing/PlayerPhaseProcInventoryTest.kt`
(the `procs` watch, steps 1169..1420 and 1169..1650). Row format: script symbol, parent (`tree N` = root
slot), first..last step. Repeated rows are separate slots with the same script, parent and steps.

### (1) Rows per run

`ram_trace_prologue_select_move`, steps 1169..1420:

| Script | Parent | Steps |
|---|---|---|
| gProcScr_GameControl | tree 3 | 1169-1420 |
| sProc_BMVSync | tree 0 | 1169-1420 |
| gProc_BMapMain | tree 2 | 1169-1420 |
| gProc_MapTask | tree 4 | 1169-1420 |
| gProcScr_PlayerPhase | gProc_BMapMain | 1169-1420 |
| ProcScr_GreenTextColor | gProcScr_GoalDisplay | 1172-1260 |
| gProcScr_GoalDisplay | tree 3 | 1172-1260 |
| gProcScr_TerrainDisplay | tree 3 | 1172-1260 |
| gProcScr_UnitDisplay_MinimugBox | tree 3 | 1172-1260 |
| ProcScr_Mu | tree 5 | 1255-1359 |
| sProcScr_MoveLimitViewChange | sProcScr_MoveLimitView | 1262-1267 |
| sProcScr_MoveLimitView | tree 4 | 1262-1294 |
| ProcScr_MuStepSe | tree 3 | 1296-1296 |
| ProcScr_MuStepSe | tree 3 | 1303-1304 |
| ProcScr_MuStepSe | tree 3 | 1311-1312 |
| sProc_MenuItem | sProc_Menu | 1314-1354 |
| sProc_Menu | tree 3 | 1314-1354 |
| sProc_MenuItem | sProc_Menu | 1314-1354 |
| ProcScr_GreenTextColor | sProc_Menu | 1314-1354 |
| ProcScr_StdEventEngine | tree 3 | 1356-1358 |
| gProcScr_SideWindowMaker | tree 3 | 1363-1363 |
| gProcScr_TerrainDisplay | tree 3 | 1364-1420 |
| gProcScr_UnitDisplay_MinimugBox | tree 3 | 1364-1420 |
| gProcScr_GoalDisplay | tree 3 | 1364-1420 |
| ProcScr_GreenTextColor | gProcScr_GoalDisplay | 1364-1420 |

`ram_trace_prologue_cancel_end`, steps 1169..1650:

| Script | Parent | Steps |
|---|---|---|
| gProcScr_GameControl | tree 3 | 1169-1650 |
| sProc_BMVSync | tree 0 | 1169-1650 |
| gProc_BMapMain | tree 2 | 1169-1650 |
| gProc_MapTask | tree 4 | 1169-1650 |
| gProcScr_PlayerPhase | gProc_BMapMain | 1169-1509 |
| ProcScr_GreenTextColor | gProcScr_GoalDisplay | 1172-1260 |
| gProcScr_GoalDisplay | tree 3 | 1172-1260 |
| gProcScr_TerrainDisplay | tree 3 | 1172-1260 |
| gProcScr_UnitDisplay_MinimugBox | tree 3 | 1172-1260 |
| ProcScr_Mu | tree 5 | 1255-1289 |
| sProcScr_MoveLimitViewChange | sProcScr_MoveLimitView | 1262-1267 |
| sProcScr_MoveLimitView | tree 4 | 1262-1289 |
| gProcScr_GoalDisplay | tree 3 | 1291-1310 |
| ProcScr_GreenTextColor | gProcScr_GoalDisplay | 1291-1310 |
| gProcScr_UnitDisplay_MinimugBox | tree 3 | 1291-1310 |
| gProcScr_TerrainDisplay | tree 3 | 1291-1310 |
| ProcScr_Mu | tree 5 | 1296-1424 |
| sProcScr_MoveLimitView | tree 4 | 1312-1354 |
| sProcScr_MoveLimitViewChange | sProcScr_MoveLimitView | 1312-1317 |
| ProcScr_MuStepSe | tree 3 | 1356-1356 |
| ProcScr_MuStepSe | tree 3 | 1363-1364 |
| ProcScr_MuStepSe | tree 3 | 1371-1372 |
| sProc_Menu | tree 3 | 1374-1399 |
| sProc_MenuItem | sProc_Menu | 1374-1399 |
| ProcScr_GreenTextColor | sProc_Menu | 1374-1399 |
| sProc_MenuItem | sProc_Menu | 1374-1399 |
| sProcScr_MoveLimitView | tree 4 | 1403-1424 |
| sProcScr_MoveLimitViewChange | sProcScr_MoveLimitView | 1403-1408 |
| ProcScr_GreenTextColor | gProcScr_GoalDisplay | 1426-1464 |
| gProcScr_GoalDisplay | tree 3 | 1426-1464 |
| gProcScr_TerrainDisplay | tree 3 | 1426-1464 |
| gProcScr_UnitDisplay_MinimugBox | tree 3 | 1426-1464 |
| ProcScr_Mu | tree 5 | 1431-1444 |
| sProc_MenuItem | sProc_Menu | 1465-1509 |
| sProc_MenuItem | sProc_Menu | 1465-1509 |
| sProc_Menu | tree 3 | 1465-1509 |
| sProc_MenuItem | sProc_Menu | 1465-1509 |
| sProc_MenuItem | sProc_Menu | 1465-1509 |
| sProc_MenuItem | sProc_Menu | 1465-1509 |
| sProc_MenuItem | sProc_Menu | 1465-1509 |
| ProcScr_GreenTextColor | sProc_Menu | 1465-1509 |
| gProcScr_BerserkCpPhase | gProc_BMapMain | 1511-1512 |
| gProcScr_BerserkCpOrder | gProcScr_BerserkCpPhase | 1511-1511 |
| ProcScr_StdEventEngine | tree 3 | 1514-1516 |
| gProcScr_ResetCursorPosition | gProc_BMapMain | 1520-1520 |
| ProcScr_PhaseIntro | gProc_BMapMain | 1520-1590 |
| gProcScr_PhaseIntroBlendBox | ProcScr_PhaseIntro | 1521-1581 |
| gProcScr_PhaseIntroSquares | ProcScr_PhaseIntro | 1521-1586 |
| gProcScr_PhaseIntroText | ProcScr_PhaseIntro | 1521-1588 |
| gProcScr_PhaseIntroUnk | gProcScr_PhaseIntroText | 1527-1588 |
| gProcScr_CpDecide | gProcScr_CpOrder | 1597-1650 |
| gProcScr_CpOrder | gProcScr_CpPhase | 1597-1650 |
| gProcScr_CpPhase | gProc_BMapMain | 1597-1650 |
| ProcScr_Mu | tree 5 | 1599-1650 |
| gProcScr_CpPerform | gProcScr_CpDecide | 1599-1650 |
| ProcScr_MuStepSe | tree 3 | 1600-1600 |
| ProcScr_MuStepSe | tree 3 | 1607-1608 |
| ProcScr_MuStepSe | tree 3 | 1615-1616 |
| ProcScr_MuStepSe | tree 3 | 1623-1624 |
| ProcScr_MuStepSe | tree 3 | 1631-1632 |
| gProcScr_AiTargetCursor | gProcScr_CpPerform | 1633-1650 |

Roots (`tree N`), select_move (13): gProcScr_GameControl, sProc_BMVSync, gProc_BMapMain, gProc_MapTask, ProcScr_Mu,
ProcScr_MuStepSe, sProcScr_MoveLimitView, sProc_Menu, gProcScr_GoalDisplay, gProcScr_TerrainDisplay,
gProcScr_UnitDisplay_MinimugBox, gProcScr_SideWindowMaker, ProcScr_StdEventEngine.
cancel_end (12): the same without gProcScr_SideWindowMaker.

### (2) Symbol to decomp source file

Found with `grep -rln "<Symbol>\[\]"` over `/home/dookim/FE8/fireemblem8u/src`; the line is the array definition.
Run: S = select_move, C = cancel_end.

| Symbol | Source file (line) | Run |
|---|---|---|
| gProc_BMapMain | bm.c (41) | S, C |
| gProcScr_ResetCursorPosition | bmcamadjust.c (14) | C |
| sProc_BMVSync | bmio.c (121) | S, C |
| gProc_MapTask | bmio.c (139) | S, C |
| gProcScr_CpDecide | cp_decide.c (48) | C |
| gProcScr_CpOrder | cp_order.c (22) | C |
| gProcScr_BerserkCpOrder | cp_order.c (31) | C |
| gProcScr_AiTargetCursor | cp_perform.c (51) | C |
| gProcScr_CpPerform | cp_perform.c (69) | C |
| gProcScr_CpPhase | cp_phase.c (17) | C |
| gProcScr_BerserkCpPhase | cp_phase.c (30) | C |
| ProcScr_StdEventEngine | event.c (23) | S, C |
| ProcScr_GreenTextColor | fontgrp.c (1055) | S, C |
| gProcScr_GameControl | gamecontrol.c (42) | S, C |
| ProcScr_Mu | mu.c (154) | S, C |
| ProcScr_MuStepSe | mu.c (38) | S, C |
| ProcScr_PhaseIntro | phasechangefx.c (68) | C |
| gProcScr_PhaseIntroText | phasechangefx.c (42) | C |
| gProcScr_PhaseIntroUnk | phasechangefx.c (37) | C |
| gProcScr_PhaseIntroSquares | phasechangefx.c (54) | C |
| gProcScr_PhaseIntroBlendBox | phasechangefx.c (61) | C |
| gProcScr_TerrainDisplay | player_interface.c (114) | S, C |
| gProcScr_UnitDisplay_MinimugBox | player_interface.c (136) | S, C |
| gProcScr_SideWindowMaker | player_interface.c (183) | S |
| gProcScr_GoalDisplay | player_interface.c (200) | S, C |
| gProcScr_PlayerPhase | playerphase.c (38) | S, C |
| sProcScr_MoveLimitView | playerphase.c (189) | S, C |
| sProcScr_MoveLimitViewChange | playerphase.c (178) | S, C |
| sProc_Menu | uimenu.c (29) | S, C |
| sProc_MenuItem | uimenu.c (46) | S, C |

Symbol count: 30 in total (select_move 17, cancel_end 29). gProcScr_SideWindowMaker appears only in select_move.

### (3) ProcCmd contents of the scripts in the listed files

Shorthand: CALL = PROC_CALL (CALL_2 = PROC_CALL_2), REPEAT, WHILE, WHILE_EXISTS, START_CHILD (incl. _BLOCKING),
SLEEP, GOTO, JUMP; `Ln` = PROC_LABEL(n). Commands are in array order. PROC_NAME, PROC_MARK, PROC_YIELD, PROC_15,
PROC_SET_END_CB and the end markers are noted per entry.

**playerphase.c**

- gProcScr_PlayerPhase (NAME E_PLAYERPHASE, MARK MAIN, YIELD): L0: CALL PlayerPhase_Suspend, RefreshEntityBmMaps,
  RenderBmMap, RefreshUnitSprites, StartPlayerPhaseStartTutorialEvent; WHILE EventEngineExists; CALL
  PlayerPhase_HandleAutoEnd, StartMapSongBgm. L9: CALL StartPlayerPhaseSideWindows, ResetUnitSpriteHover; REPEAT
  PlayerPhase_MainIdle. L1: CALL EndPlayerPhaseSideWindows; WHILE DoesBMXFADEExist; CALL SetAllUnitNotBackSprite,
  RefreshUnitSprites; START_CHILD_BLOCKING gProcScr_0859ACE8; CALL PlayerPhase_InitUnitMovementSelect; SLEEP 1;
  REPEAT PlayerPhase_RangeDisplayIdle; CALL PlayerPhase_DisplayUnitMovement; REPEAT PlayerPhase_WaitForUnitMovement.
  L2: REPEAT PlayerPhase_ApplyUnitMovement. L7: WHILE_EXISTS ProcScr_CamMove; CALL_2 PlayerPhase_PrepareAction,
  ApplyUnitAction, HandlePostActionTraps, RunPotentialWaitEvents, EnsureCameraOntoActiveUnitPosition; CALL
  PlayerPhase_FinishAction; GOTO 0. L4: WHILE DoesBMXFADEExist; GOTO 1. L5: CALL PlayerPhase_ReReadGameSaveGfx.
  L10: START_CHILD_BLOCKING gProcScr_ADJUSTSFROMXI; GOTO 9. L6: CALL PlayerPhase_ResumeRangeDisplay; GOTO 1.
  L8: YIELD; CALL EndAllMus; GOTO 0. L11: CALL EndPlayerPhaseSideWindows; WHILE DoesBMXFADEExist; CALL
  DisplayActiveUnitEffectRange; REPEAT PlayerPhase_RangeDisplayIdle; GOTO 9. L12: CALL
  PlayerPhase_DisplayDangerZone; REPEAT PlayerPhase_RangeDisplayIdle; GOTO 9. L3: WHILE DoesBMXFADEExist; END.
- Child gProcScr_0859ACE8 (not in the symbol set): CALL MakeMoveunitForActiveUnit, TryCallSelectEvents; WHILE
  EventEngineExists; END.
- sProcScr_MoveLimitView (NAME E_MOVELIMITVIEW, MARK DISP, SET_END_CB MoveLimitView_OnEnd): START_CHILD
  sProcScr_MoveLimitViewChange; CALL MoveLimitView_OnInit; REPEAT MoveLimitView_OnLoop; END.
- sProcScr_MoveLimitViewChange (NAME MLVCHC, MARK DISP): CALL MoveLimitViewChange_OnInit; REPEAT
  MoveLimitViewChange_OnLoop; END.

**player_interface.c**

- gProcScr_TerrainDisplay (NAME TI, PROC_15, YIELD): CALL TerrainDisplay_Init. L0: WHILE_EXISTS ProcScr_CamMove;
  REPEAT TerrainDisplay_Loop_OnSideChange, TerrainDisplay_Loop_SlideIn, TerrainDisplay_Loop_Display,
  TerrainDisplay_Loop_SlideOut; GOTO 0; END.
- gProcScr_UnitDisplay_MinimugBox (NAME UI1, PROC_15, YIELD): CALL MMB_Init. L0: WHILE_EXISTS ProcScr_CamMove;
  REPEAT MMB_Loop_OnSideChange, MMB_Loop_SlideIn. L2: REPEAT MMB_Loop_Display. L3: REPEAT MMB_Loop_SlideOut;
  GOTO 0. L1: CALL MMB_CheckForUnit; GOTO 2; END.
- gProcScr_SideWindowMaker: WHILE DoesBMXFADEExist; CALL InitPlayerPhaseInterface; END.
- gProcScr_GoalDisplay (NAME PI, PROC_15, YIELD): CALL GoalDisplay_Init. L0: WHILE_EXISTS ProcScr_CamMove; REPEAT
  GoalDisplay_Loop_OnSideChange, GoalDisplay_Loop_SlideIn, GoalDisplay_Loop_Display, GoalDisplay_Loop_SlideOut;
  GOTO 0; END.

**uimenu.c**

- sProc_Menu (NAME E_Menu): SLEEP 0; WHILE_EXISTS ProcScr_CamMove; CALL StartGreenText, RedrawMenu, Menu_OnInit;
  JUMP sProc_MenuMain; END.
- sProc_MenuItem: PROC_BLOCK only (no END in the array).
- sProc_MenuMain (JUMP target of sProc_Menu, not in the symbol set): REPEAT Menu_OnIdle; CALL EndGreenText; END.

**mu.c**

- ProcScr_Mu (NAME MOVEUNIT, MARK MU, SET_END_CB MU_OnEnd): REPEAT Mu_OnLoop (no END in the array).
- ProcScr_MuStepSe: CALL MuStepSe_Init; YIELD; CALL MuStepSe_PlaySeA; YIELD; CALL MuStepSe_PlaySeB; END.

**bmio.c**

- sProc_BMVSync (MARK DISP, SET_END_CB BMapVSync_OnEnd): SLEEP 0. L0: CALL BMapVSync_UpdateMapImgAnimations,
  BMapVSync_UpdateMapPalAnimations, SyncUnitSpriteSheet, WfxVSync; REPEAT BMapVSync_OnLoop; END.
- gProc_MapTask (NAME MAPTASK, END_DUPLICATES, MARK DISP): SLEEP 0. L0: CALL PutUnitSpritesOam, WfxUpdate,
  UpdateBmMapDisplay; SLEEP 0; GOTO 0.

**phasechangefx.c**

- ProcScr_PhaseIntro: CALL PhaseIntro_EndIfNoUnits, PhaseIntro_InitGraphics; START_CHILD gProcScr_PhaseIntroText,
  gProcScr_PhaseIntroSquares, gProcScr_PhaseIntroBlendBox; CALL PhaseIntro_InitDisp; REPEAT PhaseIntro_WaitForEnd;
  CALL StartMapSongBgm; END.
- gProcScr_PhaseIntroText: CALL PhaseIntroInitText; SLEEP 0x06; CALL PhaseIntroText_PutText; START_CHILD
  gProcScr_PhaseIntroUnk; REPEAT PhaseIntroText_InLoop; SLEEP 0x1E; REPEAT PhaseIntroText_OutLoop; CALL
  PhaseIntroClearText; END.
- gProcScr_PhaseIntroUnk: CALL PhaseIntroUnk_Init; REPEAT PhaseIntroUnk_Loop (no END in the array).
- gProcScr_PhaseIntroSquares: CALL PhaseIntroSquares_Init; REPEAT PhaseIntroSquares_InLoop, PhaseIntroSquares_OutLoop;
  END.
- gProcScr_PhaseIntroBlendBox: CALL PhaseIntroBlendBox_Init; REPEAT PhaseIntroBlendBox_InLoop,
  PhaseIntroBlendBox_OutLoop; END.

**cp_order.c, cp_phase.c, cp_decide.c, cp_perform.c (AI)**

- gProcScr_CpOrder (NAME E_CPORDER): REPEAT CpOrderMain; END.
- gProcScr_BerserkCpOrder (NAME E_BSKORDER): CALL CpOrderBerserkInit; REPEAT CpOrderFunc_End; END.
- gProcScr_CpPhase (NAME E_CPPHASE): CALL AiPhaseInit; YIELD; CALL AiPhaseCleanup; END.
- gProcScr_BerserkCpPhase (NAME E_BSKPHASE): CALL AiPhaseBerserkInit; YIELD; CALL AiPhaseCleanup; END.
- gProcScr_CpDecide (NAME E_CPDECIDE): L0: CALL CpDecide_Main; SLEEP 0; CALL CpDecide_Suspend; GOTO 0; END.
- gProcScr_CpPerform (NAME E_CPPERFORM): CALL CpPerform_UpdateMapMusic, CpPerform_MoveCameraOntoUnit; SLEEP 0;
  CALL CpPerform_BeginUnitMovement; WHILE MuExistsActive; CALL CpPerform_MoveCameraOntoTarget; SLEEP 0; CALL
  CpPerform_PerformAction; REPEAT CpPerform_WaitAction; CALL_2 HandlePostActionTraps, RunPotentialWaitEvents; CALL
  CpPerform_Cleanup, CpPerform_EquipBest. L1: END.
- gProcScr_AiTargetCursor: SLEEP 0; WHILE_EXISTS ProcScr_CamMove; REPEAT AiTargetCursor_Main; END.

**Not extracted:** gProc_BMapMain (bm.c), gProcScr_ResetCursorPosition (bmcamadjust.c), gProcScr_GameControl
(gamecontrol.c), ProcScr_StdEventEngine (event.c) and ProcScr_GreenTextColor (fontgrp.c) are outside the listed
file set. gProcScr_ADJUSTSFROMXI (child of playerphase.c L10) is not in the symbol set and was not looked up.
ProcScr_CamMove (WHILE_EXISTS target) was not looked up.

### `ram_trace_prologue_attack` (PP-cap3, ROM repo `07a6ca9`; for M6-C)

Turn 1 (two Soldiers at (8,6)/(9,6) are already in reach). Watches as cancel_end plus `target`
(`gUnitArrayRed[1]` 0x0202D004, Soldier, HP 22). Per-frame RAM f1255..2070, full dumps every 10f.
Battle animations are ON (save options), so the battle is the ekr screen; M6-C (map combat, no animation)
will need a variant with animations off.

| Event | Frame |
|---|---|
| A select Eirika; range | f1260; f1262 |
| RIGHT ×4 to (8,5); A confirm; MU walk | f1275–1299; f1330; f1331..1365 |
| Unit menu Attack/Item/Wait | f1365 |
| A Attack → weapon list (Rapier only, MenuItemPanel) | f1400 |
| A Rapier → TargetSelection + BKSEL forecast, cursor on the Soldier | f1430 |
| A confirm; CombatAction, ekrBattleStarting | f1460; f1464 |
| ekrBattle (animation) | f1496..1989 |
| MU back; exp 0→10 (no level-up), Eirika HP 16→7, Soldier HP 22→8 | f2010; f2028 |
| Eirika state 0x2 (acted); idle UI procs back | f2034; f2038 |

## PP-11 result (Wait after the unit menu, `ram_trace_prologue_select_move` f1356..1364)

`PlayerPhaseActionDifferentialTest` seeds f1355 (menu ended) and runs the real script from label 7 with the
ported action functions, the real event engine and the post-action event check. The dumps are VBlank
snapshots of main loop passes: the passes are f1356, f1357, f1358+f1359 (f1358 is inside
`EventEngine_OnEnd`, after the unlock, before the proc is freed), f1360..f1363 (inside `WriteSuspendSave`
and `StartPlayerPhaseSideWindows`) and f1364. The test compares state at those calls; PlayerPhase command is
compared at f1356..1359 (35) and f1364 (16, idle). Compared every dump: Eirika's state (0x1, 0x41, 0x42),
`gActionData`, active unit, all of `gBmSt` and `gPlaySt`, all units, unit map, event flags and slots, the
StdEventEngine (present, command), the MU (gone from f1360), the game lock.

- Verified (`Ported from`, break-checked): `PlayerPhase_PrepareAction` (Wait path), `ApplyUnitAction` (Wait
  branch), `HandlePostActionTraps` (the "no trap" result), `RunPotentialWaitEvents`,
  `EnsureCameraOntoActiveUnitPosition`, `PlayerPhase_FinishAction` (no fog, no win, no canto),
  `PlayerPhase_HandleAutoEnd` (the "units can act" path), `MoveActiveUnit` (alive, no rescue), `CheckForWaitEvents`,
  `RunWaitEvents` (the matching-entry path), `ShouldCallEndEvent`, `EvCheck01_AFEV`, `CountAvailableBlueUnits`,
  `AreAnyEnemyUnitDead`.
- Transcribed, not verified: `TryMakeCantoUnit` (its bail at CA_CANTO has no effect that differs from the next
  test), `UnitBeginCantoAction`, `PlayerPhase_CancelAction`, `SearchNextAvailableEvent` (reached, but the
  started entry's flag already hides it), `CountRedUnits` (the script's branch is the same for any count > 1);
  the other branches of the functions above are marked in their KDoc.
- Stand-ins in the test: `RegisterEventActivation`/`ClearActiveEventRegistry`/`GetEventTriggerId` (the one-enemy-left
  script's `CHECK_EVENTID_` needs them: slot 2 = 7), `WriteSuspendSave` and `StartPlayerPhaseSideWindows`
  (only the state at the call is compared).
- Left: wiring the new hooks (`checkForWaitEvents`, `runWaitEvents`, `shouldCallEndEvent`, `getPickTrapType`,
  `execTrap`, `writeSuspendSave`, `canUnitMove`, `pidStatsAddSquaresMoved`) in `Fe8Boot` (PP-17); porting the
  active event registry and `ExecTrap`/`GetPickTrapType`; the other `ApplyUnitAction` branches (MC-*); the
  cursor line of `FinishAction` is unobserved (the cursor already stands on the unit).

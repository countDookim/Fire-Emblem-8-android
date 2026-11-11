# Task backlog

Open rows only. Milestones, task rules and the reasons: [ROADMAP](plans/ROADMAP.md) (owner-approved 2026-11-11).

- Kinds: **CAP** capture (ROM repo) · **INV** inventory test, no port claim · **PORT** one behaviour in one
  capture window, ≤ ~8 functions · **WIRE** connect into the game + headless test + MANUAL_TEST.
- Port only functions the capture window runs. `Ported from` + break-checked `@VerifiesPort` (bare function
  names), else `Transcribed from … (not verified: why)`.
- Claim before starting (CLAUDE.md rule 10); take the first `todo` whose deps are done, in either lane.
- Status ≤ 15 words; details go to the lane's notes file. Delete a finished row and add its ID to Done.
- Fewer than 5 open rows in a lane: break the next ROADMAP milestone into rows.

## Lane A: opening wired (M6-A, D-11)

Notes: [D11-worldmap](plans/D11-worldmap.md) (§8 status), [M6-notes](plans/M6-notes.md) (monologue). Captures:
`ram_trace_prologue` (steps 1213–9313), `ram_trace_newgame_bm` (f9314–9900), the three Start-skip runs.

| ID | Kind | Task | Acceptance | Deps | Status |
|---|---|---|---|---|---|
| D11-wire-a | PORT | Start-skip audio (opsubtitle skip path, sound calls) | `gSoundSt`/player state at skip end equals the captures at 1505/2375/4405, else stays marked unverified | — | todo |
| D11-wire-b | WIRE | Monologue hooks in `Fe8Boot`: real `ScreenFade`, `ArchivedPaletteFade`, keys | Headless New Game: parent blocked at 1213, released at 4898 (callback spans allowed); 0 B/frame; MANUAL_TEST | D11-wire-a | todo |
| D11-wm3b | PORT | `SetupGraphicSystemsForWorldMap`, `sub_80B8E60` | LCD/BG registers and surviving VRAM vs f05100 | — | todo |
| D11-wm3c | PORT | `worldmap_screen*.c` procs started by `WorldMap_Init` (`NewMapScreen`, GMapScreenVSync) | Proc fields per step from 4900 | — | todo |
| D11-wm3d | PORT | Node icon display and world-map cursor procs | Proc fields per step from 4901 | — | todo |
| D11-wm3e | PORT | `StartGmapMuEntry`/`GmapMuEntry2` (4907–9285); replaces the last BeginningHooks stand-in | Proc fields per step | — | todo |
| D11-wm8a | PORT | Standalone WM event host; register B4–B7; VM-driven replay 4907–5100 | GmapRM procs and BG1 at f05100, started by the script (not by the test) | D11-wm3b–e | todo |
| D11-wm8b | PORT | Text opcodes through the host, 5100–5500 | WmText and Talk proc fields per step | D11-wm8a | todo |
| D11-wm8c | PORT | `WM_MOVECAM2` scrolls and nation highlights through the VM, 5436–8110 | UpdateExt/Border1 fields per step | D11-wm8a | todo |
| D11-wm8d | PORT | Portrait opcodes C4/C5 through the VM, 5764–7780 | Face procs per step; BG2 and palette at dumps | D11-wm8a | todo |
| D11-wm8e | PORT | Unit/dot opcodes 9E–AC through the VM, 8100–9280 | GmapUnit/AutoMu/WmPlaceDot fields per step; OAM at f08100–f09000 | D11-wm8a | todo |
| D11-wm8f | PORT | Fade-out, second event run (9289–9292), `EndWM`, `ResetFaces` restore | r09300/r09330 | D11-wm8b–e | todo |
| D11-cap1 | CAP | Per-step LCD + OAM watch for 5500–5700 and 8100–8450 | Old runs byte-identical; published; available through FE8_ASSETS | — | todo |
| D11-wm4d | PORT | Border1 blend/OAM/palette and unit-fade palettes | Per-step LCD/OAM equal D11-cap1 | D11-cap1 | todo |
| D11-wm9 | WIRE | `hooks.worldMapWrapperScript`/`endWorldMap` in `Fe8Boot` | Headless New Game reaches `StartBattleMap`; 0 B/frame; MANUAL_TEST | D11-wm8f | todo |
| D11-cap2 | CAP | New Game with no key press during the chapter intro (main path to the map) | Per-frame RAM, full dumps every 10f; old runs byte-identical; published | — | todo |
| D11-ci1 | PORT | `ChapterIntro_Init`, KeyListen, Bg1And3Scroll (newgame_bm 9322–9419; see D11-worldmap §9) | Proc fields per frame | — | todo |
| D11-ci2 | PORT | Chapter title graphics (`chapter_title.c`, `ChapterIntro_DrawChapterTitle`) | VRAM/tilemap at full dumps of D11-cap2 | D11-ci1, D11-cap2 | todo |
| D11-ci3 | PORT | Light effects (TwinLights, LightBurst, LightExplosion), main path | OAM/blend per frame in D11-cap2 | D11-ci1, D11-cap2 | todo |
| D11-ci4 | PORT | Skip path: fade out, `InitMapDisplay`, fast fade to map, `ChapterIntro_End` (9419–9506) | Proc fields per frame; existing map-start checks | D11-ci1 | todo |
| D11-ci5 | WIRE | Replace the chapter-intro stand-in (main path: fog, fade to map, from D11-cap2 inventory) | Headless full opening; MANUAL_TEST "New Game plays the whole opening" | D11-ci2–4, D11-wm9 | todo |

## Lane B: player phase (M6-B)

Notes: create `docs/plans/M6B-player-phase.md` with PP-0. Start state: `prologue.sav` (turn-1 suspend).

| ID | Kind | Task | Acceptance | Deps | Status |
|---|---|---|---|---|---|
| PP-cap1 | CAP | `ram_trace_prologue_select_move`: select Eirika, move 2 tiles, Wait | procs/gbmst/actiondata/unit watches per step; full dumps every 10 steps; old runs byte-identical | — | todo |
| PP-cap2 | CAP | Cancel paths (B at each stage), map menu, End turn | same | — | todo |
| PP-cap3 | CAP | Attack through forecast to exp gain (for M6-C) | same | — | todo |
| PP-0 | INV | Proc tree and live step ranges in cap1/cap2 | Inventory test; PP-1..16 function lists corrected | PP-cap1, PP-cap2 | todo |
| PP-1 | PORT | Movement map: `GenerateUnitMovementMap`, `GenerateMovementMap`, `SetWorkingMoveCosts`, `SetWorkingBmMap` | `gBmMapMovement` equals the dump after select | PP-0 | todo |
| PP-2 | PORT | Attack range: `GenerateUnitCompleteAttackRange`, `MapSetInRange`, `MapAddInBoundedRange` | `gBmMapRange` equals the dump | PP-1 | todo |
| PP-3 | PORT | Select: `PlayerPhase_MainIdle` A branch, `GetPlayerSelectKind`, `PlayerPhase_InitUnitMovementSelect`, `DisplayUnitEffectRange` | PlayerPhase proc position per step; active unit | PP-1 | todo |
| PP-4 | PORT | Range squares: `MoveLimitView_*`, `DisplayMoveRangeGraphics`, `HideMoveRangeGraphics` | BG2 tilemap and palette at dumps | PP-3 | todo |
| PP-5 | PORT | Path arrow data: `ResetPathArrow`, `UpdatePathArrowWithCursor`, `AddPointToPathArrowProc`, `CutOffPathLength` | Path buffers per step | PP-3 | todo |
| PP-6 | PORT | Path arrow drawing: `DrawPathArrow`, `DrawUpdatedPathArrow` | OAM at dumps | PP-5 | todo |
| PP-7 | PORT | `PlayerPhase_RangeDisplayIdle` confirm/cancel, `CanMoveActiveUnitTo`, `PlayerPhase_BackToMove`, `CancelAction` | Proc position per step in cap1/cap2 | PP-3 | todo |
| PP-8 | PORT | Move: `GenerateBestMovementScript`, `PlayerPhase_DisplayUnitMovement`, `WaitForUnitMovement`, `ApplyUnitMovement` | MU fields per step; unit x/y after | PP-7 | todo |
| PP-9 | PORT | Menu core: `uimenu.c` start/init/redraw/idle/input/end, cursor hand | Menu proc fields and BG tilemaps per step | PP-0 | todo |
| PP-10 | PORT | Unit menu items the Prologue shows; Wait effect | `gActionData` after select | PP-9 | todo |
| PP-11 | PORT | `PlayerPhase_PrepareAction`/`FinishAction`, Wait action, `HandleAutoEnd` | Unit state bits after the action | PP-8, PP-10 | todo |
| PP-12 | PORT | Map menu draw and End | Menu fields; other items are explicit stand-ins | PP-9 | todo |
| PP-13 | PORT | End turn → phase change | Faction change equals cap2 (enemy AI stand-in ends at once) | PP-12 | todo |
| PP-14 | PORT | Terrain window (`TerrainDisplay_*`) | BG tilemap per step | PP-0 | todo |
| PP-15 | PORT | Unit mini-box and HP bar (`MMB_*`, `DrawHpBar`) | BG tilemap per step | PP-0 | todo |
| PP-16 | PORT | Goal window and menu button (`GoalDisplay_*`, `MenuButtonDisp_*`) | BG tilemap/OAM per step | PP-0 | todo |
| PP-17 | WIRE | Player-phase hooks in `Fe8Boot`; replay cap1 inputs headless | Same unit position/state as cap1; 0 B/frame; MANUAL_TEST | PP-11, PP-13 | todo |

## Other open rows

| ID | Kind | Task | Acceptance | Deps | Status |
|---|---|---|---|---|---|
| CAP-hard1 | CAP | Hard-mode New Game run reaching autolevelled loads (unblocks old M6-13) | Run published; then tag `UnitAutolevelRealistic`, `UnitApplyBonusLevels`, `UnitAutolevelPenalty` | — | todo |
| CAP-collide1 | CAP | Run where a unit load lands on an occupied tile (unblocks old M6-14) | Run published; then verify `AiGetUnitClosestValidPosition`, `AdjustNewUnitPosition` | — | todo |
| D12-2 | CAP+PORT | Enemy-phase intro capture with per-scanline blend; extend `PhaseIntroDifferentialTest` | Enemy name graphics/palette and per-line blend equal the capture | — | todo; do with M6-D |
| D09-3 | PORT | Remaining item-popup branches (gold, full inventory, red/root, unit-name/number) | Each branch against a capture that reaches it, else not written | — | low priority; owner popup check pending |

Owner-PC sessions also continue [READABILITY](READABILITY.md) (rule 11).

## Done

Row details, including what each task left transcribed: [archive](plans/TASKS-archive-2026-11-11.md) and `git log`.

- **M1–M5** complete. **M6 part 1** (Prologue opening to turn 1, suspend/resume): M6-01..12, 15..19, 21..28,
  30..39, 41..43, 45, 46 done; M6-20, 29, 40, 44 done as far as captures allow.
- **D-series:** D11-ci0 (`ChapterIntroProcInventoryTest`), D07-audio, D07-trace, D09-1, D09-1b, D09-2, D11-slides, D11-fades, D11-init, D11-flare,
  D11-skip, D11-proc, D11-worldmap, D11-wm1, D11-wm2, D12-1. D11-wm3..wm7 (+ wm4a–c, wm6b–d): standalone
  ports verified as far as the Prologue runs them; the rest is in lane A.
- M4-02, M4-12, M5-56, 56b, 57..61.
- **App:** APP-1 (launcher Delete save, `e39c730`; device check pending).
- **Alignment:** PROG-1 (`b459cf3`), UNV-1, ALN-1 (`3f85702`), ALN-2, STAND-1, STAND-2, M7-0 ([M7-modding](plans/M7-modding.md): 23 proposed rows; D1 decided = option A, ADR 0015; D2–D9 open).

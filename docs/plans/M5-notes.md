# M5 notes: core loop, saves, flag registry, script interpreter

Facts for the open M5 tasks (docs/TASKS.md); checked against decomp 04d1edb and the nm file. Ground truth is only mGBA
dumps or ROM golden values (ADR 0006); ROM golden values are read at nm addresses in baserom.gba, never from our
extractor output.

Conventions: decomp = fireemblem8u at 04d1edb (src/, include/, asm/); nm = ROM repo `decomp/fireemblem8.nm.txt`
(`addr size type name`; some symbols lack a size: use the next symbol's address). ROM 0x08xxxxxx, EWRAM 0x02xxxxxx,
IWRAM 0x03xxxxxx.

## Where the M5 code is

| Area | Kotlin |
|---|---|
| Scripts | `gbafe.script` FeScriptParser, ScriptImage/assembler; `gbafe.extract.fe8.events` Fe8EventCommands, Fe8ScriptConstants |
| Flags | `gbafe.core.flags` EventFlags, FlagRegistry, VarRegistry; `fe8.game.flags.Fe8EventFlags` |
| Saves | `gba.core.sram.CartSram`; `gbafe.save` container + SaveChecksums; `fe8.game.save` Fe8SramLayout, BmSaveLib, Fe8SavImporter |
| Core loop | `fe8.game` GameControl; `fe8.game.bm` PlaySt, BmSt, ChapterData, BmMap, ChapterMapGfx, UnitTable, EntityBmMaps, BmMapRender, Camera |
| Events | `fe8.game.event` EventState, EventEngine (+EventEngineHooks), EventScrCore |
| Test infra (`:testing`, `:gba:testkit`) | ReferenceData.Run `sram()`/`watch()` → WatchTrace, ProcArrayDump, EventTrace, GameControlDifferentialTest, EventVmDifferentialTest |

## Flow: title to first player phase

1. Title_IDLE sets nextAction = GAME_ACTION_EVENT_RETURN on START; GameControl_PostIntro goes to
   LGAMECTRL_EXEC_SAVEMENU (gamecontrol.c:100): StartSaveMenu (savemenu.c:1673), GameControl_SwitchPostSaveMenu
   (:447). New Game: SaveMenuWriteNewGame (savemenu.c:518) → WriteNewGameSave (bmsave.c:105). Our stand-in until
   M5-58 just holds.
2. LGAMECTRL_EXEC_BM (:115): CheckNewGame/GameComplete branches, RememberChapterId, GameCtrlStartIntroMonologue
   (chapter 0 only), ProcScr_WorldMapWrapper (0x08A3DD50; plays EventScrWM_Prologue_Beginning), EndWM, sub_8009E28,
   StartBattleMap, then the post-chapter labels, looping to EXEC_BM.
3. StartBattleMap (bmio.c:971): SetupBackgrounds, main/VBlank callbacks, ClearBattleMapState (:964), sub_80156D4,
   ApplyUnitSpritePalettes, ResetChapterFlags, ResetUnitSprites, ResetMenuOverrides, ClearTraps; faction GREEN,
   turn 0; GetBattleMapKind, GetROMChapterStruct, InitBmBgLayers, InitChapterMap, InitMapObstacles,
   ClearTemporaryUnits, LoadChapterTraps.
4. StartBMapMain (bmio.c:1190): gProc_BMapMain (0x0859A1F0, tree 2), gameCtrl lockCnt++, BMapVSync_Start
   (sProc_BMVSync 0x0859D8B8, allocated BEFORE MapTask), gProc_MapTask (0x0859D908, tree 4; per frame
   PutUnitSpritesOam, WfxUpdate, UpdateBmMapDisplay).
5. gProc_BMapMain (bm.c:41, labels 0-11): label 0 BmMain_StartIntroFx (gProcScr_ChapterIntro 0x0859B1B0); label 1
   SetEventId_0x84 (= SetFlag(0x84)), UpdatePrevDeployStates, CallBeginningEvents (bm.c:399 → CallEvent(
   beginningSceneEvents)), UndeployEveryone, UnsetEventId_0x84.
6. Label 3: BmMain_ChangePhase (bm.c:412: SwitchPhases GREEN→BLUE turn+1, RunPhaseSwitchEvents), then
   BmMain_SuspendBeforePhase (WriteSuspendSave(SAVE_ID_SUSPEND)).
7. Label 9 (no yield between 8 and 9): gProcScr_ResetCursorPosition (bmcamadjust.c:14), ProcScr_PhaseIntro
   (0x0859AF80, blocking; PhaseIntro_EndIfNoUnits), WHILE_EXISTS ProcScr_CamMove, TickActiveFactionTurn.
8. Label 5: BmMain_StartPhase → gProcScr_PlayerPhase (0x0859AAD8; playerphase.c:38): Suspend, RefreshEntityBmMaps,
   RenderBmMap, RefreshUnitSprites, tutorial event, WHILE EventEngineExists, PlayerPhase_HandleAutoEnd,
   StartMapSongBgm; label 9 side windows, REPEAT PlayerPhase_MainIdle (:253; HandlePlayerCursorMovement :215 reads
   newKeys2).
9. Zero-unit hazard: with no units, ResetCursorPosition and PhaseIntro end at once and HandleAutoEnd jumps back to
   label 3 every phase, so the game never idles. M5 uses the raw-unit stand-in UnitTable.
10. Resume from suspend (ram_trace_ch2_scroll): GameCtrl_StartResumedGame (bmio.c:1081) re-inits the map, starts
    BMapMain, then camera = GetCameraCenteredX/Y(16 × playerCursor).

## Core loop and map

- RAM: gBmSt 0x0202BCB0 (0x40; +0x0C camera, +0x10 cameraPrevious, +0x14 playerCursor, +0x20 playerCursorDisplay,
  +0x28 cameraMax), gPlaySt 0x0202BCF0 (0x4C; +0x0D chapterVisionRange (0 = no fog), +0x0E chapterIndex, +0x0F
  faction, +0x10 turn, +0x1B chapterModeIndex), gUnitArrayBlue 0x0202BE4C, gBmMapSize 0x0202E4D4, gBmMapUnit/Terrain/
  Fog pointers 0x0202E4D8/DC/E8, gBmMapBuffer 0x02001000, gBG2TilemapBuffer 0x02023CA8, sProcArray 0x02024E68 (64 ×
  0x6C; +0 script ptr), gActionData 0x0203A958 (0x38). GameCtrlProc: nextAction +0x29, nextChapter +0x2A, ch_index +0x30.
- ROM: gChapterDataTable 0x088B0890, gChapterDataAssetTable 0x088B363C, gBmMapBaseTiles ptr 0x0859A9D4.
- Render: RenderBmMap draws 15×10 metatiles into gBG3TilemapBuffer; UpdateBmMapDisplay/RenderBmMapColumn/Line
  (bmmap.c:418-519) redraw the edge that scrolled in; DisplayBmTile picks palette bank 6 or 11 from gBmMapFog.
- Camera: sCameraAnimTable (bm.c:39) is EWRAM, computed by CamMove_OnInit (bm.c:937-942), NOT ROM data. Cursor:
  HandleMapCursorInput :612, HandleMoveMapCursor :659, HandleMoveCameraWithMapCursor :681, PutMapCursor :825.
  Callers that traces reach: GetCameraCenteredX/Y in GameCtrl_StartResumedGame (ch2_scroll), StoreAdjusted
  CameraPositions in sub_8015D84 (CamMove), EnsureCameraOntoPosition in ProcFun_ResetCursorPosition (bmcamadjust.c:94).
- GetBattleMapKind is a switch on chapterIndex in code: verify via the compiled jump table near 0x080BD068 or a trace.
- ResetCursorPosition needs units (GetPhaseAbleUnitCount, GetUnitFromCharId(GetPlayerLeaderPid()), unit x/y);
  PlayerPhase_HandleAutoEnd calls GetPhaseAbleUnitCount(faction).
- Risks: StartBattleMap/InitChapterMap overrun frames (mid-frame dumps: skip a dump whose successor's key struct is
  identical, `titleKeyPath`). Stand-ins (save menu, monologue, WM, intros, sprites, weather) shift timing, so
  whole-run frame-exact compare waits for M6; verify per function from seeded state. Pools preallocated at max size.

## Saves and SRAM

Layout, magics and checksums are in `Fe8SramLayout`/`SaveChecksums` KDoc. Facts still needed:
- Packed structs (GameSavePackedUnit, UnitUsageStats, ChapterStats, …) are GCC BITPACKED LSB-first bitstreams across
  u32 boundaries; Dungeon/GMapSaveInfo offsets are suspect until a non-zero dump confirms them.
- WriteSuspendSave (bmsave.c:482; skipped if PLAY_FLAG_TUTORIAL): writes slot + GetNextSuspendSaveId() (=
  1 − last_suspend_slot), then flips the header; the first suspend lands in entry 4 (0x204C). Callers:
  playerphase.c:210/793, bmbattle.c:2221, cp_decide.c:70, bmtrap.c:213.
- Boot: EraseInvalidSaveData (bmsave-misc.c:100) converts the old 0x40603 layout (no ground truth: needs a foreign
  save); EraseSramDataIfInvalid → LoadAndVerfySuspendSave (bmsave-xmap.c:19) runs at boot on every preloaded save.
- Imports use the first 0x8000 bytes; never "repair" +0x62/+0x63 (outside the header checksum).
- Scope: M5 = boot init, New Game write, `.sav` import; Continue/Resume (ReadGameSave/ReadSuspendSave) is M6.

## Event flags and variables

- IWRAM: gPermanentFlagBits 0x03005250 (0x19 bytes used, flags 101..300), gChapterFlagBits 0x03005270 (5 bytes);
  mirrored as one 0x30-byte array. Flag 0x84 (132) is set/cleared by BMapMain around the beginning events.
- Quirks (port rejects them, KDoc says so): chapter flags 41..99 writable but never reset/saved; permanent > 300 hit
  padding, > 356 corrupt chapter bits; slot-2 values ≥ 0x8000 go negative.
- Handlers (eventscr.c): Event02 :110 (arg < 0 = slot 2; EVBIT_T/F are per-proc state bits, not flags), Event03
  :140 (result in slot C), Event10, Event19. Check*/Reset* get no `@VerifiesPort` from RAM alone (Check only via slot
  C after check_flag; Reset sees all-zero flags on the New Game path; needs e.g. a chapter-clear run).
- Several SetFlag calls per frame collapse into one observed state: compare a subsequence plus final bytes and model
  non-script writes (0x84, Reset*) explicitly.
- Mods: separate flag bank, handles ≥ MOD_BASE inside u16, persisted by name in the mod's own save section, never by
  registration index; vanilla bytes untouched.

## Event scripts and VM

- Encoding: header u16 = cmd<<8 | len<<4 | sub (len in u16 words incl. header); lo commands 0x00..0x45, WM 0x80+.
  Compiled form keeps this layout with 32-bit virtual addresses (ADR 0001 amendment, M5-01).
- EventEngineProc: +0x2C pCallback, +0x30 pEventStart, +0x34 pEventIdk, +0x38 pEventCurrent, +0x3C evStateBits, +0x3E
  evStallTimer, +0x41 execType, +0x48 pUnitLoadData. ProcScr_StdEventEngine 0x08591AC0, BattleEventEngine 0x08591AF8.
- EventEngine_OnUpdate (event.c:72): zero slot 0 per command; codes ADVANCE_CONTINUE 0, STOP_CONTINUE 1,
  ADVANCE_YIELD 2, STOP_YIELD 3, UNK4 4, END 5, ERROR 6. CanStartSkip/StartSkip (event.c:77): START skips, skipped
  commands still run (our port is partial).
- Semantics: Event01_End pops the active queue (empty in CUTSCENE → execType 5, jumps to EventScr_08592114); Goto
  scans linearly for `label<<16 | 0x0820`; Call pushes {Idk, Current} and returns UNK4; Branch compares signed;
  SlotOperation DIV/MOD unsigned; args < 0 read slot 2.
- IWRAM globals (one 0x178 block 0x03000438..0x030005B0): gEventCallQueue (16 × {ptr, execType, used}),
  gEventSlots 0x030004B8 (14 × u32), gEventSlotQueue 0x030004F0, gEventSlotCounter 0x03000568, gEventActiveQueue
  0x03000570 (8 × {evt1, evt2}: the call stack).
- Triggers: ChapterEventGroup (0x50 B; beginningSceneEvents +0x48) via GetChapterEventDataPointer; PrologueEvents
  0x089E863C; list entries u32 (lo16 type, hi16 flag), lengths from gEventListCmdInfoTable.
- Prologue opcode mix: 05 ×119, 07 ×70, 02 ×55, 3B ×40, 01 ×32, 30 ×25, 0A ×24, 1B/1D ×24, 1A ×23, 0E ×20, 2F ×19,
  34 ×18, 11 ×16, 0B ×10; most need unported subsystems (text, MU, unit load, map load, camera, battle, asm_call).
- Oracle differential (M5-53/54): ported handlers real; unported ones are test stubs that complete when the trace pc
  moves past; compare per yield around overrun frames (LOAD/LOMA).

## Reference runs (ROM repo `reference/scripts/<run>.txt`; header comments give exact frames)

NEWGAME timeline (`gen_scripts.py`): START f902, A f964 (New Game), DOWN f1030 (Normal), A f1060, A f1130 (slot 1),
then A every 30 f f1200..f19320 (all 2-frame holds). Any cadence change shifts every later frame.
Pinned: new-game save write f1130..1132 (+1 byte 0x7224 at f1214); StdEventEngine f4907..9292 (WM) and f9508..19312
(beginning scene, already inside RenaisThroneCutscene at step 9508); InitChapterMap(0) f9314..9319; load_map ch 16
settles ~f9513; BattleEventEngine f14479..14631; PhaseIntro f19316 (= suspend entry 4 commit); PlayerPhase f19393.

| Run | Content | Used by |
|---|---|---|
| `sram_boot`, `sram_preload` | boot SRAM init; menu with `newgame_slot0.sav` | M5-24/27 |
| `ram_trace_newgame` | per-frame f955..f1230, dumpsram around the write | M5-26/32/35 |
| `ram_trace_prologue` | watches every step to f19800 (gplayst, gbmst, rng, evglobals, flags, procs, actiondata) | M5-48/52/54 |
| `ram_trace_newgame_bm` | per-frame f9314..f9900 | M5-36/37/40/43, M5-53 |
| `ram_trace_first_phase` | per-frame f19280..f19530; holds RIGHT f19421, DOWN f19461, B+RIGHT f19501 (map fits the screen: no scroll) | M5-38..47 |
| `ram_trace_ch2_scroll` | `chapter2.sav` suspend resumed (map ~f1200, map 15 wide: camera y 0..80); holds RIGHT f1301, DOWN f1361, LEFT f1421, UP f1481 (60 f), B+RIGHT f1541; per-frame f1100..f1600 | M5-41c/42/47 |
| `ram_trace_ch6_fog` | `chapter6.sav` Ch6 restart (chapterIndex 7, vision 3); dumpram every 30 f f1100..f1990, per-frame f1990..f2273; holds RIGHT/DOWN/LEFT/UP 60 f from f2004 (camera x 0..112, y 74..160) | M5-39b/42/47 |

- Fixtures (ROM repo `reference/fixtures/`, owner's mGBA saves 2026-11-05; ROM repo only): `prologue.sav` and
  `chapter1.sav` (chapter-start suspends, unused so far), `chapter2.sav`, `chapter6.sav`. Every owner save also holds
  game slot 1 = Ch6 "Victims of War" (turn 0, fog). With a suspend the main menu's first item is Resume Chapter (then
  a slot list on the suspend's slot); without one it is Restart Chapter.
- Watch files hold one entry per harness step, not per frame (`hold` without split_holds is one step).
- Gaps (add a run when a task needs it): CamMove at a scrolling chapter start (ch6 has only 30-frame dumps before
  f1990); SRAM after boot with a valid suspend preloaded (LoadAndVerfySuspendSave, M5-25: `--sram chapter2.sav` +
  dumpsram); Reset*/chapter-clear flags; suspend/resume round trips (M6).
- Test patterns: seed from dump k, step, compare with k+1 (ProcEngineDifferentialTest); compare SRAM on the frame
  after a write, whole 32 KiB or named blocks; sentinels in buffers the port must overwrite.

## Deferred to M6

- Units (bmunit.c LoadUnits, autolevel RNG), Unit struct, map sprites; M5 uses UnitTable.
- Suspend/Continue: WriteSuspendSave/ReadSuspendSave, packed units, ReadGameSave, convoy read, slot rotation; runs
  prologue_suspend/restart/resume and a savestate that carries SRAM (loadstate does not restore SRAM today).
- Persistent SaveStorage (atomic temp+rename on JVM/Android).
- UI stand-ins: save menu, intro monologue, world map (87 WM handlers), chapter intro, phase banner, text/talk,
  scripted battles, side windows, traps, weather, BMVSync animations, map BGM, cursor sprite.
- Whole-run frame-exact comparison; trigger scanning (SearchAvailableEvent, EvCheck*, RunPhaseSwitchEvents);
  sound/asm_call event handlers; LOAD unit groups for every chapter.

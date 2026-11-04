# M5 notes: core loop, saves, flag registry, script interpreter

Supporting facts for milestone M5 (New Game to the prologue's first player phase, SRAM saves, event flags/variables, event script VM); the task list itself is docs/TASKS.md. Facts checked against decomp 04d1edb and the nm symbol file, 2026-11-04.

Conventions: decomp = fireemblem8u checkout at 04d1edb (HANDOFF How-to; src/, include/, asm/); nm = Fire-Emblem-8-rom/decomp/fireemblem8.nm.txt (format `addr size type name`; some symbols have no size); ROM repo = Fire-Emblem-8-rom checkout (harness/, extracted/, dist/). ROM symbols are 0x08xxxxxx, RAM 0x02xxxxxx (EWRAM) and 0x03xxxxxx (IWRAM). Ground truth is only mGBA dumps or ROM golden values (ADR 0006).

## Flow: main menu to first player phase

1. Title: TitleScreen.Title_IDLE sets nextAction=GAME_ACTION_EVENT_RETURN on START (titlescreen.c; Kotlin TitleScreen.kt:518), then GameControl_PostIntro (gamecontrol.c) goes to label LGAMECTRL_EXEC_SAVEMENU.
2. gProcScr_GameControl (ROM 0x085916D4, 0x3D0 = 122 cmds; gamecontrol.c:42-205). LGAMECTRL_EXEC_SAVEMENU (:100): EnableSoundEffects, StartSaveMenu (savemenu.c:1673), yield, GameControl_SwitchPostSaveMenu (:447; EVENT_RETURN -> GmDataInit worldmap_path.c:1352), goto EXEC_BM.
3. Save menu New Game: SaveMenuWriteNewGame (savemenu.c:518; call at :538) -> WriteNewGameSave(slot, isDifficult, 1, isTutorial) (bmsave.c:105: SetGameTime(0), InitPlayConfig, InitUnits, ResetPermanentFlags, chapterIndex=0).
4. LGAMECTRL_EXEC_BM (:115): GameCtrl_CheckNewGameAndBranch, GameCtrl_CheckGameCompleteAndBranch, GameControl_RememberChapterId, GameCtrlStartIntroMonologue (chapter 0 only; StartIntroMonologue opsubtitle.c, gProcScr_OpSubtitle 0x08AA21BC, START skips at opsubtitle.c:143), PROC_START_CHILD_BLOCKING ProcScr_WorldMapWrapper (0x08A3DD50; worldmap_main.c:1955; plays EventScrWM_Prologue_Beginning), EndWM, sub_8009E28, StartBattleMap, then falls through to POST_NORMAL_CHAPTER / GameControl_PostChapterSwitch / label 19 / GameControl_CallPostChapterSaveMenu and loops to EXEC_BM.
5. StartBattleMap (bmio.c:971): SetupBackgrounds(NULL), SetMainUpdateRoutine(OnMain)/SetInterrupt_LCDVBlank(OnVBlank), ClearBattleMapState (:964), sub_80156D4 (bm.c:586), ApplyUnitSpritePalettes, ResetChapterFlags (bmio.c:982 -> eventinfo.c:1128), ResetUnitSprites, ResetMenuOverrides, ClearTraps; faction=GREEN, turn=0; GetBattleMapKind (worldmap_path.c:1916) and GetROMChapterStruct (chapterdata.c); InitBmBgLayers (bm.c:552), InitChapterMap (bmmap.c:60), InitMapObstacles, ClearTemporaryUnits, LoadChapterTraps.
6. StartBMapMain (bmio.c:1190): starts gProc_BMapMain (0x0859A1F0) on proc tree 2, gameCtrl lockCnt++, BMapVSync_Start (bmio.c:289; proc sProc_BMVSync 0x0859D8B8, allocated BEFORE MapTask), then gProc_MapTask (0x0859D908, tree 4; bmio.c:139: PutUnitSpritesOam, WfxUpdate, UpdateBmMapDisplay each frame).
7. gProc_BMapMain (bm.c:41, labels 0-11): label 0 BmMain_StartIntroFx (gProcScr_ChapterIntro 0x0859B1B0); label 1 SetEventId_0x84 (bm.c:59; eventcall.c:404 = SetFlag(0x84)), UpdatePrevDeployStates, PROC_CALL_2 CallBeginningEvents (bm.c:61/399 -> CallEvent(beginningSceneEvents), event.c:207 -> EventEngine_Create :216), UndeployEveryone, UnsetEventId_0x84 (:67, eventcall.c:409 = ClearFlag(0x84)).
8. Label 3: BmMain_ChangePhase (bm.c:412: ClearActiveFactionGrayedStates, RefreshUnitSprites, SwitchPhases bm.c:375 GREEN->BLUE and turn+1, RunPhaseSwitchEvents eventinfo.c:1310), then PROC_CALL BmMain_SuspendBeforePhase (bm.c:73/477 -> WriteSuspendSave(SAVE_ID_SUSPEND)).
9. Label 9 (no yield between 8 and 9): PROC_START_CHILD gProcScr_ResetCursorPosition (bmcamadjust.c:14), PROC_START_CHILD_BLOCKING ProcScr_PhaseIntro (0x0859AF80; phasechangefx.c:68; PhaseIntro_EndIfNoUnits :399), WHILE_EXISTS ProcScr_CamMove (CamMove_OnInit bm.c:914, OnLoop :958), TickActiveFactionTurn, status displays.
10. Label 5: BmMain_StartPhase (bm.c:438) -> gProcScr_PlayerPhase (0x0859AAD8; playerphase.c:38): label 0 PlayerPhase_Suspend, RefreshEntityBmMaps, RenderBmMap, RefreshUnitSprites, StartPlayerPhaseStartTutorialEvent, WHILE EventEngineExists, PlayerPhase_HandleAutoEnd, StartMapSongBgm; label 9 StartPlayerPhaseSideWindows, REPEAT PlayerPhase_MainIdle (:253; HandlePlayerCursorMovement :215 uses newKeys2).
11. Zero-unit hazard: LOAD commands are stubbed in M5, so with no units ResetCursorPosition ends at once, PhaseIntro_EndIfNoUnits ends the intro and PlayerPhase_HandleAutoEnd jumps back to label 3 every phase: the game cycles phases and never idles in PlayerPhase_MainIdle. A raw-unit stand-in is required for an idle state.

## Core loop / map

Key files: gamecontrol.c, bmio.c (62 fns), bm.c (48), bmmap.c (29), playerphase.c (40), bmcamadjust.c, phasechangefx.c, chapterdata.c, chapterintrofx.c (41, M6), titlescreen.c, opsubtitle.c (M6).
- RAM: gBmSt 0x0202BCB0 (0x40), gPlaySt 0x0202BCF0 (0x4C; types.h:171; +0x0E chapterIndex, +0x0F faction, +0x10 chapterTurnNumber, +0x1B chapterModeIndex), gUnitArrayBlue 0x0202BE4C (0x1170), gUnitArrayRed 0x0202CFBC (0xE10), gUnitArrayGreen 0x0202DDCC (0x5A0), gBmMapSize 0x0202E4D4, gBmMapUnit 0x0202E4D8, gBmMapTerrain 0x0202E4DC, gBmMapFog 0x0202E4E8 (pointers), gBmMapBuffer 0x02001000, gBG2TilemapBuffer 0x02023CA8, sProcArray 0x02024E68 (0x1B00 = 64 x 0x6C; +0 = script ptr), gProcTreeRootArray 0x02026A70, gActionData 0x0203A958 (0x38), gGMData 0x03005280.
- struct GameCtrlProc (include/gamecontrol.h:26): nextAction +0x29, nextChapter +0x2A, idle_status +0x2B, unk_2C, unk_2E +0x2E, ch_index +0x30.
- ROM: gChapterDataTable 0x088B0890 (0x2DAC), gChapterDataAssetTable 0x088B363C, gBmMapBaseTiles ptr at 0x0859A9D4, ProcScr_PhaseIntro 0x0859AF80, gProcScr_ChapterIntro 0x0859B1B0, sProc_BMVSync 0x0859D8B8 (0x50), gProc_MapTask 0x0859D908 (0x50).
- bmmap.c: InitChapterMap (UnpackChapterMap Decompress into gBmMapBuffer; UnpackChapterMapGraphics obj1/obj2 to BG VRAM 0x8000/0xC000, palette banks 6..15; BmMapInit/BmMapFill pools; InitBaseTilesBmMap; ApplyEnabledMapChanges; RefreshTerrainBmMap), RenderBmMap (15x10 metatiles into gBG3TilemapBuffer), UpdateBmMapDisplay, DisplayBmTile (reads gBmMapFog: palette bank 6 or 11), RefreshEntityBmMaps (bmmap.c:640+, fog/unit fill).
- Camera: sCameraAnimTable is `s8 EWRAM_DATA sCameraAnimTable[0x100]` (bm.c:39), computed at runtime by CamMove_OnInit (bm.c:937-942); it is NOT ROM data. Cursor: HandleMapCursorInput :612, HandleMoveMapCursor :659, HandleMoveCameraWithMapCursor :681, PutMapCursor :825 (ROM sSprite_MapCursorA/B/C), StartMapSongBgm :1202.
- GetBattleMapKind is a switch on gPlaySt.chapterIndex in code, not ROM data; verify via the compiled compare chain / jump table near ROM 0x080BD068 or via the trace, otherwise leave untagged.
- ProcScr_ResetCursorPosition path needs units: ProcFun_ResetCursorPosition, GetPlayerStartCursorPosition, GetEnemyStartCursorPosition (bmcamadjust.c:28-100) use GetPhaseAbleUnitCount, GetUnitFromCharId(GetPlayerLeaderPid()), unit->xPos/yPos. PlayerPhase_HandleAutoEnd also calls GetPhaseAbleUnitCount(gPlaySt.faction).
- sub_80156D4 (bm.c:586) calls ResetText, LoadLegacyUiFrameGraphics, ResetFaces, ResetIconGraphics_, LoadIconPalettes(4), LoadObjUIGfx (needs Copy2dChr, gGfx_MiscUiGraphics decompress): none ported yet.
- gProc_BMapMain also needs stand-ins/ports: UpdatePrevDeployStates, TickActiveFactionTurn, BmMain_UpdateTraps, sub_80155C4, RenderBmMap, StartMapSongBgm, StartMidFadeFromBlack, WaitForFade.
- Code repo today: game/.../fe8/game/GameControl.kt (284 lines; labels 0-4, 24-26 only; trailing block() stands in; postIntro() with EVENT_RETURN targets the missing label 5), GameLoop.kt (OnMain/OnVBlank, LockGame/UnlockGame; gBmSt.lock and main_loop_ended live in GameLoop, no BmSt/PlaySt model), Fe8Game.kt (boot to title only), engine/core GameClock.kt (SetGameTime already ported), gbafe.proc.ProcEngine (all opcodes needed; M4-12 Proc_ForEach marks still open), gba.data.Decompress (LZ77/raw; Huffman/RL todo), RomView (extracted fallback covers only BOOT_WINDOWS, so chapter/map/unit data needs the ROM).
- Risks: StartBattleMap/InitChapterMap overrun frames (GameLoop.overran must model it; mid-frame dumps need the "skip dump whose successor's key struct is identical" rule, TitleScreenDifferentialTest.titleKeyPath:328).
  - The event's load_map 16 replaces the map within a few frames, so the InitChapterMap(0) window is short.
  - Stand-ins (save menu, monologue, WM, chapter intro, phase intro, sprites, weather) change frame timing, so whole-run frame-exact compare waits for M6; until then verify per function from seeded state.
  - Keep PlaySt/BmSt/Unit in fe8.* (ADR 0010).
  - Pools preallocated at max map size (zero per-frame allocation).

## Saves and SRAM

Key files: bmsave.c (~30 fns), bmsave-lib.c (~40), bmsave-misc.c, bmsave-xmap.c (LoadAndVerfySuspendSave :19, ExtraMapChecksum :84), agb_sram.c, savemenu.c (1963 lines), include/bmsave.h, include/sram-layout.h, include/agb_sram.h, asm/arm.s:150 (Checksum32), main.c:55-63 (SramInit, EraseInvalidSaveData, EraseSramDataIfInvalid).
- SRAM: 32 KiB battery at 0x0E000000 (CART_SRAM_SIZE 0x8000); `gSram` = (struct SaveBlocks*)CART_SRAM (bmsave-lib.c:42).
  - Map: 0x0000 GlobalSaveInfo (0x64); 0x64 SaveBlockInfo[7] x 0x10 (ids GAME0..2 = 0-2, SUSPEND 3, SUSPEND_ALT 4, ARENA 5, XMAP 6); 0xD4 and 0x204C SuspendSaveBlock x2 (0x1F78 each); 0x3FC4/0x4D8C/0x5B54 GameSaveBlock x3 (0xDC8); 0x691C MultiArenaSaveBlock (0x874); 0x7190 GameRankSaveDataPacks (0x94); 0x7224 SoundRoomSaveData (0x24); 0x7248 bmsave_unkstruct2 (0x14); 0x725C BonusClaimSaveData (0x144); 0x73A0 reserved[4] (SramInit write test); 0x7400 xmap (0xC00).
- GlobalSaveInfo: name "AGB-FE9" (sSaveMarker, bmsave-lib.c:19); magic32 SAVEMAGIC32 0x40624 @+8; magic16 SAVEMAGIC16 0x200A @+0xC; flags @+0xE/+0xF; cleared_playthroughs[12] @+0x14; SuppordRecord[0x20] @+0x20; charKnownFlags[0x20] @+0x40; checksum u16 @+0x60; last_game_save_id @+0x62; last_suspend_slot @+0x63.
  - Header checksum = Checksum16 over the first 0x50 bytes only; +0x62/+0x63 written by WriteGlobalSaveInfoNoChecksum (bmsave.c:48, :783).
  - Imports must not "repair" them.
- SaveBlockInfo {magic32, magic16, kind, pad, u16 offset, u16 size, u32 checksum32}; magics SAVEMAGIC32 (game/suspend), 0x20112 (arena), 0x20223 (xmap).
  - WriteSaveBlockInfo (bmsave-lib.c:247) stores `offset = (uintptr_t)GetSaveWriteAddr(i)` truncated to u16 (= SRAM offset only because CART_SRAM low 16 bits are 0); kind 0xFF zeroes size/offset/magic16.
  - SramChecksum32 (bmsave.c:791) copies the block into gGenericBuffer 0x02020188 then ComputeChecksum32 (asm/arm_call.s:49, 0x080D74C0).
- Checksums: Checksum16 (bmsave-lib.c:94) = (add + xor) over u16 words, truncated to u16; used for header (0x50), rank (0x90), sound room (0x20), unkstruct2 (0x10), bonus (0x140).
  - Checksum32 (asm/arm.s:150, 0x08000360): the C comment says u32 words but the ARM code loops over u16 halfwords (ldrh, size/2) and returns `(add & 0xFFFF) | (xor << 16)`.
  - Port from the asm.
  - ExtraMapChecksum is a plain u16 sum.
- GameSaveBlock (0xDC8): PlaySt 0x4C; GameSavePackedUnit[51] x 0x24; gmUnit 0x24; supply 0xB0; pidStats 0x460 (UnitUsageStats[0x46] x 0x10); chapterStats[0x30] x 4; permanentFlags 0x19 (padded 0x1C); u32 bonusClaimFlags; GMapSaveInfo; Dungeon[2].
  - Writers WriteGameSave (bmsave.c:166), WriteNewGameSave (:105, writes zeroed packed units, needs no Unit port); reader ReadGameSave (:204); ReadGameSavePlaySt (:239).
- SuspendSaveBlock (0x1F78): PlaySt, ActionData (incl.
  - RN state), SuspendSavePackedUnit x 0x34 (51 blue, 1 wm, 50 red, 10 green), Trap[64], supply, pidStats, chapterStats, menuOverride[0x10], permanentFlags 0x19, chapterFlags 5(+2), GMapSaveInfo, Dungeon, eventSlotCnt.
  - WriteSuspendSave (bmsave.c:482; returns early if PLAY_FLAG_TUTORIAL, set only by the title demo, gamecontrol.c:521); slot written = slot + GetNextSuspendSaveId() (= 1 - last_suspend_slot), then header flips; IsValidSuspendSave :598; ReadSuspendSave :551.
  - With last_suspend_slot=0 the first suspend write lands in slot 4 (SUSPEND_ALT, offset 0x204C).
- Packed structs are GCC BITPACKED, LSB-first bitstreams crossing u32 boundaries (GameSavePackedUnit = 160 bits, then pid, wpnRanks[8], supports[7]; also UnitUsageStats, SuspendSavePackedUnit tail, GameRankSaveData, ChapterStats). struct Dungeon (bmdifficulty.h:11) and GMapSaveInfo bitfields are not packed and their offset comments are suspect: only a dump with non-zero values confirms them.
- Convoy: WriteSupplyItems/ReadSupplyItems (bmsave-lib.c:388/420): buf[0..99] item ids, buf[100..175] 6-bit uses LSB-first (masks sConvySavePackMask1/2; uses `& 0x3F`).
- Boot: EraseInvalidSaveData (bmsave-misc.c:100) copies SRAM to EWRAM 0x02000000 if magic != 0x40624 and converts old layout 0x40603 (unverified, no ground truth).
  - EraseSramDataIfInvalid (bmsave-lib.c:1218): invalid header -> InitGlobalSaveInfodata (:145; WipeSram :82 writes and verifies all 32 KiB, may span several frames), re-init bonus/rank/soundroom/unkstruct2, LoadAndVerfySuspendSave (:1235).
  - Post-boot image is deterministic; block infos stay 0xFF.
- InitPlayConfig is in src/bmio.c:936 (NOT bmsave.c); SetGameTime is src/time.c:25 and already ported.
  - WriteNewGameSave also needs SetBonusContentClaimFlags (bmsave.c:29), InvalidateSuspendSave, ResetPermanentFlags, ClearSupplyItems, gPidStatsData/gChapterStats; IsPlaythroughIdUnique needs ReadGameSavePlaySt and GetGlobalCompletionCntByInfo.
  - WriteNewGameSave then overwrites chapterModeIndex, unk_2B_00, gameSaveSlot, unk_2C_2, unk_30, playerName: only the fields InitPlayConfig writes (config bits, hard flag) plus playthroughIdentifier are comparable early.
- RAM: gPlaySt 0x0202BCF0, gGenericBuffer 0x02020188, gPidStatsData 0x0203E894 (0x460), gChapterStats 0x0203ECF4 (0xC0), gBonusContentClaimFlags 0x0203EDB4, gSuspendSaveIdOffset 0x0203EDB8, gBoolSramWorking 0x0203E88E, sGameStartSaveBuf 0x02008000.
  - Other WriteSuspendSave callers: playerphase.c:210/793, bmbattle.c:2221, cp_decide.c:70, bmtrap.c:213.
- Code repo today: engine/save SaveFormat.kt is a stub (CURRENT_VERSION=1, Migration fun interface); SaveDraw.kt has only SaveDraw_ScrollFogBG; gba:vfs is read-only (no write path); no PlaySt/Unit/convoy/Checksum ports. gba.testkit ReferenceData.Run.region(dump,"sram") reads `<dump>.sram.bin` with no testkit change.
- Layering (ADR 0010/0012): SRAM hardware model + bitstream helpers in gba.*; container, migrations, FE checksums in gbafe.save (confirm Checksum16 is shared with FE6/FE7 first); FE8 offsets, magics, layouts, .sav importer in fe8.game.save. tools/tests/test_layering.py enforces it.
- Risks: real .sav files may be 32/64/128 KiB or have footers: use the first 0x8000 bytes and validate with ReadGlobalSaveInfo logic.
  - Keep one 32 KiB ByteArray with flyweight views (zero allocation); persistence only on explicit atomic commit.
  - Play time (time_saved) depends on exact frame count.
  - The xmap block (ROM pointers, e-Reader) is low priority.
  - Pack/unpack of units/PlaySt/convoy/flags depends on other areas, so early save tasks stay byte-level.
- Scope: M5 saves = New Game write, boot SRAM init, .sav import/checksum verification. Continue/Resume (ReadGameSave/ReadSuspendSave) and suspend/resume are M6; no saved game can be loaded in M5.

## Event flags and variables

Key files: src/eventinfo.c:1087-1269 (flags), src/event.c (slots/counters), src/eventscr.c (handlers), include/event.h:85-116, include/constants/event-flags.h, include/eventscript.h:585-589.
- Storage (IWRAM): gPermanentFlagBits 0x03005250 (region 0x20, 0x19 bytes used: GetPermanentFlagBitsSize 0x08083DC8 returns 0x19; flags 101..300); gChapterFlagBits 0x03005270 (region 0x10; GetChapterFlagBitsSize 0x08083DD4 returns 5; ResetChapterFlags clears (32+8)/8 bytes). gFlagBitMaskLut ROM 0x089E857C (13 bytes: 8 masks then 5 zeros).
  - Mirror both regions as one 0x30-byte array (0x03005250..0x0300527F).
- Functions: SetChapterFlag 0x08083BD8, ClearChapterFlag 0x08083C0C, ResetChapterFlags 0x08083C48, CheckChapterFlag 0x08083C60 (flag 0 = no-op/false, bit = flag-1); SetPermanentFlag 0x08083CA0, ClearPermanentFlag 0x08083CD8, ResetPermanentFlags 0x08083D18 (25 bytes), CheckPermanentFlagFrom 0x08083D34, CheckPermanentFlag 0x08083D6C (flags <=100 no-op, bit = flag-101); dispatch SetFlag 0x08083D80, ClearFlag 0x08083D94, CheckFlag 0x08083DA8 (flag <100 chapter, else permanent; 100 is dead).
  - Wrappers SetFlag82/CheckFlag82 (eventinfo.c:1790/1796), Set/GetEventTriggerState (event.c:463/470).
  - Save I/O bmsave-lib.c:348-386.
- Reset call sites: WriteNewGameSave bmsave.c:123 (ResetPermanentFlags), gamecontrol.c:522-523 sub_8009C5C (both), StartBattleMap bmio.c:982 (chapter). Non-script flag writes on the New Game path: SetEventId_0x84/UnsetEventId_0x84 (flag 0x84 = 132, permanent) inside gProc_BMapMain.
- Quirks: chapter flags 41..99 are writable (bytes 5..12) but never reset or saved; permanent flags >300 hit padding 0x03005269..6F and >356 corrupt gChapterFlagBits; a slot-2 value >=0x8000 casts to negative s16 and goes out of bounds. Reject out-of-range in the Kotlin port and document it.
- Names (event-flags.h enum event_flag_idx): EVFLAG_ALWAYS_FALSE 0, BATTLE_QUOTES 1, DEFEAT_BOSS 2, WIN 3, BGM_CHANGE 4, EVFLAG_5, DEFEAT_ALL 6, EVFLAG_TMP(n) 7..40 (macro), EVFLAG_GAMEOVER 101, OBJWINDOW_DISABLE 102, EXTRA_UNIT_BASE 103 (macro EVFLAG_EXTRA_UNIT(i) 103..112), HIDE_BLINKING_ICON 132, EVFLAG_136.
  - Unnamed raw ids in src: 0x65, 0x71..0x77, 0x7D, 0x82, 0x84, 0x87, 0x88, 0x8A (bmsave.c:263), 0xB3.
  - Policy: decomp prefix EVFLAG_ (docs MODDING.md:70 and ADR 0001 say FLAG_ and need fixing); EVFLAG_<n> for unnamed; DecompEnums.id("EVFLAG_5","EVFLAG_") keeps EVFLAG_5; macros are not parsed by DecompEnums.
- Variables (gEventSlots u32[0xE] 0x030004B8, size 0x38; EVT_SLOT_0..D): slot 0 null (zeroed before every command, event.c:102), 1-A vars, B position (x,y u16), C check/result, D queue size. gEventSlotQueue u32[30] 0x030004F0 (0x78; SlotQueuePush/Pop event.c:490/495, pop shifts). gEventSlotCounter 0x03000568 (8 nibble counters, saturating 0..15; Set/Get event.c:509/513; saved in suspend).
  - SetEventSlotC event.c:304.
- Handlers (eventscr.c): Event02_EvBitAndIdMod :110 (arg<0 uses slot 2; also per-proc evStateBits EVBIT_T/F, which are NOT flags), Event03_CheckEvBitOrId :140 (result to slot C), Event05_SetSlot :183, Event06_SlotOperation :193 (10 ops, 3 nibble slot indices), Event07_SlotQueueOperations :247, Event0F_CounterOps :476.
- Code repo today: none. engine/core gbafe.core.flags has only FeatureFlags (mod feature toggles, unrelated; pattern reusable).
  - Converter Fe8EventCommands.kt:24-44/62-65 already names set_flag/clear_flag/check_flag/set_slot/slot_*/queue/counter commands; prologue.fe8s emits raw flag numbers and `clear_flag 0xFFFF` (slot-2 indirection); converter prints `slot_add 194` as a packed nibble word instead of 3 operands. gba.testkit RamSymbols (reference/ram_symbols.tsv) lists gEventSlots/gEventSlotQueue/gEventSlotCounter/gPermanentFlagBits/gChapterFlagBits.
- Risks: mod flags must never touch vanilla bytes (save/.sav byte-exactness); give mods a separate bank, persist by name in each mod's own save section, never by registration index.
  - Script flag args are u16, so mod handles must fit in 16 bits (MOD_BASE inside u16).
  - FlagRegistry is not wired to anything unless a task routes Event02/03/10/19 flag access, the assembler and the save container through it.
  - The "variables" half (named slots/counters, mod variables) is not covered by the flag work.
  - Keep split point, sizes and mask table in an fe8.* layout object (FE6/FE7 differ).
  - Check* and Reset* get no @VerifiesPort from RAM alone (Check only via slot C after check_flag; Reset sees all-zero flags on the New Game path).
  - Several SetFlag calls per frame collapse into one observed state (EndingScene sets 11 at once), so differentials check a subsequence plus final bytes, and must account for non-script writes (flag 0x84, Reset*) explicitly.

## Event scripts and VM

Key files: src/event.c (36 fns), src/eventscr.c (97 fns; Event00_NULL..Event45_GlowingCross :47-3899; EventScr_08592114 data :4069), eventscr2.c, eventscr3.c, eventscr_utils.c, eventcall.c (45), events_script_utils.c (21 fns + 47 common scripts), eventscr_gmap.c (87 WM handlers), eventinfo.c (110 fns, triggers), include/event.h, include/eventscript.h, include/EAstdlib.h, src/events/*-eventscript.h / *-eventinfo.h. 460 decomp functions carry system tag "events" in reference/decomp_functions.tsv.
- Encoding: header u16 = cmd<<8 | len<<4 | sub (len in u16 words incl. header).
  - Arg macros _EvtArg0, _EvtParams2, _EvtParams4, _EvtSubParam16u8 (x | y<<8), _EvtSubParam16u4 (nibbles), (EventListScr) u32 ptr/value. event_cmd_idx (eventscript.h:~4ff): lo 0x00..0x45, WM 0x80+. Tables gEventLoCmdTable 0x08591B28 (0x118), gEventHiCmdTable (event.c:42/63).
- EventEngineProc (event.h): +0x2C pCallback, +0x30 pEventStart, +0x34 pEventIdk, +0x38 pEventCurrent, +0x3C evStateBits, +0x3E evStallTimer, +0x41 execType, +0x48 pUnitLoadData. ProcScr_StdEventEngine 0x08591AC0 (0x38), ProcScr_BattleEventEngine 0x08591AF8 (0x30), ProcScr_EventEngineDeamon 0x08591DD8.
- EventEngine_OnUpdate (event.c:72): zero gEventSlots[0] per command; dispatch `(*pc>>8) < 0x80 ? Lo : Hi`.
  - Returns EVC_ADVANCE_CONTINUE 0, STOP_CONTINUE 1, ADVANCE_YIELD 2, STOP_YIELD 3, UNK4 4 (no advance, keep running), END 5 (Proc_Break), ERROR 6.
  - EventEngine_Create :216 (execType WORLDMAP 0 / CUTSCENE 1 / GAMEPLAY 2 / QUIET 3), CallEvent :207 (queues if a std engine exists), 16-entry gEventCallQueue, EventEngine_CanStartSkip/StartSkip (START; event.c:77; skipped commands still run), EventEngine_OnEnd.
- Handler semantics: Event01_End :53 pops the active queue; empty stack in CUTSCENE mode -> execType 5 and jumps to EventScr_08592114 (0x08592114, 0x2C; chains to 08592140/08592170).
  - Event09_Goto :278 scans linearly from pEventIdk for u32 `label<<16 | 0x0820`.
  - Event0A_Call :305 pushes {Idk, Current}; dst<0 = slot 2; returns EVC_UNK4 (pc advances only on return).
  - Event0B_EnqueueCall sub 1 -> EnqueueTutEvent (eventinfo.c:1272, sets tutorial_counter).
  - Event0C_Branch :353 signed slot compare.
  - Event0E_STAL :428 (sub&1 B cancels, sub&2 speed x4 with gameSpeed/A).
  - Args <0 read slot 2.
  - Slots are u32; SlotOperation DIV/MOD unsigned; Branch signed.
- Globals (IWRAM): gEventCallQueue 0x03000438 (0x80, 16 x {ptr, execType, used}); gEventSlots 0x030004B8 (0x38); gEventSlotQueue 0x030004F0 (0x78); gEventSlotCounter 0x03000568 (8); gEventActiveQueue 0x03000570 (0x40, 8 x {evt1=pEventIdk, evt2=pEventCurrent}: the global call stack); gActiveEventRegistry 0x03001C80.
  - Contiguous block 0x03000438..0x030005B0 = 0x178 bytes.
- Triggers (eventinfo.c): ChapterEventGroup (chapterdata.h:118, 0x50 B; beginningSceneEvents +0x48, endingSceneEvents +0x4C) at gChapterDataAssetTable 0x088B363C indexed by chapters.json mapEventDataId (GetChapterEventDataPointer chapterdata.c:30).
  - PrologueEvents 0x089E863C (0x50).
  - List entry u32 lo16 type, hi16 flag; lengths from gEventListCmdInfoTable (eventinfo.c:50).
  - SearchAvailableEvent :91, EvCheck00..10 :723-1074, StartEventFromInfo :71; call sites RunPhaseSwitchEvents :1310, CheckForWaitEvents/RunWaitEvents :1591/1625, StartCharacterEvent :1380, StartAvailableTileEvent :1451, :1669-1714 select/dest/after-move, RunTutorialEvent :1296.
  - Prologue Turn list at 0x089E858C (0x34), Misc list 0x089E85C8 (0x28).
  - Chapter start = bm.c:404 CallEvent(beginningSceneEvents, 1).
- Scripts: EventScr_Prologue_BeginningScene 0x089EEBE8 (0xD4); EventScrWM_Prologue_Beginning 0x08A39768 (0x598, hi commands); EventScr_CallOnTutorialMode 0x089EE0C0 (0x1C). nm counts: 897 `EventScr*` symbols in total (765 EventScr_ + 132 EventScrWM, the WM ones are INCLUDED in 897) and 480 EventListScr*, all with sizes = ROM golden for byte-exact round-trips.
- Prologue opcode mix (by handler, from prologue.fe8s lines): Event05 119, Event07 70, Event02 55, Event3B 40, Event01 32, Event30 25, Event0A 24, Event1B/1D 24 each, Event1A 23, Event0E 20, Event2F 19, Event34 18, Event11 16, Event0B 10, Event2C 9, Event17 6, Event3F 6, others <=5.
  - Most need unported subsystems (text/talk, MU, unit load, map load, camera, cursor, scripted battle, asm_call targets).
- Code repo today: engine/script ScriptVm.kt is a 16-line interface stub; :engine:extract Fe8EventCommands.kt (generated by tools/gen_fe8_event_commands.py) covers lo 0x00..0x45 but no WmEvt* 0x80+ and no 0x1F/0x20/0x23/0x24/0x28/0x35, and has no word layout; EventScriptConverter.kt (v0) prints raw u16/u32 and stops at return/end_all by heuristic; Fe8Steps.kt:111-115 converts only EventScr_Prologue* to ROM repo extracted/scripts/prologue.fe8s (664 lines, 32 events); testing EventScriptRomTest (M2-15) checks nm-size and no `raw` lines.
  - Converter quirks: dummy zero arg printed for len-4 commands, packed x/y as one number (`move_unit 24 2 0x404`), Thumb pointers unnamed (`0x80CF461` = BmGuideTextSetAllGreen|1), slots/labels as numbers.
  - Not extracted: EventScr_CallOnTutorialMode, Event_TextWithBG, EventScr_RemoveBGIfNeeded, EventScr_SetBackground, EventScr_Tutorial_Exec0/1, EventScr_08592114, EventListScr_*, ChapterEventGroup.
  - Already ported: ProcEngine, Rng, KeyStatus, m4a.
- ADR 0001 conflict: it asks for a thread pool with per-thread stacks, but FE8 has one std + one battle engine sharing global slots and an 8-deep global active queue, and Goto scans the original word layout.
  - Plan: compiled form keeps the exact u16 layout with 32-bit virtual addresses (ROM address for vanilla; positive synthetic range outside ROM for mods); slot values hold raw script pointers.
  - ADR needs an amendment; `if <cond> goto`, named labels and integer expressions from the ADR are deferred unless explicitly scheduled.
- Placement: FE8 command table/constants in gbafe.extract.fe8.events (:game gains a dependency on :engine:extract, commonMain); parser/assembler in gbafe.script (no FE8 names, test_layering); VM/handlers in fe8.game.event.
  - Changing .fe8s format means re-extracting and pushing ROM repo extracted/scripts (CI compare_extraction fails otherwise).
  - Handlers = preallocated `fun interface` objects indexed by cmd byte; no boxing/string lookups at run time.
- Risks: oracle-mode differential (ported handlers real, unported subsystems as test stubs that complete when the trace pc moves past the command) compares per yield, not strictly per frame, around overrun frames (LOAD/LOMA).
  - No end-to-end mod-script test exists in the plan (a runtime-loaded .fe8s assembled into the synthetic range, call/return between mod and vanilla scripts).
  - Parser/assembler/round-trip are our format, so ROM-golden tests without @VerifiesPort.

## Ground truth

ADR 0006: only mGBA differential runs or ROM golden values. No existing reference run passes f5000 or contains an event engine (scan of all ewram dumps for ProcScr_StdEventEngine: zero hits). Existing: 24 runs, 1.3 GB unpacked, dist/reference.tar.xz 15 MB. Harness: Fire-Emblem-8-rom/harness/harness.c (344 lines; commands run/hold/press/dump/dumpram/savestate/loadstate; flags --frames-every, --audio, --psg-mask, --psg-writes; empty in-memory save at line 275; no SRAM dump), gen_scripts.py (BOOT=[(181,A,1),(700,START,4)], script(timeline,end,full_dumps,ram_every,ram_from,split_holds)), make_reference.sh (rm -rf reference/out, "# args:" header gives flags, writes PROVENANCE.sha1).

NEWGAME input timeline (verified at 10-frame sampling, ROM c25b145e, one shared constant in gen_scripts.py): START f902 x2, A f964 x2 (New Game), DOWN f1030 x2 (Normal, non-tutorial), A f1060 x2 (Select Mode), A f1130 x2 (slot 1), then A x2 every 30 f from f1200 through f19320, then no input. Any change in cadence or difficulty shifts every later frame. Choice/text prompts ignore A for a while (suspend Yes/No ignored an A at f19780, accepted f19850).

Frames found (approximate, 10-frame resolution; all must be pinned by scan_trace):
- f1130-1140: WriteNewGameSave SRAM write; slot shows "Prologue: The Fall of Renais" by f1140; gPlaySt.chapterModeIndex 0 -> 1 between f1130 and f1140.
- f1220-4910: intro monologue (gProcScr_OpSubtitle), no event engine.
- f4910-9310: ProcScr_StdEventEngine runs the world-map narration (EventScrWM_Prologue_Beginning).
- ~f9300-9340: StartBattleMap / gProc_BMapMain appears (still approximate); f9340 gProcScr_ChapterIntro.
- f9520-19330: StdEventEngine, one continuous beginning scene; chapterIndex 16 at f9520 (Renais Castle), 64 at f13010, back to 0 at f15850; ProcScr_BattleEventEngine (Valter vs Seth scripted battle) f14480-14650.
- ~f19321-19330: BmMain_SuspendBeforePhase SRAM write AND ProcScr_PhaseIntro first appear in the SAME dump frame (no yield between them in gProc_BMapMain); f19330 PhaseIntro "PLAYER PHASE", faction 0, turn 1.
- f19400: gProcScr_PlayerPhase idle, cursor on Eirika.
- Suspend path (M6): DOWN f19500, A f19540 (map menu), DOWN f19580/19600/19620/19640, A f19680, A f19850 (Yes), A f19960; title ~f20100; A f20140/20200/20260; SaveMenu f20160-20300; PlayerPhase resumes f20330.
Still approximate: StartBattleMap frame, event end, SRAM write frame, PhaseIntro and PlayerPhase onset, all window edges below.

Planned harness changes (ROM repo; needs mGBA 0.10.5 rebuild: harness/.build absent, network+cmake+ninja needed; current bin/mgba-harness already links GBACoreSavedataClone/Restore; old runs must stay byte-identical):
- `dumpsram NAME`: NAME.sram.bin (32768 B via core->savedataClone, fallback rawRead8 0x0E000000..; 0xFF padded) + manifest line; readable as region(dump,"sram"). savedataClone may return 0 bytes before first SRAM access (assert 32768 from f1).
- `--sram FILE`: preload the in-memory save (VFileMemChunk(bytes,len), never written back); resolve relative to $ROOT in make_reference.sh (the "# args:" line is passed unexpanded).
- `watch NAME ADDR LEN`: after every step appends LEN bytes (rawRead8) to NAME.watch.bin; watches.txt lines `NAME ADDR LEN FIRST_STEP` (up to 16).
- harness/scan_trace.py (python3 -I; args run dir + nm): prints transitions of gPlaySt (chapterIndex/faction/turn/chapterModeIndex/config.controller), live sProcArray procs named via nm with scrCur offset, GameCtrlProc.nextAction, sram.bin hash, watch fields.
- Later (M6): savestate/loadstate carrying SRAM (believed NOT to hold SRAM; never branch across a save write with loadstate until verified); optional `poke ADDR VAL`.
Watch regions: gPlaySt 0202BCF0 0x4C; gBmSt 0202BCB0 0x40; RNG 03000000 len 12; event globals 03000438 len 0x178 (CallQueue+Slots+SlotQueue+Counter+ActiveQueue; NOT 0x138, which stops at 0x03000570 and misses the call stack); flags 03005250 len 0x30; sProcArray 02024E68 len 0x1B00 (~137 MB raw over ~19.8k steps: consider live slots only or every 2nd frame outside windows); gActionData 0203A958 len 0x38.
Planned runs (all use the shared NEWGAME constant): sram_boot (dumpsram+dumpram f1..f5, f60, f900; compare against the first frame where SRAM stops changing, not a hard-coded f1, since WipeSram writes 32 KiB); ram_trace_newgame (per-frame dumpram f955..f1230, dumpsram f1128..f1145 and f1230; WriteNewGameSave truth); newgame_easy (optional, no DOWN; tutorial branch); sram_preload (`--sram reference/fixtures/newgame_slot0.sav`, copied from ram_trace_newgame; run ram_trace_newgame first); ram_trace_prologue (coarse: dumpram every 30 f to ~f19800, full dumps every 300 f, all watches every step, dumpsram f1230 and f19800); ram_trace_newgame_bm (per-frame dumpram from StartBattleMap-5 to f9900 incl. ChapIntro and event start f9520, full dump on the first frame after StartBattleMap and every 30 f); ram_trace_first_phase (per-frame f19280..f19420: event end, suspend write, ChangePhase, PhaseIntro, CamMove, PlayerPhase; dumpsram f19315..f19340; then hold RIGHT 40 f, DOWN 40 f, B+RIGHT 20 f with a full dump every 10 f). Deferred runs: prologue_suspend (savestate pp1 at f19400 + suspend inputs, dumpsram f19400/f19850-19875/f20100/f20340), prologue_restart, save_resume / continue_mutated (`--sram`), prologue_clear (chapter-end WriteGameSave), ram_trace_prologue_turn (EvCheck02_TURN).
Publishing: make_reference.sh, diff old out/ vs fresh untar (only PROVENANCE.sha1 may differ), `xz -T0`, update dist/MANIFEST.sha1 (new mgba-harness sha) and dist/reference.tar.xz, copy to .assets (HANDOFF how-to). Cap: dist/reference.tar.xz must stay under ~90 MB (GitHub rejects files >100 MB; the committed tarball is 15 MB today).
Test patterns: seed state from dump k, step, compare to k+1 (ProcEngineDifferentialTest); skip mid-frame lag dumps (titleKeyPath); compare SRAM on the frame after a write; compare whole 32 KiB image or named block ranges; header u16 @0x60 = Checksum16(image[0:0x50]); each valid block's checksum32 = Checksum32(image[offset:offset+size]). ROM golden values are read at nm addresses in baserom.gba, never from our extractor output. @VerifiesPort is defined in gba/core/src/commonMain/kotlin/gba/core/Porting.kt:21.

## Deferred to M6

- Unit loading (bmunit.c LoadUnits/UnitInitFromDefinition, 92 fns, autolevel RNG), Unit struct, map sprites (bmudisp.c); M5 uses a raw-unit stand-in plus GetPhaseAbleUnitCount/GetUnitFromCharId/GetPlayerLeaderPid hooks (seeded from the trace or ROM unit definitions).
- Suspend/Continue round trips: WriteSuspendSave/ReadSuspendSave, EncodeSuspendSavePackedUnit, ReadGameSave/LoadSavedUnit, ReadSupplyItems, slot rotation (GetNextSuspendSaveId, WriteSwappedSuspendSaveId, IsValidSuspendSave); prologue_suspend/restart/resume/continue_mutated runs and the savestate-with-SRAM harness change. ram_trace_first_phase already captures the first suspend-block write.
- Persistent SaveStorage (atomic temp+rename, JVM/Android implementation, commit on Write*): decide M6 or M7 with the app.
- Visual/UI stand-ins in M5: save menu UI, intro monologue, world map and its 87 hi-command handlers (eventscr_gmap.c; narration f4910-9310 is coarse data only), chapter intro (chapterintrofx.c), phase banner, text/talk, scripted battles, side windows, traps, weather (Wfx), BMapVSync tile/palette animations, map BGM, cursor sprite (PutMapCursor/DisplayBmTextShadow).
- Whole-run frame-exact comparison (needs all stand-ins replaced and measured overrun frames).
- Trigger scanning beyond a stand-in: ChapterEventGroup/EventListScr extraction, SearchAvailableEvent, EvCheck*, RunPhaseSwitchEvents/RunTutorialEvent (turn-1 call is inside ram_trace_first_phase, so a slice could be pulled in).
- Event handlers not scheduled: sound Event12/13/14/15/16 on m4a, asm_call registry + Event0D_AsmCall (one Kotlin port per target), authoring sugar (named labels, if/goto, EAstdlib aliases), extraction of LOAD unit groups for every chapter.
- Reset* flag verification needs a later run (prologue cleared to chapter 1, or New Game after flags were set); old-layout 0x40603 save conversion and Dungeon/GMapSaveInfo layouts stay unverified until a non-zero dump exists.
- If a hard task cap applies, move: FlagRegistry mod bank wiring, .sav import, CamMove, map scroll/cursor input, full-script round-trip (reduce to prologue + common scripts).

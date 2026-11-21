# M6 task scopes

Planning revision 2026-11-15. [TASKS](../TASKS.md) is the executable queue; this document supplies
scope and stop conditions. Existing capture results remain in [M6C-J-breakdown](M6C-J-breakdown.md).
No code, captures, tests or new port credits were produced by the original planning revision.
MC-18i subsequently completed: [inventory evidence](M6C-J-breakdown.md#mc-18i-display-inventory-2026-11-15).

## How to take a task

- **CAP:** record one named scenario, initial save/ROM/harness hashes, exact inputs, start/end markers,
  and RAM/proc state at the behavior boundaries. Include VRAM/OAM/palette/LCD for visible transitions;
  include SRAM before/after writes. Store everything in the private ROM repo, preserve old captures,
  publish and install the reference assets. Do not finish with only screenshots or an unpublished run.
- **INV:** inventory just the named window. List actually entered functions and branches, proc lifetimes,
  callers, fields/buffers to compare, and dump timing/coverage gaps. Check existing ports before adding work.
  Create or correct PORT rows of at most eight functions from one source file, with exact capture/frame
  boundaries, target Kotlin file, test class and command. Add a CAP for missing evidence. No port credit.
- **Candidate PORT:** the named source functions below are a planning shortlist, **not evidence that they
  all run**. Its INV dependency must freeze the observed subset and concrete window before coding starts.
  Remove unseen functions; split extra callees into separate tasks. Never pad a task to eight functions.
  Partial implementations stay explicitly Transcribed until original data and individual break checks
  justify a tag. A constant/default-only trace is not evidence for unseen nondefault behavior.
- **WIRE:** after its scoped ports, replay the captured inputs through Fe8Boot/GameControl; compare the
  named final state and intermediate boundaries, verify zero recurring frame allocations, update
  MANUAL_TEST and announce the exact APK. Device confirmation remains separate from local verification.
  A WIRE marked `blocked: inventory must add PORT deps` cannot start when only its INV is finished.
- New test names in these plans are proposed, not existing tests. Focused command after implementation:
  `:testing:test --tests fe8.testing.<Class>` under the shared Gradle lock. One parent full JVM run at
  integration; do not regenerate progress after filtered tests. No Gradle is needed for this docs-only plan.
- This M6 decomposition does not select M7 implementation choices. Extensive scope is now accepted
  in [ADR 0016](../decisions/0016-faithful-vanilla-extensive-mods.md); see [M7](M7-modding.md) for open choices.

Task aliases retained for older code/comments: EP-12 is now an evidence inventory, EP-34 is split into
EP-34a/b, MC-18 is the display integration gate, PC-4/6/8/9/10 and IS-1/2/5..9 are integration gates.
PC-7 is split into PC-7a/b; IS-cap2/IS-4 become individual screen CAP/INV rows.
BA-1, C14-0/1, PW-0/1 and LG-0 are replaced below; old links refer to their whole family, never one coding task.

## M6-D remaining broad tasks

### EP-34a: Rescue and take target lists

Candidate source: `src/bmtarget.c`: `ForEachAdjacentUnit`, `MakeRescueTargetList`, `TryAddUnitToRescueTargetList`, `MakeTakeTargetList`, `TryAddRescuedUnitToTakeTargetList`.
Window: EP-cap2 f4181; existing inventory says empty lists. Positive cases require the rescue capture. Compare: Ordered target list and callback results. Proposed test: `MenuTargetListsDifferentialTest`.

### EP-34b: Trade talk and support target lists

Candidate source: `src/bmtarget.c`: `MakeTradeTargetList`, `TryAddUnitToTradeTargetList`, `MakeTalkTargetList`, `TryAddUnitToTalkTargetList`, `MakeTargetListForSupport`.
Window: EP-cap2 f4181; do not count unentered callbacks or claim positive target coverage. Compare: Ordered target list and callback results. Proposed test: `MenuTargetListsDifferentialTest`.

EP-12 retains the original unentered-command backlog: cp_script commands 00–04, 07–0D,
0F–11, 13–1B; command 05 ignore-list branches and fallback A/B; dance/steal-after-move,
nearest-heal-point/action-after-move, rogue helpers, staff variants and offensive-action branches.
The first inventory maps **every** old hook/command to a scenario or a further CAP row. The listed
captures are initial bounded cases, not a claim that eight runs cover every command. Staff types,
dance/steal and escape fixtures that these runs cannot reach get separate CAPs before any implementation.
Do not patch RAM to manufacture a result. Select a reachable retail save and record input provenance;
if no fixture exists, add a fixture acquisition task, not a speculative port.

### MC-18a: Level-up announcement

Candidate source: `src/mapanim_lvup.c`: `StartManimLevelUp`, `InitManimLevelUpWindow`, `ManimLevelUp_DimBgm`, `ManimLevelUp_StartLevelUpText`, `ManimLevelUp_EndLevelUpText`, `ManimLevelUp_RestoreBgm`.
Window: MC-18i confirmed this display path; use its exact subwindow below and evidence limits, not every candidate branch. Compare: Actor ID, AP procs, sound/volume and announcement OAM. Proposed test: `MapLevelUpDifferentialTest`.

Confirmed inventory window: f6413..6518 announcement; audio not directly recorded. Per-frame EWRAM; full display snapshots every ten frames.

### MC-18b: Level-up base stat drawing

Candidate source: `src/mapanim_lvupfx.c`: `PutManimLevelUpFrame`, `PutManimLevelUpStat`, `GetManimLevelUpStatGain`, `GetManimLevelUpBaseStat`.
Window: MC-18i confirmed this display path; use its exact subwindow below and evidence limits, not every candidate branch. Compare: Frame/stat tilemaps and pre/post-gain values. Proposed test: `MapLevelUpDifferentialTest`.

Confirmed inventory window: f6519 and f6563..6669 frame/stat redraws. Per-frame EWRAM; full display snapshots every ten frames.

### MC-18c: Level-up gain label animation

Candidate source: `src/mapanim_lvupfx.c`: `ManimLevelUpStatGainLabel_Finish`, `StartManimLevelUpStatGainLabels`, `EndManimLevelUpStatGainLabels`, `StartManimLevelUpStatGainLabelAnim`.
Window: MC-18i confirmed this display path; use its exact subwindow below and evidence limits, not every candidate branch. Compare: Label AP lifecycle, OAM, tiles and palettes. Proposed test: `MapLevelUpDifferentialTest`.

Confirmed inventory window: f6519..6728 label/AP lifetimes. Per-frame EWRAM; full display snapshots every ten frames.

### MC-18d: Level-up stat panel lifecycle

Candidate source: `src/mapanim_lvup.c`: `ManimLevelUp_InitMainScreen`, `ManimLevelUp_ScrollIn`, `ManimLevelUp_ScrollOut`, `ManimLevelUp_PutStatGainLabels`, `ClearManimLevelUpWindow`, `ManimLevelUp_Clear`.
Window: MC-18i confirmed this display path; use its exact subwindow below and evidence limits, not every candidate branch. Compare: Panel proc fields, scroll offsets, tilemaps and cleanup. Proposed test: `MapLevelUpDifferentialTest`.

Confirmed inventory window: f6519..6745 panel, scroll and cleanup. Per-frame EWRAM; full display snapshots every ten frames.

### MC-18e: Level-up label palette cycle

Candidate source: `src/mapanim_lvup.c`: `ManimLevelUpLabelColor_Init`, `ManimLevelUpLabelColor_Loop`.
Window: MC-18i confirmed this display path; use its exact subwindow below and evidence limits, not every candidate branch. Compare: Color-cycle counter and palette at each observed step. Proposed test: `MapLevelUpDifferentialTest`.

Confirmed inventory window: f6520..6728 label palette cycle. Per-frame EWRAM; full display snapshots every ten frames.

### MC-18f: Exp-bar level-up dispatch

Candidate source: `src/mapanim_expbar.c`: `ProcMAExpBar_LevelUpIfPossible`.
Window: MC-18i confirmed this display path; use its exact subwindow below and evidence limits, not every candidate branch. Compare: Blocking parent and child start/end frames; exp-bar resume. Proposed test: `MapLevelUpDifferentialTest`.

## M6-E chapter completion and menus

Confirmed inventory window: f6413..6746 blocking parent; later resume timing still needs checking. Per-frame EWRAM; full display snapshots every ten frames.

### PC-4a1: Post-chapter save entry and LCD setup

Source: `src/savemenu.c` / `src/bmsave-lib.c`: `Make6C_SaveMenuPostChapter`, `SaveMenuInit`, `SaveMenu_SetLcdChapterIdx`, `SaveMenuDirectlySelectSlotOnPrepScreen`, `InitSaveMenuHelpTextSt`, `ReadLastGameSaveId` (already ported: `NewFadeIn`, `FadeInExists` in `ArchivedPaletteFade`).
Window: `ram_trace_prologue_end` (`PC-cap1`) `f9444`, `f9461..9493` (world-map / Frelia / prep-screen false branches unexercised). Compare: Save proc state, `main_sel_bitfile`, LCD/blend registers, `NewFadeIn(4)`. Proposed test: `PostChapterSaveMenuDifferentialTest`.

### PC-4a2: Save menu screen and title-BG setup

Source: `src/savemenu.c` / `src/chaptertitle.c`: `SaveMenu_InitScreen`, `SaveMenuPutChapterTitle`, `SaveMenuCopyPalette`, `SaveMenuInitSubBoxText`, `PutChapterTitleBG` (already ported: `PutChapterTitleGfx`, `SetObjAffine`).
Window: `ram_trace_prologue_end` (`PC-cap1`) `f9445..9459`. Compare: BG0..3 tilemaps/LCD, palettes, slot metadata setup. Proposed test: `PostChapterSaveMenuDifferentialTest`.

### PC-4b: Save background and HBlank lifecycle

Source: `src/savedraw.c` / `src/bg_vertical_scroll.c`: `StartSaveDraw`, `SaveDraw_Init`, `SaveDraw_OnEnd`, `sub_80AB548`, `StartBgVerticalScroll`, `SetBgVerticalScrollPosition`, `sub_8014EF4`, `EndBgVerticalScroll` (already ported: `SaveDraw_ScrollFogBG`, `HBlank_BgVerticalScroll`, `GetBgVerticalScrollBuffer`, `FlipBgVerticalScroll`, `BG_SetPosition`).
Window: `ram_trace_prologue_end` (`PC-cap1`) `f9459..9862`. Compare: child procs (`ProcScr_savedraw`, `ProcScr_ApProc`), `sHBlankHandler1`, BG scroll/offsets, cleanup on exit. Proposed test: `PostChapterSaveMenuDifferentialTest`.

### PC-4c1: Save slot boxes, banner and play time draw

Source: `src/savedraw.c`: `SaveDraw_Loop_Main`, `sub_80AB05C`, `sub_80AADE0`, `sub_80AA7EC`, `sub_80AAE90` (early exit), `sub_80AAF6C` (early exit), `BitfileToIndex` (main/extra/help-box branches uncaptured in `PC-cap1`).
Window: `ram_trace_prologue_end` (`PC-cap1`) `f9459..9862`. Compare: OAM slot/banner/play-time sprites and slot tilemaps. Proposed test: `PostChapterSaveMenuDifferentialTest`.

### PC-4c2: Save palette cycle, icon spin and particles

Source: `src/savedraw.c`: `sub_80AACBC`, `sub_80AA7AC`, `sub_80AB56C`.
Window: `ram_trace_prologue_end` (`PC-cap1`) `f9459..9862`. Compare: palette animation, suspend bookmark spin/scale (`gOam` affine 3, `unk_44`), fog particles. Proposed test: `PostChapterSaveMenuDifferentialTest`.

### PC-4d: Save cursor drawing

Source: `src/savedraw.c`: `StartSaveDrawCursor`, `SaveDrawCursor_Init`, `SaveDrawCursor_Loop`, `sub_80AB4F4` (`SaveMenuTryMoveSaveSlotCursor`, `SaveMenuModifySaveSlot`, `sub_80AB514` never run in `PC-cap1` and require a slot-nav CAP; `InitSaveMenuChoice` moved to `PC-4e2`).
Window: `ram_trace_prologue_end` (`PC-cap1`) `f9459..9862`. Compare: `gProcScr_SaveDrawCursor`, selected slot 0, cursor OAM. Proposed test: `PostChapterSaveMenuDifferentialTest`.

### PC-4e1: Save select and confirm loop

Source: `src/savemenu.c`: `SaveMenu_SaveSlotSelectLoop`, `SaveMenuWaitHelpBoxAnim`, `ExecSaveMenuMiscOption`, `SaveMenuHandleHelpBox`, `SaveMenuDrawSubSelBox`, `SaveMenuDrawSubSelBoxExt` (reuses already-ported `BmGameSave.writeGameSave` for the slot-0 write at `f9800..9802`).
Window: `ram_trace_prologue_end` (`PC-cap1`) `f9494..9802`. Compare: `A` confirm subbox (`f9600`, BG1 row 17, `cursor_config = 1`), slot-0 `WriteGameSave` dispatch (`s09799..s09802`) and clear (`f9802`). Proposed test: `PostChapterSaveMenuDifferentialTest`.

### PC-4e2: Post-write slot flip, option scan and fade-out

Source: `src/savemenu.c`: `SaveMenuRegisterSlotSelected`, `SaveMenuWaitSlotBoxScrolling`, `InitSaveMenuChoice`, `AddMainMenuOption`, `AddExtraMenuOption`, `NewFadeOut`, `FadeOutExists` (+ `bmsave-lib` `IsExtra*` availability checks).
Window: `ram_trace_prologue_end` (`PC-cap1`) `f9803..9861`. Compare: `gOam` affine 0 flip, slot-0 metadata refresh (`f9814`), `InitSaveMenuChoice`, `NewFadeOut(8)` (`f9845..9860`). Proposed test: `PostChapterSaveMenuDifferentialTest`.

### PC-4f: Save slot metadata and palettes

Source: `src/difficultymenu.c` / `src/savemenu.c`: `SaveMenuInitSaveSlotData`, `SaveMenuInitSlotPalette`, `SaveDrawSetDifficultSlotPalette`, `GetChapterTitleExtra`, `IsGameNotFirstChapter`, `LoadSavedEid8A`, `ReadGameSavePlaySt`, `ReadSuspendSavePlaySt` (already ported: `IsSaveValid`, `IsValidSuspendSave`, `sub_80895B4`).
Window: `ram_trace_prologue_end` (`PC-cap1`) `f9450..9458`, `f9812..9814`. Compare: occupied/empty metadata, chapter titles, slot palettes. Proposed test: `PostChapterSaveMenuDifferentialTest`.

### PC-4g: Save menu exit

Source: `src/savemenu.c`: `PostSaveMenuHandler` (already ported in `fe8.game.savemenu.PostSaveMenu`; verify `MAIN_MENU_OPTION_INVALID` branch on `f9862 -> f9863` and `Fe8Boot` wiring).
Window: `ram_trace_prologue_end` (`PC-cap1`) `f9862 -> f9863`. Compare: `ProcScr_savedraw`/`gProcScr_SaveDrawCursor` cleanup, `sHBlankHandler1 == 0`, unlocked `GameControl` with `nextAction = 2`. Proposed test: `PostChapterSaveMenuDifferentialTest`.

### PC-8a: Game-over background and scroll

Candidate source: `src/gameoverbgfx.c`: `StartGameOverScreen`, `GameOverScreen_Init`, `GameOverScreen_RandomScroll_Init`, `GameOverScreen_RandomScroll_Loop`, `GameOverScreenHBlank`.
Window: PC-cap2 screen init through idle; add a scanline CAP if required. Compare: Proc state, scroll RNG, scanline offsets and LCD. Proposed test: `GameOverScreenDifferentialTest`.

### PC-8b: Game-over fades and teardown

Candidate source: `src/gameoverbgfx.c`: `GameOverScreen_LoopFadeIn`, `GameOverScreen_BeginIdle`, `GameOverScreen_LoopIdle`, `GameOverScreen_BeginFadeOut`, `GameOverScreen_LoopFadeOut`, `GameOverScreen_End`.
Window: PC-cap2 fade in, idle and fade out. Compare: Fade counters, palette, HBlank cleanup and child termination. Proposed test: `GameOverScreenDifferentialTest`.

### PC-9a: Difficulty screen lifecycle

Candidate source: `src/difficultymenu.c`: `NewNewGameDifficultySelect`, `InitDifficultySelectScreen`, `DifficultySelect_Init`, `DifficultySelect_OnEnd`.
Window: Existing menu run if sufficient; otherwise PC-9cap. Compare: Proc lifecycle, BG tiles, palettes and display restore. Proposed test: `DifficultyMenuDifferentialTest`.

### PC-9b: Difficulty input and selection

Candidate source: `src/difficultymenu.c`: `DrawDifficultyModeText`, `sub_80AC034`, `sub_80AC1BC`, `sub_80AC22C`, `DifficultySelect_Loop_KeyHandler`.
Window: Existing menu run if sufficient; otherwise PC-9cap. Compare: Selection, descriptions, A/B dispatch and difficulty result. Proposed test: `DifficultyMenuDifferentialTest`.

### PC-9c: Difficulty cursor and sprites

Candidate source: `src/difficultymenu.c`: `DrawDifficultySprite_Init`, `DrawDifficultyMenuCursorMaybe`, `sub_80AC4F8`, `DrawDifficultySprites_Loop`, `sub_80AC680`, `StartDrawDifficultyMenuSprites`.
Window: Existing menu run if sufficient; otherwise PC-9cap. Compare: Cursor motion, counters and OAM. Proposed test: `DifficultyMenuDifferentialTest`.

### PC-9d: New-game save-menu bridge

Candidate source: `src/savemenu.c`: `SaveMenu_NewGame`, `SaveMenuWriteNewGame`, `SaveMenu_ResetLcdFormDifficulty`, `SaveMenu_ReloadScreenFormDifficulty`, `SaveMenu_PostDifficultHandler`, `SaveMenu_SetDifficultyChoice`.
Window: PC-9cap or sufficiently dense existing New Game run; other menu callbacks are separate PC-9i follow-ups. Compare: Difficulty result, new-game slot, SRAM and scene dispatch. Proposed test: `NewGameSaveMenuDifferentialTest`.

## M6-F information screens

### IS-1a: Stat-screen display setup

Candidate source: `src/statscreen.c`: `StartStatScreen`, `StatScreen_BlackenScreen`, `StatScreen_InitDisplay`, `SetStatScreenConfig`, `GetLastStatScreenUid`, `SetLastStatScreenUid`.
Window: IS-cap1; IS-0 fixes per-action subwindows, including enemy-unit screen. Compare: Initial screen state, LCD, tiles and palette. Proposed test: `StatScreenDifferentialTest`.

### IS-1b: Stat-screen left panel

Candidate source: `src/statscreen.c`: `InitTexts`, `DisplayTexts`, `DisplayLeftPanel`, `DisplayBwl`.
Window: IS-cap1; IS-0 fixes per-action subwindows, including enemy-unit screen. Compare: Portrait/name/class/BWL fields and text tiles. Proposed test: `StatScreenDifferentialTest`.

### IS-1c: Stat-screen stats page

Candidate source: `src/statscreen.c`: `DrawStatWithBar`, `DisplayPage0`.
Window: IS-cap1; IS-0 fixes per-action subwindows, including enemy-unit screen. Compare: Stat values, bars and page tilemap. Proposed test: `StatScreenDifferentialTest`.

### IS-1d: Stat-screen inventory page

Candidate source: `src/statscreen.c`: `DisplayPage1`.
Window: IS-cap1; IS-0 fixes per-action subwindows, including enemy-unit screen. Compare: Inventory uses, equipment and battle values; tilemap/icons. Proposed test: `StatScreenDifferentialTest`.

### IS-1e: Stat-screen ranks and supports page

Candidate source: `src/statscreen.c`: `DisplayWeaponExp`, `DisplaySupportList`, `DisplayPage2`.
Window: IS-cap1; IS-0 fixes per-action subwindows, including enemy-unit screen. Compare: Rank/support fields and tilemaps. Proposed test: `StatScreenDifferentialTest`.

### IS-1f: Stat-screen page slide

Candidate source: `src/statscreen.c`: `PageSlide_OnLoop`, `PageSlide_OnEnd`, `StartPageSlide`.
Window: IS-cap1; IS-0 fixes per-action subwindows, including enemy-unit screen. Compare: Slide counter, direction, BG offsets and page buffers. Proposed test: `StatScreenDifferentialTest`.

### IS-1g: Stat-screen unit switch

Candidate source: `src/statscreen.c`: `FindNextUnit`, `UnitSlide_InitFadeOut`, `UnitSlide_FadeOutLoop`, `UnitSlide_InitFadeIn`, `UnitSlide_FadeInLoop`, `UnitSlide_SetNewUnit`, `ClearSlide`, `StartUnitSlide`.
Window: IS-cap1; IS-0 fixes per-action subwindows, including enemy-unit screen. Compare: Selected unit, fade timing and display refresh. Proposed test: `StatScreenDifferentialTest`.

### IS-1h: Stat-screen page-name animation

Candidate source: `src/statscreen.c`: `DisplayPageNameSprite`, `PageNameCtrl_OnInit`, `PageNameCtrl_OnIdle`, `PageNameCtrl_AnimOut`, `PageNameCtrl_AnimIn`.
Window: IS-cap1; IS-0 fixes per-action subwindows, including enemy-unit screen. Compare: Page-name proc and OAM through slide. Proposed test: `StatScreenDifferentialTest`.

### IS-1i: Stat-screen page indicators

Candidate source: `src/statscreen.c`: `PageNumCtrl_OnInit`, `PageNumCtrl_CheckSlide`, `PageNumCtrl_UpdateArrows`, `PageNumCtrl_UpdatePageNum`, `PageNumCtrl_DisplayMuPlatform`, `PageNumCtrl_DisplayBlinkIcons`.
Window: IS-cap1; IS-0 fixes per-action subwindows, including enemy-unit screen. Compare: Arrow/page indicators, MU platform and OAM. Proposed test: `StatScreenDifferentialTest`.

### IS-1j: Stat-screen glow and clipping

Candidate source: `src/statscreen.c`: `GlowBlendCtrl_OnInit`, `GlowBlendCtrl_OnLoop`, `StartGlowBlendCtrl`, `EndGlowBlendCtrl`, `BgOffCtrl_OnLoop`.
Window: IS-cap1; IS-0 fixes per-action subwindows, including enemy-unit screen. Compare: Blend coefficients, clipping and palette phase. Proposed test: `StatScreenDifferentialTest`.

### IS-1k: Stat-screen input and close

Candidate source: `src/statscreen.c`: `DisplayPage`, `StatScreen_Display`, `StatScreen_OnIdle`, `StatScreen_OnClose`, `StatScreen_ResumeFromHelp`.
Window: IS-cap1; IS-0 fixes per-action subwindows, including enemy-unit screen. Compare: Page/unit input dispatch, close cleanup and map restoration. Proposed test: `StatScreenDifferentialTest`.

### IS-2a: Help-box rectangle and lifecycle

Candidate source: `src/statscreen.c`: `UpdateHelpBoxDisplay`, `HelpBox_OnOpen`, `HelpBox_OnLoop`, `HelpBox_OnClose`, `HelpBox_WaitClose`, `ApplyHelpBoxContentSize`, `ApplyHelpBoxPosition`, `SetHelpBoxInitPosition`.
Window: IS-cap1 help windows; item-list/forecast contexts use IS-help-cap. Unseen staff/save-help stays out. Compare: Box position/size interpolation and proc lifecycle. Proposed test: `HelpBoxDifferentialTest`.

### IS-2b: Help-box start and close

Candidate source: `src/statscreen.c`: `StartHelpBox`, `StartItemHelpBox`, `StartHelpBoxExt`, `CloseHelpBox`, `EndHelpBox`, `ResetHelpBoxInitSize`, `GetHelpBoxItemInfoKind`, `HbPopulate_AutoItem`.
Window: IS-cap1 help windows; item-list/forecast contexts use IS-help-cap. Unseen staff/save-help stays out. Compare: Message/item selection and cleanup. Proposed test: `HelpBoxDifferentialTest`.

### IS-2c: Moving help-box navigation

Candidate source: `src/statscreen.c`: `HbMoveCtrl_OnInitBox`, `HbMoveCtrl_OnIdle`, `HbMoveCtrl_OnEnd`, `StartMovingHelpBox`, `TryRelocateHbUp`, `TryRelocateHbDown`, `TryRelocateHbLeft`, `TryRelocateHbRight`.
Window: IS-cap1 help windows; item-list/forecast contexts use IS-help-cap. Unseen staff/save-help stays out. Compare: Graph node, direction, skipped fields and target rectangle. Proposed test: `HelpBoxDifferentialTest`.

### IS-2d: Stat-screen help content

Candidate source: `src/statscreen.c`: `StartStatScreenHelp`, `HbPopulate_SSItem`, `HbPopulate_SSStatus`, `HbPopulate_SSPower`, `HbPopulate_SSWExp`, `HbPopulate_SSCharacter`, `HbPopulate_SSClass`, `HbRedirect_SSItem`.
Window: IS-cap1 help windows; item-list/forecast contexts use IS-help-cap. Unseen staff/save-help stays out. Compare: Traversed field contents, item rank/status and redirects. Proposed test: `HelpBoxDifferentialTest`.

### IS-2e: Help-box graphics

Candidate source: `src/helpbox.c`: `LoadHelpBoxGfx`, `sub_80898C4`, `DisplayHelpBoxObj`.
Window: IS-cap1 help windows; item-list/forecast contexts use IS-help-cap. Unseen staff/save-help stays out. Compare: Box OAM, frame tiles and palette. Proposed test: `HelpBoxDifferentialTest`.

### IS-2f: Help-box text progression

Candidate source: `src/helpbox.c`: `HelpBoxTextScroll_OnLoop`, `HelpBoxDrawOneLineExt`, `HelpBoxSetupstringLines`, `HelpBoxDrawstring`, `HelpBoxIntroDrawTexts`, `StartHelpBoxTextInit`, `ClearHelpBoxText`.
Window: IS-cap1 help windows; item-list/forecast contexts use IS-help-cap. Unseen staff/save-help stays out. Compare: Text cursor, timing, tile output and clear. Proposed test: `HelpBoxDifferentialTest`.

### IS-2g: Weapon and staff help fields

Candidate source: `src/helpbox.c`: `DrawHelpBoxWeaponLabels`, `DrawHelpBoxWeaponStats`, `DrawHelpBoxStaffLabels`.
Window: IS-cap1 help windows; item-list/forecast contexts use IS-help-cap. Unseen staff/save-help stays out. Compare: Item labels/stats and layout; staff only if captured. Proposed test: `HelpBoxDifferentialTest`.

### IS-5a: Unit-list entry and display

Candidate source: `src/unitlistscreen.c`: `StartUnitListScreenField`, `UnitList_Init`, `UnitList_SetBlendEffects`, `sub_8090B48`, `sub_8090C58`, `sub_8090D00`, `sub_8090D80`.
Window: IS-5i names exact screen subwindows; unavailable branches get a CAP, not a synthetic expected value. Compare: Unit roster, initial page and BG buffers. Proposed test: `UnitListDifferentialTest`.

### IS-5b: Unit-list rows and sorting

Candidate source: `src/unitlistscreen.c`: `UnitList_DrawColumnNames`, `UnitList_PutRow`, `SortUnitList`, `sub_8092298`.
Window: IS-5i names exact screen subwindows; unavailable branches get a CAP, not a synthetic expected value. Compare: Row order, values and stable selection after sort. Proposed test: `UnitListDifferentialTest`.

### IS-5c: Unit-list input and page change

Candidate source: `src/unitlistscreen.c`: `sub_809144C`, `sub_80917D8`, `UnitList_StartPageChange`, `sub_8091D54`, `sub_8091F10`, `UnitList_OnEnd`.
Window: IS-5i names exact screen subwindows; unavailable branches get a CAP, not a synthetic expected value. Compare: Page/cursor transitions and exit map state. Proposed test: `UnitListDifferentialTest`.

### IS-5d: Unit-list sprites and stat-screen return

Candidate source: `src/unitlistscreen.c`: `UnitListScreenSprites_Init`, `UnitListScreenSprites_Main`, `UnitList_StartStatScreen`, `UnitList_ResetFromStatScreen`, `UnitList_ResetDispFromStatScreen`.
Window: IS-5i names exact screen subwindows; unavailable branches get a CAP, not a synthetic expected value. Compare: OAM, child lifecycle, restored page and selected unit. Proposed test: `UnitListDifferentialTest`.

### IS-6a: Chapter-status entry and counts

Candidate source: `src/uichapterstatus.c`: `StartChapterStatusScreen`, `ChapterStatus_Init`, `ChapterStatus_SetupFont`, `ChapterStatus_ShowAllLayers`, `GetEnemyBossUnit`, `CountEnemyBossUnits`, `CountUnitsByFaction`.
Window: IS-6i names exact screen subwindows; unavailable branches get a CAP, not a synthetic expected value. Compare: Faction counts, leader/boss and initial BG/palette. Proposed test: `ChapterStatusDifferentialTest`.

### IS-6b: Chapter-status text and values

Candidate source: `src/uichapterstatus.c`: `SplitObjectiveTextOnNewline`, `DrawChapterStatusTextForUnit`, `DrawChapterStatusStatValues`, `ChapterStatus_DrawText`.
Window: IS-6i names exact screen subwindows; unavailable branches get a CAP, not a synthetic expected value. Compare: Objective layout, turn/gold/unit values and tiles. Proposed test: `ChapterStatusDifferentialTest`.

### IS-6c: Chapter-status input and sprites

Candidate source: `src/uichapterstatus.c`: `ChapterStatus_LoopKeyHandler`, `ChapterStatus_OnEnd`, `ChapterStatus_FocusLeaderUnit`, `StatusScreenSpriteDraw_Init`, `StatusScreenSpriteDraw_Loop`, `UpdateStatusFactionSelectorGlow`, `UpdateUnitSpritePal`, `StartChapterStatusHelpBox`.
Window: IS-6i names exact screen subwindows; unavailable branches get a CAP, not a synthetic expected value. Compare: Faction selection, OAM, help handoff and close. Proposed test: `ChapterStatusDifferentialTest`.

### IS-7a: Options display

Candidate source: `src/uiconfig.c`: `Config_Init`, `DrawGameOptionIcon`, `DrawGameOptionHelpText`, `DrawGameOptionText`, `DrawOptionValueTexts`, `PutGameOptionRow`, `ConfigSprites_Init`, `DrawConfigUiSprites`.
Window: IS-7i names exact screen subwindows; unavailable branches get a CAP, not a synthetic expected value. Compare: Rows, descriptions, cursor OAM and colors. Proposed test: `OptionsDifferentialTest`.

### IS-7b: Options input and values

Candidate source: `src/uiconfig.c`: `Config_Loop_KeyHandler`, `Config_HandleExit`, `GetSelectedGameOption`, `GetSelectedOptionValue`, `GetGameOption`, `SetGameOption`, `GenericOptionChangeHandler`.
Window: IS-7i names exact screen subwindows; unavailable branches get a CAP, not a synthetic expected value. Compare: Selected option/value, config bytes, keys and exit. Proposed test: `OptionsDifferentialTest`.

### IS-7c: Options live color change

Candidate source: `src/uiconfig.c`: `WindowColorOptionChangeHandler`.
Window: IS-7i names exact screen subwindows; unavailable branches get a CAP, not a synthetic expected value. Compare: New window palette and restoration after reopening. Proposed test: `OptionsDifferentialTest`.

### IS-8a: Guide entry and initial drawing

Candidate source: `src/bmguide.c`: `IsGuideLocked`, `Guide_Init`, `Guide_SetBlend`, `GuideEntry_DrawInitial`, `PutGuideBottomBarText`, `GuideSpriteDraw_Init`, `GuideSpriteDraw_Loop`.
Window: IS-8i names exact screen subwindows; unavailable branches get a CAP, not a synthetic expected value. Compare: Lock result, category list, tilemaps and OAM. Proposed test: `GuideDifferentialTest`.

### IS-8b: Guide list movement

Candidate source: `src/bmguide.c`: `GuideEntry_RedrawUp`, `GuideEntry_RedrawDown`, `GuideMenuRefresh_SyncBg1`, `GuideMenuRefresh_SyncBg0Bg1`, `GetGuideAction`, `Guide_MainLoop`.
Window: IS-8i names exact screen subwindows; unavailable branches get a CAP, not a synthetic expected value. Compare: Category/entry selection, scrolling and proc actions. Proposed test: `GuideDifferentialTest`.

### IS-8c: Guide details and exit

Candidate source: `src/bmguide.c`: `GetStringNextLine`, `MoveGuideDetailText`, `GuideDetailsRedraw_Init`, `GuideDetailsRedraw_Loop`, `Guide_OnEnd`.
Window: IS-8i names exact screen subwindows; unavailable branches get a CAP, not a synthetic expected value. Compare: Text line positions, detail scroll and map restore. Proposed test: `GuideDifferentialTest`.

### IS-9a: Minimap terrain tile selection

Candidate source: `src/minimap.c`: `GetMinimapConnectKindAt`, `GetMinimapSeaKindAt`, `GetMinimapWaterKindAt`, `GetMinimapRiverKindAt`, `GetMinimapCliffKindAt`, `GetMinimapTileAt`.
Window: IS-9i names exact screen subwindows; unavailable branches get a CAP, not a synthetic expected value. Compare: Tile choices for terrain actually present; extra terrain helpers split by inventory. Proposed test: `MinimapDifferentialTest`.

### IS-9b: Minimap drawing and initialization

Candidate source: `src/minimap.c`: `GetMinimapTerrainCellAt`, `GetMinimapObjectCellAt`, `DrawMinimapInternal`, `ApplyMinimapGraphics`, `Minimap_Init`, `Minimap_InitProcVars`, `StartMinimapPlayerPhase`.
Window: IS-9i names exact screen subwindows; unavailable branches get a CAP, not a synthetic expected value. Compare: Terrain/object buffers and initial screen state. Proposed test: `MinimapDifferentialTest`.

### IS-9c: Minimap opening and closing

Candidate source: `src/minimap.c`: `InitMinimapWindowBuffers`, `Minimap_OnHBlank`, `Minimap_InitOpenAnim`, `Minimap_OpenAnim`, `Minimap_InitCloseAnim`, `Minimap_CloseAnim`.
Window: IS-9i names exact screen subwindows; unavailable branches get a CAP, not a synthetic expected value. Compare: Window buffers, per-scanline boundaries and animation counters. Proposed test: `MinimapDifferentialTest`.

### IS-9d: Minimap viewport and input

Candidate source: `src/minimap.c`: `Minimap_PutViewport`, `Minimap_AdjustDisplay`, `Minimap_HandleMoveInput`, `Minimap_AdjustCursorOnClose`, `Minimap_Main`.
Window: IS-9i names exact screen subwindows; unavailable branches get a CAP, not a synthetic expected value. Compare: Viewport, clamping, cursor and map return. Proposed test: `MinimapDifferentialTest`.

### IS-9e: Minimap palette animation

Candidate source: `src/minimap.c`: `InitMinimapFlashPalette`, `Minimap_ApplyFlashPalette`, `Minimap_ApplyViewportFlashColor`.
Window: IS-9i names exact screen subwindows; unavailable branches get a CAP, not a synthetic expected value. Compare: Flash phase and object/viewport palette entries. Proposed test: `MinimapDifferentialTest`.

IS-0/IS-2i must also assign the shared help prompt and any unlisted helpers to separate
rows if observed. Do not implement all of helpbox.c: much of it is unrelated dialogue machinery.
The guide may be locked in the current fixture; record that and add a reachable unlocked fixture CAP.
Minimap terrain variants absent in the Prologue need later terrain captures; no generic coverage claim.
Options music toggling, solo animation and prep/world-map callers are follow-ups, not implied by IS-7.

## M6-G melee battle animation

BA-0 is a boundary/coverage inventory, not a 400-function port. BA-i3 limits its first
implementation briefs to commands actually reached by one melee round; a different command gets another
window/row. BA-i6 splits flash, shake and sound into separate source-file PORTs. BA-death/level captures
must prove those outcomes; do not assume the existing player-attack run kills or levels. Full-animation
level-up is separate from MC-18's map panel. CAP/INV can proceed from original saves; Android integration
waits for playable Prologue, preserving the owner's milestone order.

## M6-H early chapter systems

### C14-item: Consumable item

Source to inspect: bmitemuse.c, bmusemind.c.
Capture: use a Vulnerary after battle damage, then return to map.
Compare: HP, uses, action flags, popup and restored menu/map. Stop at return from this action; exclude sibling actions.

### C14-trade: Trade

Source to inspect: bmtrade.c.
Capture: exchange one occupied slot, move one item to an empty slot, cancel/finish.
Compare: Both inventories, cursor/menu state and moved-unit flags. Stop at return from this action; exclude sibling actions.

### C14-rescue: Rescue and drop

Source to inspect: bmmind.c, bmtarget.c, bmmenu.c.
Capture: rescue a unit, move carrier, choose one legal drop tile.
Compare: Rescue links, unit visibility, positions and action flags. Stop at return from this action; exclude sibling actions.

### C14-transfer: Take and give rescued unit

Source to inspect: bmmind.c, bmtarget.c.
Capture: transfer a carried ally between adjacent carriers.
Compare: Both rescue links, target order, capacity rejection and action flags. Stop at return from this action; exclude sibling actions.

### C14-deathdrop: Carrier death

Source to inspect: bmbattle.c, bmunit.c, mapanim callers.
Capture: kill a carrier holding an ally; stop after the drop settles.
Compare: Dropped unit location/state, cleared links, occupancy map and fade. Stop at return from this action; exclude sibling actions.

### C14-visit: Visit

Source to inspect: eventinfo.c, eventscr.c.
Capture: visit one settlement and accept its event reward.
Compare: Tile/event flag, inventory or gold, dialogue close and action flags. Stop at return from this action; exclude sibling actions.

### C14-seize: Seize

Source to inspect: bmmenu.c, eventinfo.c.
Capture: seize one chapter objective with the eligible lord.
Compare: Command availability, completion flag and ending-event dispatch. Stop at return from this action; exclude sibling actions.

### C14-recruit: Talk and recruit

Source to inspect: eventinfo.c, bmmenu.c, bmunit.c.
Capture: one player talk recruits one unit.
Compare: Faction/unit bytes, dialogue, maps, talk flag and action state. Stop at return from this action; exclude sibling actions.

### C14-green: Green phase

Source to inspect: bmphase.c and observed cp_* functions.
Capture: one green unit turn from banner through next phase.
Compare: Faction banner, AI decision, move/action and phase switch. Stop at return from this action; exclude sibling actions.

### C14-heal: Healing staff

Source to inspect: bmusemind.c, bmbattle.c, mapanim staff effect.
Capture: heal one damaged ally; also cancel target selection.
Compare: Target, HP, uses, exp, animation and action state. Stop at return from this action; exclude sibling actions.

### C14-key: Door and chest key

Source to inspect: bmitemuse.c, bmtrick.c, eventinfo.c.
Capture: open one door and one chest using their keys; inventory each action separately.
Compare: Terrain, trap/event flags, key uses and reward state. Stop at return from this action; exclude sibling actions.

### C14-steal: Steal

Source to inspect: bmmind.c, bmtarget.c.
Capture: steal one eligible item; inspect an ineligible target before confirming.
Compare: Target filtering, both inventories, exp and action flags. Stop at return from this action; exclude sibling actions.

### C14-shop: Map shop

Source to inspect: bmshop.c.
Capture: buy one item, sell one item, cancel and exit.
Compare: Gold, stock/held inventory, selection and transaction dialogue. Stop at return from this action; exclude sibling actions.

### C14-reinforce: Turn reinforcements

Source to inspect: eventinfo.c, eventscr.c, muctrl.c.
Capture: advance through one reinforcement trigger and following phase.
Compare: Turn/event flags, spawned units, movement and phase lock. Stop at return from this action; exclude sibling actions.

### C14-wmintro: Chapter world-map introduction

Source to inspect: worldmap_main.c and observed worldmap_*.
Capture: play the first unverified chapter 2 intro through map entry.
Compare: Node/path state, camera, script and map handoff. Stop at return from this action; exclude sibling actions.

### C14-fog: Fog reveal

Source to inspect: bmmap.c, bmfx.c and observed map callers.
Capture: move one vision source in a fog chapter; stop after refresh.
Compare: Fog/visibility maps, enemy visibility and renderer buffers. Stop at return from this action; exclude sibling actions.

C14-scout1..4 reorder captures by actual first use; labels are feature ownership, not a
promise every feature occurs in chapters 1–4. For later-only features, record the first reachable chapter
and keep their work after the early-chapter critical path. C14-w's inventory must remove such later-only
prerequisites and assign them to the appropriate later milestone before its implementation dependencies
are frozen. In particular, fog already has chapter-6 reference coverage to inspect before recapturing.
C14-key owns two action windows but produces separate door/chest PORT rows. C14-wmintro covers chapter 2
first; the scout creates distinct chapter 3/4 CAPs when their paths/nodes need new behavior.

## M6-I preparations and world-map play

### PW-prep-menu: Prep main menu

Source to inspect: prepscreen.c, prep_atmenu.c, prep_menuproc.c.
Capture: enter preparations, move between main choices, return from one submenu.
Compare: Menu/proc state, BG/OAM and chosen command. Stop at return from this action; exclude sibling actions.

### PW-prep-deploy: Prep deployment

Source to inspect: prep_unitselect.c.
Capture: toggle one optional unit, reject a forced-unit toggle, change selection.
Compare: Deployment bits/count, selected unit and UI. Stop at return from this action; exclude sibling actions.

### PW-prep-items: Prep item exchange

Source to inspect: prep_itemscreen.c, prep_itemtrade.c.
Capture: open one unit inventory, trade one item, cancel back.
Compare: Inventories, selected slots and submenu lifecycle. Stop at return from this action; exclude sibling actions.

### PW-prep-supply: Prep convoy

Source to inspect: prep_itemsupply.c, prep_itemlist.c.
Capture: deposit one item then withdraw it, changing one category.
Compare: Unit/convoy contents, category/scroll and count. Stop at return from this action; exclude sibling actions.

### PW-prep-use: Prep item use

Source to inspect: prep_itemuse.c, prep_itemusemind.c.
Capture: use one eligible consumable and return to inventory.
Compare: Item uses, affected stats/HP and item-use display. Stop at return from this action; exclude sibling actions.

### PW-prep-map: Prep view map and reposition

Source to inspect: prep_unitswap.c, prep_sallycursor.c.
Capture: enter view map, swap two deployable positions, cancel back.
Compare: Coordinates, deploy slots, cursor and restored menu. Stop at return from this action; exclude sibling actions.

### PW-prep-save: Prep save

Source to inspect: savemenu.c, savedraw.c, prep caller.
Capture: save once from preparations and reload the slot.
Compare: SRAM, deployment/inventory persistence and return state. Stop at return from this action; exclude sibling actions.

### PW-wm-travel: Free world-map travel

Source to inspect: worldmap_main.c, worldmap_mapmu.c, worldmap_player_interface.c.
Capture: move to one connected node, open/cancel its menu, enter a chapter.
Compare: Node/path, MU position, camera, menu and chapter dispatch. Stop at return from this action; exclude sibling actions.

### PW-wm-shop: World-map shop

Source to inspect: worldmap_basemenu.c, bmshop.c, prep_wm_sell.c.
Capture: buy and sell one item from a world-map shop.
Compare: Gold, inventory/convoy, shop state and map return. Stop at return from this action; exclude sibling actions.

### PW-wm-spawn: World-map monster merge

Source to inspect: worldmap_timemons.c, worldmap_main.c, worldmap_automu.c.
Capture: advance a natural monster spawn/move/merge event and re-enter map.
Compare: Monster/node state, RNG, path and icons; includes BUG-wminit evidence. Stop at return from this action; exclude sibling actions.

### PW-wm-skirmish: Skirmish

Source to inspect: worldmap_main.c and chapter entry/exit callers.
Capture: enter one skirmish and return after victory.
Compare: Encounter units, rewards, cleared node and world-map restoration. Stop at return from this action; exclude sibling actions.

### PW-wm-dungeon: Tower entry and retreat

Source to inspect: worldmap_main.c, bmdifficulty.c.
Capture: enter the Tower, clear one floor, retreat on the next.
Compare: Dungeon progress, rewards/save state and world-map return. Stop at return from this action; exclude sibling actions.

The prep inventory adds separate CAPs for sorting, stat boosters, promotion and list variants
only when needed; it does not turn PW-prep-use into all item effects. PW-wm-dungeon covers a Tower floor
and retreat, not every Tower/Ruins rule: its inventory schedules distinct Ruins and final-floor completion
captures before claiming dungeon completion. PW-w is a milestone gate; it must include these discovered
required rows, not merely the first example scenario.

## M6-J late-game systems and extras

### LG-promote: Promotion choice

Source to inspect: classchg-*.c, banim-ekrclasschg.c.
Capture: open one two-choice promotion, cancel once, then confirm one choice.
Compare: Class, stat/item changes, menu, animation and return state. Stop at return from this action; exclude sibling actions.

### LG-arena: Arena round

Source to inspect: bmarena.c, banim-ekrarena.c.
Capture: accept one wager, complete one fight, exit; inventory loss/yield gaps.
Compare: Gold, opponent generation/RNG, HP, result and return. Stop at return from this action; exclude sibling actions.

### LG-support: Support conversation

Source to inspect: uisupport.c, bmreliance.c, eventinfo.c.
Capture: activate one available support rank and finish its conversation.
Compare: Support rank/exp, action flag, conversation and battle bonus inputs. Stop at return from this action; exclude sibling actions.

### LG-route: Route selection

Source to inspect: gamecontrol.c, eventcall.c and observed events.
Capture: reach route choice; record one selection and reload for the other.
Compare: Route/chapter flags, party state and next chapter for both choices. Stop at return from this action; exclude sibling actions.

### LG-dance: Dance

Source to inspect: bmmind.c, bmtarget.c, mapanim effect.
Capture: refresh one spent ally and act with it again.
Compare: Action states, target filtering, exp and display. Stop at return from this action; exclude sibling actions.

### LG-summon: Summon

Source to inspect: bmmind.c and observed summon callers.
Capture: summon one phantom, then exercise original replacement restriction.
Compare: Unit creation/removal, ownership, summon state and animation. Stop at return from this action; exclude sibling actions.

### LG-ballista: Player ballista

Source to inspect: bmmenu.c, bmmind.c, bmbattle.c.
Capture: ride, fire one shot, then dismount.
Compare: Rider link, range, uses, battle outcome and commands. Stop at return from this action; exclude sibling actions.

### LG-status: Status staff

Source to inspect: bmusemind.c, bmbattle.c, mapanim effect.
Capture: one status staff hit and subsequent status-expiry turn.
Compare: Status/duration, hit RNG, uses, exp and UI; miss gets separate capture. Stop at return from this action; exclude sibling actions.

### LG-trap: Trap damage

Source to inspect: bmtrick.c, bmtrap.c, bmusailment.c.
Capture: trigger one fire trap and advance its countdown.
Compare: Trap timer, target list, HP and effect procs. Stop at return from this action; exclude sibling actions.

### LG-weather: Weather transition

Source to inspect: bmfx.c Wfx family.
Capture: one original rain start, steady interval and stop.
Compare: Weather proc, particle/RNG state, OAM and cleanup. Stop at return from this action; exclude sibling actions.

### LG-ending: Ending sequence

Source to inspect: ending_details.c, ending_credits.c.
Capture: one chapter-end to character ending and credits segment, then skip/finish.
Compare: Ending records, text timing, portraits, credits scroll and next action. Stop at return from this action; exclude sibling actions.

### LG-sound: Sound room

Source to inspect: soundroom.c.
Capture: select/play one unlocked song, stop, change selection and exit.
Compare: Unlock mask, cursor, playback state and menu graphics. Stop at return from this action; exclude sibling actions.

### LG-bonus: Bonus claim

Source to inspect: bonusclaim*.c.
Capture: claim one available retail reward, then inspect it as claimed.
Compare: Claim bit, inventory/convoy and menu feedback. Stop at return from this action; exclude sibling actions.

### LG-postgame: Creature Campaign

Source to inspect: gamecontrol.c, worldmap_main.c and observed event callers.
Capture: load a cleared save, enter one postgame encounter, return.
Compare: Postgame flags, encounter setup, rewards and world-map state. Stop at return from this action; exclude sibling actions.

These are initial scenario-sized tasks, not a declaration that one trap, weather type, spell
or ending covers its family. Each INV records remaining retail variants as distinct CAPs: e.g. arena loss/
yield, other status effects/staves, trap types, snow/sandstorm and other Wfx modes, paired/solo endings,
other retail extras (including opinfo.c), Ruins completion, spell hit/miss/critical variants and other
actors' dragon effects. LG-spell-index/LG-dragon-index must create the full per-family capture list;
`cap1` is not a wildcard to code the rest. LG-w cannot close with unassigned gaps.

## Integration order and safe parallel work

1. Finish current M6-D claims and ready EP rows; EP-28 unblocks EP-17. MC-18i can scope the existing
   level-up capture independently. Do not modify another agent's claim or worktree.
2. Independent lane B starts IS-cap1 → IS-0/IS-2i, then the frozen stat/help scopes. Small screen
   captures can run independently, but their ports await the corresponding inventory.
3. Lane A captures PC-cap1/2 and audits PC-9i; save-menu, game-over and difficulty sub-lanes remain
   independent until PC-w. PC-easy-w is the M6-E completion gate.
4. BA/C14/PW/LG discovery may use original saves before Android reaches those screens. Their WIRE
   gates preserve milestone order. Freeze each gate's concrete PORT dependencies after inventory;
   never run an entire screen or system in a single coding brief.
5. Planning does not resume readability work, change instruction profiles, alter port counts, or
   remove device-test requirements. M1–M5 and completed M6-A/B rows are unaffected.

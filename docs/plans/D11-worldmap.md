# D11-worldmap: scoping the New Game world-map opening (steps 4898-9313)

Scoped 2026-11-10 (docs only). Monologue (1213-4897) is D11-proc; this note covers only what runs after it.
Proc/function names below are from the pinned decomp and `fireemblem8.nm.txt`; items marked *unverified* were
not confirmed against a capture.

## 1. Source inventory and call order

Entry (`src/gamecontrol.c`, `gProcScr_GameControl` label EXEC_BM): `GameCtrlStartIntroMonologue` (chapter 0 only)
-> `PROC_START_CHILD_BLOCKING(ProcScr_WorldMapWrapper)` -> `EndWM` -> `sub_8009E28` (blend setup) ->
`StartBattleMap`. In Kotlin `GameControl.kt` already has this skeleton; `hooks.worldMapWrapperScript`
and `hooks.endWorldMap` are empty stand-ins (end at once) and `GmDataInit` is a stand-in.

`src/worldmap_main.c`:
- `ProcScr_WorldMapWrapper`: sleep 1, `NewWorldMap` (starts `ProcScr_WorldMapMain` on tree 3, `timer` 16/12/4),
  waits while it exists, ends `ProcScr_BmFadeIN`.
- `ProcScr_WorldMapMain`: `sub_80B8FD4`, `WorldMap_Init` (screen/gfx via `SetupGraphicSystemsForWorldMap`,
  `SetupGmapNodeGfx`, `DeployEveryUnit`, camera), `_FadeBgmOut`, sleep 4, **`WorldMap_CallBeginningEvent`**
  (`CallEvent(Events_WM_Beginning[gmapEventId], 0)` = `EventScrWM_Prologue_Beginning`, then `StartWMFaceCtrl`,
  `StartGmapMuEntry`), then `WorldMap_PostBeginningEvent` (repeat), `WorldMap_GenerateRandomMonsters`,
  `WorldMap_SetMonsterMergedState`, label 3 (`WorldMap_UpdateBgm`, `FadeInExists`, minimap, `WorldMap_Loop`).
  Interactive menus/status/shops/config are NOT in the opening range. How the chapter-0 flow leaves the loop
  (likely `WorldMap_CallIntroEvent` -> `CallChapterWMIntroEvents` = `EventScrWM_Prologue_ChapterIntro`, then
  exit) is *unverified*; the 4898-9313 procs below suggest the intro event dominates.
- Script data: `src/events/prologue-wm.h` (233 lines): ~54 STAL, text (`TEXTSTART/TEXTCONT/TEXTEND` x18,
  `WM_TEXT*`), `WM_SHOWPORTRAIT/CLEARPORTRAIT` x9, `WM_MOVECAM2` x9, `WM_HIGHLIGHT*` x24, `WM_PUTSPRITE/
  PUTMOVINGSPRITE/REMSPRITE/WAITFORSPRITES`, `WM_PLACEDOT`, `WM_SPAWNLORD`, `WM_SHOWDRAWNMAP`, `WM_FADEOUT`,
  `WM_FXCLEAR*`, `MUSC/MUSCFAST`, `FADI`, `EVBIT_MODIFY`. The Beginning script is the first part; the
  ChapterIntro script a later one (exact split *unverified*).
- `src/eventscr_gmap.c` opcodes 0x80-0xB8 (`Event80..B8_Wm*`). Kotlin `EventEngine.kt` has the hi-table but these
  handlers are unregistered (stubs/skip).

Subsystems the opcodes drive (all unported; grep of `Ported from src/worldmap*` / `eventscr_gmap` / `events_wm`
in Kotlin finds nothing but `GetBattleMapKind` in `BattleMapKind.kt`, transcribed, unverified):
- `worldmap_scrollmanage.c` (camera scroll/centre), `worldmap_rm.c` (`GmapRm*`: map BG, borders, palette anims),
  `worldmap_gmapunit.c` / `worldmap_mapmu.c` / `worldmap_automu.c` (map sprites, moves, fades),
  `worldmap_face.c` (portrait ctrl, `E_FACE`), `worldmap_text.c` / `worldmap_screen*.c` (text box, big map),
  `worldmap_palette_fade.c`, `worldmap_line_fade.c`, `worldmap_effect.c`, `worldmap_node_data.c` /
  `worldmap_path.c` (node/path tables, dots; `WmDotPalAnim`), `worldmap_entry.c` (`GmDataInit`).

## 2. Captures and gaps

Everything below is in `$FE8_ASSETS/reference/out`:
- `ram_trace_newgame` covers only f955-f1230; `ram_trace_newgame_bm` only f9314-f9900. **Neither covers
  4898-9313.** `ram_trace_subtitle_boundary` / `fade_start` end near 2380 / 1281.
- `ram_trace_prologue` (steps 30-19800): per-step watches (`procs` 0x02024E68 x6912, `evglobals`, `gbmst`, `gplayst`,
  `flags`, `rng`, `actiondata`) for every step; `rNNNNN` (ewram+iwram) every 30 steps; `fNNNNN` (full:
  vram/pal/oam/io/frame.rgb) at 5100, 5400, ..., 9300 (every 300). Step axis agrees with newgame_bm (its own
  proc timeline shows the monologue parent ending at 4898 and ChapterIntro_KeyListen_Loop appearing at 9384;
  same input timeline is *unverified*).
- Live proc scripts (field 0 of each slot; value is the current script pointer, so names are symbol+offset):
  gProcScr_GameControl (whole range); step 4899 on: ProcScr_GmapRm* parent region at 0x08205F70, 4900 on:
  `ProcScr_GMapMuPrim` (+0x30), `GmMuPrim_OnEnd_Null`, 4941 `UpdateSleep`, 4947-9286 `GmapRm_Blocking`
  (BG map), 5191-7872 `ProcScr_0859160C/08591624` (+ `GmapRmUpdateExt`), 5527-8110 `ProcScr_GmapRmBorder1`,
  5764-7780 `gProcScr_E_FACE_ExtraFrame` (portraits), 6964-7058 `ProcScr_GmapRmBaPalAnim1`, 8111-9278
  `ProcScr_WmDotPalAnim`, 8171-9281 `ProcScr_GmapUnit`, 8173-8429 `GfxSet_WmNationMap`, 8217-9128 GMapMuPrim
  later states, 8371-8399 `GmapUnitFade_Loop`, 8885-8916 `GmapAutoMu_WaitForFadeEnd`, 8918-9127
  `GmapAutoMu_80C3350`, 9157-9172 `sub_8008FB4`, 9288 on `gUnknown_08206868+108` (script after the
  worldmap ends). The `ProcScr_WorldMapMain` / `ProcScr_WorldMapWrapper` roots are not named in this list because
  the pointer shows the current command; resolve exact roots from the decomp script data before the first test
  (*unverified*).
- Gaps: no full dumps between 5100 steps (VRAM/OAM at 300-step spacing only; transitions need r + watches); no
  capture of `gGMData` over time (is in ewram, so r dumps give it); no audio PCM for the world-map stretch
  (BGM only via `evglobals`/`gSoundSt` in r dumps). A new capture with callback/entry snapshots at specific events
  (like `ram_trace_fade_start`) is needed for per-frame camera/scroll and each fade. Add only when a task needs it.

## 3. Tasks (superseded 2026-11-11)

The old wm1–wm8 table is retired. Open rows are in [TASKS](../TASKS.md) lane A; status per finished sub-task is
in section 8. Mapping: wm3 rest → D11-wm3b…e; wm8 → D11-wm8a…f and D11-wm9 (wiring); border/fade effects → D11-cap1, D11-wm4d. Rule kept: nothing is "ported"
until break-checked (CLAUDE.md rule 4); do not mark D-11 solved on subtitle wiring alone.

## 4. Proc tree (resolved by D11-wm1)

Test: `testing/.../WorldMapProcInventoryTest` (`ram_trace_prologue` `procs` watch, steps 4898-9313; not a port
claim). `proc_script` (+0) is the script start, so the section-2 "symbol+offset" names were only mid-script reads
via `ProcScr_*`/`gUnknown` labels; the real script symbols are below. Parent (+0x14) is a slot address, or a
small integer = root tree index (`tree N`).

- `gProcScr_GameControl` (root, whole range) -> child `ProcScr_WorldMapWrapper` 4898-9311 (blocking child, as in
  `gamecontrol.c`). `ProcScr_WorldMapMain` is a root (`tree 3`), 4899-9309 (started by `NewWorldMap`, not a child
  of the wrapper); so both section-1 roots exist and are named exactly.
- Under `ProcScr_WorldMapMain` (all 4901-9309 unless noted): `ProcScr_GMapMuPrim` x6, `ProcScr_GMapMu`,
  `ProcScr_WorldMapTextHandler`, `ProcScr_WorldMapFaceCtrl` (4907-9285) with child `ProcScr_WorldMapFaceHolder`.
- Roots started by `WorldMap_Init`/`WorldMapMain`, 4900-4901 to 9309: `ProcScr_GMapScreen` (tree 5; children
  `ProcScr_GmapUnit` x1, `ProcScr_GmapUnitContainer`, `ProcScr_GMapRoute`), `ProcScr_GMapScreenVSync` (tree 0),
  `gProcScr_GmapCursor`, `ProcScr_GmNodeIconDisplay`.
- 4907 on (event started): `ProcScr_StdEventEngine` (-9284) and `gProcScr_GmapMuEntry2` (-9285).
- `GmapRm_Blocking` is a function inside `ProcScr_GmapRM` (root, 4942-9287); child `ProcScr_GmapRmUpdateDirect`
  4948-9285. `ProcScr_BmFadeIN` 4944-5011 and `ProcScr_GmapLineFade` 4944-4998 are the opening fades.
- Text: `gProcScr_Talk` 5106-9284 and `gProcScr_TalkSkipListener` 5108-9284 (roots, whole text phase); per text
  box short-lived `gProcScr_TalkWaitForInput` (e.g. 5131-5161), `gProcScr_TalkLock`,
  `ProcScr_TalkSpriteShiftClear` with `ProcScr_0859160C`/`ProcScr_08591624` (sprite shift children).
- Per text/map-pan cycle (about every 268 steps from 5436): `ProcScr_GmapRmUpdateExt` (e.g. 5436-5497),
  `gProcScr_E_FACE_ExtraFrame` (5496-5688, then repeats; last 8496-8846), `ProcScr_GmapRmBorder1` (5528-5670),
  `ProcScr_GmapRmBaPalAnim1` (5554-5648; also 6965-7059).
- Map-overview/units phase: `ProcScr_WmPlaceDot` x2 + `ProcScr_WmDotPalAnim` 8112-9280, `ProcScr_GmapUnit` x3-5
  8172-9282, `ProcScr_GmapAutoMu` 8174-9129 (several runs), `ProcScr_GmapUnitFade` 8185-9220 (several runs).
- Exit: `ProcScr_FadeToBlack` (child of `ProcScr_StdEventEngine`) 9253-9268; second `ProcScr_WorldMapFaceCtrl`/
  `FaceHolder`/`GmapMuEntry2`/`StdEventEngine` run 9289-9292 (second event, likely the ChapterIntro event) before
  everything vanishes (step 9311-9313; GameControl alone remains).

Corrections to section 1/2: the `gUnknown_08206868+108` and `GmapRm_Blocking`/`GmapRmUpdateExt` "script" names
were current-position labels of other procs, not roots; `ProcScr_GmapUnit`/`GMapMuPrim` are children as listed
above. The chapter-0 loop exit is still not read from a capture, but the second event run at 9289-9292 supports the
"ChapterIntro after Beginning" order (two StdEventEngine runs: 4907-9284 and 9289-9291).

## 5. Steps 4899-4901 are one multi-frame callback (D11-wm3)

`ProcScr_WorldMapMain` holds script position 3 (inside `call WorldMap_Init`) for trace steps 4899-4901,
while `ProcScr_GMapScreen` appears at 4900 and 15 more procs (VSync, route, icon, unit container, cursor, MU,
text handler, GmapUnit) at 4901, in the creation order of one `WorldMap_Init` call; the wrapper and
GameControl do not move. `gGameClock` still advances 30 over steps 4890-4920 (r dumps; per-step clock is not
watched). So these are lag frames inside one long callback (`SetupGraphicSystemsForWorldMap` gfx loads, as with
`sub_80C50A0`), not a yield; `sleep(4)` is entered at 4902 (sleepTime 4, then 3..0). Tests align on completed
main-proc updates (dwell per position), no artificial delay in the port. Not observable in the trace:
`WmMain_MoveCamera` (ix/iy and camera constant), `sub_80B90CC`/`sub_80B9114` (units 4-6 already 0, not merged).

## 6. Beginning hooks status (D11-wm3)

`WorldMapBeginning` (fe8.game.worldmap) supplies real `fadeOutBgm`, `beginningEvent` (`gmapEventId` at +0x75 of
the chapter struct, `Events_WM_Beginning` 0x088B39EC), `resetGmStoryNode`, `clearMergeNextNode`, `callEvent`
(EventEngine). Stand-ins left: `sub80BA008`, `startWmFaceCtrl`, `startGmapMuEntry`. Not wired into Fe8Boot yet.
Untagged test: the event engine it starts matches `ProcScr_StdEventEngine` pEventStart/pEventIdk/execType and
`chapterIndex` at step 4907.

## 7. Portrait review (2026-11-10)

`90811ce` verifies 11 world-map portrait functions using isolated capture-driven calls on the
observed state-3 path. Each retained function passed an individual failing mutation and restored
green run. The two init claims were removed: fresh-state zero defaults did not prove their writes.
The reference replay checks proc/wrapper fields, face metadata, BG2 maps/9-bit scroll, palette,
portrait VRAM and OAM; 86 eligible map dumps include three nonempty maps. Tests cache the immutable
proc watch rather than reparsing 131 MiB on every step. Production caches the tile table and scroll
scratch, and null-parent starts correctly use tree 0.

Event C4/C5, delayed merge, unseen state branches and complete event VM timing remain unverified.
The host is not wired into Fe8Boot. Before the world-map/battle-map handoff, implement/reference-check
the original `ResetFaces` default VRAM/palette configuration restore; per-slot configuration is now
mutable so the world map can use its own portrait layout.

## 8. Status by sub-task (moved from HANDOFF, 2026-11-11)

Open rows: [TASKS](../TASKS.md) lane A. All of this is standalone and unwired.

- wm3: `WorldMap_Init` spans 4899–4901 as one multi-frame callback (no yield; `b9ba3b6`); `sub_80B8FEC`
  verified; `WorldMapBeginning` gives the real Prologue hooks (event lookup, ResetGmStoryNode,
  CallEvent) and matches the `StdEventEngine` at 4907 (`c7ed068`). Stand-ins: `sub_80BA008`,
  `StartGmapMuEntry`; the optional portrait controller bridge is available. Boot wiring remains.
- wm4 (`a3214a6`): GmapRM BG procs, BG1 scroll/tilemaps and Event85 camera verified; Prologue uses no
  other camera opcode. Remaining: opcodes B4–B7, blend paths, proc end.
- wm4a/b (`5e9722e`, `7a6f911`): `GmapRmBaPalAnim1` transcribed; `GmapRmBorder1` proc logic (80C28C4/2964/29F8/2A1C)
  verified per step on the procs watch (nation 7, 5528–5670; 18 capped 1-frame resyncs asserted). Blend, OAM,
  palette 0x19 and VRAM effects, MergeIn/Out and Start/Show are unverified: no dump falls inside the runs
  (needs a per-step lcd/OAM watch capture). Unwired; nation-highlight event opcode not registered.
- wm5 (`807369a`): text handler verified; GmPalFade and opcodes 80–82/AF–B3 transcribed, unregistered.
- wm7 (`90811ce`): 11 portrait functions verified/mutation-checked against original proc, BG,
  palette, VRAM and OAM state. Init writes remain transcribed; observed state-3 path only.
  Event C4/C5 and full VM timing remain unverified/unregistered.
- wm6 (scheduled 2026-11-10, `b027750`): `WorldMapUnitTest` verifies 13 GmapUnit/container functions
  (fields per step, 4890–9310); u16/s8/u8 narrowings untested. Loop/OAM, fades, palette ids, scripts and
  opcodes 9E–AC remain transcribed/unregistered.
- wm6b (scheduled 2026-11-11): GmapUnit_Loop (UPDATEGFX clear only), SetGfxNeedsUpdate and unit fades
  (StartGmapUnitFade, GmapUnitFade_Init/Loop/Destruct, GetGmapUnitFade, sub_80BB49C/4C0/538) verified per
  step; fade palettes are not in the per-step trace (sub_80BB26C transcribed); sub_80BB538's final
  SetDisplayEnabled is undetectable in the trace. OAM still unchecked.
- wm6c (scheduled 2026-11-11): new `WorldMapMu.kt` (worldmap_mapmu.c); StartGmMu/GmMu_OnInit/GmMuPrim_OnInit
  verified (7 prims at 4901); all GmMu_* accessors transcribed. Movement routines (GmMuPrim_80BD444/
  80BD830/80BD9D8/80BDA78), sub_80BE45C step SE, `sub_80BD20C` (hook, not in decomp src) and the
  centered-camera hook (WorldMapScroll port exists) remain. automu.c not started.
- wm6c movement (owner PC 11-11, `daa1d2b`): linear GmMuPrim_80BD9D8/80BDA78 verified on three prims' first
  moves from 8218; spline movers 80BD444/80BD830 transcribed (Prologue runs type-2 moves only).
- wm6d automu (owner PC 11-11, `818c1b3`): 7 GmapAutoMu callbacks verified per step on all 7 Prologue runs
  (8174-9129), started from captured args; MU-moving/fade waits driven by the capture. Type0/node path,
  StartFadeIn, Type1 start, OnEnd, End/IsActive transcribed. Unwired.
- wm4c (`c2025c8`): events B4–BA (big map, nation highlight) transcribed with unit tests; unverified, unregistered.
- wm8 (end-to-end + registration): rows D11-wm8a…f, D11-wm9. Restore default face
  VRAM/palette configuration at the world-map exit before sharing TalkFace with battle dialogue.
`@VerifiesPort` takes bare function names; `"src/x.c: F"` silently counts nothing (fixed in wm4b/wm6 tests).
Node table checks use pinned runtime source: `tools/setup_decomp.sh` or `FE8U_DECOMP` (read-only).

## 9. Chapter-intro inventory (D11-ci0, ram_trace_newgame_bm)

Inventory only, no port claim. Test: `testing/src/test/kotlin/fe8/testing/ChapterIntroProcInventoryTest.kt`
(full EWRAM dumps, `ProcArrayDump.ofEwram`). Window: frames 9314-9540.

Why 9540: `gProcScr_ChapterIntro` is last alive at 9506. The fade to the map is `ProcScr_FadeFromBlack`
(9523-9538), started under `ProcScr_StdEventEngine` (a root from 9508), not under the intro, so a name-only rule
would end the window near 9510. The window keeps the fade and stops two frames before the next proc
(`ProcScr_BrownTextBox`, 9541). To exclude the fade, set `LAST = 9510` and drop the FadeFromBlack and
StdEventEngine rows.

Rows (`script <- parent`, frames):

| script | parent | frames |
|---|---|---|
| gProcScr_GameControl | tree 3 | 9314-9540 |
| gProc_BMapMain | tree 2 | 9319-9540 |
| sProc_BMVSync | tree 0 | 9319-9540 |
| gProc_MapTask | tree 4 | 9319-9512 |
| gProcScr_ChapterIntro | gProc_BMapMain | 9322-9506 |
| ProcScr_ChapterIntro_Bg1And3Scroll | gProcScr_ChapterIntro | 9325-9500 |
| ProcScr_ChapterIntro_KeyListen | gProcScr_ChapterIntro | 9385-9419 |
| ProcScr_StdEventEngine | tree 3 | 9508-9540 |
| gProc_MapTask | tree 4 | 9513-9540 |
| ProcScr_FadeFromBlack | ProcScr_StdEventEngine | 9523-9538 |

Decomp file per script symbol (`fireemblem8u/`):

| script | file |
|---|---|
| gProcScr_GameControl | src/gamecontrol.c |
| gProc_BMapMain | src/bm.c |
| sProc_BMVSync | src/bmio.c (static) |
| gProc_MapTask | src/bmio.c:139 |
| ProcScr_StdEventEngine | src/event.c:23 |
| gProcScr_ChapterIntro | src/chapterintrofx.c:106 |
| ProcScr_ChapterIntro_Bg1And3Scroll | src/chapterintrofx.c:38 |
| ProcScr_ChapterIntro_KeyListen | src/chapterintrofx.c:30 |
| ProcScr_FadeFromBlack | src/bmlib.c:986 |

Functions named by each chapter-intro script's `struct ProcCmd` array:

- gProcScr_ChapterIntro (main path): BMapDispSuspend; ChapterIntro_Init; SLEEP 60; START_CHILD KeyListen;
  ChapterIntro_SetSkipTarget(1); ChapterIntro_TwinLights_InitAndPlaySe; REPEAT ChapterIntro_TwinLights_Loop;
  ChapterIntro_InitBLDCNT; START_CHILD LightBurst; ChapterIntro_DrawChapterTitle; SLEEP 1;
  ChapterIntro_LightExplosion_Init; REPEAT ChapterIntro_LightExplosion_Loop; ChapterIntro_SetBG_802009C;
  START_CHILD Bg2Scroll; START_CHILD PutDecalSprite; ChapterIntro_InitFogGfx; REPEAT
  ChapterIntro_BlendFogAlpha_Loop; ChapterIntro_SetTimer(160); REPEAT ChapterIntro_TickTimerMaybe;
  ChapterIntro_8020A40; REPEAT ChapterIntro_8020A8C; END_EACH Bg2Scroll, Bg1And3Scroll, PutDecalSprite;
  ChapterIntro_SetSkipTarget(0); ChapterIntro_InitCameraYPos; BMapDispResume; ChapterIntro_InitMapDisplay;
  ChapterIntro_BeginFadeToMap; REPEAT ChapterIntro_LoopFadeToMap; SLEEP 30; GOTO 99.
  Label 1 (skip path; this is what the capture runs from 9419): ChapterIntro_BeginFadeOut; REPEAT ChapterIntro_LoopFadeOut; SLEEP 60;
  ChapterIntro_InitCameraYPos; BMapDispResume; ChapterIntro_InitMapDisplay; ChapterIntro_BeginFastFadeToMap;
  REPEAT ChapterIntro_LoopFastFadeToMap. Label 99: ChapterIntro_End.
- ProcScr_ChapterIntro_KeyListen: ChapterIntro_KeyListen_Init; REPEAT ChapterIntro_KeyListen_Loop.
- ProcScr_ChapterIntro_Bg1And3Scroll: ChapterIntro_Bg1And3Scroll_Init; REPEAT ChapterIntro_Bg1And3Scroll_Loop.
  Started by C code (`Proc_Start`, chapterintrofx.c:363), not by START_CHILD in the intro script.
- ProcScr_FadeFromBlack: FadeFromBlack_OnInit; SLEEP 0; REPEAT FadeFromCommon_OnLoop; BLOCK.

Finding (cause found in review): the capture presses A every 30 frames (script `ram_trace_newgame_bm`), so
`ChapterIntro_KeyListen_Loop` sees A at 9419 and jumps the intro to label 1 (skip path). The main path after the
twin lights (LightBurst, title, LightExplosion, Bg2Scroll, PutDecalSprite/RevealDecalSprite, fog, fade to map)
never runs in this run. D11-ci1 (Init, key listener, skip path) can use this run; the full path needs the new
capture D11-cap2 (New Game with no key press during the intro).

### 9a. Main-path capture `ram_trace_newgame_intro` (D11-cap2, ROM repo `eb263d1`)

NEWGAME inputs to f9321, then no key presses. Per-frame RAM f9320..9905, full dumps every 10f, per-step watches
as `ram_trace_prologue`. Up to f9322 identical to `ram_trace_newgame_bm`.

| Event | Frame |
|---|---|
| gProcScr_ChapterIntro; Bg1And3Scroll; KeyListen (never fires) | f9322; f9325..9831; f9385..9872 |
| LightBurst | f9455..9502 |
| Bg2Scroll + PutDecalSprite; RevealDecalSprite | f9526..9831; f9556..9597 |
| Intro proc ends; beginning-scene StdEventEngine | f9872; f9873 |
| FadeFromBlack (fade to map) | f9888..9904 |

Twin lights, title draw, light explosion and fog have no proc of their own; they run inside the intro proc's
callbacks and show only in the dumps. Screenshots (title card f9530, fog f9700) checked by the parent.

### 8a. D11-wm8a analysis (owner PC 11-11; no code yet)

Opcodes run in 4907..5100 (`EventScrWM_Prologue_Beginning`, checked against StdEventEngine `pEventCurrent`
+0x38 in the procs watch):

| Step | Opcode | State |
|---|---|---|
| 4908 | 10 EVBIT_MODIFY(1), C2 WmNoFade, C3 SpawnLord, 85 camera, 13 MUSCFAST(0x7fff → `Sound_FadeOutBGM(2)`) | 10/85 ported; C2, C3 (`SetGmCharUnit` → `mapMuSetUnitChar`), `Event13_BgmFadeIn` unported |
| 4909..4940 | 0E STAL(32) | ported |
| 4941 | 12 MUSC | verified |
| 4942 | B4 SHOWDRAWNMAP (starts GmapRM), 0E STAL(2) | B4 transcribed, unregistered |
| 4944 | B2 FADEOUT(0): `StartGmapLineFade(0)` (4944..4998) + `NewFadeIn(2,0)` (BmFadeIN 4944..5011) | worldmap_line_fade.c (~6 functions) unwritten; NewFadeIn verified |
| ..4999 | B3 WaitFade | transcribed |
| 5000; 5001..5060 | 10 EVBIT_MODIFY(0) (bits 0x72 → 0x02 at 5001); 0E STAL(60) | ported |
| 5061..5100 | AF SHOWTEXTWINDOW (`gmMuEntryStartShow`, verified); B1 TextBoxWait | `GetGmMuEntryFlag` unported (trivial) |

Steps 4945..4947 are lag frames inside `GmapRm_StartUpdateDirect` (as in `WorldMapRmTest`); port frame n = step
4907+n up to 4944. Register opcodes one by one (not `registerOn`, which adds opcodes outside the window). Only B4
of B4–B7 runs here (B5–B7 from 5436). Seed: iwram/ewram r04860/r04920, GmapMu proc + 7 prims + container as in
`WorldMapMuTest`; C3 is provable against r04920 with `units[0]`, camera and no_fade zeroed in the seed. The
`StartGmapMuEntry` hook in `WorldMapBeginning.hooks` is still a stand-in (`WorldMapMuEntry.startGmapMuEntry` exists).

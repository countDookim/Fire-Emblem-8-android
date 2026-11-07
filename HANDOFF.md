# HANDOFF

_Updated 2026-11-06 (owner PC: audit of scheduled work, M6-23, M6-25..27, M6-30..34, M6-38/39, M6-46). Owner decisions: docs/PROJECT_BRIEF.md. Backlog:
docs/TASKS.md. M5 facts and reference runs: docs/plans/M5-notes.md. History: git log._

## NEEDS USER
_(nothing blocked)_

## In progress
- M4-12: owner PC, until ~16:05Z

## Check first (owner, 2026-11-07)
- Device test of APK 5e213f1 passed all 3 MANUAL_TEST items (KMP rebuild, issue #4 Prologue map + song, resume turn
  1). Issue #4 can be closed. Owner-reported problem on the Prologue opening (owner 11-07: note it, don't
  investigate yet): the opening plays but its cutscenes are skipped and no dialogue is shown; the screen flashes a
  few times; many cutscene units that should not be there end up on the battle map with the allies; then the cursor
  can be moved but nothing happens; there is no player phase and no enemies. Code facts (read, not traced):
  `Fe8Boot.eventEngine` (Fe8Boot.kt:184-252) registers only EventScrCore, unit loads, ChangeMap, Fade, Bgm; Talk
  (M6-33/34), Mu (M6-25..27) and `EventUnitState` (Event34) are ported but not registered there, so their commands
  hit `ReleaseEventStubs` no-ops. Note the headless `AppRomPathTest`/ `PrologueMapTest` do reach `playerPhaseScript`
  (15 red units), so the device path differs: find out why first.
- Open from issue #4: Event15 + `StartBgmVolumeChange` (save-menu BGM ramp, ram_trace_newgame f1174..1193),
  GameControl fade stand-ins (GameControl.kt:206-212, 409-414). Map palette not yet compared to first_phase pal.bin.

## Audit 11-06 20:10Z (owner PC) of scheduled work 3c26e9e..ab985a8
Full jvmTest green (555). 6 tags withheld because breaks survived (UpdateMapImgAnimations: VRAM every 10th frame;
GameCtrl_StartResumedGame: camera 0; FadeFromCommon_OnLoop: one fade speed; GetUnitSpriteHideFlag; SetEventSlotCounter;
WriteLastGameSaveId): stronger tests can reinstate them. KDoc fixes done (M6-46). Unchecked groups: ReadGameSave/
WriteGameSave, ReadSuspendSave, unit-sprite reset/use/refresh, StartLockingFadeFromBlack, SwitchPostSaveMenu.
Same session: M6-23 (EventChangeMap), M6-25/26/27 (Mu.kt start, movement, display; AP_* animation player is a hook),
M6-30 (gbafe.text.MsgStrings), M6-31/32 (gbafe.text Font/TextSystem fonts, widths, glyph drawing, PutText) done;
M6-46: no "Ported from" without a tag left (`progress.py` prints `ported-without-tag`; it now ignores .claude/ worktrees).
M6-33/34 (fe8.game.scene Talk: init, interpreter, wait-for-A; the whole first talk matches per frame) and M6-38/39
(BmBattle + BattleGenerate: unit init, stats, scripted hits; Prologue battle matches r14430) done. Next: M6-35/36
(faces, bubble/shift procs: TalkInterpretDifferentialTest has transcribed stand-ins for them), M6-37 (Event1A/1B),
M6-40/41 (battle result + Event3F).
 Merged `wip/M6-05`, `wip/M6-17` deleted.

## Next task: M6-29 (wip/M6-29-v2), M6-20 rest (wip/M6-20), M6-19 rest, then M6-44 (TASKS)
Owner PC 11-07 15:10-16:05Z: M6-37 done (EventTextDifferentialTest; EventText not in Fe8Boot). CI on master failed since
11-06 23:49 (Dokka: 2 undocumented; summary: battle_end run not packed): fixed (1d089a8; ROM repo c66e0fd packs
ram_trace_prologue_battle_end, old runs byte-identical; `.assets` updated). Full jvmTest green, 518 verified. CI does
commit README/port_map itself after a green master run (the How-to line saying it never does was wrong). Remote
`wip/M6-41` (merged) and `wip/M6-29` (same changes as wip/M6-29-v2) can be deleted: auto mode refused the delete.
Scheduled 11-07 13:05Z (full jvmTest green 13:27Z): Fe8Boot now registers Event34 (EventUnitState, hooks: unitAt only) and Event3F (EventScriptBattle,
host: lock = loop.gameLock, entity-map refresh; anim/AiRefreshMap/RefreshUnitSprites default) (81c9e51; Fe8Boot/Prologue/
NewGame/ResumeBoot tests green). `wip/M6-29-v2` = wip/M6-29 cherry-picked onto master (old wip/M6-29 has unrelated
history in the shallow clone; delete it and wip/M6-41, branch deletes are refused for scheduled runs) + EventVm replay runs
real 0x3F (out of STUBS/UNIT_STUBS): ScriptBattleDeamon breaks at the gbmst lock and its unit result equals the r15090 dump
(base run; battle run: lock never returns in range). ScriptBattleDeamon still untagged (thin: 1 unit HP; PROC_MARK_EVENT
unchecked). `wip/M6-20`: StartBgmVolumeChange, MusicVc_OnLoop, Sound_SetSEVolume, m4aSongNumStop, Event15 transcribed and
registered (EventVm replay green), untagged; `BgmVolumeChangeTest` (newgame f1174..1193) disabled: the trace ramps BGM to
0 over ~19 f (not the PostSaveMenu (0xC0,0x100,0x10) guess) and track flags alternate 131/128 per dump (order vs MPlayMain).
Scheduled 11-07 11:15Z (full jvmTest green 11:14Z): M6-41 Event3F_ScriptBattle + GenerateScriptBattleHitFormEventQueue
verified (`EventScriptBattleDifferentialTest`, prologue_battle step 14430 = r14429 inputs, battle structs/hits unmasked);
deamon untagged, not registered yet. M6-42 `fe8.game.event.EventInfos` (search + EvCheck00/02 tagged; others throw "not
ported"). M6-43 RunPhaseSwitchEvents tagged (no-event path only; no trace starts a turn event) and wired in Fe8Boot.
`wip/M6-41` merged (remote branch delete was refused this run: delete it).
Scheduled 11-07 05:15Z: M6-40 rank mismatch solved: the f15075 unit update of `ram_trace_prologue_battle_end` is
`EventBattleReloadBmStatus` (eventscr3.c, ScriptBattleDeamon end of Event3F), not BattleApplyGameStateUpdates (disasm +
trace: wexp would be 182; the ROM never calls it). Ported `BattleGenerate.eventBattleReloadBmStatus` (hooks for AiRefreshMap/
RefreshEntityBmMaps/RefreshUnitSprites), `EventBattleReloadDifferentialTest` (r15074->r15075; masks +0x3C..3F, unit 0x01
US_HIDDEN, cleared by battle-anim end code) replaces BattleApplyDifferentialTest; UnitRemoveInvalidItems tag withdrawn;
wexp class baseRanks offset fixed (+0x2C). BattleApply*/UpdateUnitFromBattle stay untagged (needs a player-phase battle run).
battle_end added to M4A_UNCOVERED_RUNS (assumed same Prologue divergence, not confirmed): the run can now be packed; with it
(run locally: harness takes 7 s) jvmTest is green. Full jvmTest green 05:12Z without it. Next: M6-41 Event3F using the reload.
`wip/M6-41` (493de0e): `fe8.game.event.EventScriptBattle` transcribes Event3F_ScriptBattle, ScriptBattleDeamon and the
slot-queue hit builder behind `EventScriptBattleHost` (untagged, not registered). To verify: parametrize EventTrace.prologue/
Replayer by run name and replay `ram_trace_prologue_battle`; host gameLock = gBmSt lock from the gbmst watch; seed
BattleGenerate (actor/target/stats as in EventBattleReloadDifferentialTest) from the A-step ewram; give the deamon a parent
proc; then drop 0x3F from STUBS/UNIT_STUBS for that run. Item-override 0x3F still throws.
Scheduled 11-06 23:38Z: M6-40 partial (BmBattle `battleApplyGameStateUpdates` chain, `BattleApplyDifferentialTest`
r15074->r15075). New ROM-repo script `ram_trace_prologue_battle_end` (old run ends f15059, the update runs at f15075);
owner: run make_reference + repack dist (CI skips the test until then). WARNING: with this run present,
M4aNoteTest (r15059 ch0 envelope) and M4aSequencerTest (r15131 track 0) fail: the m4a tests scan every run, so fix
or exclude before publishing. Full jvmTest otherwise green 11-07 00:0xZ (341, only those 2 fail). Only UnitRemoveInvalidItems tagged: Seth's sword
rank 181 + weaponExp 1 -> we write 182, ROM keeps 181 (masked); find why (item weaponExp offset 0x20? input decode?), then tag.
Scheduled 11-06 22:51Z (full jvmTest green 23:12Z): M6-36 done (0c3439a, `fe8.game.scene.TalkBubble`: open/bubble/shift-clear procs, BG1 tilemap matches per
frame f9866..10565). M6-35 done (9ebd577, `TalkFace`: load/layer/move procs; face tiles diverge from f10040 in the eye area
until blink/mouth procs exist; face palettes need StartFaceFadeIn). Neither is wired into a production TalkHost/Fe8Boot yet
(owner's Prologue report). `wip/M6-37`: `fe8.game.event.EventText` transcribes Event1A/1B/1C/1D behind `EventTextHost`;
EventVm replay runs 1A/1B/1C real (test `idleAware` wrapper keeps the old parked-before-command skip), 0x1D still stubbed
(needs live talk state + choice result); untagged: replay checks only return codes/engine state. Next: wire EventText to
Talk/TalkBubble/TalkFace with a real host, then a talk-effect check to tag it; merge wip/M6-37.
Scheduled 11-06 19:50Z: M6-12 done (eb45763): `Fe8Boot(saveStorage)`, MainActivity `filesDir/fe8.sav`; save menu stand-in
resumes whenever a valid suspend exists (else New Game); `ResumeBootTest` Ch2/Prologue reach idle, units == ch2_scroll/
prologue_resume r01299 (mask +0x3C). `wip/M6-29`: Event26/3B/3C transcribed, untagged: 0x3C runs in no event trace;
3B needs the EventDisplayCursor child proc compared (procs watch +0x64..0x6A); talk/battle runs replay with a one-line
`EventTrace.prologue` change, but talk mismatches at step 9803: our 0x3B parks at 0x089EECF4 (char 15 absent: units made by stubbed 0x2C inside
the window are not in the replay's seeded table; lookup itself matches C). wip/M6-29 now skips those lookups and replays
talk/battle without mismatches (>1000 pairs each). Still untagged: 3B child proc, 0x3C, camera. M6-10: ResumeFromPhaseIdle verified (BMapMain = proc slot 2 of
gProcArray 0x02024E68, cmd 40 = label 4 at r01150; dispcnt vs iwram 0x03003080); other variants unreached by traces.
Full jvmTest green 19:58Z. CI eb45763 failed only the summary skip guard (prologue.sav absent in CI); fixed in bc7c23b (SRAM
dump s01001 of ram_trace_prologue_resume).
Scheduled 11-06 19:06Z: M6-19 partial: `MapAnimations` tile anim verified + wired in Fe8Boot (see TASKS). Full jvmTest green 19:06Z.
Scheduled 11-06 19:00Z: M6-18 done (`MapCursor`, wired; `MapCursorDifferentialTest` vs first_phase OAM objects 0..3; drawn
position = dumped playerCursorDisplay minus the slide step, test searches 0..4 px: tighten if a per-frame gBmSt trace exists).
GetCurrentMapMusicIndex stays untagged: Prologue traces cannot tell red from green slots, no victory/flag-4/skirmish case (needs a later-chapter run).
Scheduled 11-06 18:55Z: M6-17 done. `UnitSpritesOam` (PutUnitSpritesOam/IconsOam verified vs first_phase oam.bin
f19430..19530: cursor = objects 0..3, ours from 4, clock = dump clock - 1). Fe8Boot `wireUnitSprites` replaces the
BattleMapStart/BMapMain/PlayerPhase unit-sprite stand-ins (trap pool zeroed stand-in); `PrologueMapTest` sees 6 unit
objects at idle (not compared to the dump: cutscene stubs). OBJ drawn by HardwareScene (code-read; not yet seen on
device). Local `dokkaGenerate` not found without ANDROID_HOME; CI runs it. Force-push and branch delete are blocked here: `wip/M6-17` is merged and can be deleted.
Scheduled 11-06 18:16Z: M6-11 done (`savemenu/PostSaveMenu.kt`, `PostSaveMenuDifferentialTest`; stand-in selection; not
yet wired into `GameControl.hooks.startSaveMenu`: do it in M6-12). M6-13 blocked (no run reaches the four functions; TASKS).
M6-15/16 done (`UnitSprites`, `UnitSpriteHandles`, `UnitSpritesDifferentialTest` vs f19430). `wip/M6-17`: Sync/ForceSync
UnitSpriteSheet; ForceSync verified over f19430..19530 (>=68 branch unobserved; SyncUnitSpriteSheet untagged, testable at
f19450/f19490 clock 32/0; `registerDataMove` copies at once instead of queueing FlushTiles); open: PutUnitSpritesOam,
MapTask hook, Fe8Game.present OBJ. Full jvmTest + :game Dokka green 11-06 18:34Z (master ce4cf72).
Scheduled 11-06 18:xx: M6-08 done (`SaveStorage`, `FileSaveStorage` temp+ATOMIC_MOVE in gba/core jvmMain,
`CartSram.loadFrom/flushTo`; Android wiring = `File(filesDir, "fe8.sav")` in M6-12). M6-09 done
(`BattleMapStart.startResumedGame`, wired in Fe8Boot; `StartResumedGameDifferentialTest` r01149 gBmSt; camera only
weakly covered, ch2_scroll untested). M6-10 partial: variants + dispatch transcribed, untagged; for M6-12 set
`battleMap.hooks.suspendPointType` from a `SuspendRam.actionData[0x16]` filled by ReadSuspendSave, wire
`hooks.refreshEntityBmMaps/refreshUnitSprites` (EntityBmMaps is ported but not injected), then tag PhaseIdle vs a
dump that shows the BMapMain label (subagent could not find the proc in the r01149 proc array). M6-11: 
`GameControl.switchPostSaveMenu` was already transcribed (checked vs gamecontrol.c:447); open: PostSaveMenuHandler
(needs a SaveMenuProc) and the ch2_scroll/ch6_start tests. Full jvmTest green 11-06 18:12Z.
M6-05/06/07 done (scheduled 11-06): ReadSuspendSave* and ReadGameSave/LoadSavedUnit verified (`ReadSuspendSaveReferenceTest`,
`ReadGameSaveReferenceTest`, `BmGameSave.kt`); WriteGameSave round trip masks 3 uninitialised-stack bytes + checksum; WriteGlobalSaveInfo (character-known marking) unobserved, untagged. Fix: ReadPermanentFlags reads 0x19 bytes (was 0x18). Weak spots: ReadGameSave's
`gameSaveSlot = slot` and `InvalidateSuspendSave` are unobserved by the ch6 run. Decomp C is not in setup's assets:
`git clone --depth 1 --filter=blob:none --sparse https://github.com/FireEmblemUniverse/fireemblem8u` + `sparse-checkout set src include`.
`wip/M6-05` (remote) is merged and can be deleted.
Device crash fixed 11-06 (bca8381): START on the title with only the boot/sound windows (debug APK, no import) read
chapter data outside the view; the New Game stand-in now runs only on `RomView.isWholeRom`. `AppRomPathTest` drives
`Fe8Game` (sound + present) on both ROM sources. Owner had installed the 887d744 APK (before M5-61); the next device
test needs the APK from bca8381 or later + Re-import ROM.
Owner 11-06: run ONE agent/task at a time from now on. M5-58/59 merged (11-06): Fe8Boot runs New Game → beginning
events (unskipped; START skip reads the real KeyStatus) → Prologue PlayerPhase idle; `PrologueMapTest` (BG3 tiles vs
ram_trace_first_phase f19430, 0 B/frame idle with D-pad). Known gap: release no-op stubs leave cutscene units (9 blue/
15 red vs the reference's 2/3), so `UnitLoader.closestValidPosition` (transcribed, `ClosestValidPositionTest`) runs
for red units 139/142; closed by M6-22/28/44. M5-61 (11-06): import keeps `base/rom/baserom.gba` and
the app runs on `RomView.ofRom` (old installs: re-import); device test pending. M6-03/04: the suspend save is written at each phase start through `Fe8Boot.saveLib` (stand-ins:
actionData, traps, convoy, WM info, dungeon; GMapSaveInfo is 0x24). M6-21/22: `EventVmDifferentialTest` compares
unit arrays; `EventUnitState` (Event34) registered in the test engine only (not Fe8Boot), untagged. New runs ram_trace_prologue_{talk,battle,resume}
(ROM repo c80bcb8; excluded from m4a tests). Old `wip/` branches deleted 11-06.

## State
- Boot (H&S, logos, op anim, title) matches mGBA: 27 lag frames, 249 frames pixel-exact, gap f1180 only.
- Sound: m4a sequencer and DirectSound mixer byte-exact vs per-frame RAM traces (`M4aNoteTest`, 3145 frames);
  `CgbSound` verified; `PsgSynth` (GBATEK/mGBA behaviour, not a port) RMS 1249 vs mGBA 1200.
- Owner device tests passed: boot to title, touch buttons, gamepad, GPU backend, audio, ROM import (~4 s) + boot
  from it. Pending: smoke test of the first APK built on the KMP android variants (MANUAL_TEST "Newly testable").
- Verified ports: 486 / 8352 (`tools/progress.py`, 2026-11-06; audit withheld 6 M5-60 overclaims). Scripts: 897/897 EventScr* byte-exact in 46 `.fe8s`.
- Layout: `:gba:*` game-neutral (ADR 0012), `gbafe.*` in `:engine:*`, `fe8.*` in `:game`/`:app`; KMP jvm+android
  (ADR 0013) except `:gba:testkit`, `:testing`, `:tools:extract`.

## Open leads (low priority unless the owner reports a problem)
- m4a tests skip the M5 runs (`M4aRuns.kt`): our sequencer diverges on `ram_trace_newgame` r00975 channel 12
  (new-game menu songs) and on `ram_trace_ch2_scroll` (r01469 track 0 status 0xC0 vs our 0x80; r01104 player 2).
  Investigate before claiming m4a coverage there.
- Test strength: ~40 ProcEngine tags share one Kotlin function with another tag (a break proves the group, not each
  name); OpAnimMergeBGProcInit, sub_80C8608 untagged. Untagged for lack of a trace: SaveBonusContentData,
  SaveRankings, WriteLinkArenaStruct2, VerifySramFast_Core,
  GameCtrl_Check{NewGame,GameComplete}AndBranch, GetPhaseAbleUnitCount exclusion branches, ply_keysh, ply_xcmd,
  GameControl_EnableSoundEffects, EndAllProcChildren, StartOrChangeBgm, Title_IDLE, Title_EndAllProcChildren,
  DrawTitleSprites_Init, SetSecondaryHBlankHandler, OpAnimHBlank1, ColorFadeSetupFrom{ColorToWhite,White},
  sub_80C85FC, sub_80C8684, sub_8009B64, GameControl_PostIntro (partial), m4a fade-in/`TEMPORARY_FADE`.
  `m4aSongNumStartOrChange` is never called by FE8: leave it unclaimed.
- Owner fixture `complete100.sav` (ROM repo reference/fixtures, 11-06): 100% game, 2 Creature Campaign slots, no
  run yet. Could give ground truth for the untagged save/post-game paths above (SaveBonusContentData, SaveRankings,
  GameCtrl_CheckGameCompleteAndBranch, link arena, sound room) and later world-map/Creature Campaign loads.
- M5-32: slots 3/4 block-info checksum 1F1F1F1F in the dump vs ours for invalid kinds (stack garbage; test checks shape).
- PSG: 6 of 857 quarter frames differ from mGBA (ch1 f953/f991/f1009, wave f980): note-specific envelope steps,
  not a phase error (`SEQ_OFFSET_CYCLES` 68198 is best).
- f1180: BG VRAM 0xD800..0xE800 differs from the dump (sync timing of a BG map copy).
- Slow app open (2026-10-30): likely fixed (assets counted on `Dispatchers.IO`); if reported again, measure
  `MainActivity.onCreate`/Compose init and the first Play frame.
- Later: `.fe8s` beyond the prologue need each `ChapterEventGroup`'s LOAD targets; keysplit/drum sub-voicegroups
  are not written as JSON; OBJ mosaic; mosaic on affine BGs; GPU sprites still rasterised on the CPU.

## How-to
- New reference runs (ROM repo; needs cmake, ninja, network for a harness rebuild): add to `harness/gen_scripts.py`
  (`# args: --sram reference/fixtures/X.sav` header for save-based runs; owner saves go to `reference/fixtures/`),
  run it and `harness/make_reference.sh` (rebuilds all runs); check old runs are byte-identical to a fresh untar of
  `dist/reference.tar.xz` (only PROVENANCE.sha1 may differ); pack `tar -C reference --sort=name -cf - out | xz -9 -T0
  > dist/reference.tar.xz`; update its sha1 in `dist/MANIFEST.sha1` (else publish fails); push. The `publish-assets`
  workflow makes release `assets-<sha>` (latest); in the code repo `rm .assets/MANIFEST.sha1 && tools/setup.sh`.
  If new runs break the m4a tests, list them in `M4aRuns.kt`. PSG solo runs: `--audio --psg-mask N`; ours:
  `FE8_PSG_MASK` (with `--rerun`).
- Harness script: `run N`, `hold KEYS N`, `press`, `dump`/`dumpram`/`dumpsram NAME`, `watch NAME ADDR LEN` (≤ 16,
  LEN ≤ 0x10000, hex needs `0x`), `savestate`/`loadstate` (does not restore SRAM). `python3 -I harness/scan_trace.py
  <run dir>` names procs/gPlaySt/SRAM transitions; `gen_scripts.py --scratch-newgame OUT END RAM_EVERY [FROM]`.
- Re-extract: `./gradlew :tools:extract:run --args="--rom $FE8_ASSETS/baserom.gba --out <dir outside repo> --decomp
  <fireemblem8u> --map <rom repo>/decomp/fireemblem8.map --symbols <rom repo>/decomp/fireemblem8.nm.txt"`, then
  `python3 tools/compare_extraction.py <rom repo>/extracted <out>`, copy changed files into the ROM repo's
  `extracted/`, push there (CI fails until you do).
- Shader parity after a shader/`PackedCompositor` change: `FE8_SHADER_DUMP=<dir> ./gradlew :testing:test --tests
  '*FrameCompareTest'`, then `node tools/shader_parity.mjs <dir>`.
- Decomp: `git clone --filter=blob:none --sparse https://github.com/FireEmblemUniverse/fireemblem8u`, checkout
  `04d1edb…`, `git sparse-checkout set --no-cone include src texts asm data sound`.
- Trace timing: a frame that overruns its VBlank is dumped mid-frame; step only on dumps whose successor's key
  struct differs (`titleKeyPath`).

## Rules and caveats
- Aim for a `@VerifiesPort` every run or two. No `java.*`/`javax.*`/`System.*` in `commonMain`; battle rolls only
  via `mechanics.battleRolls`.
- Always `source .assets.env` before Gradle (else Maven Central 429s).
- Pushes race CI's `ci: update progress [skip ci]` commit: `git fetch && git rebase origin/master`, push again.
- CI Dokka fails on ANY warning (every public declaration incl. constructor `val`s needs KDoc): run `./gradlew
  dokkaGenerate -Pfe8.docs=true` (per module on the owner PC) before pushing public API. Keep `configureDokkaLint()`
  on Groovy property access.
- `tools/progress.py` credits a `@VerifiesPort` test to the LAST `class` declared before it: keep helper classes
  below the test class.
- CI's "Commit progress (master)" step commits README.md + docs/port_map.json after a green master run; when CI is
  red they go stale (run `python3 tools/progress.py --write` after a full `jvmTest test` and commit them yourself).
- Allocation tests can flake (200 B once in 1000 frames): use `AllocationMeter.measureMin`.
- `FrameCompareTest.applyHBlank` knows only `HBlank_BgVerticalScroll`; dumped RAM is one game step ahead of the
  dumped frame.
- CI `startup_failure` → check the owner's GitHub billing first. Actions storage is small: master's APK goes only to
  the `latest` release (branch APK artifacts 2 days, Dokka 1 day, results 7 days); if storage fills, delete old
  artifacts (`gh api -X DELETE repos/OWNER/REPO/actions/artifacts/ID`).
- `gh release view/download` (GraphQL) is refused; use `gh api repos/.../releases/...` (REST). Delete a `wip/`
  branch once merged.
- Data: `cursor_hand.png` colours `paletteSource: caller`; `effectiveAgainst` holds class numbers; classes inline
  shared terrain tables; `RamSymbols[name]` throws on ambiguous static names.

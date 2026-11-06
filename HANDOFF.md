# HANDOFF

_Updated 2026-11-06 (owner PC: M5-58/59/61, M6-01..04, M6-21/22, tag audit, device-crash fix). Owner decisions: docs/PROJECT_BRIEF.md. Backlog:
docs/TASKS.md. M5 facts and reference runs: docs/plans/M5-notes.md. History: git log._

## NEEDS USER
_(nothing blocked)_

## In progress
_(none; claim tasks here before starting them, CLAUDE.md rule 10)_

## Check first (owner, 2026-11-06)
- Issue #4 fixed 11-06 (cloud owner session), awaiting device retest: causes were BMapMain fade stand-ins (no-op;
  now `ScreenFade`, bmlib.c), map palette never unpacked (`StartBattleMap` now calls `unpackChapterMapGraphics`),
  and no map/event BGM (`MapMusic`, `EventBgm`). Open: Event15 + `StartBgmVolumeChange` (save-menu BGM ramp,
  ram_trace_newgame f1174..1193), GameControl fade stand-ins (GameControl.kt:206-212, 409-414), the opening event
  repeats fades because text/camera/unit commands are stubs. Map palette not yet compared to first_phase pal.bin.

## Next task: M6-17 (on `wip/M6-17`), then M6-12 (M6-10 rest, M6-20 rest: Event15/volume change)
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
unit arrays; `EventUnitState` (Event34) registered, untagged. New runs ram_trace_prologue_{talk,battle,resume}
(ROM repo c80bcb8; excluded from m4a tests). Old `wip/` branches deleted 11-06.

## State
- Boot (H&S, logos, op anim, title) matches mGBA: 27 lag frames, 249 frames pixel-exact, gap f1180 only.
- Sound: m4a sequencer and DirectSound mixer byte-exact vs per-frame RAM traces (`M4aNoteTest`, 3145 frames);
  `CgbSound` verified; `PsgSynth` (GBATEK/mGBA behaviour, not a port) RMS 1249 vs mGBA 1200.
- Owner device tests passed: boot to title, touch buttons, gamepad, GPU backend, audio, ROM import (~4 s) + boot
  from it. Pending: smoke test of the first APK built on the KMP android variants (MANUAL_TEST "Newly testable").
- Verified ports: 376 / 8352 (`tools/progress.py`, 2026-11-06; audit withheld 6 M5-60 overclaims). Scripts: 897/897 EventScr* byte-exact in 46 `.fe8s`.
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

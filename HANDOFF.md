# HANDOFF

_Updated: 2026-10-27 07:25 UTC (scheduled run, started 06:51)._

## Owner decisions 2026-10-25 (read docs/decisions/0009)
- The app is a 1:1 copy. Modding = extension points only; ship no touch gameplay, QoL flags or alternate
  formulas. Only touch input shipped: on-screen GBA buttons (task M3-15).
- Every ported system adds strategy interfaces to `gbafe.core.mechanics.Mechanics` (vanilla impl only,
  alternatives only in tests). Battle must use `mechanics.battleRolls` for hit/crit/skill rolls, never
  `rng.roll1/roll2` directly.

- Reuse by future FE6/FE7 ports (docs/decisions/0010): packages are now `gbafe.*` (engine, extract) vs
  `fe8.*` (game, app, testing, `gbafe.extract.fe8`); data formats `gbafe.<table>` + `"game": "fe8"`.
  New shared systems go in `gbafe.<system>` modules; FE8-only ones (world map, skirmishes...) in `fe8`.
  `ExtractCli` now dispatches on `--game` (default fe8) to `gbafe.extract.fe8.runFe8Steps` (Fe8Steps.kt).

## NEEDS USER
_(nothing blocked)_

## Status
- This run: intro (`opanim-healthsafetyscreen.c`) step logic fully ported + **proc scheduler** (M4-01, new TASKS M4 section).
  - `gbafe.proc` (engine/core): `ProcEngine` (src/proc.c: alloc stack, 8 root trees, `Proc_Start/StartBlocking/End/Run/
    Break/Goto/Find`, all 26 opcodes incl. `PROC_JUMP` via `Proc.scrScript`), `ProcScript`/`ProcScripts` (`PROC_*` builders),
    proc-specific fields in `Proc.state` (`ProcScript.newState`). `fe8.game.opanim.EarlyStartUi` = `ProcScr_GameEarlyStartUI`
    with the same 28 commands (indices match `proc_scrCur`); not-yet-ported calls are `Env` hooks.
  - `ProcEngineDifferentialTest` runs the intro proc frame by frame from the f400 state of the new `ram_trace_logo_skip`
    run and matches scrCur / idleCb / fields / palette every frame through the B skip and `PROC_END`. `ProcEngineTest` =
    unit tests for opcodes the trace misses.
  - `OpAnimHs`: `StepResult` (CONTINUE/BREAK/SKIP), Nintendo / IS fades, `earlyStartDelay`, `tryTickExit`, status setters,
    skip fade (`startSkipFade`/`skipFade` = `sub_80CBF9C`/`sub_80CBFC0`). `softwareReset` is a parameter (`IsSoftwareReset`).
  - `gbafe.render.BgTilemap.applyTsa` = `TmApplyTsa`/`CallARM_FillTileRect`, verified vs the BG tilemap buffers.
  - Assets: `ui/intro_nintendo.*`, `ui/intro_is.*` (`gbafe.screen` gains optional `firstTile`/`firstPalette`, schema updated;
    ROM repo `2d00c82`); `IntroLogosScreenTest` pixel-exact on 4 `boot_logos` frames incl. fades (palette = PNG palette
    through `brightenPalette` with the dumped proc timer **minus one**: the frame shows the previous step's palette).
  - `gbafe.core.time.GameClock` (`src/time.c` clock) and `gbafe.input.KeyComboReset` (`gKeyComboResetEN`; `shouldReset`
    = the `SoftResetIfKeyComboPressed` test, no reset yet), checked every frame in `ProcEngineDifferentialTest`.
  - `gbafe.data.JsonReader` (tiny dependency-free JSON reader) + `TrigTable` (`data/trig.json` loader); `TrigAssetTest`.
  - New reference runs (ROM repo `399c4d4`, `89e1944`): `ram_trace_logo_skip` (B at f470 on the IS logo, RAM every frame
    f400..f520), `boot_logos` (full dumps f300/360/430/470). `gen_scripts.py` `script()` takes `ram_from`.
- Verified ports this run: +23 -> **42 / 8352** locally (OpAnimHs steps x9, sub_80CBF9C/FC0, Proc_Start/Run/Break/Goto/End/Find, TmApplyTsa,
  CallARM_FillTileRect, EnableKeyComboResetEN, CheckCanKeyComboReset, IncrementGameClock, GetGameClock). `tools/progress.py` counts a test only for the LAST class declared before it: helper classes go below the test class (fixed this run in `OpAnimHsLogoDifferentialTest`).
- CI: green through 6e9602d; later pushes not checked at wrap-up (check the latest run first). Session setup: ~105 s. No `wip/` branches.

## Run rules (owner, 2026-10-26)
- Budget **~45 min** (guideline; a little over is fine to finish a nearly done task). Work until ~minute 38, never end
  early: when a task finishes, start the next (on `wip/<task-id>` if it may not finish). Wrap up by ~45.
- Run `tools/setup.sh` in the background with a log and poll it. Never fetch full CI job logs (step conclusions
  first, then `tail_lines` <= 80 of the failing job).
- Interleave verifiable ports with infrastructure: a `@VerifiesPort` lands every run or two.

## Next task: M4-02 rest (screen loads) or M3-07 GPU backend
- M4-02: port `PrepareHealthAndSafetyScreen` and `GameIntroPrepareNintendofx` so `EarlyStartUi.Env` hooks go away. They need
  `SetupBackgrounds` (bgconfig + clear tilemap buffers), `SetPrimaryHBlankHandler`, `Sound_FadeOutBGM` (stub until M3-13),
  `Decompress` to a VRAM model, `CpuFastFill16`, `EnablePaletteSync`/`BG_EnableSyncByMask` (sync flags), `SetDispEnable`
  (exists), `SetKeyStatus_IgnoreMask`. Verify vs `boot_logos`/`ram_trace_boot` full dumps (VRAM, tilemap buffers, LCD).
  Then a boot driver: game main loop (`AgbMain`/`OnGameLoopMain`: keys -> `Proc_Run` on roots -> sync) to run the intro
  from power-on and compare against `boot_to_title`.
- Proc engine gaps: `Proc_ForEach*`, mark block/unblock/end, `Proc_FindAfter*`, `CountProcs`, `Proc_SetRepeatCb` not ported.
  The game's main proc (intro's parent; `u32 @+0x14` is an EWRAM proc) is not modelled: tests use a blocking stand-in root.
- M3-07 GPU backend (`:app`, manual test): can now show H&S and both logos from `ui/*` (see `IntroLogosScreenTest`).
- `gSinLookup` loader exists (`TrigTable`), but `SaveDraw` still takes a `ShortArray` and the app does not load it yet.
- Re-extracting after an extractor change: run the CLI (see Known bugs), compare with `python3 tools/compare_extraction.py
  <rom repo>/extracted <out>`, copy only changed/new files into the ROM repo's `extracted/`, push there; CI fails until you do.
- New reference runs: add to `harness/gen_scripts.py`, run it + `harness/make_reference.sh` (seconds), diff old `out/` vs a
  fresh untar of the old `dist/reference.tar.xz` (only PROVENANCE.sha1 may differ), `tar -C reference -cJf
  dist/reference.tar.xz out`, update the sha1 in `dist/MANIFEST.sha1`, push. Also untar into `.assets/reference` locally.
  Fetch the ROM repo before committing there (the session checkout can be behind origin).
- M3-10 Android ActionSources, M3-12..15 audio/overlay. Open renderer items: OBJ mosaic, mosaic on affine BGs.
- Follow-ups noted, not tasks yet: unit placement / `.fe8s` for chapters other than the prologue need the event
  converter to follow `LOAD1/LOAD2` targets from each chapter's `ChapterEventGroup` (`include/chapterdata.h`);
  keysplit/drum sub-voicegroups are scanned for samples but not written as their own `voicegroups/*.json`.

## Known bugs / caveats
- Frame compares: `FrameCompareTest.applyHBlank` only knows `HBlank_BgVerticalScroll` and fails on any other handler;
  dumped RAM is one game step ahead of the dumped frame (the menu already flipped its scroll buffer: the test un-flips).
- `RenderStateTest.filling a frame allocates nothing` flaked (200 bytes once in 1000 frames, locally and in CI run
  73): now uses `AllocationMeter.measureMin` (min over 3 rounds; a real per-frame allocation still fails). Use it
  for other allocation tests if they flake.
- `tools/progress.py` attributes a `@VerifiesPort` test to the LAST `class` declared before it in the file: keep
  helper classes below the test class (or top-level after it), or the port is not counted.
- Local `master` in a fresh session can be an old unrelated history (seen this run): the checkout came up detached
  at origin/master; use `git checkout -B master origin/master` style recovery (rename the stale branch first).
- Extractor CLI: `./gradlew :tools:extract:run --args="--rom $FE8_ASSETS/baserom.gba --out <dir outside repo> --decomp
  <fireemblem8u checkout> --map <rom repo>/decomp/fireemblem8.map --symbols <rom repo>/decomp/fireemblem8.nm.txt"`.
- `gh release view/download <tag>` (GraphQL) is refused in Claude sessions; use `gh api repos/.../releases/...`
  (REST), as `setup.sh` 3b does. `gh release download` without a tag still works.
- Hand cursor colours in `cursor_hand.png` are a stand-in: the game draws it with whatever OBJ palette 0 the calling
  screen loaded (`paletteSource: caller` in cursors.json).
- CI's Dokka step fails on ANY warning (every public declaration, incl. constructor `val`s, needs KDoc). Before
  pushing new public API run `./gradlew dokkaGenerate -Pfe8.docs=true` (after `tools/setup.sh` + `source .assets.env`).
- Decomp checkout for this work: sparse set must include `texts` (face names in messages.json) and `sound`
  (song/sample ground truth): `git sparse-checkout set --no-cone include src texts asm data sound`.
- `.fe8s` v0 prints args as raw numbers (u16, or u32 for ROM pointers/`set_slot`); symbolic constants and a
  real parser are M5. `move_unit_modify` etc. = the macro's 1-bit `modify` parameter baked into the sub-command.
- The decomp **source** is not in the session assets (only `decomp/fireemblem8.{map,nm.txt}` in the ROM
  repo). Extraction with names needs a checkout: `git clone --filter=blob:none --sparse
  https://github.com/FireEmblemUniverse/fireemblem8u` then `git checkout 04d1edb…` + `git sparse-checkout
  set --no-cone include src texts asm data` (~10 s, works through the session proxy). Tests hard-code the few
  names/addresses they need, so CI does not need it.
- Classes inline copies of shared terrain tables; terrains.json also lists them by symbol. Decide with the
  data loader (later M2) whether classes should reference tables by name.
- `effectiveAgainst` in items.json holds class numbers until items and classes are cross-linked.
- Always `source .assets.env` in the same shell before Gradle: without `FE8_MAVEN_MIRROR` set, Dokka
  resolution goes straight to Maven Central and fails with 429s.
- Long Gradle runs (Dokka with `-Pfe8.docs=true`) can exceed the 2-min tool timeout on a cold daemon: run
  them in the background, or repeat (second run is warm).
- Maven Central through the session proxy returns spurious 404/429; `tools/setup.sh` starts
  `tools/maven_mirror.py` (retries). CI is unaffected.
- `GameSurfaceView` is untested on a real device (no gameplay yet; manual test only).
- Static C names can repeat across files; `RamSymbols[name]` throws if ambiguous — use unique names.
- ROM data symbols in `decomp/fireemblem8.nm.txt` (ROM repo) mostly have no size (asm labels): use the
  next symbol's address as the span, as `Lz77RomTest` does.
- Dokka's Kotlin types are not on the build classpath outside docs builds; `configureDokkaLint()` in
  `build.gradle.kts` configures it via Groovy property access — keep it that way.
